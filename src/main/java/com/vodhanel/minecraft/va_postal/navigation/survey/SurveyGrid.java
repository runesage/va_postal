package com.vodhanel.minecraft.va_postal.navigation.survey;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.Map;

/**
 * A {@link Grid} over chunk snapshots, so a survey can run off the main thread. Chunks that weren't loaded
 * (or don't exist yet) read as {@link Cell#UNKNOWN}: a survey never walks into terrain it hasn't seen.
 */
public final class SurveyGrid implements Grid {
    private static Map<Material, Cell> cells;
    /** Snow deeper than this many layers is ground (a postman steps over half a block at most). */
    static final int DEEP_SNOW = 4;

    private final Map<Long, ChunkSnapshot> chunks;
    private final int min_y;
    private final int max_y;

    /** Call on the main thread before the first survey runs. */
    public static synchronized void init() {
        if (cells != null) {
            return;
        }
        Map<Material, Cell> map = new EnumMap<>(Material.class);
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isBlock()) {
                map.put(m, classify(m));
            }
        }
        cells = map;
    }

    public SurveyGrid(Map<Long, ChunkSnapshot> chunks, int min_y, int max_y) {
        this.chunks = chunks;
        this.min_y = min_y;
        this.max_y = max_y;
    }

    public static long chunk_key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    @Override
    public Cell cell(int x, int y, int z) {
        if (y < min_y) {
            return Cell.UNKNOWN;
        }
        if (y >= max_y) {
            return Cell.OPEN;
        }
        ChunkSnapshot snap = chunks.get(chunk_key(x >> 4, z >> 4));
        if (snap == null) {
            return Cell.UNKNOWN;
        }
        Material type = snap.getBlockType(x & 15, y, z & 15);
        if (type == Material.SNOW) {
            // Up to four layers (half a block) is stepped over; deeper snow is ground to climb onto.
            org.bukkit.block.data.BlockData data = snap.getBlockData(x & 15, y, z & 15);
            return data instanceof org.bukkit.block.data.type.Snow sn && sn.getLayers() > DEEP_SNOW ? Cell.GROUND : Cell.OPEN;
        }
        Cell c = cells.get(type);
        if (c == Cell.HATCH) {
            // An open trapdoor stands up against its side: a postman walks or climbs past it.
            org.bukkit.block.data.BlockData data = snap.getBlockData(x & 15, y, z & 15);
            if (data instanceof org.bukkit.block.data.Openable o && o.isOpen()) {
                return Cell.OPEN;
            }
        }
        if (c == Cell.STEP) {
            // Stairs and slabs: which way, and which half, from the block data.
            org.bukkit.block.data.BlockData data = snap.getBlockData(x & 15, y, z & 15);
            if (data instanceof org.bukkit.block.data.type.Stairs st) {
                return st.getHalf() == org.bukkit.block.data.Bisected.Half.TOP ? Cell.ROAD : Cell.stairs(st.getFacing().name());
            }
            if (data instanceof org.bukkit.block.data.type.Slab sl && sl.getType() != org.bukkit.block.data.type.Slab.Type.BOTTOM) {
                return Cell.ROAD;
            }
        }
        return c != null ? c : Cell.GROUND;
    }

    /** What a block is to a postman. */
    static Cell classify(Material m) {
        return m.isAir() ? Cell.OPEN : Cell.of(m.name(), m.isSolid());
    }
}
