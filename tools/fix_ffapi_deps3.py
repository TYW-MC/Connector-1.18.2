"""Restore moduleDependencies/testDependencies in FFAPI-1.18.2 from the 1.20.1 fork,
then prune/replace deps on modules absent from 1.18.2.

Source of truth for the original lists: F:\\ForgifiedFabricAPI-1.20.1 (same fork base).
"""
import os
import re
import glob

OLD = r'F:\ForgifiedFabricAPI-1.20.1'
NEW = r'F:\ForgifiedFabricAPI-1.18.2'

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

INCLUDED = set()
for line in open(os.path.join(NEW, 'settings.gradle'), encoding='utf-8'):
    m = re.match(r"include\s+'([^']+)'", line.strip())
    if m and not line.strip().startswith('//'):
        INCLUDED.add(m.group(1).replace(':', '/'))


def find_bg(base, rel):
    p = os.path.join(base, rel)
    return p if os.path.isfile(p) else None


def rebuild(items):
    out = []
    for it in items:
        bare = it.lstrip(':').replace(':', '/')
        if bare in REMOVED:
            rep = REMOVED[bare]
            if rep and rep in INCLUDED and rep not in out:
                out.append(rep)
            continue
        if bare in INCLUDED and it not in out:
            out.append(it)
    return out


changed = 0
for bg_new in glob.glob(os.path.join(NEW, '**', 'build.gradle'), recursive=True):
    rel = os.path.relpath(bg_new, NEW).replace(os.sep, '/')
    if rel == 'build.gradle':
        continue
    bg_old = os.path.join(OLD, rel)
    if not os.path.isfile(bg_old):
        print("!! no 1.20.1 counterpart:", rel)
        continue
    src_old = open(bg_old, encoding='utf-8').read()
    dst = open(bg_new, encoding='utf-8').read()
    orig_dst = dst
    for helper in ('moduleDependencies', 'testDependencies'):
        m_old = re.search(helper + r"\(project,\s*\[(.*?)\]\s*\)", src_old, re.S)
        items = re.findall(r"'([^']+)'", m_old.group(1)) if m_old else []
        m_new = re.search(helper + r"\(project,\s*\[(.*?)\]\s*\)", dst, re.S)
        if m_new is None and m_old is None:
            continue
        out = rebuild(items)
        block = helper + "(project, [\n" + "".join("\t'%s',\n" % i for i in out) + "])"
        if m_new:
            dst = dst[:m_new.start()] + block + dst[m_new.end():]
        elif m_old:
            # helper existed in 1.20.1 but was clobbered from the 1.18.2 file; re-insert
            anchor = m_old.group(0)
            dst = dst.rstrip('\n') + '\n\n' + block + '\n'
    if dst != orig_dst:
        open(bg_new, 'w', encoding='utf-8', newline='\n').write(dst)
        changed += 1
        print(" -", rel)
print("changed:", changed)
