"""Probe: list the asset paths inside every Master Duel bundle (bundle file -> container paths).

Research only; the mod does its own lookup. Writes a TSV to the given output path.
    python probe_index.py "<Master Duel>/LocalData/<id>" out.tsv
"""
import os, sys
from multiprocessing import Pool

import UnityPy


def paths(f):
    try:
        env = UnityPy.load(f)
        return f, [p for p in env.container.keys()]
    except Exception as e:
        return f, ["!ERR " + repr(e)[:120]]


def main():
    root, out = sys.argv[1], sys.argv[2]
    files = []
    for d, _, fs in os.walk(root):
        for n in fs:
            files.append(os.path.join(d, n))
    with Pool(7) as pool, open(out, "w", encoding="utf-8") as o:
        for i, (f, ps) in enumerate(pool.imap_unordered(paths, files, chunksize=32)):
            rel = os.path.relpath(f, root).replace("\\", "/")
            for p in ps:
                o.write(f"{rel}\t{p}\n")
            if i % 2000 == 0:
                print(i, len(files), flush=True)


if __name__ == "__main__":
    main()
