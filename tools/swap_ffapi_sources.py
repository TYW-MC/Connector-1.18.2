import os
import re
import shutil

OFFICIAL = r'F:\ConnectorTest-1.18.2\_tmp\fabric-api-0.77.0-1.18.2'
NEW = r'F:\ForgifiedFabricAPI-1.18.2'
BACKUP = r'F:\ConnectorTest-1.18.2\_tmp\ffapi_src_backup'

INCLUDED = []
for line in open(os.path.join(NEW, 'settings.gradle'), encoding='utf-8'):
    m = re.match(r"include\s+'([^']+)'", line.strip())
    if m and not line.strip().startswith('//'):
        INCLUDED.append(m.group(1).replace(':', os.sep))

for mod in INCLUDED:
    rel = mod.replace(os.sep, '_')
    src_new = os.path.join(NEW, mod, 'src')
    src_off = os.path.join(OFFICIAL, mod, 'src')
    if not os.path.isdir(src_off):
        print("!! official module missing:", mod)
        continue
    # backup
    if os.path.isdir(src_new):
        b = os.path.join(BACKUP, rel, 'src')
        if os.path.isdir(b):
            shutil.rmtree(b)
        shutil.copytree(src_new, b)
        shutil.rmtree(src_new)
    shutil.copytree(src_off, src_new)
    # access wideners at module root
    for f in os.listdir(os.path.join(OFFICIAL, mod)):
        if f.endswith('.accesswidener'):
            shutil.copy2(os.path.join(OFFICIAL, mod, f), os.path.join(NEW, mod, f))
            # also place where FFAPI build.gradle usually expects it
            res = os.path.join(NEW, mod, 'src', 'main', 'resources', f)
            shutil.copy2(os.path.join(OFFICIAL, mod, f), res)
    print("replaced:", mod)
print("done")
