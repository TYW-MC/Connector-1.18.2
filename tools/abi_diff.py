"""
ABI compatibility check for the Connector 1.18.2 port.

Compares every reference an *Adapter* artifact makes into `org.spongepowered.asm.**`
against the Mixin jar that actually ships with the target Forge version.

Why this exists
---------------
`Adapter-1.18.2/definition/build.gradle.kts` declares

    "testClassesImplementation"(implementation(group = "net.fabricmc", name = "sponge-mixin", ...))

The `implementation(...)` call has a side effect: it registers the dependency on the
*main* `implementation` configuration as well, so the Adapter is compiled against
Fabric's Mixin fork instead of the Mixin that the runtime provides. Any member the
fork adds but upstream Mixin lacks compiles fine and then dies at runtime with
NoSuchMethodError / NoClassDefFoundError - which is exactly what happened with

    Locals.getLocalsAt(ClassNode, MethodNode, AbstractInsnNode, int)
    org.spongepowered.asm.mixin.FabricUtil

Running this script enumerates *all* such landmines in one shot instead of
discovering them one boot cycle at a time.

Usage
-----
    python abi_diff.py <adapter-jar> [<adapter-jar> ...] --runtime <mixin-jar>

Both javap and the JDK's jsr-… are taken from PATH; run it from a shell that has a
JDK 17 on PATH (the repo's `javap` works).
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

# `javap -c` renders constant-pool references as comments such as
#   // Method org/spongepowered/asm/util/Locals.getLocalsAt:(...)Ljava/util/...
#   // Field org/spongepowered/asm/mixin/FabricUtil.COMPATIBILITY_0_9_2:I
REF_RE = re.compile(
    r"//\s+(?:Interface)?(?:Method|Field)\s+"
    r"(?P<owner>[\w/$]+)\."
    r"(?P<name>[\w$<>]+):(?P<desc>\(?[^\s]*)"
)
# javap prints class names in their *dotted* source form (org.spongepowered.asm.util.Locals,
# with inner classes as ...Locals$Settings), so the pattern has to accept '.' as well; the
# parsed name is normalised to the internal slash form afterwards.
CLASS_DECL_RE = re.compile(
    r"^(?:public |final |abstract |sealed |non-sealed )*(?:class|interface|enum|record)\s+(?P<name>[\w/$\.]+)"
    r"(?:\s+extends\s+(?P<super>[\w/$\.]+))?"
    r"(?:\s+implements\s+(?P<ifaces>[^{]+))?")
PKG_PREFIX = "org/spongepowered/asm/"


def javap(args):
    proc = subprocess.run(["javap", *args], capture_output=True, text=True, errors="replace")
    return proc.stdout


def collect_members(jar, tmp):
    """Returns {internal class name: {'members': {(name, desc)}, 'super': name|None, 'ifaces': [..]}}."""
    out_dir = os.path.join(tmp, "rt")
    with zipfile.ZipFile(jar) as zf:
        zf.extractall(out_dir)

    classes = {}
    paths = []
    for root, _dirs, files in os.walk(out_dir):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, out_dir).replace(os.sep, "/")
                if rel.startswith(PKG_PREFIX):
                    paths.append(rel)

    if not paths:
        return classes

    class_names = [p[:-len(".class")] for p in paths]

    # `javap -p -s` prints each member followed by its descriptor on the next line:
    #   public static org.objectweb.asm.tree.LocalVariableNode[] getLocalsAt(...);
    #     descriptor: (Lorg/...;)...[Lorg/...;
    chunk = 200
    for i in range(0, len(class_names), chunk):
        batch = class_names[i:i + chunk]
        text = javap(["-p", "-s", "-classpath", out_dir, *batch])
        current = None
        pending_name = None
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            if stripped.startswith("Compiled from"):
                continue
            if not line.startswith(" ") and not line.startswith("\t"):
                # class header line, e.g. "public class org.spongepowered...Locals {"
                m = CLASS_DECL_RE.search(stripped)
                if m and stripped.endswith("{"):
                    current = m.group("name").replace(".", "/")
                    super_name = m.group("super")
                    ifaces = m.group("ifaces") or ""
                    classes[current] = {
                        "members": set(),
                        "super": super_name.replace(".", "/") if super_name else None,
                        "ifaces": [x.strip().replace(".", "/") for x in ifaces.split(",") if x.strip()],
                    }
                continue
            if current is None:
                continue
            if stripped.startswith("descriptor:"):
                desc = stripped[len("descriptor:"):].strip()
                if pending_name:
                    classes[current]["members"].add((pending_name, desc))
                pending_name = None
            else:
                # Member declaration line. javap renders a field as
                #   public static final int COMPATIBILITY_0_9_2;
                # and a method/constructor as
                #   public static ... getLocalsAt(java.lang.String, int);
                #   public org.spongepowered.asm.util.Locals$Settings(int, int);
                head = stripped.split("(")[0].strip()
                name = head.split()[-1] if head else ""
                name = name.rstrip(";")
                if not name:
                    continue
                # Constructors and static initialisers are printed with their
                # declaration spelling, but references use <init>/<clinit>.
                simple = current.rsplit("/", 1)[-1].rsplit("$", 1)[-1]
                if name == simple or name == current:
                    name = "<init>"
                elif name == "static":
                    name = "<clinit>"
                pending_name = name
    return classes


def resolve(classes, owner, name, desc):
    """Walks the class hierarchy looking for a member with an exactly matching descriptor."""
    seen = set()
    stack = [owner]
    while stack:
        cur = stack.pop()
        if cur in seen or cur not in classes:
            continue
        seen.add(cur)
        info = classes[cur]
        if (name, desc) in info["members"]:
            return True
        if info["super"]:
            stack.append(info["super"])
        stack.extend(info["ifaces"])
    return False


def scan_jar(jar, runtime, tmp, verbose):
    out_dir = os.path.join(tmp, os.path.basename(jar))
    os.makedirs(out_dir, exist_ok=True)
    with zipfile.ZipFile(jar) as zf:
        zf.extractall(out_dir)

    class_names = []
    for root, _dirs, files in os.walk(out_dir):
        for f in files:
            if f.endswith(".class"):
                rel = os.path.relpath(os.path.join(root, f), out_dir).replace(os.sep, "/")
                class_names.append(rel[:-len(".class")])

    refs = defaultdict(set)   # (owner, name, desc) -> {referencing classes}
    chunk = 200
    for i in range(0, len(class_names), chunk):
        batch = class_names[i:i + chunk]
        text = javap(["-p", "-c", "-classpath", out_dir, *batch])
        current = None
        for line in text.splitlines():
            stripped = line.strip()
            if stripped.endswith("{") and CLASS_DECL_RE.search(stripped):
                current = CLASS_DECL_RE.search(stripped).group("name").replace(".", "/")
            m = REF_RE.search(line)
            if m and m.group("owner").startswith(PKG_PREFIX):
                refs[(m.group("owner"), m.group("name"), m.group("desc"))].add(current)

    missing = {}
    for (owner, name, desc), referers in sorted(refs.items()):
        if owner not in runtime:
            missing[(owner, name, desc)] = referers
        elif not resolve(runtime, owner, name, desc):
            missing[(owner, name, desc)] = referers
        elif verbose:
            print("  ok   %s.%s%s" % (owner, name, desc))

    print("=" * 78)
    print("JAR: %s" % jar)
    print("  distinct org.spongepowered.asm references : %d" % len(refs))
    print("  MISSING in runtime Mixin jar             : %d" % len(missing))
    for (owner, name, desc), referers in sorted(missing.items()):
        users = ", ".join(sorted(x for x in referers if x))
        print("  MISS %s.%s%s" % (owner, name, desc))
        if users:
            print("       used by: %s" % users)
    print("=" * 78)
    return missing


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jars", nargs="+")
    ap.add_argument("--runtime", required=True)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    if shutil.which("javap") is None:
        sys.exit("javap is not on PATH; run this from a shell with a JDK 17 available")

    with tempfile.TemporaryDirectory() as tmp:
        print("Building runtime member index from %s ..." % args.runtime)
        runtime = collect_members(args.runtime, tmp)
        print("  indexed %d classes" % len(runtime))
        total = {}
        for jar in args.jars:
            total.update(scan_jar(jar, runtime, tmp, args.verbose))
        return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
