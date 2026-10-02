package com.vodhanel.minecraft.va_postal.common;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;

/**
 * Block orientation helpers replacing v4's legacy data bytes (2 = north, 3 = south, 4 = west,
 * 5 = east for chests and wall signs) with modern {@link BlockData}.
 */
public final class BlockFacing {
    private BlockFacing() {
    }

    /** Direction a chest, wall sign or other directional block faces, or null if it has none. */
    public static BlockFace facing(Block block) {
        if (block == null) {
            return null;
        }
        BlockData data = block.getBlockData();
        return data instanceof Directional ? ((Directional) data).getFacing() : null;
    }

    /** Turns a directional block to face {@code face}; does nothing for other blocks. */
    public static void set_facing(Block block, BlockFace face) {
        if (block == null || face == null) {
            return;
        }
        BlockData data = block.getBlockData();
        if (data instanceof Directional && ((Directional) data).getFaces().contains(face)) {
            ((Directional) data).setFacing(face);
            block.setBlockData(data);
        }
    }

    /** The block in front of a chest (where its sign hangs), or the chest itself if it has no facing. */
    public static Block front(Block block) {
        BlockFace face = facing(block);
        return face == null ? block : block.getRelative(face);
    }

    /** The block a wall sign hangs on, or the sign itself if it has no facing. */
    public static Block behind(Block block) {
        BlockFace face = facing(block);
        return face == null ? block : block.getRelative(face.getOppositeFace());
    }

    public static boolean is_wall_sign(Block block) {
        return block != null && Tag.WALL_SIGNS.isTagged(block.getType());
    }

    /** Places an oak wall sign at {@code target}, facing {@code face}. */
    public static boolean place_wall_sign(Block target, BlockFace face) {
        if (target == null || face == null) {
            return false;
        }
        target.setType(Material.OAK_WALL_SIGN, false);
        set_facing(target, face);
        return true;
    }

    /** Legacy 2/3/4/5 direction byte to a face, as stored in old configs. */
    public static BlockFace from_legacy(int dir) {
        switch (dir) {
            case 2:
                return BlockFace.NORTH;
            case 3:
                return BlockFace.SOUTH;
            case 4:
                return BlockFace.WEST;
            case 5:
                return BlockFace.EAST;
            default:
                return null;
        }
    }

    /** Face to the legacy 2/3/4/5 direction byte (0 if not horizontal). */
    public static int to_legacy(BlockFace face) {
        if (face == null) {
            return 0;
        }
        switch (face) {
            case NORTH:
                return 2;
            case SOUTH:
                return 3;
            case WEST:
                return 4;
            case EAST:
                return 5;
            default:
                return 0;
        }
    }
}
