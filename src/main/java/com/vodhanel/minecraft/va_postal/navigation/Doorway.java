package com.vodhanel.minecraft.va_postal.navigation;

import com.vodhanel.minecraft.va_postal.VA_postal;
import com.vodhanel.minecraft.va_postal.common.Util;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;

/**
 * Going through a door or fence gate. When a door stands just ahead of a postman, on the way to his waypoint,
 * Postal takes over from Citizens (whose pathfinder won't plan through it): he lines up in front of it, faces it,
 * opens it, walks through, and closes it behind him if it was closed. A surveyed route keeps a waypoint on
 * either side of every door, so the crossing is always short and straight. Same idea as {@link Climb}.
 */
public final class Doorway {
    /** Doorway replaces v4's door sequencer (ID_WTR.door_sequencer), which is kept only as a fallback. */
    public static final boolean ENABLED = true;
    /** How far ahead (blocks) a door is noticed. */
    static final double AHEAD = 1.6D;
    /** Walking speed through the doorway, blocks per tick. */
    static final double SPEED = 0.18D;
    /** Ticks he waits with the door open before stepping through. */
    static final int PAUSE = 6;

    private static final Map<Integer, Crossing> active = new HashMap<>();

    private Doorway() {
    }

    /** Shutdown or reload: every crossing and climb is over, and every door and hatch postmen opened is closed. */
    public static void shutdown() {
        active.clear();
        Climb.forget_all();
        Openings.release_all();
    }

    /** Forgets any crossing in progress (route cancelled or finished). */
    public static void reset(int id) {
        Crossing c = active.remove(id);
        if (c != null) {
            c.close(true);
        }
    }

    /** Takes postman {@code id} through a door just ahead of him, if there is one. True if it handled this tick. */
    public static boolean tick(int id) {
        Entity e = VA_postal.wtr_npc[id] == null ? null : VA_postal.wtr_npc[id].getEntity();
        Location target = VA_postal.wtr_waypoint[id];
        if (e == null || target == null || !target.getWorld().equals(e.getWorld())) {
            reset(id);
            return false;
        }
        Crossing c = active.get(id);
        if (c != null && (!same_block(c.target, target)
                || e.getLocation().distanceSquared(c.door.getLocation().add(0.5D, 0.0D, 0.5D)) > STALE * STALE)) {
            // Left over from before a teleport (stall watchdog, stuck recovery, respawn) or a new waypoint: drop it.
            reset(id);
            c = null;
        }
        if (c == null) {
            c = start(e.getLocation(), target);
            if (c == null) {
                return false;
            }
            active.put(id, c);
            if (VA_postal.wtr_nav[id] != null && VA_postal.wtr_nav[id].isNavigating()) {
                VA_postal.wtr_nav[id].cancelNavigation();
            }
        }
        VA_postal.wtr_watchdog_stuck_stamp[id] = System.currentTimeMillis();
        VA_postal.wtr_watchdog_ext_npc_stamp[id] = Util.time_stamp();
        if (c.step(e)) {
            active.remove(id);
            Location at = e.getLocation();
            if (at.getBlockX() == target.getBlockX() && at.getBlockZ() == target.getBlockZ()) {
                ID_WTR.invoke_next_waypoint(id);
            } else {
                ID_WTR.safe_re_target(id);
            }
        }
        return true;
    }

    /** A crossing if a door or gate lies just ahead, on the straight line to the waypoint. */
    static Crossing start(Location at, Location target) {
        Vector dir = target.toVector().subtract(at.toVector()).setY(0);
        double len = dir.length();
        if (len < 0.5D || len > 12.0D) {
            return null;
        }
        dir.multiply(1.0D / len);
        for (double t = 0.3D; t <= Math.min(len, AHEAD); t += 0.2D) {
            Block b = at.getWorld().getBlockAt((int) Math.floor(at.getX() + dir.getX() * t), at.getBlockY(),
                    (int) Math.floor(at.getZ() + dir.getZ() * t));
            if (!b.equals(at.getBlock()) && ID_WTR.is_route_door(b.getType())
                    && b.getBlockData() instanceof Openable) {
                BlockFace axis = axis(b, at, dir);
                Crossing c = new Crossing(b, axis, target);
                return c.sound() ? c : null; // nowhere clear to stand either side: leave it to Citizens
            }
        }
        return null;
    }

    /** True if a route door or gate stands on the straight line from {@code at} to {@code target}, at his feet. */
    static boolean door_between(Location at, Location target) {
        if (at == null || target == null || !at.getWorld().equals(target.getWorld())) {
            return false;
        }
        Vector dir = target.toVector().subtract(at.toVector()).setY(0);
        double len = dir.length();
        if (len < 0.3D) {
            return false;
        }
        dir.multiply(1.0D / len);
        for (double t = 0.3D; t < len; t += 0.25D) {
            Block b = at.getWorld().getBlockAt((int) Math.floor(at.getX() + dir.getX() * t), at.getBlockY(),
                    (int) Math.floor(at.getZ() + dir.getZ() * t));
            if (!b.equals(at.getBlock()) && ID_WTR.is_route_door(b.getType())) {
                return true;
            }
        }
        return false;
    }

