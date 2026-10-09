"""Preflight: lay every design sheet over the others before a build.

Blocking (exit 1): unfilled cells, references between sheets that don't resolve, deck cards that don't
resolve to a playable Master Duel card, prompt/event ids that aren't in ocgcore.
Reported (not blocking): rows not yet verified, systems whose class doesn't exist yet.

    python -I tools/preflight.py [--quiet]
"""
import json, os, re, struct, sys

sys.stdout.reconfigure(encoding="utf-8")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
D = os.path.join(ROOT, "design")
sheets = {}
for f in sorted(os.listdir(D)):
    if f.endswith(".json"):
        sheets[f[:-5]] = json.load(open(os.path.join(D, f), encoding="utf-8"))

errors, unfinished = [], []


def col_names(sh):
    if "columns" in sh:
        return sh["columns"]
    cols = []
    for r in sh["rows"]:
        for k in r:
            if k not in cols:
                cols.append(k)
    return cols


def key_of(name, r):
    for k in ("id", "msgId", "key", "entity", "file", "mdKind", "mdRace", "mdAttribute", "mdBit"):
        if k in r:
            return f"{name}[{r[k]}{'/' + str(r['mdIcon']) if 'mdIcon' in r else ''}]"
    return f"{name}[?]"


# 1. every cell filled
for name, sh in sheets.items():
    cols = col_names(sh)
    for r in sh["rows"]:
        for c in cols:
            v = r.get(c, None)
            empty = v is None or (isinstance(v, str) and v.strip() in ("", "TODO", "?"))
            # empty layouts/logText are meaningful only where the sheet doc says so
            if empty and (name, c) in (("duel_events", "layout"), ("duel_events", "logText"), ("systems", "dependsOn")):
                continue
            if empty and c == "extra" and name == "decks":
                continue
            if empty:
                errors.append(f"unfilled: {key_of(name, r)}.{c}")
        if r.get("verified") is False:
            unfinished.append(f"unverified: {key_of(name, r)}")

ids = lambda s, k="id": {r[k] for r in sheets[s]["rows"]}
systems = ids("systems")


def ref(sheet, col, target, tset, many=False):
    for r in sheets[sheet]["rows"]:
        vals = r[col] if many else [r[col]]
        for v in vals:
            if v not in tset:
                errors.append(f"unresolved: {key_of(sheet, r)}.{col} -> {target} '{v}'")


# 2. references
ref("systems", "dependsOn", "systems", systems, many=True)
ref("duel_prompts", "uiWidget", "ui_widgets", ids("ui_widgets"))
ref("duel_prompts", "aiPolicy", "ai_policies", ids("ai_policies"))
ref("payloads", "handler", "systems", systems)
ref("hooks", "system", "systems", systems)
ref("decks", "usedBy", "systems", systems)
ref("mob_duelists", "deck", "decks", ids("decks"))
ref("settings", "usedBy", "systems", systems)
ref("md_assets", "usedBy", "systems", systems)
for s, col, tgt in (("ui_widgets", "id", ("duel_prompts", "uiWidget")), ("ai_policies", "id", ("duel_prompts", "aiPolicy"))):
    used = {r[tgt[1]] for r in sheets[tgt[0]]["rows"]}
    for r in sheets[s]["rows"]:
        if r[col] not in used:
            errors.append(f"orphan: {key_of(s, r)} is used by no {tgt[0]} row")
for r in sheets["mob_duelists"]["rows"]:
    if not isinstance(r["rewardCards"], int) or r["rewardCards"] < 0:
        errors.append(f"bad value: {key_of('mob_duelists', r)}.rewardCards")
if "*" not in ids("mob_duelists", "entity"):
    errors.append("mob_duelists has no '*' fallback row")

# 3. message ids against ocgcore
consts = {}
for line in open(os.path.join(ROOT, "third_party", "ocgcore", "ocgapi_constants.h"), encoding="utf-8"):
    m = re.match(r"#define\s+(MSG_\w+)\s+(\d+)", line)
    if m:
        consts[m.group(1)] = int(m.group(2))
for s in ("duel_prompts", "duel_events"):
    seen = set()
    for r in sheets[s]["rows"]:
        if consts.get(r["name"]) != r["msgId"]:
            errors.append(f"bad msgId: {key_of(s, r)} {r['name']}={r['msgId']} (ocgcore says {consts.get(r['name'])})")
        if r["msgId"] in seen:
            errors.append(f"duplicate msgId {r['msgId']} in {s}")
        seen.add(r["msgId"])
both = ids("duel_prompts", "msgId") & ids("duel_events", "msgId")
for m in both:
    errors.append(f"msgId {m} is both a prompt and an event")
