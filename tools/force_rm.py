import os
import shutil
import stat

def force_rmtree(path):
    """Delete a directory tree file-by-file (bypasses directory trash hook)."""
    for dirpath, dirnames, filenames in os.walk(path, topdown=False):
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            os.chmod(p, stat.S_IWRITE)
            os.remove(p)
        for dn in dirnames:
            os.rmdir(os.path.join(dirpath, dn))
    os.rmdir(path)

import sys
for p in sys.argv[1:]:
    if os.path.isdir(p):
        force_rmtree(p)
        print("removed dir:", p)
    elif os.path.isfile(p):
        os.chmod(p, stat.S_IWRITE)
        os.remove(p)
        print("removed file:", p)
