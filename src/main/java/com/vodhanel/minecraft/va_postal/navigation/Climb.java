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
 * column, then up or down a little every tick, facing the ladder. A surveyed route keeps a waypoint at the foot
 * and top of every ladder, so this is all a ladder needs.
 */
public final class Climb {
    /** Blocks per tick: a little slower than a player climbs (0.2). */
    static final double SPEED = 0.15D;

    private Climb() {
    }

    /** True if this block is something to climb. */
    public static boolean climbable(Block b) {
        return b != null && Tag.CLIMBABLE.isTagged(b.getType());
    }

    /** True if a waypoint is on a ladder (in one, or just above its top): it mustn't be moved to the ground. */
    public static boolean on_ladder(Location at) {
        return at != null && (climbable(at.getBlock()) || climbable(at.getBlock().getRelative(BlockFace.DOWN)));
    }

    /**
     * Climbs postman {@code id} towards his waypoint, if that's a climb: straight up or down a ladder column from
     * where he is. Returns true if it handled this tick (and the waypoint, once he's there).
     */
    public static boolean tick(int id) {
        Location target = VA_postal.wtr_waypoint[id];
        Entity e = VA_postal.wtr_npc[id] == null ? null : VA_postal.wtr_npc[id].getEntity();
        if (target == null || e == null || !target.getWorld().equals(e.getWorld())) {
            return false;
        }
        Location at = e.getLocation();
        if (at.getBlockX() != target.getBlockX() || at.getBlockZ() != target.getBlockZ()) {
            return false;
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
        face_ladder(next);
        e.teleport(next);
        e.setVelocity(new Vector(0, 0, 0));
        e.setFallDistance(0.0F);
        if (Math.abs(target.getBlockY() - next.getY()) < 0.2D) {
            Location done = next.clone();
            done.setY(target.getBlockY());
            e.teleport(done);
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
            if (!climbable(blk) && !(y == lo && climbable(blk.getRelative(BlockFace.UP)))) {
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
