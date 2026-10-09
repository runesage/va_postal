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

    /** Forgets any crossing in progress (route cancelled or finished). */
    public static void reset(int id) {
        Crossing c = active.remove(id);
        if (c != null && c.opened) {
            for (Block d : c.opened_doors) {
                ID_WTR.set_door_open(d, false, true);
            }
            c.opened_doors.clear();
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
                // Cross along the door's axis: the larger component of the way he's going.
                BlockFace axis = Math.abs(dir.getX()) >= Math.abs(dir.getZ())
                        ? (dir.getX() > 0 ? BlockFace.EAST : BlockFace.WEST)
                        : (dir.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
                return new Crossing(b, axis);
            }
        }
        return null;
    }

    /** Most doors in a row one crossing takes (a door straight onto a fence gate, a double-thick entrance). */
    static final int MAX_RUN = 3;

    /**
     * One crossing: line up, open, pause, walk through, close. Doors straight one after another (a door onto a fence
     * gate) are crossed together: all opened, walked through to the first free block past the last, and closed.
     */
    static final class Crossing {
        final Block door;
        final java.util.List<Block> doors = new java.util.ArrayList<>();
        final java.util.List<Block> opened_doors = new java.util.ArrayList<>();
        final Location before;
        final Location after;
        final Location face;
        boolean opened;
        int phase;
        int wait;

        Crossing(Block door, BlockFace axis) {
            this.door = door;
            Block b = door;
            for (int i = 0; i < MAX_RUN && ID_WTR.is_route_door(b.getType()) && b.getBlockData() instanceof Openable; i++) {
                doors.add(b);
                b = b.getRelative(axis);
            }
            Location first = door.getLocation().add(0.5D, 0.0D, 0.5D);
            Location last = doors.get(doors.size() - 1).getLocation().add(0.5D, 0.0D, 0.5D);
            this.before = first.clone().subtract(axis.getModX(), 0, axis.getModZ());
            this.after = last.clone().add(axis.getModX(), 0, axis.getModZ());
            this.face = first.clone().add(0, 1.0D, 0);
        }

        /** Advances one tick; true when he's through. */
        boolean step(Entity e) {
            switch (phase) {
                case 0: // line up in front of the door
                    if (move(e, before)) {
                        for (Block d : doors) {
                            if (!((Openable) d.getBlockData()).isOpen()) {
                                ID_WTR.set_door_open(d, true, false);
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
                        close();
                        return true;
                    }
                    return false;
            }
        }

        /** Closes the doors this crossing opened. */
        void close() {
            for (Block d : opened_doors) {
                ID_WTR.set_door_open(d, false, false);
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
