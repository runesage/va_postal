#!/usr/bin/env python3
"""Generates the "smp" load-test world for Postal: many towns spread over a large superflat map, each with a
post office and lots of houses on a street grid, the way a busy survival server might look. Nothing here has
a route: every route is left for Postal to survey (/postal survey), so a load soak on this world tests the
survey and the postmen together, at scale.

Towns take turns at the obstacles of the small test towns (build_towns.py): a river crossed by bridges, a
raised district reached by stairs, a street closed by a fence gate; trees grow in the gaps. Everything comes
from a seeded random generator, so the world is the same block for block on every run.

  --towns N        how many towns (default 12)
  --addresses N    houses per town (default 20)
  --datapack DIR   a data pack: postal_smp:town_<i> builds town i (one function per town keeps each under
                   Minecraft's command limit), postal_smp:central builds Central
  --config FILE    the matching Postal config (Central, offices, addresses; no routes), appended to FILE
  --list FILE      a JSON list of the towns and their addresses, for the load soak's report

Usage: build_smp.py [--towns N] [--addresses N] [--datapack DIR] [--config FILE] [--list FILE]
"""
import argparse
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_towns as bt  # noqa: E402  (the World, buildings and themes are shared)

G = bt.G
SPACING = 260        # between town centres
HALF = 64            # a town spans its centre +-HALF
STREETS = (-48, -24, 0, 24, 48)   # north-south streets, relative to the centre
NAMES = ["Ashford", "Brookhaven", "Coldwater", "Dunmore", "Elmstead", "Fairhollow", "Glenrock", "Hartwell",
         "Ironbridge", "Juniper", "Kingsmoor", "Larkfield", "Millbrook", "Northgate", "Oakridge", "Pinecrest",
         "Queensbury", "Ravensworth", "Stonehaven", "Thornbury", "Upton", "Valewood", "Westmarch", "Yarrow"]
THEME_CYCLE = ["alpine", "tudor", "cabin"]
FACING = {"south": (0, 1), "north": (0, -1), "east": (1, 0), "west": (-1, 0)}
ROAD = "minecraft:dirt_path"


def town_centre(i):
    """Towns on a grid around Central (0, 0), skipping the middle."""
    cells = [(gx, gz) for r in range(1, 6) for gx in range(-r, r + 1) for gz in range(-r, r + 1)
             if max(abs(gx), abs(gz)) == r]
    gx, gz = cells[i]
    return gx * SPACING, gz * SPACING


def stand(chest, facing):
    """Where the postman stands to use a mailbox: two blocks in front of it."""
    dx, dz = FACING[facing]
    return chest[0] + 2 * dx, chest[1], chest[2] + 2 * dz


