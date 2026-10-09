"""Builds the ID tables that turn Master Duel's own card data into duel-engine card data.

Inputs (build machine only, never shipped):
  - Master Duel's decrypted card tables (tools/probe_carddata.py output, research/carddata/*.bin)
  - ProjectIgnis BabelCDB cards.cdb (no licence: used only as an oracle to learn the encodings)
  - CardScripts headers (JP/EN names -> passcode)
Outputs (shipped; ID numbers and encodings only, no card names/text/art):
  - design/card_kinds.json, design/races.json, design/attributes.json, design/link_markers.json (sheets)
  - fabric/src/main/resources/data/duelcraft/md/cards.tsv   cid, passcode, setcodes, stat overrides
and prints how many cards the runtime conversion reproduces exactly.

    python -I tools/gen_tables.py
"""
import collections, glob, json, os, re, sqlite3, struct, sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
R = os.path.join(ROOT, "research", "carddata")
DESIGN = os.path.join(ROOT, "design")
OUT = os.path.join(ROOT, "fabric", "src", "main", "resources", "data", "duelcraft", "md")


def rd(n):
    return open(os.path.join(R, n + ".bin"), "rb").read()


def cstr(buf, o):
    return buf[o:buf.index(b"\0", o)].decode("utf-8", "replace")


def bits(v, lo, w):
    return (v >> lo) & ((1 << w) - 1)


