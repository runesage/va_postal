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

Usage: build_towns.py [--datapack DIR] [--config FILE] [--routes FILE]
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

    def house(self, x1, z1, x2, z2, y=G, height=3, wall="minecraft:oak_planks", roof="minecraft:spruce_slab"):
        """Walls and a slab roof; the inside is left empty for doors and furniture."""
        self.fill(x1, y, z1, x2, y + height - 1, z2, wall)
        self.fill(x1 + 1, y, z1 + 1, x2 - 1, y + height - 1, z2 - 1, AIR)
        self.fill(x1, y + height, z1, x2, y + height, z2, roof)

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
    w.set(0, G, 0, "minecraft:chest[facing=south]")
    w.fill(-4, G - 1, -4, 4, G - 1, 6, "minecraft:stone_bricks")  # a plaza
    return {"location": (0, G, 2)}


def hillcrest(w):
    """Terraces climbed by stairs, a slab ramp and an L-shaped stair; a one-block step up."""
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
    w.fill(70, G, 20, 80, G, 30, "minecraft:stone")

    w.mailbox(70, G, 0, "south", "Hillcrest", "[Local]")
    w.mailbox(75, G, -20, "south", "Hillcrest", "Foot")
    w.house(72, -27, 78, -21)
    w.mailbox(75, G + 1, 22, "south", "Hillcrest", "Ledge")
    w.mailbox(95, G + 3, 20, "south", "Hillcrest", "Terrace")
    w.house(94, 13, 98, 18, y=G + 3)
    w.mailbox(112, G + 6, 5, "south", "Hillcrest", "Ramp")
    w.mailbox(130, G + 9, 0, "south", "Hillcrest", "Summit")
    w.house(127, -7, 133, -2, y=G + 9)

    to_t1 = [(70, G, 2), (76, G, 1), (82, G, 0), (86, G, 0), (90, G + 3, 0)]
    to_t2 = to_t1 + [(93, G + 3, -5), (96, G + 3, -10), (98, G + 3, -14), (105, G + 6, -14)]
    return {
        "location": (70, G, 2),
        "addresses": {
            "Foot": [(70, G, 2), (73, G, -3), (75, G, -8), (75, G, -13), (75, G, -18)],
            "Ledge": [(70, G, 2), (70, G, 8), (72, G, 14), (78, G, 18), (78, G + 1, 21), (78, G + 1, 24),
                      (75, G + 1, 24)],
            "Terrace": to_t1 + [(92, G + 3, 5), (93, G + 3, 10), (93, G + 3, 15), (93, G + 3, 20),
                                (95, G + 3, 22)],
            "Ramp": to_t2 + [(108, G + 6, -8), (109, G + 6, -2), (109, G + 6, 4), (110, G + 6, 7),
                             (112, G + 6, 7)],
            "Summit": to_t2 + [(109, G + 6, -13), (114, G + 6, -13), (117, G + 8, -13), (117, G + 9, -8),
                               (122, G + 9, -3), (126, G + 9, 2), (130, G + 9, 2)],
        },
        "expect_unreachable": [],
        "notes": {
            "Foot": "flat ground",
            "Ledge": "a one-block step up onto a platform",
            "Terrace": "three stone brick stairs",
            "Ramp": "stairs, then a ramp of slabs and full blocks",
            "Summit": "stairs, the slab ramp, then an L-shaped stair with a landing",
        },
    }


def riverside(w):
    """A river with a flat bridge and an arched bridge, a fence gate, a door and a narrow alley."""
    w.fill(-45, G - 2, 98, 45, G - 1, 102, "minecraft:water")
    # Flat bridge, three wide, with fence railings.
    w.fill(-21, G - 1, 97, -19, G - 1, 103, "minecraft:oak_planks")
    w.fill(-22, G, 98, -22, G, 102, "minecraft:oak_fence")
    w.fill(-18, G, 98, -18, G, 102, "minecraft:oak_fence")
    # Arched bridge: a stair up, a deck one block above the banks, a stair down.
    w.fill(19, G, 96, 21, G, 96, "minecraft:spruce_stairs[facing=south]")
    w.fill(19, G, 97, 21, G, 103, "minecraft:spruce_planks")
    w.fill(19, G, 104, 21, G, 104, "minecraft:spruce_stairs[facing=north]")
    # Gatehouse: a fenced yard entered through a closed fence gate.
    w.fill(-32, G, 72, -24, G, 80, "minecraft:oak_fence")
    w.fill(-31, G, 73, -25, G, 79, AIR)
    w.set(-28, G, 80, "minecraft:oak_fence_gate[facing=south,open=false]")
    # Doorstep: the mailbox is inside a house, through a closed door.
    w.house(20, 70, 28, 78, wall="minecraft:cobblestone")
    w.door(24, G, 78, "north")
    # Alley: one block wide between two walls.
    w.fill(7, G, 72, 7, G + 2, 80, "minecraft:stone_bricks")
    w.fill(9, G, 72, 9, G + 2, 80, "minecraft:stone_bricks")
    w.fill(7, G, 69, 9, G + 2, 69, "minecraft:stone_bricks")

    w.mailbox(0, G, 85, "south", "Riverside", "[Local]")
    w.mailbox(-20, G, 115, "south", "Riverside", "Bridgeend")
    w.house(-23, 108, -17, 114)
    w.mailbox(20, G, 115, "south", "Riverside", "Archway")
    w.house(17, 108, 23, 114)
    w.mailbox(-28, G, 74, "south", "Riverside", "Gatehouse")
    w.mailbox(24, G, 72, "south", "Riverside", "Doorstep")
    w.mailbox(8, G, 70, "south", "Riverside", "Alley")
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
            "Alley": [(0, G, 87), (4, G, 85), (8, G, 83), (8, G, 79), (8, G, 75), (8, G, 72)],
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


