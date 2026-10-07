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
        Cell c = cells.get(snap.getBlockType(x & 15, y, z & 15));
        return c != null ? c : Cell.GROUND;
    }

    /** What a block is to a postman. */
    static Cell classify(Material m) {
        return m.isAir() ? Cell.OPEN : Cell.of(m.name(), m.isSolid());
    }
}
