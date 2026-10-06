#!/usr/bin/env python3
"""Generates the "towns" test world for Postal: three small towns with hills, a river, bridges, doors,
gates, a tunnel, trees and hedges, each with a post office and five addresses joined by routes.

It writes two things, both reproducible block for block:

  --datapack DIR   a data pack whose function postal_towns:build builds the towns on a superflat world
                   (surface y=-60); drop it in <world>/datapacks and run /function postal_towns:build
  --config FILE    the matching Postal config (Central, offices, addresses, routes), appended to FILE
  --routes FILE    a JSON summary of each address and whether a postman is expected to reach it

Every route is checked against the blocks this script places: each step along a route must stand on
something walkable with two blocks of headroom (doors and fence gates count as passable), and no
waypoint may be more than MAX_GAP blocks from the next. The script fails if a route doesn't check out.

  --map FILE       a top-down picture of the towns with every route drawn on it (needs Pillow)
  --iso DIR        an isometric picture of each town, to check the buildings (needs Pillow)

Usage: build_towns.py [--datapack DIR] [--config FILE] [--routes FILE] [--map FILE] [--iso DIR]
"""
import argparse
import json
import math
import os
import random
import sys

DATA_PACK_FORMAT = (101, 1)  # Paper 26.1.2's data pack version (version.json "pack_version")
G = -60          # superflat: grass at y=-61, so feet stand at y=-60
MAX_FILL = 32768
MAX_GAP = 10.0   # longest allowed step between two waypoints

AIR = "minecraft:air"
# Blocks a postman can walk through (or open); everything else placed here is solid.
PASSABLE = ("air", "water", "sign", "door", "fence_gate", "ladder", "wheat", "torch", "leaves_top")


# Each town's architecture. wall_x/wall_z are the wall blocks for walls running along x and along z
# (they differ for horizontal logs).
THEMES = {
    "civic": {  # Central: a white stone civic post office
        "base": "minecraft:polished_andesite", "wall_x": "minecraft:quartz_block", "wall_z": "minecraft:quartz_block",
        "corner": "minecraft:quartz_pillar", "floor": "minecraft:polished_andesite",
        "roof": "minecraft:stone_brick_stairs", "roof_block": "minecraft:stone_bricks",
        "window": "minecraft:glass_pane", "sign": "minecraft:birch_wall_sign", "sign_color": "red",
        "base_rows": 1, "flag": "minecraft:red_wool",
    },
    "alpine": {  # Hillcrest: stone ground floor, spruce above, dark slate roofs
        "base": "minecraft:stone_bricks", "wall_x": "minecraft:spruce_planks", "wall_z": "minecraft:spruce_planks",
        "corner": "minecraft:spruce_log", "floor": "minecraft:spruce_planks",
        "roof": "minecraft:deepslate_tile_stairs", "roof_block": "minecraft:deepslate_tiles",
        "window": "minecraft:glass_pane", "sign": "minecraft:spruce_wall_sign", "sign_color": "white",
        "base_rows": 2, "chimney": "minecraft:stone_bricks", "flag": "minecraft:blue_wool",
    },
    "tudor": {  # Riverside: brick base, white plaster, dark timber frame and roofs
        "base": "minecraft:bricks", "wall_x": "minecraft:white_terracotta", "wall_z": "minecraft:white_terracotta",
        "corner": "minecraft:dark_oak_log", "floor": "minecraft:oak_planks",
        "roof": "minecraft:dark_oak_stairs", "roof_block": "minecraft:dark_oak_planks",
        "window": "minecraft:glass_pane", "sign": "minecraft:dark_oak_wall_sign", "sign_color": "white",
        "base_rows": 1, "beams": "minecraft:dark_oak_log", "chimney": "minecraft:bricks",
        "flag": "minecraft:red_wool",
    },
    "cabin": {  # Woodvale: log cabins on cobblestone, spruce roofs
        "base": "minecraft:cobblestone", "wall_x": "minecraft:oak_log[axis=x]", "wall_z": "minecraft:oak_log[axis=z]",
        "corner": "minecraft:spruce_log", "floor": "minecraft:spruce_planks",
        "roof": "minecraft:spruce_stairs", "roof_block": "minecraft:spruce_planks",
        "window": "minecraft:glass_pane", "sign": "minecraft:oak_wall_sign", "sign_color": "black",
        "base_rows": 1, "chimney": "minecraft:cobblestone", "flag": "minecraft:lime_wool",
    },
}


def passable(block):
    if block is None:
        return True
    name = block.split("[")[0].split("{")[0].replace("minecraft:", "")
    return any(p in name for p in PASSABLE) and "trapdoor" not in name


