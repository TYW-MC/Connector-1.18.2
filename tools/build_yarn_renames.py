"""Build a Yarn 1.20.1 -> Yarn 1.18.2 class rename table, aligned by intermediary ID.

Tiny v2 format (header: tiny\t2\t0\tofficial\tintermediary\tnamed):
  c\t<official>\t<intermediary>\t<named>
  \tm\t<srcDesc>\t<srcOfficial>\t<srcIntermediary>\t<srcNamed>\t<dstDesc>\t<dstOfficial>...
"""
import json
import sys

def parse(path):
    """Returns {intermediary_class: named_class}, plus member maps."""
    classes = {}
    members = {}  # intermediary_class -> {'m': {mid: (named, desc)}, 'f': {fid: (named, desc)}}
    with open(path, encoding='utf-8') as f:
        header = f.readline().rstrip('\n').split('\t')
        # namespaced columns: official, intermediary, named
        for line in f:
            if line.startswith('c\t'):
                parts = line.rstrip('\n').split('\t')
                official, inter, named = parts[1], parts[2], parts[3]
                classes[inter] = named
                members[inter] = {'m': {}, 'f': {}}
            elif line.startswith('\tm\t') or line.startswith('\t\tm\t'):
                parts = line.rstrip('\n').split('\t')
                # line starts with empty col: ['', 'm', ...] or ['', '', 'm', ...]
                if parts[1] == 'm':
                    # c  -> m: ['', 'm', desc, official, inter, named]
                    desc, inter_m, named = parts[2], parts[4], parts[5]
                else:
                    # nested member inside class: ['', '', 'm', desc, official, inter, named]
                    desc, inter_m, named = parts[3], parts[5], parts[6]
                cls = current[0]
                members[cls]['m'][inter_m] = (named, desc)
            elif line.startswith('\tf\t') or line.startswith('\t\tf\t'):
                parts = line.rstrip('\n').split('\t')
                if parts[1] == 'f':
                    desc, inter_f, named = parts[2], parts[4], parts[5]
                else:
                    desc, inter_f, named = parts[3], parts[5], parts[6]
                cls = current[0]
                members[cls]['f'][inter_f] = (named, desc)
            elif line.startswith('c\t'):
                pass
    return classes, members


def parse2(path):
    classes = {}
    members = {}
    current = None
    with open(path, encoding='utf-8') as f:
        f.readline()  # tiny 2 0 intermediary named
        for line in f:
            line = line.rstrip('\n')
            if not line:
                continue
            depth = 0
            while line.startswith('\t'):
                depth += 1
                line = line[1:]
            parts = line.split('\t')
            if depth == 0 and parts[0] == 'c':
                current = parts[1]
                classes[current] = parts[2]
                members[current] = {'m': {}, 'f': {}}
            elif depth == 1 and parts[0] in ('m', 'f'):
                kind = parts[0]
                desc, inter, named = parts[1], parts[2], parts[3]
                if current:
                    members[current][kind][inter] = (named, desc)
    return classes, members


old_m, old_mem = parse2(sys.argv[1])   # 1.20.1
new_m, new_mem = parse2(sys.argv[2])   # 1.18.2

# class rename table: named_1.20.1 -> named_1.18.2
cls_renames = {}
both = 0
for inter, old_named in old_m.items():
    if inter in new_m:
        both += 1
        new_named = new_m[inter]
        if new_named != old_named:
            cls_renames[old_named] = new_named

print(f"1.20.1 classes: {len(old_m)}, 1.18.2 classes: {len(new_m)}, matched by intermediary: {both}, renames: {len(cls_renames)}")

# member rename table keyed by (old_class, old_member_name) -> (new_class, new_member)
# only for members whose intermediary persisted
mem_renames = {}
mem_kept = 0
for inter, mems in old_mem.items():
    if inter not in new_mem:
        continue
    old_cls = old_m[inter]
    new_cls = new_m[inter]
    for kind in ('m', 'f'):
        for mid, (oname, odesc) in mems[kind].items():
            entry = new_mem[inter][kind].get(mid)
            if entry is None:
                continue
            nname, ndesc = entry
            if oname != nname or old_cls != new_cls:
                mem_renames.setdefault((old_cls, oname, kind), []).append((new_cls, nname, odesc, ndesc))
            else:
                mem_kept += 1

print(f"member rename entries: {len(mem_renames)}, unchanged: {mem_kept}")
out = {
    'class_renames': cls_renames,
    'member_renames': {f"{c}|{n}|{k}": v for (c, n, k), v in mem_renames.items()},
}
with open(sys.argv[3], 'w', encoding='utf-8') as f:
    json.dump(out, f, indent=0)
print("written", sys.argv[3])
