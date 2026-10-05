import re
import sys
from collections import Counter

log = open(sys.argv[1], encoding='gbk', errors='replace').read()
errs = re.findall(r'([A-Z]:\\[^\n:]+\.java):(\d+): 错误: ([^\n]+)', log)
print("总错误:", len(errs))


def norm(m):
    return m.split(':')[0][:70]


c = Counter(norm(e[2]) for e in errs)
for msg, n in c.most_common(40):
    print("%4d  %s" % (n, msg))

print()
print("== 按文件 ==")
f = Counter(e[0] for e in errs)
for p, n in f.most_common(25):
    print("%4d  %s" % (n, p.replace('F:\\ForgifiedFabricAPI-1.18.2\\', '')))
