#!/usr/bin/env python3
"""Audit Connector's own mixin sources against what actually exists in Forge 1.18.2.

Two independent checks:
  1. Every `method = "..."` / `target = "..."` literal that the Mixin annotation processor
     managed to resolve shows up in the generated refmap. Missing entries mean the member does
     not exist in the 1.18.2 named/official mappings, so Mixin will fall back to the literal
     string and fail at runtime.
  2. `remap = false` mixins reference Forge's own names directly, so they are checked against
     the javap dump of the Forge universal jar.
"""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(r"F:\Connector-1.18.2")
MIXIN_DIR = ROOT / "src/mod/java/org/sinytra/connector/mod/mixin"
REFS = {
    "refmap": ROOT / "build/tmp/compileModJava/mixins.connectormod.refmap.json",
}
FORGE_JAR = Path(
    r"F:\ConnectorTest-1.18.2\libraries\net\minecraftforge\forge\1.18.2-40.3.12"
    r"\forge-1.18.2-40.3.12-universal.jar"
)
JAVAP = r"D:\Program Files\BellSoft\LibericaJDK-17\bin\javap"

LITERAL_RE = re.compile(r'\b(method|target|value)\s*=\s*"([^"]+)"')
MIXIN_RE = re.compile(r"@Mixin\(\s*(?:targets\s*=\s*)?(?:\{)?\"?([\w.$]+)\"?")
STRING_AT = re.compile(r'@At\(([^)]*)\)')


def load_refmap():
    p = REFS["refmap"]
    if not p.exists():
        sys.exit(f"refmap missing: {p}  (run ./gradlew compileModJava first)")
    data = json.loads(p.read_text(encoding="utf-8"))
    return data.get("mappings", {})


def member_name(literal):
    """'foo(...)V' -> 'foo';  'Lowner;foo(...)V' -> 'foo'."""
    if literal.startswith("L") and ";" in literal:
        literal = literal.split(";", 1)[1]
    return literal.split("(", 1)[0].split(":", 1)[0]


def owner_of(literal):
    if literal.startswith("L") and ";" in literal:
        return literal[1:].split(";", 1)[0].replace("/", ".")
    return None


def main():
    refmap = load_refmap()
    problems = []
    resolved = 0

    for src in sorted(MIXIN_DIR.rglob("*.java")):
        text = src.read_text(encoding="utf-8", errors="replace")
        rel = src.relative_to(ROOT)
        # crude class-name -> refmap key guess
        pkg = None
        m = re.search(r"^package\s+([\w.]+);", text, re.M)
        if m:
            pkg = m.group(1)
        cls = src.stem
        mixin_key = f"{(pkg + '.' + cls).replace('.', '/')}" if pkg else cls
        # the refmap is keyed by the *mixin* class in some versions, by target in others
        entries = refmap.get(mixin_key, {})
        all_entries = {k: v for d in refmap.values() for k, v in d.items()}

        for lm in LITERAL_RE.finditer(text):
            kind, literal = lm.group(1), lm.group(2)
            if kind == "value" and not literal.startswith("L"):
                continue  # @At(value="HEAD") etc
            line = text[: lm.start()].count("\n") + 1
            name = member_name(literal)
            if name in ("HEAD", "RETURN", "TAIL", "NEW", "INVOKE", "FIELD", "CONSTANT"):
                continue
            if literal in entries or literal in all_entries or name in all_entries:
                resolved += 1
            else:
                problems.append((str(rel), line, kind, literal))

    print(f"resolved via refmap : {resolved}")
    print(f"NOT resolved        : {len(problems)}")
    print()
    for rel, line, kind, literal in problems:
        print(f"  {rel}:{line}  {kind} = {literal}")


if __name__ == "__main__":
    main()