def woodvale(w, routes_so_far=None):
    """A forest with a winding glade, a tunnel through a mound, a hedge maze, a farm, and a loft
    reachable only by ladder (postmen can't climb; that address is expected to fail)."""
    # Tunnel: a mound with a 1-wide, 2-high tunnel along z=10.
    w.fill(-118, G, 0, -112, G + 5, 20, "minecraft:dirt")
    w.fill(-118, G + 6, 0, -112, G + 6, 20, "minecraft:grass_block")
    w.fill(-118, G, 10, -112, G + 1, 10, AIR)
    # Hedge maze: leaf walls two high along a zigzag corridor three wide.
    corridor = [(-80, 13), (-80, 20), (-86, 20), (-86, 25), (-80, 25), (-80, 31)]
    for x in range(-91, -74):
        for z in range(10, 36):
            d = min(seg_dist((x, z), a, b) for a, b in zip(corridor, corridor[1:]))
            if 1.5 < d <= 2.5 and z > 13:  # open at the entrance
                w.fill(x, G, z, x, G + 1, z, "minecraft:oak_leaves[persistent=true]")
    # Farm: wheat on farmland with a water channel down the middle.
    w.fill(-102, G - 1, 26, -92, G - 1, 36, "minecraft:farmland[moisture=7]")
    w.fill(-102, G, 26, -92, G, 36, "minecraft:wheat[age=7]")
    w.fill(-97, G - 1, 26, -97, G - 1, 36, "minecraft:water")
    w.fill(-97, G, 26, -97, G, 36, AIR)
    # Loft: the mailbox is upstairs, reached only by a ladder.
    w.house(-130, -30, -124, -24, height=8, wall="minecraft:spruce_planks")
    w.fill(-129, G + 4, -29, -125, G + 4, -25, "minecraft:spruce_planks")
    w.set(-125, G + 4, -28, AIR)
    for y in range(G, G + 5):
        w.set(-125, y, -28, "minecraft:ladder[facing=west]")
    w.door(-124, G, -27, "west")

    w.mailbox(-70, G, 0, "south", "Woodvale", "[Local]")
    w.mailbox(-100, G, -20, "south", "Woodvale", "Glade")
    w.mailbox(-125, G, 8, "south", "Woodvale", "Tunnel")
    w.mailbox(-80, G, 34, "north", "Woodvale", "Hedge")
    w.mailbox(-106, G, 28, "south", "Woodvale", "Farm")
    w.mailbox(-127, G + 5, -27, "south", "Woodvale", "Loft")

    info = {
        "location": (-70, G, 2),
        "addresses": {
            "Glade": [(-70, G, 2), (-76, G, -2), (-82, G, -8), (-88, G, -6), (-94, G, -12), (-100, G, -14),
                      (-100, G, -18)],
            "Tunnel": [(-70, G, 2), (-76, G, 5), (-84, G, 8), (-92, G, 10), (-100, G, 10), (-108, G, 10),
                       (-111, G, 10), (-115, G, 10), (-119, G, 10), (-125, G, 10)],
            "Hedge": [(-70, G, 2), (-74, G, 8), (-78, G, 12), (-80, G, 15), (-80, G, 20), (-86, G, 20),
                      (-86, G, 25), (-80, G, 25), (-80, G, 29), (-80, G, 32)],
            "Farm": [(-70, G, 2), (-66, G, 10), (-66, G, 20), (-70, G, 28), (-73, G, 33), (-77, G, 38), (-86, G, 38),
                     (-95, G, 38), (-103, G, 36), (-105, G, 33), (-106, G, 30)],
            "Loft": [(-70, G, 2), (-78, G, -6), (-86, G, -14), (-94, G, -22), (-102, G, -27), (-110, G, -27),
                     (-118, G, -27), (-123, G, -27), (-125, G, -27), (-127, G + 5, -25)],
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

FORCELOAD = [(-16, -16, 16, 16), (60, -35, 145, 35), (-45, 65, 45, 125), (-140, -40, -60, 45)]


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


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--datapack")
    ap.add_argument("--config")
    ap.add_argument("--routes")
    args = ap.parse_args()
    w, towns = build()
    if args.datapack:
        write_datapack(w, args.datapack)
    if args.config:
        write_config(towns, args.config)
    if args.routes:
        write_routes(towns, args.routes)
    n = sum(len(i.get("addresses", {})) for i in towns.values())
    print(f"Test towns: {len(towns) - 1} towns, {n} addresses, {len(w.commands)} build commands; routes check out.",
          file=sys.stderr)


if __name__ == "__main__":
    main()