def build_town(w, i, n_addresses, rnd):
    name = NAMES[i % len(NAMES)] + ("" if i < len(NAMES) else str(i // len(NAMES)))
    theme = THEME_CYCLE[i % 3]
    feature = ("river", "hill", "gate")[i % 3]
    cx, cz = town_centre(i)
    x1, x2, z1, z2 = cx - HALF, cx + HALF, cz - HALF, cz + HALF
    hill_x = cx + 30      # the raised district starts here (feature "hill")
    river_z = cz + 27     # the river runs along here (feature "river"), three wide

    def ground_y(x):
        return G + 2 if feature == "hill" and x >= hill_x else G

    if feature == "hill":
        w.fill(hill_x, G, z1, x2, G, z2, "minecraft:dirt")
        w.fill(hill_x, G + 1, z1, x2, G + 1, z2, "minecraft:grass_block")
    # Streets: a main street east-west through the centre, and the north-south streets.
    for sx in STREETS:
        x = cx + sx
        y = ground_y(x)
        w.fill(x - 1, y - 1, z1, x + 1, y - 1, z2, ROAD)
    w.fill(x1, G - 1, cz - 1, min(x2, hill_x - 1) if feature == "hill" else x2, G - 1, cz + 1, ROAD)
    if feature == "hill":
        w.fill(hill_x, G + 1, cz - 1, x2, G + 1, cz + 1, ROAD)
        # Two stairs up onto the hill, the width of the street.
        w.fill(hill_x - 2, G, cz - 1, hill_x - 2, G, cz + 1, "minecraft:stone_brick_stairs[facing=east]")
        w.fill(hill_x - 1, G, cz - 1, hill_x - 1, G, cz + 1, "minecraft:stone_bricks")
        w.fill(hill_x - 1, G + 1, cz - 1, hill_x - 1, G + 1, cz + 1, "minecraft:stone_brick_stairs[facing=east]")
    if feature == "river":
        w.fill(x1, G - 1, river_z - 1, x2, G - 1, river_z + 1, "minecraft:water")
        for sx in STREETS:  # a flat bridge on every street
            w.fill(cx + sx - 1, G - 1, river_z - 1, cx + sx + 1, G - 1, river_z + 1, "minecraft:oak_planks")
    if feature == "gate":
        # One street is fenced across, with a gate in the middle: houses beyond it are through the gate.
        gx, gz = cx - 24, cz + 20
        w.fill(gx - 3, G, gz, gx + 3, G, gz, "minecraft:oak_fence")
        w.set(gx, G, gz, "minecraft:oak_fence_gate[facing=south]")

    # The post office faces the main street from the north.
    office_chest = (cx, G, cz - 3)
    w.postal_building(office_chest, "south", theme, name, "Office", width=11, depth=8, office=True)
    office = stand(office_chest, "south")

    # House lots beside the north-south streets, facing the street.
    lots = []
    for sx in STREETS:
        x = cx + sx
        for side, facing in ((3, "west"), (-3, "east")):
            for z in range(z1 + 6, z2 - 5, 12):
                if abs(z - cz) < 8:
                    continue  # the main street
                if feature == "river" and river_z - 6 <= z <= river_z + 6:
                    continue
                if feature == "gate" and sx == -24 and abs(z - (cz + 20)) < 6:
                    continue
                chest_x = x + side
                # The house (7 deep behind the mailbox) must stay on one level and inside the town.
                bx_lo, bx_hi = (chest_x + 1, chest_x + 7) if facing == "west" else (chest_x - 7, chest_x - 1)
                if bx_lo < x1 or bx_hi > x2:
                    continue
                if feature == "hill" and (bx_lo < hill_x <= bx_hi or bx_lo <= hill_x - 1 <= bx_hi and bx_hi >= hill_x - 2):
                    continue
                if abs(chest_x - cx) < 10 and cz - 14 < z < cz:
                    continue  # the post office
                lots.append((chest_x, z, facing))
    rnd.shuffle(lots)
    addresses = {}
    for chest_x, z, facing in lots:
        if len(addresses) >= n_addresses:
            break
        y = ground_y(chest_x if facing == "west" else chest_x)
        if feature == "hill" and (chest_x >= hill_x) != (chest_x + (4 if facing == "west" else -4) >= hill_x):
            continue
        house = "House%02d" % (len(addresses) + 1)
        try:
            w.postal_building((chest_x, y, z), facing, theme, name, house)
        except SystemExit:
            continue  # it would overlap something: skip the lot
        addresses[house] = stand((chest_x, y, z), facing)

    # Trees in the gaps.
    for _ in range(60):
        tx, tz = rnd.randint(x1 + 3, x2 - 3), rnd.randint(z1 + 3, z2 - 3)
        y = ground_y(tx)
        if y != G or any(w.block_at(x, yy, z) is not None for x in range(tx - 2, tx + 3)
                         for z in range(tz - 2, tz + 3) for yy in range(G, G + 7)):
            continue
        if any((w.block_at(x, G - 1, z) or "") in (ROAD, "minecraft:water", "minecraft:oak_planks")
               for x in range(tx - 3, tx + 4) for z in range(tz - 3, tz + 4)):
            continue
        w.tree(tx, tz, rnd.randint(4, 6))
    return name, {"location": office, "addresses": addresses, "feature": feature,
                  "area": (x1 - 8, z1 - 8, x2 + 8, z2 + 8)}


def build(n_towns, n_addresses, seed=1):
    rnd = random.Random(seed)
    towns = {}
    pieces = []  # (function name, World) so each town builds in its own function
    w = bt.World()
    towns["Central"] = bt.central(w)
    pieces.append(("central", w, (-16, -20, 16, 16)))
    for i in range(n_towns):
        w = bt.World()
        name, info = build_town(w, i, n_addresses, rnd)
        towns[name] = info
        pieces.append(("town_%d" % i, w, info["area"]))
    return towns, pieces


def write_datapack(pieces, path):
    fn_dir = os.path.join(path, "data", "postal_smp", "function")
    os.makedirs(fn_dir, exist_ok=True)
    major, minor = bt.DATA_PACK_FORMAT
    with open(os.path.join(path, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "Postal SMP load-test world",
                            "min_format": [major, 0], "max_format": [major, minor]}}, f, indent=2)
    for fn, w, (ax1, az1, ax2, az2) in pieces:
        with open(os.path.join(fn_dir, fn + ".mcfunction"), "w") as f:
            f.write("# Generated by dev/towns/build_smp.py; edit that, not this.\n")
            f.write(f"forceload add {ax1} {az1} {ax2} {az2}\n")
            for cmd in w.commands:
                f.write(cmd + "\n")
            # Not kept loaded: on a real server most towns are unloaded most of the time.
            f.write(f"forceload remove {ax1} {az1} {ax2} {az2}\n")
            f.write(f'say Postal SMP built {fn}.\n')


def write_config(towns, path):
    loc = bt.loc
    lines = ["Postoffice:", "  Central:", f"    Location: {loc(towns['Central']['location'])}", "  Local:"]
    for town, info in towns.items():
        if town != "Central":
            lines += [f"    {town}:", f"      Location: {loc(info['location'])}"]
    lines.append("Address:")
    for town, info in towns.items():
        if town == "Central":
            continue
        lines.append(f"  {town}:")
        for addr, p in info["addresses"].items():
            lines += [f"    {addr}:", "      Residence:", f"        Location: {loc(p)}"]
    with open(path, "a") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--towns", type=int, default=12)
    ap.add_argument("--addresses", type=int, default=20)
    ap.add_argument("--datapack")
    ap.add_argument("--config")
    ap.add_argument("--list")
    args = ap.parse_args()
    towns, pieces = build(args.towns, args.addresses)
    if args.datapack:
        write_datapack(pieces, args.datapack)
    if args.config:
        write_config(towns, args.config)
    if args.list:
        with open(args.list, "w") as f:
            json.dump({t: {"feature": i.get("feature"), "addresses": sorted(i.get("addresses", {}))}
                       for t, i in towns.items() if t != "Central"}, f, indent=2)
    n = sum(len(i.get("addresses", {})) for i in towns.values())
    cmds = max(len(w.commands) for _, w, _ in pieces)
    print(f"SMP world: {len(towns) - 1} towns, {n} addresses, at most {cmds} commands per function.", file=sys.stderr)


if __name__ == "__main__":
    main()
