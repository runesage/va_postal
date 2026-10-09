package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * Ladder climbing. Citizens' pathfinder doesn't climb, so when a postman's next waypoint is straight above or
 * below him in a ladder (or vine, or scaffolding) column, Postal moves him itself: onto the middle of the
 * column, then up or down a little every tick, facing the ladder. A trapdoor over the shaft (a hatch) is opened as
 * he reaches it and closed behind him. A surveyed route keeps a waypoint at the foot
 * and top of every ladder, so this is all a ladder needs.
 */
public final class Climb {
    /** Blocks per tick: a little slower than a player climbs (0.2). */
    static final double SPEED = 0.15D;

    private Climb() {
    }

    /** Hatches each postman has opened on his way through, closed once he's past them. */
    private static final java.util.Map<Integer, java.util.List<Block>> hatches = new java.util.HashMap<>();

    /**
     * True if he's at the waypoint's column: within 0.75 blocks of its middle, or 0.6 of its corner. Citizens walks him
     * to the corner of the block it's given (and stops up to its distance margin short), so his feet are often just
     * over the line in the next block.
     */
    static boolean in_column(Location at, Location target) {
        double dx = at.getX() - (target.getBlockX() + 0.5D), dz = at.getZ() - (target.getBlockZ() + 0.5D);
        double cx = at.getX() - target.getBlockX(), cz = at.getZ() - target.getBlockZ();
        return dx * dx + dz * dz <= 0.75D * 0.75D || cx * cx + cz * cz <= 0.6D * 0.6D;
    }

    /** True if this block is something to climb. */
    public static boolean climbable(Block b) {
        return b != null && Tag.CLIMBABLE.isTagged(b.getType());
    }

    /** True if this block is a trapdoor over a ladder: a hatch Postal opens for a climber. */
    static boolean hatch(Block b) {
        return b != null && Tag.TRAPDOORS.isTagged(b.getType()) && climbable(b.getRelative(BlockFace.DOWN));
    }

    /**
     * True if a waypoint is on a ladder (in one, just above its top, or on the hatch over it): it mustn't be moved to
     * the ground.
     */
    public static boolean on_ladder(Location at) {
        if (at == null) {
            return false;
        }
        Block b = at.getBlock(), under = b.getRelative(BlockFace.DOWN);
        return climbable(b) || climbable(under) || hatch(b) || hatch(under);
    }

    /** Closes the hatches he opened that he's clear of now (not at his feet or head). */
    private static void close_passed(int id, Location at) {
        java.util.List<Block> open = hatches.get(id);
        if (open == null) {
            return;
        }
        int feet = at.getBlockY();
        open.removeIf(b -> {
            boolean clear = b.getX() != at.getBlockX() || b.getZ() != at.getBlockZ() || b.getY() < feet || b.getY() > feet + 1;
            if (clear) {
                Openings.release(b, false);
            }
            return clear;
        });
        if (open.isEmpty()) {
            hatches.remove(id);
        }
    }

    /** Forgets every climb (shutdown; {@link Openings#release_all} closes the hatches). */
    static void forget_all() {
        hatches.clear();
    }

    /** Closes any hatch postman {@code id} opened (his route was cancelled or finished). */
    public static void reset(int id) {
        java.util.List<Block> open = hatches.remove(id);
        if (open != null) {
            for (Block b : open) {
                Openings.release(b, true);
            }
        }
    }

    /**
     * Opens a closed hatch in his way: just under his feet (going down), at his feet or head, or just above his head
     * (going up).
     */
    private static void open_hatches(int id, Location next) {
        int feet = (int) Math.floor(next.getY());
        for (int y = feet - 1; y <= feet + 2; y++) {
            Block b = next.getWorld().getBlockAt(next.getBlockX(), y, next.getBlockZ());
            java.util.List<Block> mine = hatches.computeIfAbsent(id, k -> new java.util.ArrayList<>());
            if (hatch(b) && !mine.contains(b) && Openings.hold(b, false)) {
                mine.add(b);
            }
        }
    }

