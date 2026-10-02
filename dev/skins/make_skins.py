#!/usr/bin/env python3
"""Draws Postal's bundled NPC skins (64x64, classic arms) as PNGs.

    python3 dev/skins/make_skins.py        # writes postman.png and postmaster.png next to this script

The PNGs are the source of truth. To change a skin: edit this script (or the PNG), upload the PNG to
mineskin.org, and save the response's texture value and signature as <name>.texture.json and paste them into
navigation/NpcLook.java.
Needs Pillow.
"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))


def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


CLEAR = (0, 0, 0, 0)

# Cuboid UV layouts: (u, v, width, height, depth)
HEAD, HAT = (0, 0, 8, 8, 8), (32, 0, 8, 8, 8)
BODY, JACKET = (16, 16, 8, 12, 4), (16, 32, 8, 12, 4)
R_ARM, L_ARM = (40, 16, 4, 12, 4), (32, 48, 4, 12, 4)
R_LEG, L_LEG = (0, 16, 4, 12, 4), (16, 48, 4, 12, 4)


def faces(box):
    u, v, w, h, d = box
    return {
        'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
        'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h), 'back': (u + 2 * d + w, v + d, w, h),
    }


def paint(img, box, fn):
    """fn(face, x, y, w, h) -> RGBA or None (leave as is) for every pixel of every face."""
    for face, (fx, fy, fw, fh) in faces(box).items():
        for y in range(fh):
            for x in range(fw):
                c = fn(face, x, y, fw, fh)
                if c is not None:
                    img.putpixel((fx + x, fy + y), c)


def shade(c, f):
    return tuple(max(0, min(255, int(ch * f))) for ch in c[:3]) + (255,)


def noisy(c, x, y, amt=0.03):
    # Cheap deterministic texture so flat colours don't look plastic.
    return shade(c, 1 + amt * (((x * 5 + y * 3 + x * y) % 7) - 3) / 3)


def draw(p):
    img = Image.new('RGBA', (64, 64), CLEAR)
    skin, skin_dk = rgb(p['skin']), shade(rgb(p['skin']), 0.85)
    hair = rgb(p['hair'])
    shirt, trousers, shoes = rgb(p['shirt']), rgb(p['trousers']), rgb(p['shoes'])
    vest = rgb(p['vest']) if p.get('vest') else None
    cap, brim, badge = rgb(p['cap']), rgb(p['brim']), rgb(p['badge'])
    strap = rgb(p['strap']) if p.get('strap') else None

    def head(face, x, y, w, h):
        if face == 'top':
            return noisy(hair, x, y)
        if face == 'back':
            return noisy(hair, x, y) if y < 6 else skin_dk
        if face in ('left', 'right'):
            if y < 2 or (y < 5 and ((face == 'right' and x < 5) or (face == 'left' and x > 2))):
                return noisy(hair, x, y)
            return skin if y < 7 else skin_dk
        if face == 'bottom':
            return skin_dk
        # front
        if y < 2:
            return noisy(hair, x, y)
        if y == 2 and x in (0, 7):
            return noisy(hair, x, y)
        if y == 3 and x in (1, 2, 5, 6):  # eyebrows / eyes row
            return rgb(p['brow'])
        if y == 4 and x in (1, 6):
            return rgb('#FFFFFF')
        if y == 4 and x in (2, 5):
            return rgb(p['eyes'])
        if p.get('glasses') and y == 4 and x in (0, 3, 4, 7):
            return rgb('#3A3A3A')
        if y == 5 and x in (3, 4):
            return skin_dk  # nose
        if p.get('moustache') and y == 6 and 1 <= x <= 6:
            return noisy(hair, x, y)
        if y == 7 and 3 <= x <= 4:
            return rgb(p['mouth']) if not p.get('moustache') else skin
        return skin
    paint(img, HEAD, head)

    def hat(face, x, y, w, h):
        # Peaked postal cap on the overlay layer: crown, band, badge, and a brim stub at the front.
        if face == 'top':
            return noisy(cap, x, y)
        if face == 'bottom':
            return CLEAR
        if y < 2:
            return noisy(cap, x, y)
        if y == 2:
            if face == 'front' and x in (3, 4):
                return badge
            return shade(cap, 0.7)  # band
        if y == 3 and face == 'front':
            return brim
        return CLEAR
    paint(img, HAT, hat)

    def body(face, x, y, w, h):
        if face in ('top', 'bottom'):
            return noisy(shirt, x, y)
        if y == 9:
            return rgb(p['belt'])
        if y > 9:
            return noisy(trousers, x, y)
        if face == 'front':
            if strap and (x + (11 - y)) // 1 in (8, 9) and y < 9:  # strap from left shoulder to right hip
                return strap
            if p.get('tie') and x in (3, 4) and 1 <= y <= 8:
                return rgb(p['tie']) if y > 1 else shade(rgb(p['tie']), 0.8)
            if vest and (x < 3 or x > 4) and y >= 2:
                return noisy(vest, x, y)
            if y == 0 and x in (2, 5):
                return shade(shirt, 0.85)  # collar
            if x in (3, 4) and y in (2, 4, 6) and not p.get('tie') and not (strap and x + 11 - y in (8, 9)):
                return rgb('#E8E8E8')  # buttons
            if x == 1 and y == 3:
                return badge  # chest badge
        if face == 'back' and strap and (x + y) in (8, 9) and y < 9:
            return strap
        if vest and face in ('left', 'right', 'back') and y >= 1:
            return noisy(vest, x, y)
        return noisy(shirt, x, y)
    paint(img, BODY, body)

    def arm(face, x, y, w, h):
        if face == 'bottom':
            return skin
        if y >= 10:
            return skin  # hands
        if y == 9:
            return shade(shirt, 0.85)  # cuff
        return noisy(shirt, x, y)
    paint(img, R_ARM, arm)
    paint(img, L_ARM, arm)

    def leg(face, x, y, w, h):
        if face == 'bottom':
            return shoes
        if y >= 10:
            return shoes if y > 10 or face != 'back' else noisy(trousers, x, y)
        if face in ('right', 'left') and x == 1 and p.get('stripe'):
            return rgb(p['stripe'])
        return noisy(trousers, x, y)
    paint(img, R_LEG, leg)
    paint(img, L_LEG, leg)

    if p.get('satchel'):
        # Satchel on the right hip, on the right leg's overlay layer.
        sat = rgb(p['satchel'])
        def bag(face, x, y, w, h):
            if face in ('right', 'front', 'back') and y <= 4:
                if face == 'right' and y == 0:
                    return shade(sat, 0.75)  # flap edge
                return noisy(sat, x, y)
            return CLEAR
        paint(img, (0, 32, 4, 12, 4), bag)
    return img


POSTMAN = dict(
    skin='#C99B7A', hair='#4A3020', brow='#3A2618', eyes='#3B6FB6', mouth='#9C5A4A',
    shirt='#9CC3E6', trousers='#24345C', shoes='#2B2B2B', belt='#1E1E1E', stripe='#C0392B',
    cap='#24345C', brim='#151E36', badge='#E5C14B', strap='#6B4423', satchel='#7A4E2A',
)
POSTMASTER = dict(
    skin='#D6A887', hair='#B8B8B8', brow='#9A9A9A', eyes='#4B3A2A', mouth='#9C5A4A',
    moustache=True,
    shirt='#EEEEEE', vest='#8E1F2B', tie='#24345C', trousers='#24345C', shoes='#2B2B2B',
    belt='#1E1E1E', stripe='#E5C14B', cap='#8E1F2B', brim='#151E36', badge='#E5C14B',
)

if __name__ == '__main__':
    for name, p in (('postman', POSTMAN), ('postmaster', POSTMASTER)):
        out = os.path.join(HERE, name + '.png')
        draw(p).save(out)
        print('wrote', out)
