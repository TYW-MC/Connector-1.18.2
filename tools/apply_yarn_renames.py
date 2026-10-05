"""Phase 1: apply Yarn 1.20.1 -> 1.18.2 class renames to FFAPI sources.

Handles dotted FQNs (imports, code) and slashed forms (strings, descriptors).
Only replaces on identifier boundaries to avoid prefix collisions
(Registry vs RegistryKeys).
"""
import json
import os
import re
import sys

ROOT = r'F:\ForgifiedFabricAPI-1.18.2'
TABLE = r'F:/ConnectorTest-1.18.2/_tmp/yarn_renames.json'

d = json.load(open(TABLE, encoding='utf-8'))
cls = d['class_renames']

# dotted form: net.minecraft.registry.Registry -> net.minecraft.util.registry.Registry
dot_map = {k.replace('/', '.'): v.replace('/', '.') for k, v in cls.items()}
# longest first so nested-package entries win
dot_keys = sorted(dot_map, key=len, reverse=True)
dot_re = re.compile(
    r'(?<![A-Za-z0-9_.$])(' + '|'.join(re.escape(k) for k in dot_keys) +
    r')(?![A-Za-z0-9_])')

# slashed form (string literals like "net/minecraft/..." or L descriptors)
slash_map = {k: v for k, v in cls.items() if '/' in k}
slash_keys = sorted(slash_map, key=len, reverse=True)
slash_re = re.compile(
    r'(?<![A-Za-z0-9_/$])(' + '|'.join(re.escape(k) for k in slash_keys) +
    r')(?![A-Za-z0-9_/])')

changed = 0
touched = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [x for x in dirnames if x not in ('.gradle', 'build', '.git')]
    for fn in filenames:
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        src = open(p, encoding='utf-8').read()
        out = dot_re.sub(lambda m: dot_map[m.group(1)], src)
        out = slash_re.sub(lambda m: slash_map[m.group(1)], out)
        if out != src:
            open(p, 'w', encoding='utf-8', newline='').write(out)
            changed += 1
            touched.append(os.path.relpath(p, ROOT))
print('files changed:', changed)
