"""Packs EDOPro's card scripts (ProjectIgnis/CardScripts, AGPL-3.0) into the mod's resources.

Includes the root helper scripts (constant.lua, utility.lua, proc_*.lua ...), official/ and pre-release/.
    python -I tools/pack_scripts.py
"""
import os, subprocess, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "third_party", "CardScripts")
OUT = os.path.join(ROOT, "fabric", "src", "main", "resources", "cardscripts.zip")
rev = subprocess.run(["git", "-C", SRC, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
n = 0
with zipfile.ZipFile(OUT + ".tmp", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for name in sorted(os.listdir(SRC)):
        if name.endswith(".lua"):
            z.write(os.path.join(SRC, name), name)
            n += 1
    for sub in ("official", "pre-release"):
        for name in sorted(os.listdir(os.path.join(SRC, sub))):
            if name.endswith(".lua"):
                z.write(os.path.join(SRC, sub, name), f"{sub}/{name}")
                n += 1
    z.write(os.path.join(SRC, "COPYING"), "COPYING")
    z.writestr("SOURCE.txt", f"Project Ignis CardScripts, https://github.com/ProjectIgnis/CardScripts commit {rev}\nLicensed AGPL-3.0 (COPYING).\n")
os.replace(OUT + ".tmp", OUT)
print(n, "scripts,", os.path.getsize(OUT) // 1024, "KB, commit", rev)
