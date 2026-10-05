import re
import sys
from collections import Counter

log = open(sys.argv[1], encoding='gbk', errors='replace').read()

# javac blocks: file(line): 错误: msg ... 符号: X ... 位置: Y
blocks = re.split(r'\n(?=[A-Z]:\\[^\n]+\.java:\d+: 错误)', log)
sym_counter = Counter()
file_syms = {}
for b in blocks:
    m = re.match(r'([A-Z]:\\[^\n:]+\.java):(\d+): 错误: ([^\n]+)', b)
    if not m:
        continue
    f, line, msg = m.group(1), m.group(2), m.group(3)
    sym = re.search(r'符号: (类|变量|方法) ([^\n]+)', b)
    loc = re.search(r'位置: (?:类|接口|程序包|变量) ([^\n]+)', b)
    if '找不到符号' in msg and sym:
        kind, name = sym.group(1), sym.group(2).strip()
        l = loc.group(1).strip() if loc else '?'
        sym_counter[(kind, name[:60], l[:60])] += 1
        file_syms.setdefault(f.replace('F:\\ForgifiedFabricAPI-1.18.2\\', ''), []).append((line, name[:50], l[:50]))

print("== 符号统计 top40 ==")
for (kind, name, loc), n in sym_counter.most_common(40):
    print("%4d  %s %s  @ %s" % (n, kind, name, loc))
