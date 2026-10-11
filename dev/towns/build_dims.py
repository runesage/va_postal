#!/usr/bin/env python3
"""Generates the "dims" test world for Postal: one post office network spread over three dimensions.

  - Overworld (superflat): Central, and the town of Brookfield on a plain street.
  - The Nether: Cinderholm, in a sealed blackstone cavern (no lava or ghasts get in) lit by glowstone. A lava
    channel crosses the town, with one bridge over it, and the far half of the town is beyond the lava.
  - The End: Voidreach, on a floating platform out in the void, well away from the main island. One house
    stands on an islet reached only by a one-wide bridge over the void.

Central's postman teleports between offices, so mail for Cinderholm or Voidreach leaves Central in the
Overworld and is carried to another dimension. Inside each town the postmen walk as usual. Nothing has a
route: every route is left for Postal to survey (/postal survey), so the survey runs in all three dimensions.

  --datapack DIR   a data pack whose function postal_dims:build builds all three towns; drop it in
                   <world>/datapacks and run /function postal_dims:build
  --config FILE    the matching Postal config (Central, offices, addresses; no routes), appended to FILE
  --list FILE      a JSON list of the towns, their dimension and their addresses

Usage: build_dims.py [--datapack DIR] [--config FILE] [--list FILE]
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_towns as bt  # noqa: E402  (World, buildings and themes are shared)

# Bukkit's world names for the three dimensions of the level "world".
WORLDS = {"overworld": "world", "the_nether": "world_nether", "the_end": "world_the_end"}

bt.THEMES.update({
    "nether": {  # Cinderholm: nether brick and blackstone, crimson wood (which doesn't burn)
        "base": "minecraft:polished_blackstone_bricks", "wall_x": "minecraft:red_nether_bricks",
        "wall_z": "minecraft:red_nether_bricks", "corner": "minecraft:crimson_stem", "floor": "minecraft:crimson_planks",
        "roof": "minecraft:nether_brick_stairs", "roof_block": "minecraft:nether_bricks",
        "window": "minecraft:glass_pane", "sign": "minecraft:crimson_wall_sign", "sign_color": "yellow",
        "base_rows": 1, "chimney": "minecraft:polished_blackstone_bricks", "flag": "minecraft:orange_wool",
        "door": "minecraft:crimson_door",
    },
    "end": {  # Voidreach: purpur and end stone
        "base": "minecraft:purpur_block", "wall_x": "minecraft:end_stone_bricks", "wall_z": "minecraft:end_stone_bricks",
        "corner": "minecraft:purpur_pillar", "floor": "minecraft:purpur_block",
        "roof": "minecraft:purpur_stairs", "roof_block": "minecraft:purpur_block",
        "window": "minecraft:glass_pane", "sign": "minecraft:birch_wall_sign", "sign_color": "purple",
        "base_rows": 1, "flag": "minecraft:magenta_wool",
    },
})


class DimWorld(bt.World):
    """A World in one dimension. Only the superflat Overworld has ground everywhere; in the Nether and the End
    the floor is whatever this script places."""

    def __init__(self, dimension):
        super().__init__()
        self.dimension = dimension
        self.forceload = []
        self.theme_door = None

    def block_at(self, x, y, z):
        if self.dimension == "overworld":
            return super().block_at(x, y, z)
        return self.blocks.get((x, y, z))

    def door(self, x, y, z, facing):
        if not self.theme_door:
            return super().door(x, y, z, facing)
        self.set(x, y, z, f"{self.theme_door}[facing={facing},half=lower]")
        self.set(x, y + 1, z, f"{self.theme_door}[facing={facing},half=upper]")

    def run(self, cmd):
        """The command as the data pack runs it: in this world's dimension."""
        if self.dimension == "overworld":
            return cmd
        return f"execute in minecraft:{self.dimension} run {cmd}"


def stand(chest, facing):
    """Where the postman stands to use a mailbox: two blocks in front of it."""
    dx, dz = {"south": (0, 1), "north": (0, -1)}[facing]
    return chest[0], chest[1], chest[2] + 2 * dz