    /** A crossing more than this far (blocks) from where he is now is stale: he was teleported away from it. */
    static final double STALE = 4.0D;

    static boolean same_block(Location a, Location b) {
        return a != null && b != null && a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    /**
     * The way through a door: along the door's own facing (a door or gate is walked through along the direction it
     * faces), towards the side he's heading for. Not his approach angle, which from a diagonal would put him into the
     * wall beside the door.
     */
    static BlockFace axis(Block door, Location at, Vector dir) {
        boolean along_x;
        if (door.getBlockData() instanceof org.bukkit.block.data.Directional d) {
            BlockFace f = d.getFacing();
            along_x = f == BlockFace.EAST || f == BlockFace.WEST;
        } else {
            along_x = Math.abs(dir.getX()) >= Math.abs(dir.getZ());
        }
        double toward = along_x ? dir.getX() : dir.getZ();
        if (Math.abs(toward) < 1.0E-3D) { // heading along the door's plane: go away from the side he's on
            toward = along_x ? door.getX() + 0.5D - at.getX() : door.getZ() + 0.5D - at.getZ();
        }
        if (along_x) {
            return toward > 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return toward > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /** True if a postman can stand at {@code at}: feet and head clear. */
    static boolean clear(Location at) {
        Block feet = at.getBlock();
        return feet.isPassable() && feet.getRelative(BlockFace.UP).isPassable();
    }

    /** Most doors in a row one crossing takes (a door straight onto a fence gate, a double-thick entrance). */
    static final int MAX_RUN = 3;

    /**
     * One crossing: line up, open, pause, walk through, close. Doors straight one after another (a door onto a fence
     * gate) are crossed together: all opened, walked through to the first free block past the last, and closed.
     */
    static final class Crossing {
        final Block door;
        final Location target;
        /** The block past the last door of the run: must be clear (not a fourth door, not a wall). */
        final Block beyond;
        final java.util.List<Block> doors = new java.util.ArrayList<>();
        final java.util.List<Block> opened_doors = new java.util.ArrayList<>();
        final Location before;
        final Location after;
        final Location face;
        boolean opened;
        int phase;
        int wait;

        Crossing(Block door, BlockFace axis, Location target) {
            this.door = door;
            this.target = target;
            Block b = door;
            for (int i = 0; i < MAX_RUN && ID_WTR.is_route_door(b.getType()) && b.getBlockData() instanceof Openable; i++) {
                doors.add(b);
                b = b.getRelative(axis);
            }
            this.beyond = b;
            Location first = door.getLocation().add(0.5D, 0.0D, 0.5D);
            Location last = doors.get(doors.size() - 1).getLocation().add(0.5D, 0.0D, 0.5D);
            this.before = first.clone().subtract(axis.getModX(), 0, axis.getModZ());
            this.after = last.clone().add(axis.getModX(), 0, axis.getModZ());
            this.face = first.clone().add(0, 1.0D, 0);
        }

        /** True if the crossing can be made: somewhere clear to stand before the first door and past the last. */
        boolean sound() {
            boolean beyond_is_door = ID_WTR.is_route_door(beyond.getType());
            return !beyond_is_door && clear(before) && clear(after);
        }

        /** Advances one tick; true when he's through. */
        boolean step(Entity e) {
            switch (phase) {
                case 0: // line up in front of the door
                    if (move(e, before)) {
                        for (Block d : doors) {
                            if (Openings.hold(d, false)) {
                                opened_doors.add(d);
                            }
                        }
                        opened = !opened_doors.isEmpty();
                        phase = 1;
                        wait = PAUSE;
                    }
                    return false;
                case 1: // a moment with the door open
                    look(e, face);
                    if (--wait <= 0) {
                        phase = 2;
                    }
                    return false;
                default: // through, then close them behind him
                    if (move(e, after)) {
                        close(false);
                        return true;
                    }
                    return false;
            }
        }

        /** Lets go of the doors this crossing opened (each closes once no other postman is in it). */
        void close(boolean quiet) {
            for (Block d : opened_doors) {
                Openings.release(d, quiet);
            }
            opened_doors.clear();
            opened = false;
        }

        /** Moves a step towards {@code to} (keeping his height), facing it; true once he's there. */
        private boolean move(Entity e, Location to) {
            Location at = e.getLocation();
            double dx = to.getX() - at.getX(), dz = to.getZ() - at.getZ();
            double d = Math.sqrt(dx * dx + dz * dz);
            Location next = at.clone();
            if (d <= SPEED) {
                next.setX(to.getX());
                next.setZ(to.getZ());
            } else {
                next.setX(at.getX() + dx / d * SPEED);
                next.setZ(at.getZ() + dz / d * SPEED);
            }
            next.setY(door.getY());
            orient(next, face);
            e.teleport(next);
            e.setVelocity(new Vector(0, 0, 0));
            return d <= SPEED;
        }

        private static void look(Entity e, Location at) {
            Location l = e.getLocation();
            orient(l, at);
            e.teleport(l);
        }

        private static void orient(Location from, Location to) {
            double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
            if (dx * dx + dz * dz > 0.01D) {
                from.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                from.setPitch(0.0F);
            }
        }
    }
}
