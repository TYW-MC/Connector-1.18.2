#!/usr/bin/env python3
"""Check built FFAPI module jars for mixin annotation strings that lack refmap entries.

Any string in a class constant pool that looks like a mixin member target
(intermediary method_/field_ ids, or Yarn->SRG remappable descriptors) but is
absent from the refmap will fail at mixin apply time on Forge (SRG runtime).
"""
import zipfile, json, re, glob, sys, io, struct

REFMAP_SUFFIX = "-refmap.json"

def strings_from_class(data):
    """Extract constant pool UTF8 strings from a .class file."""
    out = []
    if data[:4] != b'\xca\xfe\xba\xbe':
        return out
    idx = 10  # magic(4) + minor(2) + major(2) + cp_count(2)
    n = struct.unpack('>H', data[8:10])[0]
    i = 1
    while i < n and idx < len(data):
        tag = data[idx]; idx += 1
        if tag == 1:
            ln = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
            out.append(data[idx:idx+ln].decode('utf-8', 'replace')); idx += ln
        elif tag in (7, 8, 16, 19, 20):
            idx += 2
        elif tag in (15,):
            idx += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            idx += 4
        elif tag in (5, 6):
            idx += 8; i += 1
        else:
            break
        i += 1
    return out

TARGET_RE = re.compile(r'^(?:L[\w/$]+;)?(?:method_\d+|field_\d+|<init>|m_\d+_+|f_\d+_+)[^{}]*$')

def main():
    jars = sys.argv[1:] or glob.glob('*/build/libs/*.jar') + glob.glob('deprecated/*/build/libs/*.jar')
    jars = [j for j in jars if 'sources' not in j and 'testmod' not in j]
    problems = 0
    for jar in jars:
        try:
            z = zipfile.ZipFile(jar)
        except Exception:
            continue
        refmaps = {n[:-len(REFMAP_SUFFIX)]: json.loads(z.read(n))['mappings']
                   for n in z.namelist() if n.endswith(REFMAP_SUFFIX)}
        refkeys = set()
        for m in refmaps.values():
            for cls, entries in m.items():
                refkeys.update(entries.keys())
                refkeys.update(entries.values())
        for n in z.namelist():
            if not n.endswith('.class'):
                continue
            is_mixin = '/mixin/' in n
            for s in strings_from_class(z.read(n)):
                if len(s) >= 300 or s == '<init>':
                    continue
                # intermediary names in annotation strings are never valid at runtime (SRG/mojang
                # runtime); bare SRG refs (m_xxx_) are normal post-remap bytecode, skip them.
                needs_map = bool(re.search(r'method_\d+|field_\d+', s))
                if not needs_map and is_mixin and s.startswith('lambda$'):
                    needs_map = True
                if needs_map and s not in refkeys:
                    print(f"MISSING [{jar.split('/')[0].split(chr(92))[0]}] {n}: {s!r}")
                    problems += 1
    print("TOTAL MISSING:", problems)

main()
