import os
import re
import glob

NEW = r'F:\ForgifiedFabricAPI-1.18.2'

changed = 0
for bg in glob.glob(os.path.join(NEW, '**', 'build.gradle'), recursive=True):
    rel = os.path.relpath(bg, NEW).replace(os.sep, '/')
    if rel == 'build.gradle':
        continue
    mod_dir = os.path.dirname(bg)
    src = open(bg, encoding='utf-8').read()
    orig = src
    for m in re.finditer(r'\n[ \t]*accessWidenerPath = file\("([^"]+)"\)', src):
        aw_rel = m.group(1)
        p = os.path.join(mod_dir, aw_rel.replace('/', os.sep))
        if not os.path.isfile(p):
            src = src.replace(m.group(0), '\n\t// access widener removed: not present in Fabric API 0.77.0+1.18.2 (%s)' % aw_rel)
            print("removed AW ref:", rel, "->", aw_rel)
    # drop now-empty loom blocks that only contained the AW line? leave comments, harmless
    if src != orig:
        open(bg, 'w', encoding='utf-8', newline='\n').write(src)
        changed += 1
print("changed:", changed)
