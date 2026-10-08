package com.vodhanel.minecraft.va_postal.navigation.survey;

import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Result;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The survey on a server-sized map: a seeded 600 x 600 block town of houses (walls with a door), fences, trees,
 * a river with bridges and a raised district reached by stairs; 60 surveys between random spots up to ~250
 * blocks apart. Each must finish inside its work bound, and all of them well inside a few seconds, so
 * surveying every address of a big server doesn't stall it.
 */
class SurveyorLoadTest {
    static final int SIZE = 600;

    /** A flat grid stored in arrays: much faster than a map, so the test measures the search, not the grid. */
    static final class MapGrid implements Grid {
        final Cell[] ground = new Cell[SIZE * SIZE];   // y = -1
        final Cell[] low = new Cell[SIZE * SIZE];      // y = 0
        final Cell[] high = new Cell[SIZE * SIZE];     // y = 1

        MapGrid() {
            java.util.Arrays.fill(ground, Cell.GROUND);
            java.util.Arrays.fill(low, Cell.OPEN);
            java.util.Arrays.fill(high, Cell.OPEN);
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (x < 0 || z < 0 || x >= SIZE || z >= SIZE) {
                return Cell.UNKNOWN;
            }
            int i = x * SIZE + z;
            return switch (y) {
                case -1 -> ground[i];
                case 0 -> low[i];
                case 1 -> high[i];
                default -> y < -1 ? Cell.GROUND : Cell.OPEN;
            };
        }

        void wall(int x, int z, Cell c) {
            low[x * SIZE + z] = c;
            high[x * SIZE + z] = c;
        }
    }

    static MapGrid town(long seed) {
        Random rnd = new Random(seed);
        MapGrid g = new MapGrid();
        // Roads every 40 blocks.
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                if (x % 40 < 3 || z % 40 < 3) {
                    g.ground[x * SIZE + z] = Cell.ROAD;
                }
            }
        }
        // A river along z = 300..304, with a bridge on every north-south road.
        for (int x = 0; x < SIZE; x++) {
            for (int z = 300; z <= 304; z++) {
                g.ground[x * SIZE + z] = x % 40 < 3 ? Cell.ROAD : Cell.WATER;
            }
        }
        // Houses in each block: 7x7 walls with a door facing the road.
        for (int bx = 0; bx < SIZE / 40; bx++) {
            for (int bz = 0; bz < SIZE / 40; bz++) {
                if (bz == 7) {
                    continue; // the river's block
                }
                for (int h = 0; h < 4; h++) {
                    int x0 = bx * 40 + 5 + (h % 2) * 17 + rnd.nextInt(5), z0 = bz * 40 + 5 + (h / 2) * 17 + rnd.nextInt(5);
                    for (int i = 0; i < 7; i++) {
                        g.wall(x0 + i, z0, Cell.GROUND);
                        g.wall(x0 + i, z0 + 6, Cell.GROUND);
                        g.wall(x0, z0 + i, Cell.GROUND);
                        g.wall(x0 + 6, z0 + i, Cell.GROUND);
                    }
                    g.wall(x0 + 3, z0, Cell.DOOR);
                }
                // Fences and trees scattered between them.
                for (int k = 0; k < 25; k++) {
                    int x = bx * 40 + 3 + rnd.nextInt(37), z = bz * 40 + 3 + rnd.nextInt(37);
                    if (g.low[x * SIZE + z] == Cell.OPEN) {
                        g.wall(x, z, rnd.nextBoolean() ? Cell.WALL : Cell.GROUND);
                    }
                }
            }
        }
        // A raised district (one block up) in one corner, with stairs on its edge roads.
        for (int x = 440; x < SIZE; x++) {
            for (int z = 440; z < SIZE; z++) {
                if (g.low[x * SIZE + z] == Cell.OPEN) {
                    g.low[x * SIZE + z] = x == 440 || z == 440 ? (x % 40 < 3 || z % 40 < 3 ? Cell.STEP : Cell.GROUND) : Cell.ROAD;
                }
            }
        }
        return g;
    }

    @Test
    void surveysAcrossAServerSizedTownStayFastAndBounded() {
        MapGrid g = town(42L);
        Random rnd = new Random(7L);
        int ok = 0, n = 60, max_expanded = 0;
        long worst_ms = 0, t_all = System.nanoTime();
        for (int i = 0; i < n; i++) {
            // Start on a road (as an office would be), end anywhere up to ~250 blocks away.
            int sx = (rnd.nextInt(SIZE / 40)) * 40 + 1, sz = rnd.nextInt(SIZE - 20) + 10;
            int tx = Math.max(5, Math.min(SIZE - 5, sx + rnd.nextInt(360) - 180));
            int tz = Math.max(5, Math.min(SIZE - 5, sz + rnd.nextInt(360) - 180));
            long t0 = System.nanoTime();
            Result r = new Surveyor(g).survey(sx, 0, sz, tx, 0, tz);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            worst_ms = Math.max(worst_ms, ms);
            max_expanded = Math.max(max_expanded, r.expanded());
            assertTrue(r.expanded() <= Surveyor.MAX_EXPANDED + 1, "work bound exceeded: " + r.expanded());
            if (r.ok()) {
                ok++;
                assertTrue(r.waypoints().size() >= 2);
            }
        }
        long total_ms = (System.nanoTime() - t_all) / 1_000_000L;
        System.out.printf("SurveyorLoadTest: %d/%d surveys found a route; worst %d ms, total %d ms, most positions %d%n",
                ok, n, worst_ms, total_ms, max_expanded);
        // Almost every spot is reachable (a few random ends land inside houses' walls or fences, or the water).
        assertTrue(ok >= n * 3 / 4, ok + " of " + n + " surveys found a route");
        assertTrue(total_ms < 30_000L, "60 surveys took " + total_ms + " ms");
    }

    @Test
    void anUnreachableAddressGivesUpWithinItsBound() {
        // The worst case: a sealed room ~250 blocks away. The search fills its whole box before giving up.
        MapGrid g = town(42L);
        for (int i = 0; i < 5; i++) {
            g.wall(500 + i, 100, Cell.GROUND);
            g.wall(500 + i, 104, Cell.GROUND);
            g.wall(500, 100 + i, Cell.GROUND);
            g.wall(504, 100 + i, Cell.GROUND);
        }
        g.low[502 * SIZE + 102] = Cell.OPEN;
        g.high[502 * SIZE + 102] = Cell.OPEN;
        long t0 = System.nanoTime();
        Result r = new Surveyor(g).survey(321, 0, 300, 502, 0, 102);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.printf("SurveyorLoadTest: unreachable survey gave up after %d positions in %d ms (%s)%n",
                r.expanded(), ms, r.failure());
        assertTrue(!r.ok());
        assertTrue(r.expanded() <= Surveyor.MAX_EXPANDED + 1);
        assertTrue(ms < 15_000L, "an unreachable survey took " + ms + " ms");
    }
}
