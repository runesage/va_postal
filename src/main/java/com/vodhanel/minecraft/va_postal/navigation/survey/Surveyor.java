package com.vodhanel.minecraft.va_postal.navigation.survey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds a walking route between two places over a {@link Grid} (docs/design/routes.md, option A), and cuts it
 * down to waypoints a postman can follow. Pure Java with no Bukkit types: it runs off the main thread, on a
 * snapshot of the blocks.
 * <p>
 * Positions are a postman's feet: he stands in a passable cell, with a passable cell for his head above it and
 * a floor below (or he's on a ladder). From there he can walk to any of the four neighbours, or diagonally
 * when both cells beside the diagonal are clear too (no cutting corners); step up one block if there's
 * headroom; drop up to three; walk through doors and gates; and climb ladders. Steps cost more on grass than
 * on roads, more again on rough ground, through doors, crops, and next to walls, so the route keeps to roads
 * and the middle of passages.
 * <p>
 * The search is bounded: by an area around the two ends, and by how many positions it may look at.
 */
public final class Surveyor {
    /** How a postman gets to a point from the one before it. */
    public enum Move { START, WALK, STEP_UP, DROP, DOOR, LADDER }

    /** A point on a route (feet position) and how it's reached. */
    public record Point(int x, int y, int z, Move move) {
    }

    /**
     * A survey's outcome: the full path (every block), the waypoints cut from it, how many positions the search
     * looked at, and why it failed (null if it didn't).
     */
    public record Result(List<Point> path, List<Point> waypoints, int expanded, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    /** Longest step between two waypoints, in blocks: well inside Citizens' pathfinding range. */
    public static final double MAX_GAP = 8.0D;
    /** How far (horizontally) the search may stray outside the box around its two ends. */
    public static final int MARGIN = 32;
    /** How far up or down it may stray. */
    public static final int MARGIN_Y = 16;
    /** At most this many positions are looked at. */
    public static final int MAX_EXPANDED = 400_000;
    /** The highest a postman drops. */
    public static final int MAX_DROP = 3;

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGONALS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Grid grid;
    private final int max_expanded;

    public Surveyor(Grid grid) {
        this(grid, MAX_EXPANDED);
    }

    public Surveyor(Grid grid, int max_expanded) {
        this.grid = grid;
        this.max_expanded = max_expanded;
    }

    // ---- Standing ---------------------------------------------------------------------------

    /** True if a postman can stand with his feet at (x, y, z). */
    public boolean stand(int x, int y, int z) {
        Cell feet = grid.cell(x, y, z);
        if (!feet.passable || !grid.cell(x, y + 1, z).passable) {
            return false;
        }
        return feet == Cell.LADDER || grid.cell(x, y - 1, z).floor;
    }

    /** The place to stand nearest (x, y, z), within two blocks; null if there's none. */
    public int[] nearest_stand(int x, int y, int z) {
        int[] best = null;
        int best_d = Integer.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int d = dx * dx + dz * dz + 2 * dy * dy;
                    if (d < best_d && stand(x + dx, y + dy, z + dz)) {
                        best = new int[]{x + dx, y + dy, z + dz};
                        best_d = d;
                    }
                }
            }
        }
        return best;
    }

    // ---- Search -----------------------------------------------------------------------------

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final Move move;
        final double g;
        final double f;
        final Node parent;

        Node(int x, int y, int z, Move move, double g, double f, Node parent) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.move = move;
            this.g = g;
            this.f = f;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** Surveys a route from (sx, sy, sz) to (gx, gy, gz): both are snapped to the nearest place to stand. */
    public Result survey(int sx, int sy, int sz, int gx, int gy, int gz) {
        int[] s = nearest_stand(sx, sy, sz);
        int[] t = nearest_stand(gx, gy, gz);
        if (s == null) {
            return new Result(List.of(), List.of(), 0, "nowhere to stand at the start (" + sx + "," + sy + "," + sz + ")");
        }
        if (t == null) {
            return new Result(List.of(), List.of(), 0, "nowhere to stand at the end (" + gx + "," + gy + "," + gz + ")");
        }
        int min_x = Math.min(s[0], t[0]) - MARGIN, max_x = Math.max(s[0], t[0]) + MARGIN;
        int min_z = Math.min(s[2], t[2]) - MARGIN, max_z = Math.max(s[2], t[2]) + MARGIN;
        int min_y = Math.min(s[1], t[1]) - MARGIN_Y, max_y = Math.max(s[1], t[1]) + MARGIN_Y;

        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Double> best = new HashMap<>();
        Node start = new Node(s[0], s[1], s[2], Move.START, 0.0D, h(s[0], s[1], s[2], t), null);
        open.add(start);
        best.put(key(s[0], s[1], s[2]), 0.0D);
        int expanded = 0;
        Node closest = start;
        double closest_d = Double.MAX_VALUE;
        List<Node> next = new ArrayList<>(12);
        while (!open.isEmpty()) {
            Node n = open.poll();
            Double known = best.get(key(n.x, n.y, n.z));
            if (known != null && known < n.g) {
                continue; // a better way here was found since this was queued
            }
            if (n.x == t[0] && n.y == t[1] && n.z == t[2]) {
                List<Point> path = path(n);
                return new Result(path, waypoints(path), expanded, null);
            }
            double d = Math.abs(n.x - t[0]) + Math.abs(n.z - t[2]) + Math.abs(n.y - t[1]);
            if (d < closest_d) {
                closest_d = d;
                closest = n;
            }
            if (++expanded > max_expanded) {
                return new Result(List.of(), List.of(), expanded, "gave up after looking at " + max_expanded + " positions");
            }
            next.clear();
            neighbours(n, t, next);
            for (Node m : next) {
                if (m.x < min_x || m.x > max_x || m.z < min_z || m.z > max_z || m.y < min_y || m.y > max_y) {
                    continue;
                }
                long k = key(m.x, m.y, m.z);
                Double g = best.get(k);
                if (g == null || m.g < g) {
                    best.put(k, m.g);
                    open.add(m);
                }
            }
        }
        return new Result(List.of(), List.of(), expanded, "no walkable way between them (got as close as "
                + closest.x + "," + closest.y + "," + closest.z + ", " + (int) closest_d + " blocks from the end at "
                + t[0] + "," + t[1] + "," + t[2] + ")");
    }

    /** A lower bound on the cost to the goal: octile distance at road cost; climbing costs at least half. */
    private static double h(int x, int y, int z, int[] t) {
        int dx = Math.abs(x - t[0]), dz = Math.abs(z - t[2]);
        double flat = Math.max(dx, dz) + (Math.sqrt(2.0D) - 1.0D) * Math.min(dx, dz);
        return flat * Cell.ROAD.cost + 0.5D * Math.abs(y - t[1]);
    }

    private void neighbours(Node n, int[] t, List<Node> out) {
        int x = n.x, y = n.y, z = n.z;
        Cell here = grid.cell(x, y, z);
        boolean in_doorway = here == Cell.DOOR || here == Cell.GATE;
        for (int[] d : SIDES) {
            int nx = x + d[0], nz = z + d[1];
            if (stand(nx, y, nz)) {
                Cell feet = grid.cell(nx, y, nz);
                add(out, n, nx, y, nz, feet == Cell.DOOR || feet == Cell.GATE ? Move.DOOR : Move.WALK, 1.0D, t);
                continue;
            }
            // Step up one block: needs headroom above where he is now.
            if (!in_doorway && here != Cell.LADDER && grid.cell(x, y + 2, z).passable && stand(nx, y + 1, nz)
                    && !isDoor(grid.cell(nx, y + 1, nz))) {
                add(out, n, nx, y + 1, nz, Move.STEP_UP, 1.0D, t);
                continue;
            }
            // Drop: he moves out over the edge (feet and head clear), then falls to the first floor.
            if (!grid.cell(nx, y, nz).passable || !grid.cell(nx, y + 1, nz).passable || isDoor(grid.cell(nx, y, nz))) {
                continue;
            }
            for (int fall = 1; fall <= MAX_DROP; fall++) {
                Cell c = grid.cell(nx, y - fall, nz);
                if (!c.passable || c == Cell.CROP && fall < MAX_DROP && !grid.cell(nx, y - fall - 1, nz).floor) {
                    break;
                }
                if (stand(nx, y - fall, nz)) {
                    add(out, n, nx, y - fall, nz, Move.DROP, 1.0D, t);
                    break;
                }
            }
        }
        if (!in_doorway) {
            for (int[] d : DIAGONALS) {
                int nx = x + d[0], nz = z + d[1];
                if (stand(nx, y, nz) && stand(x + d[0], y, z) && stand(x, y, z + d[1])
                        && !isDoor(grid.cell(nx, y, nz)) && !isDoor(grid.cell(x + d[0], y, z))
                        && !isDoor(grid.cell(x, y, z + d[1])) && here != Cell.LADDER) {
                    add(out, n, nx, y, nz, Move.WALK, Math.sqrt(2.0D), t);
                }
            }
        }
        // Ladders: up while there's ladder (or room) above, down while there's ladder below.
        if (here == Cell.LADDER && grid.cell(x, y + 1, z).passable && grid.cell(x, y + 2, z).passable) {
            add(out, n, x, y + 1, z, Move.LADDER, 0.0D, t);
        }
        if (grid.cell(x, y - 1, z) == Cell.LADDER) {
            add(out, n, x, y - 1, z, Move.LADDER, 0.0D, t);
        }
    }

    private static boolean isDoor(Cell c) {
        return c == Cell.DOOR || c == Cell.GATE;
    }

    private void add(List<Node> out, Node from, int x, int y, int z, Move move, double length, int[] t) {
        double cost;
        Cell feet = grid.cell(x, y, z);
        if (move == Move.LADDER) {
            cost = Cell.LADDER.cost;
        } else {
            Cell under = grid.cell(x, y - 1, z);
            double surface = under.floor ? under.cost : 1.0D;
            if (feet == Cell.LADDER) {
                surface = 1.0D;
            }
            cost = length * surface + (feet.passable ? feet.cost : 0.0D);
            if (move == Move.STEP_UP) {
                // Up a stair or onto a slab is a step; onto a full block is a jump.
                cost += jump(x, y, z) ? 3.0D : 0.3D;
            } else if (move == Move.DROP) {
                int fall = from.y - y;
                cost += fall + (fall >= 2 ? 3.0D : 0.0D);
            }
            cost += 0.25D * walls_beside(x, y, z);
        }
        double g = from.g + cost;
        out.add(new Node(x, y, z, move, g, g + h(x, y, z, t), from));
    }

    /** True if standing at (x, y, z) after stepping up means he jumped (onto a full block, not a stair or slab). */
    private boolean jump(int x, int y, int z) {
        return grid.cell(x, y - 1, z) != Cell.STEP;
    }

    /** How many of the four sides of a position are blocked at feet or head height (to keep off walls). */
    private int walls_beside(int x, int y, int z) {
        int n = 0;
        for (int[] d : SIDES) {
            if (!grid.cell(x + d[0], y, z + d[1]).passable || !grid.cell(x + d[0], y + 1, z + d[1]).passable) {
                n++;
            }
        }
        return n;
    }

    private static List<Point> path(Node end) {
        List<Point> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            out.add(new Point(n.x, n.y, n.z, n.move));
        }
        Collections.reverse(out);
        return out;
    }

    // ---- Waypoints --------------------------------------------------------------------------

    /**
     * Cuts a path down to waypoints: as few as possible, each straight hop walkable (checked across the
     * postman's width, so no cut corners), at most {@link #MAX_GAP} apart, and never skipping a point on either
     * side of a door, a ladder or a big drop. The first and last points are always kept.
     */
    public List<Point> waypoints(List<Point> path) {
        if (path.size() <= 2) {
            return new ArrayList<>(path);
        }
        boolean[] keep = new boolean[path.size()];
        keep[0] = true;
        keep[path.size() - 1] = true;
        for (int i = 1; i < path.size(); i++) {
            Point p = path.get(i);
            boolean special = p.move() == Move.DOOR || p.move() == Move.LADDER
                    || p.move() == Move.DROP && path.get(i - 1).y() - p.y() >= 2
                    || p.move() == Move.STEP_UP && jump(p.x(), p.y(), p.z());
            if (special) {
                keep[i - 1] = true;
                if (p.move() == Move.LADDER) {
                    keep[i] = true;
                }
                // The point after a door or a drop: he's through it.
                if (i + 1 < path.size()) {
                    keep[i + 1] = true;
                }
            }
        }
        // A door cell itself isn't a waypoint: Postal's door handling expects the door between two waypoints.
        for (int i = 1; i < path.size() - 1; i++) {
            if (path.get(i).move() == Move.DOOR && isDoor(grid.cell(path.get(i).x(), path.get(i).y(), path.get(i).z()))) {
                keep[i] = false;
            }
        }
        List<Point> out = new ArrayList<>();
        out.add(path.get(0));
        int anchor = 0;
        while (anchor < path.size() - 1) {
            int last_ok = anchor + 1;
            for (int j = anchor + 1; j < path.size(); j++) {
                if (!hop_ok(path, anchor, j)) {
                    break;
                }
                last_ok = j;
                if (keep[j]) {
                    break;
                }
            }
            // A door cell can't be a waypoint; step past it to the point beyond.
            while (last_ok < path.size() - 1 && path.get(last_ok).move() == Move.DOOR && !keep[last_ok]
                    && isDoor(grid.cell(path.get(last_ok).x(), path.get(last_ok).y(), path.get(last_ok).z()))) {
                last_ok++;
            }
            out.add(path.get(last_ok));
            anchor = last_ok;
        }
        return out;
    }

    /** True if a postman can walk straight from path[a] to path[b], with the path between staying close by. */
    private boolean hop_ok(List<Point> path, int a, int b) {
        Point p = path.get(a), q = path.get(b);
        double dx = q.x() - p.x(), dz = q.z() - p.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > MAX_GAP) {
            return false;
        }
        if (b == a + 1) {
            return true;
        }
        int ylo = Integer.MAX_VALUE, yhi = Integer.MIN_VALUE;
        for (int i = a; i <= b; i++) {
            Point m = path.get(i);
            if (i > a && (m.move() == Move.DOOR || m.move() == Move.LADDER
                    || i < b && m.move() == Move.STEP_UP && jump(m.x(), m.y(), m.z()))) {
                return false; // doors, ladders and jumps are their own hops
            }
            ylo = Math.min(ylo, m.y());
            yhi = Math.max(yhi, m.y());
            // The path between must hug the straight line, so a hop never shortcuts around an obstacle.
            if (len > 0.0D && Math.abs((m.x() - p.x()) * dz - (m.z() - p.z()) * dx) / len > 0.75D) {
                return false;
            }
        }
        if (yhi - ylo > 3) {
            return false;
        }
        // Every spot along the line, at the postman's four corners, has somewhere to stand.
        int steps = Math.max(1, (int) Math.ceil(len * 4.0D));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double cx = p.x() + 0.5D + dx * t, cz = p.z() + 0.5D + dz * t;
            for (double ox : new double[]{-0.3D, 0.3D}) {
                for (double oz : new double[]{-0.3D, 0.3D}) {
                    if (!column_ok((int) Math.floor(cx + ox), (int) Math.floor(cz + oz), ylo, yhi)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean column_ok(int x, int z, int ylo, int yhi) {
        for (int y = ylo; y <= yhi; y++) {
            if (stand(x, y, z) && grid.cell(x, y, z) != Cell.CROP) {
                return true;
            }
        }
        return false;
    }
}