    /**
     * Climbs postman {@code id} towards his waypoint, if that's a climb: straight up or down a ladder column from
     * where he is. Returns true if it handled this tick (and the waypoint, once he's there).
     */
    /**
     * At the foot (or top) of a ladder with his next waypoint off it on the same level, Postal walks him off it too.
     * Left to Citizens, its path started in the ladder block and its ladder handling climbed him a rung instead: at
     * the foot of the cellar's ladder he hung under the hatch until he was rescued.
     */
    private static boolean step_off(int id, Entity e, Location at, Location target) {
        if (!climbable(at.getBlock()) || target.getBlockY() != at.getBlockY()) {
            return false;
        }
        double dx = target.getBlockX() + 0.5D - at.getX(), dz = target.getBlockZ() + 0.5D - at.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) {
            return false;
        }
        Location next = at.clone().add(dx / len * SPEED, 0, dz / len * SPEED);
        next.setY(at.getBlockY());
        Block feet = next.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        if (!((feet.isPassable() || climbable(feet)) && (head.isPassable() || climbable(head)))) {
            return false; // nothing to step onto that way: leave him to Citizens and the stuck handling
        }
        if (VA_postal.wtr_nav[id] != null && VA_postal.wtr_nav[id].isNavigating()) {
            VA_postal.wtr_nav[id].cancelNavigation();
        }
        VA_postal.wtr_watchdog_stuck_stamp[id] = System.currentTimeMillis();
        VA_postal.wtr_watchdog_ext_npc_stamp[id] = Util.time_stamp();
        next.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
        next.setPitch(0.0F);
        e.teleport(next);
        e.setVelocity(new Vector(0, 0, 0));
        e.setFallDistance(0.0F);
        return true;
    }

    public static boolean tick(int id) {
        Location target = VA_postal.wtr_waypoint[id];
        Entity e = VA_postal.wtr_npc[id] == null ? null : VA_postal.wtr_npc[id].getEntity();
        if (target == null || e == null || !target.getWorld().equals(e.getWorld())) {
            return false;
        }
        Location at = e.getLocation();
        if (!in_column(at, target)) {
            return step_off(id, e, at, target);
        }
        double dy = target.getBlockY() - at.getY();
        if (Math.abs(dy) < 0.2D || !column_climbable(at, target)) {
            return false;
        }
        if (VA_postal.wtr_nav[id] != null && VA_postal.wtr_nav[id].isNavigating()) {
            VA_postal.wtr_nav[id].cancelNavigation();
        }
        VA_postal.wtr_watchdog_stuck_stamp[id] = System.currentTimeMillis();
        VA_postal.wtr_watchdog_ext_npc_stamp[id] = Util.time_stamp();

        Location next = at.clone();
        next.setX(target.getBlockX() + 0.5D);
        next.setZ(target.getBlockZ() + 0.5D);
        double step = Math.max(-SPEED, Math.min(SPEED, dy));
        next.setY(at.getY() + step);
        open_hatches(id, next);
        face_ladder(next);
        e.teleport(next);
        e.setVelocity(new Vector(0, 0, 0));
        e.setFallDistance(0.0F);
        if (Math.abs(target.getBlockY() - next.getY()) < 0.2D) {
            Location done = next.clone();
            done.setY(target.getBlockY());
            e.teleport(done);
            close_passed(id, done); // the hatches behind him: above him going down, under his feet at the top
            ID_WTR.invoke_next_waypoint(id);
        }
        return true;
    }

    private static boolean column_climbable(Location a, Location b) {
        int lo = Math.min(a.getBlockY(), b.getBlockY()), hi = Math.max(a.getBlockY(), b.getBlockY());
        Block base = a.getWorld().getBlockAt(b.getBlockX(), lo, b.getBlockZ());
        // Every block of the climb is ladder, except the top one he climbs out into (and he may start
        // standing on the floor at the foot).
        for (int y = lo; y < hi; y++) {
            Block blk = base.getRelative(0, y - lo, 0);
            if (!climbable(blk) && !hatch(blk) && !(y == lo && climbable(blk.getRelative(BlockFace.UP)))) {
                return false;
            }
        }
        return true;
    }

    /** Turns {@code at} to face the wall the ladder hangs on. */
    private static void face_ladder(Location at) {
        Block b = at.getBlock();
        if (!climbable(b)) {
            b = b.getRelative(BlockFace.DOWN);
        }
        if (b.getBlockData() instanceof Directional d) {
            BlockFace wall = d.getFacing().getOppositeFace();
            at.setYaw(switch (wall) {
                case SOUTH -> 0.0F;
                case WEST -> 90.0F;
                case NORTH -> 180.0F;
                default -> -90.0F;
            });
            at.setPitch(-10.0F);
        }
    }
}
