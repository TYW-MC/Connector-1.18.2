"""Fix moduleDependencies/testDependencies in FFAPI-1.18.2 build files.

Rule: keep every dependency declared by the 1.20.1 fork unless the target module
was pruned from settings.gradle (1.18.2 has no such module). Replacements where
a 1.18.2 equivalent exists: fabric-command-api-v2 -> deprecated/fabric-command-api-v1.
"""
import os
import re
import glob

REMOVED = {
    'fabric-block-view-api-v2': None,
    'fabric-client-tags-api-v1': None,
    'fabric-command-api-v2': 'deprecated/fabric-command-api-v1',
    'fabric-data-attachment-api-v1': None,
    'fabric-item-group-api-v1': None,
    'fabric-message-api-v1': None,
    'fabric-model-loading-api-v1': None,
    'fabric-recipe-api-v1': None,
    'fabric-sound-api-v1': None,
    'fabric-gametest-api-v1': None,
    'fabric-crash-report-info-v1': None,
}

# modules actually included in settings.gradle
INCLUDED = set()
sp = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                  'ForgifiedFabricAPI-1.18.2', 'settings.gradle')
for line in open(r'F:\ForgifiedFabricAPI-1.18.2\settings.gradle', encoding='utf-8'):
    m = re.match(r"include\s+'([^']+)'", line.strip())
    if m and not line.strip().startswith('//'):
        INCLUDED.add(m.group(1).replace(':', '/'))
print("included modules:", len(INCLUDED))

root = r'F:\ForgifiedFabricAPI-1.18.2'
changed = []
for bg in glob.glob(os.path.join(root, '**', 'build.gradle'), recursive=True):
    rel = os.path.relpath(bg, root).replace(os.sep, '/')
    if rel == 'build.gradle':
        continue
    src = open(bg, encoding='utf-8').read()
    orig = src
    for helper in ('moduleDependencies', 'testDependencies'):
        m = re.search(helper + r"\(project,\s*\[(.*?)\]\s*\)", src, re.S)
        if not m:
            continue
        items = re.findall(r"'([^']+)'", m.group(1))
        out = []
        for it in items:
            bare = it.lstrip(':').replace('/', ':').replace(':', '/')
            if bare in REMOVED:
                rep = REMOVED[bare]
                if rep and rep in INCLUDED and rep not in out:
                    out.append(rep)
                continue
            if it.lstrip(':') in INCLUDED or it in INCLUDED:
                if it not in out:
                    out.append(it)
            # unknown module names: keep as-is only if it was a fabric module we know
        block = helper + "(project, [\n" + "".join("\t'%s',\n" % i for i in out) + "])"
        src = src[:m.start()] + block + src[m.end():]
    if src != orig:
        open(bg, 'w', encoding='utf-8', newline='\n').write(src)
        changed.append(rel)
print("changed:", len(changed))
for c in changed:
    print(" -", c)