class World:
    def __init__(self):
        self.commands = []
        self.blocks = {}  # (x, y, z) -> block, for route checks; the superflat ground is implicit

    def block_at(self, x, y, z):
        if (x, y, z) in self.blocks:
            return self.blocks[(x, y, z)]
        return "minecraft:grass_block" if y < G else None

    def fill(self, x1, y1, z1, x2, y2, z2, block, mode=""):
        x1, x2 = sorted((x1, x2))
        y1, y2 = sorted((y1, y2))
        z1, z2 = sorted((z1, z2))
        # Split along x so no single /fill exceeds the block limit.
        per_x = (y2 - y1 + 1) * (z2 - z1 + 1)
        step = max(1, MAX_FILL // per_x)
        for xs in range(x1, x2 + 1, step):
            xe = min(x2, xs + step - 1)
            self.commands.append(f"fill {xs} {y1} {z1} {xe} {y2} {z2} {block}{(' ' + mode) if mode else ''}")
        for x in range(x1, x2 + 1):
            for y in range(y1, y2 + 1):
                for z in range(z1, z2 + 1):
                    if mode == "keep" and self.block_at(x, y, z) is not None:
                        continue
                    self.blocks[(x, y, z)] = None if block == AIR else block

    def set(self, x, y, z, block):
        self.commands.append(f"setblock {x} {y} {z} {block}")
        self.blocks[(x, y, z)] = None if block == AIR else block

    # ---- Pieces ----------------------------------------------------------------------------

    def mailbox(self, x, y, z, facing, line2, line3):
        """A chest with its [Postal_Mail] sign on the front, as /setlocal and /setaddr make them."""
        self.set(x, y, z, f"minecraft:chest[facing={facing}]")
        dx, dz = {"south": (0, 1), "north": (0, -1), "east": (1, 0), "west": (-1, 0)}[facing]
        text = '["[Postal_Mail]","%s","%s",""]' % (line2, line3)
        self.set(x + dx, y, z + dz, "minecraft:oak_wall_sign[facing=%s]{front_text:{messages:%s}}" % (facing, text))

    def door(self, x, y, z, facing):
        self.set(x, y, z, f"minecraft:oak_door[facing={facing},half=lower]")
        self.set(x, y + 1, z, f"minecraft:oak_door[facing={facing},half=upper]")

    def assert_clear(self, x1, z1, x2, z2, y, height, what):
        """Fails if anything already stands in the box, or if it isn't on level, solid ground."""
        for x in range(x1, x2 + 1):
            for z in range(z1, z2 + 1):
                for yy in range(y, y + height):
                    if self.block_at(x, yy, z) is not None:
                        raise SystemExit(f"{what} overlaps {self.block_at(x, yy, z)} at ({x}, {yy}, {z})")
                if passable(self.block_at(x, y - 1, z)):
                    raise SystemExit(f"{what} isn't on level ground at ({x}, {y - 1}, {z})")

    def building(self, x1, z1, x2, z2, y, front, theme, door_at, height=4, sign=None, what="building"):
        """A themed building: base course, walls with corner posts and windows, a door in the front wall,
        a floor, and a gabled roof with its ridge parallel to the front. Returns nothing; the mailbox is
        placed separately against (or inside) the front wall."""
        t = THEMES[theme]
        self.assert_clear(x1, z1, x2, z2, y, height, what)
        ns = front in ("north", "south")  # ridge along x if the front faces north or south
        top = y + height - 1
        # Walls: base course, then wall blocks; corners get posts; the inside is emptied.
        base_top = y + t.get("base_rows", 1) - 1
        self.fill(x1, y, z1, x2, base_top, z2, t["base"])
        self.fill(x1, base_top + 1, z1, x2, top, z2, t["wall_x"] if ns else t["wall_z"])
        if t.get("wall_z") != t.get("wall_x"):
            self.fill(x1, base_top + 1, z1, x1, top, z2, t["wall_z"])
            self.fill(x2, base_top + 1, z1, x2, top, z2, t["wall_z"])
            self.fill(x1, base_top + 1, z1, x2, top, z1, t["wall_x"])
            self.fill(x1, base_top + 1, z2, x2, top, z2, t["wall_x"])
        for cx, cz in ((x1, z1), (x1, z2), (x2, z1), (x2, z2)):
            self.fill(cx, y, cz, cx, top, cz, t["corner"])
        self.fill(x1 + 1, y, z1 + 1, x2 - 1, top, z2 - 1, AIR)
        self.fill(x1 + 1, y - 1, z1 + 1, x2 - 1, y - 1, z2 - 1, t["floor"])
        # Windows: every third block along each wall, at eye height, away from corners.
        for x in range(x1 + 2, x2 - 1, 3):
            self.set(x, y + 2, z1, t["window"])
            self.set(x, y + 2, z2, t["window"])
        for z in range(z1 + 2, z2 - 1, 3):
            self.set(x1, y + 2, z, t["window"])
            self.set(x2, y + 2, z, t["window"])
        # Timber framing: posts every fourth block along each wall.
        if t.get("beams"):
            for x in range(x1 + 4, x2 - 1, 4):
                self.fill(x, y + 1, z1, x, top, z1, t["beams"])
                self.fill(x, y + 1, z2, x, top, z2, t["beams"])
            for z in range(z1 + 4, z2 - 1, 4):
                self.fill(x1, y + 1, z, x1, top, z, t["beams"])
                self.fill(x2, y + 1, z, x2, top, z, t["beams"])
        # Door in the front wall.
        if front == "south":
            dx, dz, facing = door_at, z2, "north"
        elif front == "north":
            dx, dz, facing = door_at, z1, "south"
        elif front == "east":
            dx, dz, facing = x2, door_at, "west"
        else:
            dx, dz, facing = x1, door_at, "east"
        self.door(dx, y, dz, facing)
        self.set(dx, y + 2, dz, t["wall_x"] if dz in (z1, z2) else t["wall_z"])  # a lintel to hang the sign on
        if sign:
            out = {"south": (0, 1), "north": (0, -1), "east": (1, 0), "west": (-1, 0)}[front]
            text = '["","%s","%s",""]' % sign
            self.set(dx + out[0], y + 2, dz + out[1],
                     "%s[facing=%s]{front_text:{messages:%s,color:\"%s\"}}" % (t["sign"], front, text, t["sign_color"]))
        # Gabled roof with a one-block overhang all round.
        if ns:
            a1, a2, b1, b2 = x1 - 1, x2 + 1, z1 - 1, z2 + 1
        else:
            a1, a2, b1, b2 = z1 - 1, z2 + 1, x1 - 1, x2 + 1
        i = 0
        while b1 + i <= b2 - i:
            ry = top + 1 + i
            lo, hi = b1 + i, b2 - i
            if lo == hi:
                self.roof_row(ns, a1, a2, lo, ry, t["roof_block"])
            else:
                self.roof_row(ns, a1, a2, lo, ry, "%s[facing=%s]" % (t["roof"], "south" if ns else "east"))
                self.roof_row(ns, a1, a2, hi, ry, "%s[facing=%s]" % (t["roof"], "north" if ns else "west"))
                # Gable ends: fill the wall up under this roof row.
                if lo + 1 <= hi - 1:
                    gable = t["wall_z"] if ns else t["wall_x"]
                    if ns:
                        self.fill(x1, ry, lo + 1, x1, ry, hi - 1, gable)
                        self.fill(x2, ry, lo + 1, x2, ry, hi - 1, gable)
                    else:
                        self.fill(lo + 1, ry, z1, hi - 1, ry, z1, gable)
                        self.fill(lo + 1, ry, z2, hi - 1, ry, z2, gable)
            i += 1
        if t.get("chimney"):
            back = {"south": (x2 - 1, z1 + 1), "north": (x2 - 1, z2 - 1),
                    "east": (x1 + 1, z2 - 1), "west": (x2 - 1, z2 - 1)}[front]
            self.chimney(back[0], back[1], y, top + i, t["chimney"])
        self.last_box = (x1, z1, x2, z2)

    def chimney(self, x, z, y, peak, block):
        self.fill(x, y, z, x, peak + 2, z, block)

    def roof_row(self, ns, a1, a2, b, y, block):
        if ns:
            self.fill(a1, y, b, a2, y, b, block)
        else:
            self.fill(b, y, a1, b, y, a2, block)

    def postal_building(self, chest, facing, theme, town, name, width=7, depth=7, door_offset=2, office=False):
        """A building standing behind its mailbox: the chest sits against the front wall, outside, with its
        [Postal_Mail] sign facing the street, and the door is beside it."""
        cx, cy, cz = chest
        hw = width // 2
        if facing == "south":
            box = (cx - hw, cz - depth, cx + hw, cz - 1)
            door_at = cx + door_offset
        elif facing == "north":
            box = (cx - hw, cz + 1, cx + hw, cz + depth)
            door_at = cx + door_offset
        elif facing == "east":
            box = (cx - depth, cz - hw, cx - 1, cz + hw)
            door_at = cz + door_offset
        else:
            box = (cx + 1, cz - hw, cx + depth, cz + hw)
            door_at = cz + door_offset
        sign = ("Post Office", town) if office else (name, town)
        self.building(*box, cy, facing, theme, door_at, height=5 if office else 4, sign=sign,
                      what=f"{town}/{name}")
        self.mailbox(cx, cy, cz, facing, town, "[Local]" if office else name)
        if office:
            # A flagpole in the town's colour at the front corner.
            bx1, bz1, bx2, bz2 = box
            fx, fz = {"south": (bx2 + 2, bz2 + 1), "north": (bx2 + 2, bz1 - 1),
                      "east": (bx2 + 1, bz2 + 2), "west": (bx1 - 1, bz2 + 2)}[facing]
            self.fill(fx, cy, fz, fx, cy + 7, fz, "minecraft:oak_fence")
            self.fill(fx + 1, cy + 6, fz, fx + 2, cy + 7, fz, THEMES[theme]["flag"])

    def tree(self, x, z, height=5):
        self.fill(x - 2, G + height - 2, z - 2, x + 2, G + height - 1, z + 2, "minecraft:oak_leaves[persistent=true]", "keep")
        self.fill(x - 1, G + height, z - 1, x + 1, G + height, z + 1, "minecraft:oak_leaves[persistent=true]", "keep")
        self.fill(x, G, z, x, G + height - 1, z, "minecraft:oak_log")

    # ---- Route checks ----------------------------------------------------------------------

    def walkable(self, x, z, ylo, yhi):
        """True if a postman can stand somewhere at column (x, z) between ylo and yhi."""
        for y in range(ylo - 1, yhi + 2):
            below = self.block_at(x, y - 1, z)
            if below is None or passable(below) and "ladder" not in below:
                continue
            if passable(self.block_at(x, y, z)) and passable(self.block_at(x, y + 1, z)):
                return True
        return False

    def check_route(self, name, points):
        problems = []
        for a, b in zip(points, points[1:]):
            gap = math.dist((a[0], a[2]), (b[0], b[2]))
            if gap > MAX_GAP:
                problems.append(f"{a}->{b}: {gap:.1f} blocks between waypoints (max {MAX_GAP})")
            steps = max(1, int(gap * 4))
            for i in range(steps + 1):
                t = i / steps
                x = math.floor(a[0] + (b[0] - a[0]) * t + 0.5)
                z = math.floor(a[2] + (b[2] - a[2]) * t + 0.5)
                if not self.walkable(x, z, min(a[1], b[1]), max(a[1], b[1])):
                    problems.append(f"{a}->{b}: blocked at ({x}, {z})")
                    break
        for p in points:
            if not self.walkable(p[0], p[2], p[1], p[1]):
                problems.append(f"waypoint {p} isn't a place to stand")
        if problems:
            raise SystemExit("route %s doesn't check out:\n  %s" % (name, "\n  ".join(problems)))


# ---- The towns -------------------------------------------------------------------------------

def central(w):
    """Central: a civic post office on a plaza, flagpoles either side of the steps."""
    w.fill(-12, G - 1, -16, 12, G - 1, 6, "minecraft:stone_bricks")
    w.building(-8, -12, 8, -1, G, "south", "civic", 3, height=6, sign=("Central", "Post Office"), what="Central")
    w.set(0, G, 0, "minecraft:chest[facing=south]")
    # Portico: columns along the front carrying a quartz entablature.
    for x in (-7, -4, 4, 7):
        w.fill(x, G, 1, x, G + 5, 1, "minecraft:quartz_pillar")
    w.fill(-8, G + 6, 0, 8, G + 6, 1, "minecraft:smooth_quartz")
    w.fill(-8, G + 7, 1, 8, G + 7, 1, "minecraft:smooth_quartz_slab[type=bottom]")
    for x in (-10, 10):
        w.fill(x, G, 1, x, G + 6, 1, "minecraft:oak_fence")
        w.fill(x + 1, G + 5, 1, x + 1, G + 6, 1, "minecraft:red_wool")
    return {"location": (0, G, 2)}


def hillcrest(w):
    """Alpine: terraces climbed by stairs, a slab ramp and an L-shaped stair; a one-block step up."""
    # Terraces: T1 stands at y=-57, T2 at -54, T3 at -51.
    w.fill(90, G, -30, 140, G + 1, 30, "minecraft:dirt")
    w.fill(90, G + 2, -30, 140, G + 2, 30, "minecraft:grass_block")
    w.fill(105, G + 3, -20, 140, G + 4, 20, "minecraft:dirt")
    w.fill(105, G + 5, -20, 140, G + 5, 20, "minecraft:grass_block")
    w.fill(116, G + 6, -10, 140, G + 7, 10, "minecraft:dirt")
    w.fill(116, G + 8, -10, 140, G + 8, 10, "minecraft:grass_block")
    # T0 -> T1: three stone brick stairs up the west face, three wide.
    for i, x in enumerate((87, 88, 89)):
        if i:
            w.fill(x, G, -1, x, G + i - 1, 1, "minecraft:stone_bricks")
        w.fill(x, G + i, -1, x, G + i, 1, "minecraft:stone_brick_stairs[facing=east]")
    # T1 -> T2: a ramp of alternating slabs and full blocks, two wide.
    for i, x in enumerate(range(99, 105)):
        top = G + 3 + i // 2
        if top > G + 3:
            w.fill(x, G + 3, -15, x, top - 1, -14, "minecraft:cobblestone")
        w.fill(x, top, -15, x, top, -14,
               "minecraft:cobblestone_slab[type=bottom]" if i % 2 == 0 else "minecraft:cobblestone")
    # T2 -> T3: two stairs east, a landing, then one stair north (an L).
    w.set(115, G + 6, -13, "minecraft:oak_stairs[facing=east]")
    w.set(116, G + 6, -13, "minecraft:oak_planks")
    w.set(116, G + 7, -13, "minecraft:oak_stairs[facing=east]")
    w.fill(117, G + 6, -14, 118, G + 7, -12, "minecraft:oak_planks")
    w.fill(117, G + 6, -11, 118, G + 7, -11, "minecraft:oak_planks")
    w.fill(117, G + 8, -11, 118, G + 8, -11, "minecraft:oak_stairs[facing=north]")
    # A raised platform one block high: the postman has to step up.
    w.fill(70, G, 20, 80, G, 31, "minecraft:stone")

    w.postal_building((70, G, 0), "south", "alpine", "Hillcrest", "Office", width=11, depth=8, office=True)
    w.postal_building((75, G, -20), "south", "alpine", "Hillcrest", "Foot")
    w.postal_building((75, G + 1, 24), "north", "alpine", "Hillcrest", "Ledge", depth=7)
    w.postal_building((95, G + 3, 20), "south", "alpine", "Hillcrest", "Terrace")
    w.postal_building((110, G + 6, 12), "south", "alpine", "Hillcrest", "Ramp")
    w.postal_building((130, G + 9, 0), "south", "alpine", "Hillcrest", "Summit")

    to_t1 = [(70, G, 2), (76, G, 1), (82, G, 0), (86, G, 0), (90, G + 3, 0)]
    to_t2 = to_t1 + [(93, G + 3, -5), (96, G + 3, -10), (98, G + 3, -14), (105, G + 6, -14)]
    return {
        "location": (70, G, 2),
        "addresses": {
            "Foot": [(70, G, 2), (78, G, 2), (80, G, -4), (80, G, -11), (80, G, -18), (75, G, -18)],
            "Ledge": [(70, G, 2), (70, G, 8), (72, G, 14), (75, G, 18), (75, G + 1, 22)],
            "Terrace": to_t1 + [(96, G + 3, 3), (100, G + 3, 8), (100, G + 3, 14), (100, G + 3, 20),
                                (97, G + 3, 22), (95, G + 3, 22)],
            "Ramp": to_t2 + [(106, G + 6, -8), (106, G + 6, -2), (106, G + 6, 4), (106, G + 6, 10),
                             (106, G + 6, 14), (110, G + 6, 14)],
            "Summit": to_t2 + [(109, G + 6, -13), (114, G + 6, -13), (117, G + 8, -13), (117, G + 9, -8),
                               (122, G + 9, -3), (126, G + 9, 2), (130, G + 9, 2)],
        },
        "expect_unreachable": [],
        "notes": {
            "Foot": "flat ground, around the post office",
            "Ledge": "a one-block step up onto a platform",
            "Terrace": "three stone brick stairs",
            "Ramp": "stairs, then a ramp of slabs and full blocks",
            "Summit": "stairs, the slab ramp, then an L-shaped stair with a landing",
        },
    }


def riverside(w):
    """Tudor: a river with a flat bridge and an arched bridge, a fence gate, a door and a narrow alley."""
    w.fill(-45, G - 2, 98, 45, G - 1, 102, "minecraft:water")
    # Flat bridge, three wide, with fence railings.
    w.fill(-21, G - 1, 97, -19, G - 1, 103, "minecraft:oak_planks")
    w.fill(-22, G, 98, -22, G, 102, "minecraft:oak_fence")
    w.fill(-18, G, 98, -18, G, 102, "minecraft:oak_fence")
    # Arched bridge: a stair up, a deck one block above the banks, a stair down.
    w.fill(19, G, 96, 21, G, 96, "minecraft:spruce_stairs[facing=south]")
    w.fill(19, G, 97, 21, G, 103, "minecraft:spruce_planks")
    w.fill(19, G, 104, 21, G, 104, "minecraft:spruce_stairs[facing=north]")
    # Gatehouse: a cottage in a fenced yard entered through a closed fence gate.
    w.fill(-34, G, 64, -22, G, 80, "minecraft:oak_fence")
    w.fill(-33, G, 65, -23, G, 79, AIR)
    w.set(-28, G, 80, "minecraft:oak_fence_gate[facing=south,open=false]")
    # Alley: one block wide between two brick walls, ending at the Alley house.
    w.fill(7, G, 72, 7, G + 2, 80, "minecraft:bricks")
    w.fill(9, G, 72, 9, G + 2, 80, "minecraft:bricks")

    w.postal_building((0, G, 85), "south", "tudor", "Riverside", "Office", width=11, depth=8, office=True)
    w.postal_building((-20, G, 115), "south", "tudor", "Riverside", "Bridgeend")
    w.postal_building((20, G, 115), "south", "tudor", "Riverside", "Archway")
    w.postal_building((-28, G, 74), "south", "tudor", "Riverside", "Gatehouse", depth=8)
    w.postal_building((8, G, 70), "south", "tudor", "Riverside", "Alley")
    # Doorstep: the mailbox is inside the house, through its closed front door.
    w.building(20, 70, 28, 78, G, "south", "tudor", 24, sign=("Doorstep", "Riverside"), what="Riverside/Doorstep")
    w.mailbox(24, G, 72, "south", "Riverside", "Doorstep")
    return {
        "location": (0, G, 87),
        "addresses": {
            "Bridgeend": [(0, G, 87), (-6, G, 90), (-12, G, 93), (-20, G, 95), (-20, G, 100), (-20, G, 105),
                          (-15, G, 106), (-15, G, 112), (-15, G, 117), (-20, G, 117)],
            "Archway": [(0, G, 87), (6, G, 90), (12, G, 93), (20, G, 94), (20, G + 1, 99), (20, G + 1, 102),
                        (20, G, 106), (25, G, 106), (25, G, 112), (25, G, 117), (20, G, 117)],
            "Gatehouse": [(0, G, 87), (-8, G, 86), (-16, G, 84), (-24, G, 83), (-28, G, 82), (-28, G, 78),
                          (-28, G, 76)],
            "Doorstep": [(0, G, 87), (8, G, 86), (16, G, 84), (24, G, 81), (24, G, 79), (24, G, 77), (24, G, 74)],
            "Alley": [(0, G, 87), (4, G, 86), (8, G, 83), (8, G, 79), (8, G, 75), (8, G, 72)],
        },
        "expect_unreachable": [],
        "notes": {
            "Bridgeend": "a flat bridge over the river",
            "Archway": "an arched bridge: stair up, deck, stair down",
            "Gatehouse": "a closed fence gate into a yard",
            "Doorstep": "a closed door into a house",
            "Alley": "a one-block-wide alley between walls",
        },
    }


def woodvale(w):
    """Log cabins in a forest: a winding glade, a tunnel through a mound, a hedge maze, a farm, and a loft
    reachable only by ladder (postmen can't climb; that address is expected to fail)."""
    # Tunnel: a mound with a 1-wide, 2-high tunnel along z=10.
    w.fill(-118, G, 0, -112, G + 5, 20, "minecraft:dirt")
    w.fill(-118, G + 6, 0, -112, G + 6, 20, "minecraft:grass_block")
    w.fill(-118, G, 10, -112, G + 1, 10, AIR)
    # Hedge maze: leaf walls two high along a zigzag corridor three wide, open at the entrance.
    corridor = [(-80, 13), (-80, 20), (-86, 20), (-86, 25), (-80, 25), (-80, 31)]
    for x in range(-91, -74):
        for z in range(10, 35):
            d = min(seg_dist((x, z), a, b) for a, b in zip(corridor, corridor[1:]))
            if 1.5 < d <= 2.5 and z > 13:
                w.fill(x, G, z, x, G + 1, z, "minecraft:oak_leaves[persistent=true]")
    # Farm: wheat on farmland with a water channel down the middle.
    w.fill(-102, G - 1, 26, -92, G - 1, 36, "minecraft:farmland[moisture=7]")
    w.fill(-102, G, 26, -92, G, 36, "minecraft:wheat[age=7]")
    w.fill(-97, G - 1, 26, -97, G - 1, 36, "minecraft:water")
    w.fill(-97, G, 26, -97, G, 36, AIR)

    w.postal_building((-70, G, 0), "south", "cabin", "Woodvale", "Office", width=11, depth=8, office=True)
    w.postal_building((-100, G, -20), "south", "cabin", "Woodvale", "Glade")
    w.postal_building((-125, G, 8), "south", "cabin", "Woodvale", "Tunnel")
    w.postal_building((-80, G, 34), "north", "cabin", "Woodvale", "Hedge", depth=6)
    w.postal_building((-106, G, 28), "south", "cabin", "Woodvale", "Farm")
    # Loft: a two-storey cabin; the mailbox is upstairs and only a ladder goes up.
    w.building(-130, -30, -124, -24, G, "east", "cabin", -27, height=9, sign=("Loft", "Woodvale"),
               what="Woodvale/Loft")
    w.fill(-129, G + 4, -29, -125, G + 4, -25, "minecraft:spruce_planks")
    for y in range(G, G + 5):
        w.set(-125, y, -28, "minecraft:ladder[facing=west]")
    w.mailbox(-127, G + 5, -27, "south", "Woodvale", "Loft")

    info = {
        "location": (-70, G, 2),
        "addresses": {
            "Glade": [(-70, G, 2), (-78, G, 3), (-82, G, -3), (-86, G, -8), (-92, G, -10), (-97, G, -14),
                      (-100, G, -18)],
            "Tunnel": [(-70, G, 2), (-76, G, 5), (-84, G, 8), (-92, G, 10), (-100, G, 10), (-108, G, 10),
                       (-111, G, 10), (-115, G, 10), (-119, G, 10), (-125, G, 10)],
            "Hedge": [(-70, G, 2), (-74, G, 8), (-78, G, 12), (-80, G, 15), (-80, G, 20), (-86, G, 20),
                      (-86, G, 25), (-80, G, 25), (-80, G, 29), (-80, G, 32)],
            "Farm": [(-70, G, 2), (-66, G, 10), (-66, G, 20), (-70, G, 28), (-73, G, 35), (-75, G, 43),
                     (-83, G, 45), (-91, G, 42), (-99, G, 39), (-104, G, 35), (-106, G, 30)],
            "Loft": [(-70, G, 2), (-78, G, 3), (-84, G, -4), (-90, G, -12), (-94, G, -18), (-94, G, -25),
                     (-96, G, -31), (-104, G, -31), (-112, G, -31), (-118, G, -28), (-123, G, -27),
                     (-125, G, -27), (-127, G + 5, -25)],
        },
        "expect_unreachable": ["Loft"],
        "notes": {
            "Glade": "a path winding between trees",
            "Tunnel": "a one-wide, two-high tunnel through a mound",
            "Hedge": "a zigzag corridor between hedges",
            "Farm": "around a wheat field",
            "Loft": "upstairs, only by ladder (expected to fail: postmen can't climb)",
        },
    }
    plant_trees(w, info)
    return info


def plant_trees(w, info):
    """Scatters trees through the forest, keeping trunks clear of every route and building."""
    rng = random.Random(4013)
    keep_clear = [p for pts in info["addresses"].values() for p in pts]
    placed = 0
    for _ in range(400):
        if placed >= 45:
            break
        x, z = rng.randint(-138, -62), rng.randint(-38, 42)
        if any(math.dist((x, z), (p[0], p[2])) < 4 for p in keep_clear):
            continue
        if any(seg_dist((x, z), (a[0], a[2]), (b[0], b[2])) < 3
               for pts in info["addresses"].values() for a, b in zip(pts, pts[1:])):
            continue
        if any(w.blocks.get((x + dx, G, z + dz)) for dx in range(-2, 3) for dz in range(-2, 3)):
            continue  # buildings, hedges, the farm
        w.tree(x, z, height=rng.randint(4, 6))
        placed += 1


def seg_dist(p, a, b):
    (px, pz), (ax, az), (bx, bz) = p, a, b
    dx, dz = bx - ax, bz - az
    if dx == dz == 0:
        return math.dist(p, a)
    t = max(0.0, min(1.0, ((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz)))
    return math.dist(p, (ax + t * dx, az + t * dz))


# ---- Output ----------------------------------------------------------------------------------

FORCELOAD = [(-16, -20, 16, 16), (60, -35, 145, 35), (-45, 60, 45, 125), (-140, -40, -60, 50)]


def build():
    w = World()
    towns = {"Central": central(w), "Hillcrest": hillcrest(w), "Riverside": riverside(w),
             "Woodvale": woodvale(w)}
    for town, info in towns.items():
        for addr, points in info.get("addresses", {}).items():
            if addr not in info["expect_unreachable"]:
                w.check_route(f"{town}/{addr}", points)
    return w, towns


def write_datapack(w, path):
    fn_dir = os.path.join(path, "data", "postal_towns", "function")
    os.makedirs(fn_dir, exist_ok=True)
    major, minor = DATA_PACK_FORMAT
    with open(os.path.join(path, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "Postal test towns",
                            "min_format": [major, 0], "max_format": [major, minor]}}, f, indent=2)
    with open(os.path.join(fn_dir, "build.mcfunction"), "w") as f:
        f.write("# Generated by dev/towns/build_towns.py; edit that, not this.\n")
        for x1, z1, x2, z2 in FORCELOAD:
            f.write(f"forceload add {x1} {z1} {x2} {z2}\n")
        for cmd in w.commands:
            f.write(cmd + "\n")
        f.write('say Postal test towns built.\n')


def loc(p):
    return f"world,{p[0]}.0,{p[1]}.0,{p[2]}.0"


def write_config(towns, path):
    lines = ["Postoffice:", "  Central:", f"    Location: {loc(towns['Central']['location'])}", "  Local:"]
    for town, info in towns.items():
        if town != "Central":
            lines += [f"    {town}:", f"      Location: {loc(info['location'])}"]
    lines.append("Address:")
    for town, info in towns.items():
        if town == "Central":
            continue
        lines.append(f"  {town}:")
        for addr, points in info["addresses"].items():
            lines += [f"    {addr}:", "      Residence:", f"        Location: {loc(points[-1])}", "      Route:"]
            for n, p in enumerate(points):
                lines += [f"        '{n}':", f"          Location: {loc(p)}"]
    with open(path, "a") as f:
        f.write("\n".join(lines) + "\n")


def write_routes(towns, path):
    out = {}
    for town, info in towns.items():
        for addr, points in info.get("addresses", {}).items():
            out.setdefault(town, {})[addr] = {
                "reachable": addr not in info["expect_unreachable"],
                "waypoints": len(points),
                "what": info["notes"][addr],
            }
    with open(path, "w") as f:
        json.dump(out, f, indent=2)


COLORS = [  # (substring of the block id, RGB), first match wins
    ("water", (64, 110, 200)), ("wheat", (215, 190, 90)), ("farmland", (120, 80, 40)), ("leaves", (60, 120, 50)),
    ("oak_log", (110, 85, 50)), ("spruce_log", (80, 60, 40)), ("dark_oak", (70, 50, 30)),
    ("deepslate", (70, 70, 80)), ("quartz", (235, 230, 225)), ("white_terracotta", (220, 205, 190)),
    ("stone_brick", (130, 130, 130)), ("brick", (150, 75, 60)), ("cobble", (115, 115, 115)), ("stone", (140, 140, 140)),
    ("andesite", (150, 150, 150)), ("spruce", (115, 85, 55)), ("oak", (170, 135, 80)), ("chest", (220, 160, 40)),
    ("sign", (250, 230, 120)), ("door", (200, 120, 60)), ("fence", (160, 120, 70)), ("wool", (200, 40, 40)),
    ("glass", (180, 220, 240)), ("ladder", (190, 150, 90)), ("grass", (95, 160, 70)), ("dirt", (130, 95, 60)),
]


def write_map(w, towns, path, scale=4):
    """A top-down picture of the towns: the highest block in each column, shaded by height, with every
    route drawn over it (unreachable ones in red)."""
    from PIL import Image, ImageDraw
    x0, x1, z0, z1 = -145, 150, -48, 132
    img = Image.new("RGB", ((x1 - x0) * scale, (z1 - z0) * scale), (95, 160, 70))
    top = {}
    for (x, y, z), block in w.blocks.items():
        if block is not None and (x, z) not in top or block is not None and y > top[(x, z)][0]:
            top[(x, z)] = (y, block)
    draw = ImageDraw.Draw(img)
    for (x, z), (y, block) in top.items():
        if not (x0 <= x < x1 and z0 <= z < z1):
            continue
        rgb = next((c for k, c in COLORS if k in block), (200, 0, 200))
        f = max(0.6, min(1.3, 1 + (y - G) * 0.03))
        rgb = tuple(min(255, int(c * f)) for c in rgb)
        px, pz = (x - x0) * scale, (z - z0) * scale
        draw.rectangle([px, pz, px + scale - 1, pz + scale - 1], fill=rgb)
    palette = [(255, 255, 255), (255, 210, 0), (0, 230, 255), (255, 120, 255), (120, 255, 120)]
    for town, info in towns.items():
        for i, (addr, points) in enumerate(info.get("addresses", {}).items()):
            color = (230, 30, 30) if addr in info["expect_unreachable"] else palette[i % len(palette)]
            xy = [((p[0] - x0 + 0.5) * scale, (p[2] - z0 + 0.5) * scale) for p in points]
            draw.line(xy, fill=color, width=2)
            for px, pz in xy:
                draw.ellipse([px - 2, pz - 2, px + 2, pz + 2], fill=color)
            px, pz = xy[-1]
            draw.text((px + 4, pz - 4), addr, fill=(0, 0, 0))
        if "location" in info:
            p = info["location"]
            draw.text(((p[0] - x0) * scale + 4, (p[2] - z0) * scale + 6), town, fill=(0, 0, 0))
    img.save(path)


ISO_VIEWS = {  # name: (x1, z1, x2, z2) area to draw
    "central": (-14, -18, 14, 8), "hillcrest": (60, -32, 142, 34),
    "riverside": (-38, 60, 32, 122), "woodvale": (-140, -36, -60, 50),
}


def block_rgb(block):
    return next((c for k, c in COLORS if k in block), (200, 0, 200))


def write_iso(w, directory, scale=6):
    """An isometric picture of each town (viewed from the south-east), for checking the buildings."""
    from PIL import Image, ImageDraw
    os.makedirs(directory, exist_ok=True)
    solid = {k: v for k, v in w.blocks.items() if v is not None}
    for name, (x1, z1, x2, z2) in ISO_VIEWS.items():
        cells = [(k, v) for k, v in solid.items() if x1 <= k[0] <= x2 and z1 <= k[2] <= z2]
        ys = [k[1] for k, _ in cells] + [G - 1]
        y_top = max(ys)
        h = scale // 2

        def project(x, y, z):
            return ((x - z) - (x1 - z2)) * scale, ((x + z) - (x1 + z1)) * h + (y_top - y) * scale

        width = ((x2 - z1) - (x1 - z2) + 2) * scale
        height = ((x2 + z2) - (x1 + z1) + 2) * h + (y_top - G + 3) * scale
        img = Image.new("RGB", (width, height), (200, 225, 245))
        d = ImageDraw.Draw(img)
        # Ground: the superflat grass under everything not covered.
        ground = [(x, G - 1, z) for x in range(x1, x2 + 1) for z in range(z1, z2 + 1) if (x, G - 1, z) not in w.blocks]
        everything = [(k, "minecraft:grass_block") for k in ground] + cells
        everything.sort(key=lambda kv: (kv[0][0] + kv[0][2], kv[0][1]))
        for (x, y, z), block in everything:
            if (x, y + 1, z) in solid and (x + 1, y, z) in solid and (x, y, z + 1) in solid:
                continue  # hidden
            r, g, b = block_rgb(block)
            px, py = project(x, y, z)
            top = [(px, py), (px + scale, py + h), (px, py + 2 * h), (px - scale, py + h)]
            left = [(px - scale, py + h), (px, py + 2 * h), (px, py + 2 * h + scale), (px - scale, py + h + scale)]
            right = [(px, py + 2 * h), (px + scale, py + h), (px + scale, py + h + scale), (px, py + 2 * h + scale)]
            d.polygon(left, fill=(int(r * .75), int(g * .75), int(b * .75)))
            d.polygon(right, fill=(int(r * .6), int(g * .6), int(b * .6)))
            d.polygon(top, fill=(r, g, b))
        img.save(os.path.join(directory, f"{name}.png"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--datapack")
    ap.add_argument("--config")
    ap.add_argument("--routes")
    ap.add_argument("--map", help="write a top-down PNG of the towns and routes (needs Pillow)")
    ap.add_argument("--iso", help="write an isometric PNG of each town into this directory (needs Pillow)")
    args = ap.parse_args()
    w, towns = build()
    if args.datapack:
        write_datapack(w, args.datapack)
    if args.config:
        write_config(towns, args.config)
    if args.routes:
        write_routes(towns, args.routes)
    if args.map:
        write_map(w, towns, args.map)
    if args.iso:
        write_iso(w, args.iso)
    n = sum(len(i.get("addresses", {})) for i in towns.values())
    print(f"Test towns: {len(towns) - 1} towns, {n} addresses, {len(w.commands)} build commands; routes check out.",
          file=sys.stderr)


if __name__ == "__main__":
    main()
