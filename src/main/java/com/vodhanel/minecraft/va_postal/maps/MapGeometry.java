package com.vodhanel.minecraft.va_postal.maps;

import java.util.ArrayList;
import java.util.List;

/**
 * The geometry of a Postal map (docs/design/central-office.md §6): fitting world points onto the 128 x 128 map, and
 * the pixels of a line between two of them. Pure functions, so they can be tested without a server.
 */
public final class MapGeometry {
    private MapGeometry() {
    }

    /** A map is 128 pixels square. */
    public static final int SIZE = 128;

    /** Fits world x/z points onto the map, the same scale both ways, centred, with a margin. */
    public record Projection(double min_x, double min_z, double scale, double offset_x, double offset_z) {
        /** The map pixel (0..127) of world {@code x}, {@code z}. */
        public int[] pixel(double x, double z) {
            int px = (int) Math.round(offset_x + (x - min_x) * scale);
            int pz = (int) Math.round(offset_z + (z - min_z) * scale);
            return new int[]{clamp(px), clamp(pz)};
        }

        /** The cursor coordinate (-128..127, two per pixel) of world {@code x}, {@code z}. */
        public byte[] cursor(double x, double z) {
            int[] p = pixel(x, z);
            return new byte[]{(byte) Math.max(-128, Math.min(127, p[0] * 2 - 128)), (byte) Math.max(-128, Math.min(127, p[1] * 2 - 128))};
        }
    }

    private static int clamp(int p) {
        return Math.max(0, Math.min(SIZE - 1, p));
    }

    /**
     * The projection that fits every point ({x, z} pairs) inside the map, {@code margin} pixels from its edges. With one
     * point, or all in one place, they sit in the middle.
     */
    public static Projection fit(List<double[]> points, int margin) {
        if (points.isEmpty()) {
            return new Projection(0, 0, 1, SIZE / 2.0, SIZE / 2.0);
        }
        double min_x = Double.MAX_VALUE, max_x = -Double.MAX_VALUE, min_z = Double.MAX_VALUE, max_z = -Double.MAX_VALUE;
        for (double[] p : points) {
            min_x = Math.min(min_x, p[0]);
            max_x = Math.max(max_x, p[0]);
            min_z = Math.min(min_z, p[1]);
            max_z = Math.max(max_z, p[1]);
        }
        double span = Math.max(max_x - min_x, max_z - min_z);
        double room = SIZE - 1 - 2.0 * Math.max(0, margin);
        double scale = span <= 0 ? 1.0 : room / span;
        double offset_x = (SIZE - 1 - (max_x - min_x) * scale) / 2.0;
        double offset_z = (SIZE - 1 - (max_z - min_z) * scale) / 2.0;
        return new Projection(min_x, min_z, scale, offset_x, offset_z);
    }

    /** The pixels of the line from {@code a} to {@code b} (Bresenham), both ends included. */
    public static List<int[]> line(int[] a, int[] b) {
        List<int[]> out = new ArrayList<>();
        int x = a[0], y = a[1];
        int dx = Math.abs(b[0] - x), dy = -Math.abs(b[1] - y);
        int sx = x < b[0] ? 1 : -1, sy = y < b[1] ? 1 : -1;
        int err = dx + dy;
        while (true) {
            out.add(new int[]{x, y});
            if (x == b[0] && y == b[1]) {
                return out;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }
}