def street(w, name, theme, x0, y, z0, length, houses, road, skip=()):
    """A street running east from x0 along z0, the post office at its west end on the north side, and
    houses facing it from both sides every 12 blocks (except at the x positions in skip)."""
    w.theme_door = bt.THEMES[theme].get("door")
    w.fill(x0, y - 1, z0 - 1, x0 + length, y - 1, z0 + 1, road)
    office_chest = (x0 + 6, y, z0 - 3)
    w.postal_building(office_chest, "south", theme, name, "Office", width=11, depth=8, office=True)
    addresses = {}
    x = x0 + 20
    n = 0
    while n < len(houses) and x <= x0 + length - 4:
        if x not in skip:
            for facing, z in (("south", z0 - 3), ("north", z0 + 3)):
                if n < len(houses):
                    w.postal_building((x, y, z), facing, theme, name, houses[n])
                    addresses[houses[n]] = stand((x, y, z), facing)
                    n += 1
        x += 12
    if n < len(houses):
        raise SystemExit(f"{name}: room for only {n} of {len(houses)} houses")
    return {"location": stand(office_chest, "south"), "addresses": addresses}


def brookfield(w):
    """Overworld: a plain street east of Central."""
    x0, y, z0 = 40, bt.G, 40
    info = street(w, "Brookfield", "tudor", x0, y, z0, 60, ["Mill", "Green", "Well", "Orchard"],
                  "minecraft:dirt_path")
    w.forceload.append((x0 - 10, z0 - 20, x0 + 70, z0 + 20))
    return info


def cinderholm(w):
    """The Nether: a sealed cavern with a lava channel across it and one bridge."""
    x1, x2, z1, z2 = -10, 100, -22, 22
    y = 64                   # feet; the floor is at y - 1
    top = y + 13
    shell = "minecraft:blackstone"
    # The shell's six faces, then the inside emptied: no Nether terrain, lava or mobs get in.
    w.fill(x1, y - 2, z1, x2, y - 2, z2, shell)
    w.fill(x1, top, z1, x2, top, z2, shell)
    w.fill(x1, y - 1, z1, x1, top, z2, shell)
    w.fill(x2, y - 1, z1, x2, top, z2, shell)
    w.fill(x1, y - 1, z1, x2, top, z1, shell)
    w.fill(x1, y - 1, z2, x2, top, z2, shell)
    w.fill(x1 + 1, y, z1 + 1, x2 - 1, top - 1, z2 - 1, bt.AIR)
    w.fill(x1 + 1, y - 1, z1 + 1, x2 - 1, y - 1, z2 - 1, "minecraft:netherrack")
    for gx in range(x1 + 4, x2 - 2, 8):          # glowstone set in the roof
        for gz in range(z1 + 4, z2 - 2, 8):
            w.set(gx, top, gz, "minecraft:glowstone")
    # The lava channel, flush with the floor, wall to wall; the street crosses on a nether brick bridge with
    # fences along its sides.
    lava_x = 50
    w.fill(lava_x, y - 1, z1 + 1, lava_x + 2, y - 1, z2 - 1, "minecraft:lava")
    info = street(w, "Cinderholm", "nether", 0, y, 0, 96, ["Forge", "Ember", "Basalt", "Ashen", "Brimstone", "Wither"],
                  "minecraft:nether_bricks", skip=(44, 56))
    w.fill(lava_x - 1, y - 1, -1, lava_x + 3, y - 1, 1, "minecraft:nether_bricks")
    w.fill(lava_x, y, -2, lava_x + 2, y, -2, "minecraft:nether_brick_fence")
    w.fill(lava_x, y, 2, lava_x + 2, y, 2, "minecraft:nether_brick_fence")
    w.forceload.append((x1, z1, x2, z2))
    info["beyond"] = sorted(a for a, p in info["addresses"].items() if p[0] > lava_x)
    return info


