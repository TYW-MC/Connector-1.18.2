import re, os, glob

# 0.77.0+1.18.2 真实依赖图（来自各模块 fabric.mod.json depends）
real = {
 'fabric-api-lookup-api-v1': ['fabric-api-base', 'fabric-lifecycle-events-v1'],
 'fabric-biome-api-v1': [],
 'fabric-block-api-v1': [],
 'fabric-blockrenderlayer-v1': ['fabric-api-base'],
 'fabric-command-api-v1': ['fabric-api-base'],
 'fabric-commands-v0': ['fabric-api-base', 'fabric-command-api-v1'],
 'fabric-containers-v0': ['fabric-api-base', 'fabric-networking-api-v1'],
 'fabric-content-registries-v0': ['fabric-api-base', 'fabric-lifecycle-events-v1', 'fabric-resource-loader-v0'],
 'fabric-convention-tags-v1': [],
 'fabric-data-generation-api-v1': [],
 'fabric-dimensions-v1': ['fabric-api-base'],
 'fabric-entity-events-v1': [],
 'fabric-events-interaction-v0': ['fabric-api-base'],
 'fabric-events-lifecycle-v0': ['fabric-api-base', 'fabric-item-api-v1', 'fabric-lifecycle-events-v1'],
 'fabric-game-rule-api-v1': [],
 'fabric-item-api-v1': ['fabric-api-base'],
 'fabric-key-binding-api-v1': [],
 'fabric-keybindings-v0': ['fabric-key-binding-api-v1'],
 'fabric-lifecycle-events-v1': ['fabric-api-base'],
 'fabric-loot-api-v2': ['fabric-api-base', 'fabric-resource-loader-v0'],
 'fabric-loot-tables-v1': ['fabric-api-base', 'fabric-loot-api-v2'],
 'fabric-mining-level-api-v1': ['fabric-api-base', 'fabric-lifecycle-events-v1', 'fabric-resource-loader-v0'],
 'fabric-models-v0': ['fabric-api-base'],
 'fabric-networking-api-v1': ['fabric-api-base'],
 'fabric-networking-v0': ['fabric-api-base', 'fabric-networking-api-v1'],
 'fabric-object-builder-api-v1': ['fabric-api-base'],
 'fabric-particles-v1': [],
 'fabric-registry-sync-v0': ['fabric-api-base', 'fabric-networking-api-v1'],
 'fabric-renderer-api-v1': ['fabric-api-base'],
 'fabric-renderer-indigo': ['fabric-api-base', 'fabric-renderer-api-v1'],
 'fabric-renderer-registries-v1': ['fabric-api-base', 'fabric-rendering-v1'],
 'fabric-rendering-data-attachment-v1': ['fabric-api-base'],
 'fabric-rendering-fluids-v1': ['fabric-api-base'],
 'fabric-rendering-v0': ['fabric-api-base', 'fabric-rendering-v1'],
 'fabric-rendering-v1': ['fabric-api-base'],
 'fabric-resource-conditions-api-v1': [],
 'fabric-resource-loader-v0': [],
 'fabric-screen-api-v1': ['fabric-api-base'],
 'fabric-screen-handler-api-v1': ['fabric-api-base', 'fabric-networking-api-v1'],
 'fabric-transfer-api-v1': ['fabric-api-lookup-api-v1', 'fabric-rendering-fluids-v1'],
 'fabric-transitive-access-wideners-v1': [],
}
valid = set(real) | {'deprecated'}
root = r'F:\ForgifiedFabricAPI-1.18.2'
changed = []
for bg in glob.glob(os.path.join(root, '**', 'build.gradle'), recursive=True):
    rel = os.path.relpath(bg, root).replace(os.sep, '/')
    if rel == 'build.gradle' or rel.startswith('deprecated/build.gradle'):
        continue
    src = open(bg, encoding='utf-8').read()
    orig = src
    m = re.search(r"moduleDependencies\(project,\s*\[(.*?)\]\s*\)", src, re.S)
    if m:
        items = re.findall(r"'([^']+)'", m.group(1))
        parts = rel.replace('\\', '/').split('/')
        mod = parts[1] if parts[0] == 'deprecated' else parts[0]
        target = real.get(mod, ['fabric-api-base'])
        kept = [i for i in items if i in valid]
        for t in target:
            if t not in kept:
                kept.append(t)
        new_block = "moduleDependencies(project, [\n" + "".join("\t'%s',\n" % k for k in kept) + "])"
        src = src[:m.start()] + new_block + src[m.end():]
    if src != orig:
        open(bg, 'w', encoding='utf-8', newline='\n').write(src)
        changed.append(rel)
print("changed:", len(changed))
for c in changed:
    print(" -", c)
