"""What each activated effect does, from the Project Ignis card scripts (systems: effect_hints).

  gen_effect_hints.py

An activation prompt only carries aux.Stringid(id, n) = (passcode << 20) | n. Each script names that description on
the effect it belongs to, next to the effect's categories, location, type and trigger, usually under a short comment
("--Destroy monster"). This writes fabric/src/main/resources/data/duelcraft/md/effect_hints.tsv:

  passcode <tab> n <tab> label <tab> tags

label: the script comment, in card-text words (empty when there is none); tags: comma-separated keywords
(categories, quick/trigger/ignition, gy/hand/banished/field/deck, summoned/destroyed/sent/attack/activation)
the duel screen uses to word the effect and to find its sentence in the card text. The scripts are AGPL-3.0 like
DuelCraft and ship with it (cardscripts.zip).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPTS = os.path.join(ROOT, "third_party", "CardScripts", "official")
OUT = os.path.join(ROOT, "fabric", "src", "main", "resources", "data", "duelcraft", "md", "effect_hints.tsv")

CATEGORIES = {
    "SPECIAL_SUMMON": "special summon", "SUMMON": "normal summon", "TOHAND": "add to hand", "SEARCH": "search",
    "DESTROY": "destroy", "ATKCHANGE": "change atk", "DEFCHANGE": "change def", "DRAW": "draw", "REMOVE": "banish",
    "DAMAGE": "damage", "RECOVER": "gain lp", "TOGRAVE": "send to gy", "TODECK": "return to deck", "SET": "set",
    "POSITION": "change position", "DISABLE": "negate effect", "NEGATE": "negate activation", "EQUIP": "equip",
    "HANDES": "discard", "TOKEN": "token", "CONTROL": "take control", "LVCHANGE": "change level", "COUNTER": "counter",
    "DECKDES": "mill", "FUSION_SUMMON": "fusion summon", "LEAVE_GRAVE": "from gy", "TOEXTRA": "to extra deck",
    "RELEASE": "tribute", "COIN": "coin toss", "DICE": "dice roll", "DISABLE_SUMMON": "negate summon",
}
RANGES = [("LOCATION_GRAVE", "gy"), ("LOCATION_HAND", "hand"), ("LOCATION_REMOVED", "banished"), ("LOCATION_FZONE", "field zone"),
          ("LOCATION_PZONE", "pendulum zone"), ("LOCATION_DECK", "deck")]
EVENTS = [("EVENT_SPSUMMON_SUCCESS", "summoned"), ("EVENT_SUMMON_SUCCESS", "summoned"), ("EVENT_FLIP_SUMMON_SUCCESS", "summoned"),
          ("EVENT_FLIP", "flipped"), ("EVENT_DESTROYED", "destroyed"), ("EVENT_BATTLE_DESTROYED", "destroyed"),
          ("EVENT_BATTLE_DESTROYING", "destroys by battle"), ("EVENT_TO_GRAVE", "sent to gy"), ("EVENT_REMOVE", "got banished"),
          ("EVENT_ATTACK_ANNOUNCE", "attack"), ("EVENT_BE_BATTLE_TARGET", "attack"), ("EVENT_CHAINING", "activation"),
          ("EVENT_PHASE+PHASE_END", "end phase"), ("EVENT_PHASE+PHASE_STANDBY", "standby phase"), ("EVENT_BE_MATERIAL", "used as material"),
          ("EVENT_LEAVE_FIELD", "leaves the field"), ("EVENT_TO_HAND", "added to hand"), ("EVENT_DAMAGE_STEP_END", "after battle"),
          ("EVENT_BATTLE_DAMAGE", "battle damage")]
TYPES = [("EFFECT_TYPE_QUICK", "quick"), ("EFFECT_TYPE_TRIGGER", "trigger"), ("EFFECT_TYPE_IGNITION", "ignition"), ("EFFECT_TYPE_ACTIVATE", "activate")]

CREATE = re.compile(r"^\s*(?:local\s+)?(\w+)\s*=\s*Effect\.CreateEffect\(")
CLONE = re.compile(r"^\s*(?:local\s+)?(\w+)\s*=\s*(\w+):Clone\(\)")
SET = re.compile(r"^\s*(\w+):Set(Description|Category|Type|Code|Range)\((.*)\)\s*$")
STRINGID = re.compile(r"aux\.Stringid\(\s*id\s*,\s*(\d+)\s*\)")
COMMENT = re.compile(r"^\s*--\s*(.+?)\s*$")


# script shorthand -> card-text wording
WORDS = [(r"\bsp ?summon(ed|s)?\b", "Special Summon"), (r"\bspecial summon\b", "Special Summon"), (r"\bnormal summon\b", "Normal Summon"),
         (r"\b(?:add )?to ?hand\b", "add to hand"), (r"\batk ?up\b", "gain ATK"), (r"\batk ?down\b", "lose ATK"),
         (r"^recover\b", "Gain LP"), (r"\b(?:send )?to ?grave\b", "send to GY"), (r"\b(?:return )?to ?deck\b", "return to Deck"),
         (r"^return add to hand\b", "return to hand"), (r"^salvage\b", "add from GY to hand"), (r"^turn set\b", "Set face-down"),
         (r"^pos(ition)?( change)?$", "change battle position"), (r"^to defense$", "change to Defense Position"), (r"^handes\b", "discard"),
         (r"^discard deck\b", "mill"), (r"^lv ?up$", "Level up"), (r"\bself\b", "this card"), (r"^atk( change)?$", "change ATK"),
         (r"^level( change)?$", "change Level"), (r"^level up$", "increase Level"), (r"^disable$", "negate effect"), (r"^control$", "take control"),
         (r"\bgrave(yard)?\b", "GY"), (r"\bgy\b", "GY"), (r"\batk\b", "ATK"), (r"\bdef\b", "DEF"), (r"\blv\b", "Level"),
         (r"\blp\b", "LP"), (r"\bdes\b", "destroy"), (r"\bdestory\b", "destroy"), (r"\bxyz\b", "Xyz"), (r"\bfusion\b", "Fusion"),
         (r"\bsynchro\b", "Synchro"), (r"\blink\b", "Link"), (r"\bpendulum\b", "Pendulum"), (r"\bspell/trap\b", "Spell/Trap"),
         (r"\bs/t\b", "Spell/Trap"), (r"\bmat(erial)?s?\b(?!\w)", "materials"), (r"\beff\b", "effect"), (r"\bopp\b", "opponent's"),
         (r"^remove\b", "Banish")]


def tidy(comment):
    c = comment.strip().rstrip(".")
    if not c or len(c) > 60 or not re.search(r"[A-Za-z]", c) or c.lower().startswith(("check", "register", "workaround", "fix", "to do", "todo", "hint", "global", "summon success", "effect")):
        return ""
    for pat, rep in WORDS:
        c = re.sub(pat, rep, c, flags=re.I)
    c = c.replace("(self)", "(this card)").replace("itself", "this card")
    return c[0].upper() + c[1:]


def parse(path):
    effects, by_var, last_comment = [], {}, ""
    lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
    for i, line in enumerate(lines):
        if i < 2:  # the Japanese and English card names
            continue
        m = COMMENT.match(line)
        if m:
            last_comment = m.group(1)
            continue
        m = CREATE.match(line)
        if m:
            e = {"comment": last_comment, "desc": [], "cat": "", "type": "", "code": "", "range": ""}
            effects.append(e)
            by_var[m.group(1)] = e
            last_comment = ""
            continue
        m = CLONE.match(line)
        if m and m.group(2) in by_var:
            src = by_var[m.group(2)]
            e = dict(src, desc=[], comment=last_comment or src["comment"])
            effects.append(e)
            by_var[m.group(1)] = e
            last_comment = ""
            continue
        m = SET.match(line)
        if m and m.group(1) in by_var:
            e, what, arg = by_var[m.group(1)], m.group(2), m.group(3)
            if what == "Description":
                e["desc"] = [int(n) for n in STRINGID.findall(arg)]
            else:
                e[what.lower() if what != "Category" else "cat"] = arg
        if line.strip() and not line.strip().startswith("--"):
            last_comment = "" if CREATE.match(line) is None and not SET.match(line) else last_comment
    return effects


def tags(e):
    t = []
    t += [w for k, w in CATEGORIES.items() if re.search(r"CATEGORY_" + k + r"\b", e["cat"])]
    t += [w for k, w in TYPES if k in e["type"]]
    t += [w for k, w in RANGES if k in e["range"]]
    t += [w for k, w in EVENTS if k in e["code"]]
    if "EVENT_FREE_CHAIN" in e["code"] and "quick" not in t and "activate" not in t:
        t.append("quick")
    return list(dict.fromkeys(t))


def main():
    rows = 0
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as o:
        o.write("# What each activated effect does, from the Project Ignis card scripts (AGPL-3.0). Generated by tools/gen_effect_hints.py.\n")
        o.write("# passcode\tn\tlabel\ttags\n")
        for f in sorted(os.listdir(SCRIPTS)):
            m = re.match(r"c(\d+)\.lua$", f)
            if not m:
                continue
            seen = set()
            for e in parse(os.path.join(SCRIPTS, f)):
                tg = tags(e)
                label = tidy(e["comment"])  # empty: the duel screen words it from the tags
                for n in e["desc"]:
                    if n in seen:
                        continue
                    seen.add(n)
                    o.write(f"{m.group(1)}\t{n}\t{label.replace(chr(9), ' ')}\t{','.join(tg)}\n")
                    rows += 1
    print(rows, "effect hints ->", OUT)


if __name__ == "__main__":
    main()