def voidreach(w):
    """The End: a floating platform in the void, and an islet reached by a one-wide bridge."""
    x0, y, z0 = 400, 64, 0
    floor = "minecraft:end_stone_bricks"
    w.fill(x0 - 4, y - 1, z0 - 14, x0 + 64, y - 1, z0 + 14, floor)
    info = street(w, "Voidreach", "end", x0, y, z0, 60, ["Purpur", "Shulker", "Chorus", "Elytra"],
                  "minecraft:purpur_block")
    # The islet: 14 blocks south over the void, by a one-wide bridge.
    ix, iz = x0 + 30, z0 + 30
    w.fill(ix - 6, y - 1, iz - 6, ix + 6, y - 1, iz + 6, floor)
    w.fill(ix, y - 1, z0 + 15, ix, y - 1, iz - 7, "minecraft:purpur_slab[type=top]")
    w.theme_door = None
    w.postal_building((ix, y, iz - 2), "north", "end", "Voidreach", "Outlook")
    info["addresses"]["Outlook"] = stand((ix, y, iz - 2), "north")
    w.forceload.append((x0 - 10, z0 - 20, x0 + 70, iz + 10))
    return info


def build():
    worlds = {d: DimWorld(d) for d in WORLDS}
    towns = {}
    ow = worlds["overworld"]
    towns["Central"] = dict(bt.central(ow), dimension="overworld")
    ow.forceload.append((-16, -20, 16, 16))
    towns["Brookfield"] = dict(brookfield(ow), dimension="overworld")
    towns["Cinderholm"] = dict(cinderholm(worlds["the_nether"]), dimension="the_nether")
    towns["Voidreach"] = dict(voidreach(worlds["the_end"]), dimension="the_end")
    return towns, worlds


def write_datapack(worlds, path):
    fn_dir = os.path.join(path, "data", "postal_dims", "function")
    os.makedirs(fn_dir, exist_ok=True)
    major, minor = bt.DATA_PACK_FORMAT
    with open(os.path.join(path, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"description": "Postal cross-dimension test world",
                            "min_format": [major, 0], "max_format": [major, minor]}}, f, indent=2)
    with open(os.path.join(fn_dir, "build.mcfunction"), "w") as f:
        f.write("# Generated by dev/towns/build_dims.py; edit that, not this.\n")
        for w in worlds.values():
            for x1, z1, x2, z2 in w.forceload:
                f.write(w.run(f"forceload add {x1} {z1} {x2} {z2}") + "\n")
            for cmd in w.commands:
                f.write(w.run(cmd) + "\n")
        f.write("say Postal dims built.\n")


def loc(dimension, p):
    return f"{WORLDS[dimension]},{p[0]}.0,{p[1]}.0,{p[2]}.0"


def write_config(towns, path):
    c = towns["Central"]
    lines = ["Postoffice:", "  Central:", f"    Location: {loc(c['dimension'], c['location'])}", "  Local:"]
    for town, info in towns.items():
        if town != "Central":
            lines += [f"    {town}:", f"      Location: {loc(info['dimension'], info['location'])}"]
    lines.append("Address:")
    for town, info in towns.items():
        if town == "Central":
            continue
        lines.append(f"  {town}:")
        for addr, p in info["addresses"].items():
            lines += [f"    {addr}:", "      Residence:", f"        Location: {loc(info['dimension'], p)}"]
    with open(path, "a") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--datapack")
    ap.add_argument("--config")
    ap.add_argument("--list")
    args = ap.parse_args()
    towns, worlds = build()
    if args.datapack:
        write_datapack(worlds, args.datapack)
    if args.config:
        write_config(towns, args.config)
    if args.list:
        with open(args.list, "w") as f:
            json.dump({t: {"dimension": i["dimension"], "world": WORLDS[i["dimension"]],
                           "addresses": sorted(i.get("addresses", {})), "beyond_lava": i.get("beyond", [])}
                       for t, i in towns.items() if t != "Central"}, f, indent=2)
    n = sum(len(i.get("addresses", {})) for i in towns.values())
    cmds = sum(len(w.commands) for w in worlds.values())
    print(f"Dims world: {len(towns) - 1} towns in 3 dimensions, {n} addresses, {cmds} commands.", file=sys.stderr)


if __name__ == "__main__":
    main()