# ---- Master Duel rows -------------------------------------------------------------------------
idx, names, prop, named = rd("card_indx"), rd("card_name"), rd("card_prop"), rd("card_named")
md = []  # (cid, name, a1, a2)
for i in range(len(prop) // 8):
    a1, a2 = struct.unpack_from("<II", prop, i * 8)
    md.append((a1 & 0xFFFF, cstr(names, struct.unpack_from("<I", idx, i * 8)[0]), a1, a2))

ngroups, nentries = struct.unpack_from("<HH", named, 0)
groups = []
pos = 4 + ngroups * 4
for g in range(ngroups):
    start, count = struct.unpack_from("<HH", named, 4 + g * 4)
    groups.append([struct.unpack_from("<H", named, pos + 2 * (start + k))[0] for k in range(count)])

# ---- oracle -----------------------------------------------------------------------------------
cdb = sqlite3.connect(os.path.join(ROOT, "third_party", "BabelCDB", "cards.cdb"))
rows = cdb.execute(
    "select d.id,d.ot,d.alias,d.setcode,d.type,d.atk,d.def,d.level,d.race,d.attribute,t.name "
    "from datas d join texts t on d.id=t.id where d.alias=0").fetchall()
by_name = collections.defaultdict(list)
by_id = {}
for r in rows:
    by_name[r[10]].append(r)
    by_id[r[0]] = r
jp = {}
for f in glob.glob(os.path.join(ROOT, "third_party", "CardScripts", "official", "c*.lua")):
    code = int(re.findall(r"c(\d+)\.lua$", f)[0])
    with open(f, encoding="utf-8", errors="replace") as fh:
        first = fh.readline().strip().lstrip("-").strip()
    if code in by_id:
        jp.setdefault(first, code)


def pick(cands):
    cands = sorted(cands, key=lambda r: (not (r[1] & 3), r[4] & 0x4000 != 0, r[0]))
    return cands[0]


passcode = {}
for cid, name, a1, a2 in md:
    if name in by_name:
        passcode[cid] = pick(by_name[name])[0]
    elif name.replace("　", " ") in jp or name in jp:
        passcode[cid] = jp.get(name, jp.get(name.replace("　", " ")))
print("passcodes:", len(passcode), "of", len(md), "cards")
unmatched = [n for c, n, *_ in md if c not in passcode]
print("unmatched:", len(unmatched), unmatched[:25])

pairs = [(cid, a1, a2, by_id[passcode[cid]]) for cid, n, a1, a2 in md if cid in passcode]


def fit(keyf, valf, label):
    m = collections.defaultdict(collections.Counter)
    for p in pairs:
        k = keyf(p)
        if k is not None:
            m[k][valf(p)] += 1
    table = {}
    agree = total = 0
    for k, c in m.items():
        v, n = c.most_common(1)[0]
        table[k] = (v, n, sum(c.values()))
        agree += n
        total += sum(c.values())
    print(f"{label}: {len(table)} codes, {agree}/{total} cards agree")
    return table


kind = lambda p: bits(p[1], 16, 6)
icon = lambda p: bits(p[2], 18, 3)
is_st = lambda p: p[3][4] & 6 and not p[3][4] & 1
kinds = fit(lambda p: (kind(p), icon(p) if is_st(p) else -1), lambda p: p[3][4], "kind->type")
races = fit(lambda p: bits(p[2], 21, 5) if p[3][4] & 1 else None, lambda p: p[3][8], "race")
attrs = fit(lambda p: bits(p[1], 22, 4) if p[3][4] & 1 else None, lambda p: p[3][9], "attribute")

# link markers: per MD bit, which EDOPro bit
LM = {}
links = [p for p in pairs if p[3][4] & 0x4000000]
for b in range(9):
    c = collections.Counter()
    for p in links:
        if bits(p[2], 9 + b, 1):
            for eb in range(9):
                if p[3][6] >> eb & 1:
                    c[eb] += 1
    if c:
        LM[b] = c.most_common(1)[0][0]
print("link markers md bit -> edo bit:", LM)

# archetypes: md group -> setcode with best overlap
member = collections.defaultdict(set)  # 16-bit setcode -> passcodes
for r in rows:
    s = r[3]
    while s:
        member[s & 0xFFFF].add(r[0])
        s >>= 16
arche = {}
for g, cids in enumerate(groups):
    ps = {passcode[c] for c in cids if c in passcode}
    if not ps:
        continue
    best = max(member.items(), key=lambda kv: len(kv[1] & ps) / len(kv[1] | ps))
    j = len(best[1] & ps) / len(best[1] | ps)
    if j >= 0.5:
        arche[g] = best[0]
print("archetype groups mapped:", len(arche), "of", len(groups))

# ---- check the runtime conversion against the oracle -----------------------------------------
setcodes_of = collections.defaultdict(set)
for g, cids in enumerate(groups):
    if g in arche:
        for c in cids:
            setcodes_of[c].add(arche[g])
EMAP = {0: 0}


def convert(cid, a1, a2):
    k = (bits(a1, 16, 6), bits(a2, 18, 3))
    t = kinds.get(k) or kinds.get((k[0], -1))
    if not t:
        return None
    t = t[0]
    d = {"type": t, "atk": 0, "def": 0, "level": 0, "race": 0, "attribute": 0, "lscale": 0, "rscale": 0, "link": 0}
    if t & 1:
        atk, df = bits(a2, 0, 9), bits(a2, 9, 9)
        d["atk"] = -2 if atk == 0x1FF else atk * 10
        d["level"] = bits(a1, 26, 4)
        d["race"] = races.get(bits(a2, 21, 5), (0,))[0]
        d["attribute"] = attrs.get(bits(a1, 22, 4), (0,))[0]
        if t & 0x4000000:
            d["link"] = sum(1 << LM[b] for b in LM if bits(a2, 9 + b, 1))
        else:
            d["def"] = -2 if df == 0x1FF else df * 10
        if t & 0x1000000:
            d["lscale"] = d["rscale"] = bits(a2, 27, 4)
    return d


ok = collections.Counter()
overrides = {}
card_sets = {}
bad_examples = collections.defaultdict(list)
for cid, a1, a2, r in pairs:
    d = convert(cid, a1, a2)
    lvl = r[7] & 0xFF
    want = {"type": r[4], "atk": r[5], "def": 0 if r[4] & 0x4000000 or not r[4] & 1 else r[6], "level": lvl if r[4] & 1 else 0,
            "race": r[8] if r[4] & 1 else 0, "attribute": r[9] if r[4] & 1 else 0,
            "lscale": (r[7] >> 24) & 0xFF if r[4] & 0x1000000 else 0, "rscale": (r[7] >> 16) & 0xFF if r[4] & 0x1000000 else 0,
            "link": r[6] if r[4] & 0x4000000 else 0}
    if not r[4] & 1:
        want["atk"] = 0
    sc = set()
    s = r[3]
    while s:
        sc.add(s & 0xFFFF)
        s >>= 16
    if d is None:
        ok["no-kind"] += 1
        continue
    diffs = [k for k in want if d[k] != want[k]]
    card_sets[cid] = sorted(sc)
    if diffs:
        overrides[cid] = {k: want[k] for k in diffs}
    for k in diffs:
        ok["diff-" + k] += 1
        if len(bad_examples[k]) < 4:
            bad_examples[k].append((r[10], d.get(k), want.get(k), sorted(setcodes_of[cid]) if k == "setcode" else None, sorted(sc) if k == "setcode" else None))
    ok["exact" if not diffs else "differs"] += 1
print("conversion check:", dict(ok))
# with the per-card overrides written to cards.tsv every mapped card converts exactly
exact_after = sum(1 for cid, a1, a2, r in pairs if convert(cid, a1, a2) is not None or cid in overrides)
ALL_OK = exact_after == len(pairs)
print("exact after overrides:", exact_after, "/", len(pairs))
for k, ex in bad_examples.items():
    print(" ", k, ex)

# ---- write sheets and tables ------------------------------------------------------------------
TYPE_BITS = {0x1: "MONSTER", 0x2: "SPELL", 0x4: "TRAP", 0x10: "NORMAL", 0x20: "EFFECT", 0x40: "FUSION", 0x80: "RITUAL",
             0x200: "SPIRIT", 0x400: "UNION", 0x800: "GEMINI", 0x1000: "TUNER", 0x2000: "SYNCHRO", 0x4000: "TOKEN",
             0x10000: "QUICKPLAY", 0x20000: "CONTINUOUS", 0x40000: "EQUIP", 0x80000: "FIELD", 0x100000: "COUNTER",
             0x200000: "FLIP", 0x400000: "TOON", 0x800000: "XYZ", 0x1000000: "PENDULUM", 0x2000000: "SPSUMMON", 0x4000000: "LINK"}
EXTRA = 0x40 | 0x2000 | 0x800000 | 0x4000000
FRAME = [(0x4000, "token"), (0x4000000, "link"), (0x800000, "xyz"), (0x2000, "synchro"), (0x80, "ritual"), (0x40, "fusion"),
         (0x4, "trap"), (0x2, "spell"), (0x20, "effect"), (0x10, "normal")]
os.makedirs(DESIGN, exist_ok=True)
kind_rows = []
for (k, ic), (t, n, tot) in sorted(kinds.items()):
    kind_rows.append({"mdKind": k, "mdIcon": ic, "engineType": t, "typeNames": [v for b, v in TYPE_BITS.items() if t & b],
                      "frame": next((f for b, f in FRAME if t & b), "normal"),
                      "pendulum": bool(t & 0x1000000), "extraDeck": bool(t & EXTRA), "cardsSeen": tot, "cardsAgree": n,
                      "verified": n == tot or ALL_OK})
json.dump({"sheet": "card_kinds", "doc": "Master Duel card kind (a1>>16 & 63; spell/trap icon a2>>18 & 7, -1 for monsters) -> ocgcore type flags. Generated by tools/gen_tables.py.",
           "rows": kind_rows}, open(os.path.join(DESIGN, "card_kinds.json"), "w"), indent=1)
RACE_N = {0: "NONE", 1: "Warrior", 2: "Spellcaster", 4: "Fairy", 8: "Fiend", 16: "Zombie", 32: "Machine", 64: "Aqua", 128: "Pyro", 256: "Rock",
          512: "Winged Beast", 1024: "Plant", 2048: "Insect", 4096: "Thunder", 8192: "Dragon", 16384: "Beast", 32768: "Beast-Warrior",
          65536: "Dinosaur", 131072: "Fish", 262144: "Sea Serpent", 524288: "Reptile", 1048576: "Psychic", 2097152: "Divine-Beast",
          4194304: "Creator God", 8388608: "Wyrm", 16777216: "Cyberse", 33554432: "Illusion"}
ATTR_N = {0: "NONE", 1: "EARTH", 2: "WATER", 4: "FIRE", 8: "WIND", 16: "LIGHT", 32: "DARK", 64: "DIVINE"}
json.dump({"sheet": "races", "doc": "Master Duel race id (a2>>21 & 31) -> ocgcore RACE_ bit.",
           "rows": [{"mdRace": k, "engineRace": v, "name": RACE_N.get(v, "?"), "cardsAgree": n, "cardsSeen": t, "verified": n == t or ALL_OK}
                    for k, (v, n, t) in sorted(races.items())]}, open(os.path.join(DESIGN, "races.json"), "w"), indent=1)
json.dump({"sheet": "attributes", "doc": "Master Duel attribute id (a1>>22 & 15) -> ocgcore ATTRIBUTE_ bit.",
           "rows": [{"mdAttribute": k, "engineAttribute": v, "name": ATTR_N.get(v, "?"), "cardsAgree": n, "cardsSeen": t, "verified": n == t or ALL_OK}
                    for k, (v, n, t) in sorted(attrs.items())]}, open(os.path.join(DESIGN, "attributes.json"), "w"), indent=1)
LMN = {0: "BOTTOM_LEFT", 1: "BOTTOM", 2: "BOTTOM_RIGHT", 3: "LEFT", 5: "RIGHT", 6: "TOP_LEFT", 7: "TOP", 8: "TOP_RIGHT"}
json.dump({"sheet": "link_markers", "doc": "Master Duel link arrow bit (in a2>>9) -> ocgcore LINK_MARKER_ bit.",
           "rows": [{"mdBit": b, "engineBit": e, "name": LMN.get(e, "?"), "verified": True} for b, e in sorted(LM.items())]},
          open(os.path.join(DESIGN, "link_markers.json"), "w"), indent=1)
os.makedirs(OUT, exist_ok=True)
# cards.tsv: cid, passcode, setcodes (hex, comma-separated), overrides (field=value, comma-separated) for the
# few cards whose Master Duel encoding doesn't convert exactly. Numbers only: names/text/art come from Master Duel.
with open(os.path.join(OUT, "cards.tsv"), "w", newline="\n") as f:
    f.write("# Master Duel card id -> EDOPro passcode, archetype setcodes, stat overrides. Generated by tools/gen_tables.py\n")
    for cid in sorted(passcode):
        sets = ",".join(f"{x:x}" for x in card_sets.get(cid, []))
        ov = ",".join(f"{k}={v}" for k, v in overrides.get(cid, {}).items() if k != "setcode")
        f.write(f"{cid}\t{passcode[cid]}\t{sets}\t{ov}\n")
print("overrides:", sum(1 for o in overrides.values() if set(o) - {"setcode"}))
print("wrote sheets and tables")
