package com.vodhanel.minecraft.va_postal.common;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;

/**
 * Block orientation helpers for chests and wall signs, over modern {@link BlockData}.
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

}