# every MSG_SELECT_/SORT_/ANNOUNCE_ the core can send must be a prompt
for n, v in consts.items():
    if re.match(r"MSG_(SELECT|SORT|ANNOUNCE)_|MSG_ROCK_PAPER", n) and v not in ids("duel_prompts", "msgId"):
        errors.append(f"missing prompt row for {n} ({v})")

# 4. decks resolve against Master Duel data + cards.tsv + scripts
R = os.path.join(ROOT, "research", "carddata")
resolved_decks = {}
if os.path.exists(os.path.join(R, "card_prop.bin")):
    rd = lambda n: open(os.path.join(R, n + ".bin"), "rb").read()
    idx, names, prop = rd("card_indx"), rd("card_name"), rd("card_prop")
    cstr = lambda b, o: b[o:b.index(b"\0", o)].decode("utf-8")
    by_name, kind_of = {}, {}
    for i in range(len(prop) // 8):
        a1, a2 = struct.unpack_from("<II", prop, i * 8)
        cid = a1 & 0xFFFF
        by_name.setdefault(cstr(names, struct.unpack_from("<I", idx, i * 8)[0]), []).append(cid)
        kind_of[cid] = ((a1 >> 16) & 0x3F, (a2 >> 18) & 7)
    passcode = {}
    for line in open(os.path.join(ROOT, "fabric", "src", "main", "resources", "data", "duelcraft", "md", "cards.tsv"), encoding="utf-8"):
        if not line.startswith("#"):
            c, p, *_ = line.rstrip("\n").split("\t")
            passcode[int(c)] = int(p)
    kinds = {(r["mdKind"], r["mdIcon"]): r for r in sheets["card_kinds"]["rows"]}
    scripts = os.path.join(ROOT, "third_party", "CardScripts")
    for r in sheets["decks"]["rows"]:
        out = {"main": [], "extra": []}
        for part in ("main", "extra"):
            for name, n in r[part]:
                cids = [c for c in by_name.get(name, []) if c in passcode]
                if not cids:
                    errors.append(f"deck {r['id']}: '{name}' not in Master Duel data with a passcode")
                    continue
                cid = cids[0]
                k = kinds.get(kind_of[cid]) or kinds.get((kind_of[cid][0], -1))
                if not k:
                    errors.append(f"deck {r['id']}: '{name}' kind {kind_of[cid]} not in card_kinds")
                    continue
                if k["extraDeck"] != (part == "extra"):
                    errors.append(f"deck {r['id']}: '{name}' belongs in the {'extra' if k['extraDeck'] else 'main'} deck")
                p = passcode[cid]
                if "NORMAL" not in k["typeNames"] and not any(os.path.exists(os.path.join(scripts, d, f"c{p}.lua")) for d in ("official", "pre-release")):
                    errors.append(f"deck {r['id']}: '{name}' ({p}) has no script")
                out[part] += [cid] * n
        if not 40 <= len(out["main"]) <= 60:
            errors.append(f"deck {r['id']}: main deck has {len(out['main'])} cards (40-60)")
        if len(out["extra"]) > 15:
            errors.append(f"deck {r['id']}: extra deck has {len(out['extra'])} cards (max 15)")
        from collections import Counter
        for cid, c in Counter(out["main"] + out["extra"]).items():
            if c > 3:
                errors.append(f"deck {r['id']}: more than 3 copies of cid {cid}")
        resolved_decks[r["id"]] = out
    json.dump(resolved_decks, open(os.path.join(ROOT, "build", "decks_resolved.json"), "w"), indent=0) if os.path.isdir(os.path.join(ROOT, "build")) else None
else:
    errors.append("research/carddata missing: run tools/probe_carddata.py to resolve decks")

# 5. implementation status
JAVA = [os.path.join(ROOT, "fabric", "src", s, "java") for s in ("main", "client")]
for r in sheets["systems"]["rows"]:
    jc = r["javaClass"]
    if jc.startswith("dev."):
        rel = jc.replace(".", "/") + ".java"
        if not any(os.path.exists(os.path.join(j, rel)) for j in JAVA):
            unfinished.append(f"not implemented: systems[{r['id']}] {jc}")
    elif not os.path.exists(os.path.join(ROOT, jc)):
        unfinished.append(f"not implemented: systems[{r['id']}] {jc}")

quiet = "--quiet" in sys.argv
print(f"sheets: {len(sheets)}, rows: {sum(len(s['rows']) for s in sheets.values())}")
print(f"BLOCKING: {len(errors)}")
for e in errors:
    print("  ", e)
print(f"unfinished: {len(unfinished)}")
if not quiet:
    for u in unfinished:
        print("  ", u)
sys.exit(1 if errors else 0)
