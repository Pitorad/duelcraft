"""Marks sheet rows verified after their test passed (the only way 'verified' should change).

    python -I tools/verify.py <sheet> <key> [<key> ...]      e.g. verify.py systems lz4 unity_bundle
Keys match the row's id / msgId / key / entity / file column.
"""
import json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sheet, keys = sys.argv[1], set(sys.argv[2:])
p = os.path.join(ROOT, "design", sheet + ".json")
d = json.load(open(p, encoding="utf-8"))
hit = set()
for r in d["rows"]:
    for k in ("id", "msgId", "key", "entity", "file"):
        if k in r and str(r[k]) in keys:
            r["verified"] = True
            hit.add(str(r[k]))
json.dump(d, open(p, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
missing = keys - hit
print(f"{sheet}: verified {sorted(hit)}" + (f"; NOT FOUND {sorted(missing)}" if missing else ""))
sys.exit(1 if missing else 0)
