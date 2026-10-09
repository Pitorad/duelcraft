"""Probe: pull Master Duel's encrypted card tables and find the decryption key.

    python probe_carddata.py <LocalData/id> <index.tsv> <outdir>
Research only (writes into the research folder, which is never shipped).
"""
import os, sys, zlib

import UnityPy

NAMES = ["card_prop", "card_name", "card_indx", "card_desc", "card_intid", "card_named", "card_genre"]


def decrypt(data, key):
    out = bytearray(data)
    for i in range(len(out)):
        v = i + key + 0x23D
        v *= key
        v ^= i % 7
        out[i] ^= v & 0xFF
    return zlib.decompress(bytes(out))


def main():
    root, index, outdir = sys.argv[1:4]
    os.makedirs(outdir, exist_ok=True)
    want = {}
    for line in open(index, encoding="utf-8"):
        f, p = line.rstrip("\n").split("\t")
        base = p.rsplit("/", 1)[-1].removesuffix(".bytes")
        if "/en-us/" in p and base in NAMES:
            want[base] = f
    key = None
    for base, f in want.items():
        env = UnityPy.load(os.path.join(root, f))
        for obj in env.objects:
            if obj.type.name == "TextAsset":
                raw = obj.read().m_Script
                raw = raw.encode("utf-8", "surrogateescape") if isinstance(raw, str) else bytes(raw)
                keys = [key] if key is not None else range(256)
                for k in keys:
                    try:
                        dec = decrypt(raw[:4096] if key is None else raw, k) if False else decrypt(raw, k)
                        key = k
                        break
                    except Exception:
                        continue
                else:
                    print(base, "no key works; size", len(raw), raw[:16].hex())
                    continue
                open(os.path.join(outdir, base + ".bin"), "wb").write(dec)
                print(base, "key", hex(key), "size", len(raw), "->", len(dec), dec[:32].hex())


if __name__ == "__main__":
    main()
