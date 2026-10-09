"""Draws the mod's own textures (no game assets): card frame icons, deck box, card back, card frames, duel mat.

    .venv/Scripts/python -I tools/make_textures.py
"""
import json, os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A = os.path.join(ROOT, "fabric", "src", "main", "resources", "assets", "duelcraft")
FRAMES = {"normal": (227, 195, 107), "effect": (201, 115, 59), "fusion": (140, 91, 166), "ritual": (91, 143, 214),
          "synchro": (236, 236, 236), "xyz": (43, 43, 43), "link": (44, 90, 168), "spell": (29, 158, 116), "trap": (188, 90, 132),
          "token": (160, 160, 160)}
kinds = json.load(open(os.path.join(ROOT, "design", "card_kinds.json")))["rows"]
missing = {r["frame"] for r in kinds} - set(FRAMES)
assert not missing, f"no colour for frames {missing}"


def dark(c, f=0.55):
    return tuple(int(v * f) for v in c)


def light(c, f=0.45):
    return tuple(int(v + (255 - v) * f) for v in c)


def save(img, *path):
    p = os.path.join(A, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)


# item icons 16x16: a small card with an art window
for name, c in FRAMES.items():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([3, 1, 12, 14], fill=c + (255,), outline=dark(c) + (255,))
    d.rectangle([5, 3, 10, 8], fill=(70, 120, 160, 255), outline=dark(c, 0.4) + (255,))
    d.line([5, 10, 10, 10], fill=dark(c) + (255,))
    d.line([5, 12, 9, 12], fill=dark(c) + (255,))
    save(im, "textures", "item", f"card_{name}.png")

box = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
d = ImageDraw.Draw(box)
d.rectangle([2, 3, 13, 14], fill=(110, 60, 30, 255), outline=(60, 30, 12, 255))
d.rectangle([2, 3, 13, 6], fill=(140, 80, 40, 255), outline=(60, 30, 12, 255))
d.rectangle([6, 8, 9, 11], fill=(220, 180, 60, 255))
save(box, "textures", "item", "deck_box.png")

# card back 64x92 (original design: brown swirl back with an oval)
back = Image.new("RGBA", (64, 92), (90, 50, 25, 255))
d = ImageDraw.Draw(back)
d.rectangle([0, 0, 63, 91], outline=(40, 20, 10, 255), width=3)
for i in range(6):
    d.ellipse([8 + i * 2, 16 + i * 3, 56 - i * 2, 76 - i * 3], outline=(150 + i * 15, 90 + i * 10, 30, 255), width=2)
d.ellipse([22, 36, 42, 56], fill=(30, 20, 15, 255), outline=(220, 170, 60, 255), width=2)
save(back, "textures", "entity", "card_back.png")

# card fronts 64x92: frame colour, name bar, art window (art drawn over it at x 7..57, y 15..65), text box
for name, c in FRAMES.items():
    im = Image.new("RGBA", (64, 92), c + (255,))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 63, 91], outline=dark(c) + (255,), width=2)
    d.rectangle([4, 4, 59, 12], fill=light(c) + (255,), outline=dark(c) + (255,))
    d.rectangle([6, 14, 57, 65], fill=(20, 20, 30, 255), outline=dark(c, 0.3) + (255,))
    d.rectangle([4, 68, 59, 87], fill=(235, 225, 200, 255) if name not in ("xyz",) else (200, 200, 200, 255), outline=dark(c) + (255,))
    save(im, "textures", "entity", f"frame_{name}.png")

# duel mat 128x80: dark blue with zone outlines (5 columns + side piles per half)
# mat is 7.6 x 4.0 blocks drawn at 40 px/block (304 x 160); card 0.6 x 0.87 at x = -3.2..3.2 step 0.8, z = +-0.6, +-1.5
PX = 40
mat = Image.new("RGBA", (int(7.6 * PX), int(4.0 * PX)), (18, 28, 52, 240))
d = ImageDraw.Draw(mat)
d.rectangle([0, 0, mat.width - 1, mat.height - 1], outline=(200, 170, 80, 255), width=3)
d.line([6, mat.height // 2, mat.width - 7, mat.height // 2], fill=(200, 170, 80, 160), width=2)
for zc in (-1.5, -0.6, 0.6, 1.5):
    for i in range(9):
        xc = -3.2 + i * 0.8
        if abs(zc) == 1.5 and i == 8:
            continue
        x0, x1 = (xc + 3.8 - 0.32) * PX, (xc + 3.8 + 0.32) * PX
        y0, y1 = (zc + 2.0 - 0.46) * PX, (zc + 2.0 + 0.46) * PX
        d.rectangle([x0, y0, x1, y1], outline=(110, 150, 210, 170), width=2)
for xc in (-0.8, 0.8):  # extra monster zones on the centre line
    d.rectangle([(xc + 3.8 - 0.32) * PX, (2.0 - 0.46) * PX, (xc + 3.8 + 0.32) * PX, (2.0 + 0.46) * PX], outline=(210, 170, 110, 170), width=2)
save(mat, "textures", "entity", "mat.png")

# item models: one select model on custom_model_data strings[0] -> frame
os.makedirs(os.path.join(A, "items"), exist_ok=True)
cases = [{"when": f, "model": {"type": "minecraft:model", "model": f"duelcraft:item/card_{f}"}} for f in FRAMES]
json.dump({"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0, "cases": cases,
                     "fallback": {"type": "minecraft:model", "model": "duelcraft:item/card_normal"}}},
          open(os.path.join(A, "items", "card.json"), "w"), indent=1)
json.dump({"model": {"type": "minecraft:model", "model": "duelcraft:item/deck_box"}}, open(os.path.join(A, "items", "deck_box.json"), "w"), indent=1)
os.makedirs(os.path.join(A, "models", "item"), exist_ok=True)
for f in list(FRAMES) + ["deck_box"]:
    tex = f"duelcraft:item/{'card_' + f if f != 'deck_box' else f}"
    json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": tex}},
              open(os.path.join(A, "models", "item", f"{'card_' + f if f != 'deck_box' else f}.json"), "w"), indent=1)
print("textures and models written for", len(FRAMES), "frames")
