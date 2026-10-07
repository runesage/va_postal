package com.vodhanel.minecraft.va_postal.navigation.survey;

import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Move;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Point;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Result;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveyorTest {
    /** Flat ground (y < 0) with air above; blocks set on top. Feet stand at y = 0. */
    static final class TestGrid implements Grid {
        final Map<Long, Cell> cells = new HashMap<>();

        @Override
        public Cell cell(int x, int y, int z) {
            Cell c = cells.get(Surveyor.key(x, y, z));
            if (c != null) {
                return c;
            }
            return y < 0 ? Cell.GROUND : Cell.OPEN;
        }

        TestGrid set(int x, int y, int z, Cell c) {
            cells.put(Surveyor.key(x, y, z), c);
            return this;
        }

        TestGrid fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell c) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                        set(x, y, z, c);
                    }
                }
            }
            return this;
        }
    }

    private static void assertHopsWalkable(TestGrid g, List<Point> wps) {
        for (int i = 1; i < wps.size(); i++) {
            Point a = wps.get(i - 1), b = wps.get(i);
            double gap = Math.hypot(b.x() - a.x(), b.z() - a.z());
            assertTrue(gap <= Surveyor.MAX_GAP + 1e-9, "gap " + gap + " between " + a + " and " + b);
        }
    }

    @Test
    void aStraightRouteIsCutIntoShortHops() {
        TestGrid g = new TestGrid();
        Result r = new Surveyor(g).survey(0, 0, 0, 20, 0, 0);
        assertTrue(r.ok(), r.failure());
        assertEquals(21, r.path().size());
        assertEquals(new Point(0, 0, 0, Move.START), r.waypoints().get(0));
        assertEquals(20, r.waypoints().get(r.waypoints().size() - 1).x());
        assertEquals(4, r.waypoints().size()); // 0, 8, 16, 20
        assertHopsWalkable(g, r.waypoints());
    }

    @Test
    void goesThroughTheGapInAWallWithoutCuttingTheCorner() {
        TestGrid g = new TestGrid().fill(5, 0, -10, 5, 1, 10, Cell.WALL).fill(5, 0, 6, 5, 1, 6, Cell.OPEN);
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 0, 0);
        assertTrue(r.ok(), r.failure());
        assertTrue(r.path().stream().anyMatch(p -> p.x() == 5 && p.z() == 6));
        // A waypoint at or beside the gap: the straight hops can't pass through the wall.
        assertTrue(r.waypoints().stream().anyMatch(p -> Math.abs(p.x() - 5) <= 1 && Math.abs(p.z() - 6) <= 1),
                r.waypoints().toString());
        assertHopsWalkable(g, r.waypoints());
    }

    @Test
    void aDoorLiesBetweenTwoWaypointsAndIsNeverOne() {
        TestGrid g = new TestGrid().fill(5, 0, -10, 5, 1, 10, Cell.WALL).fill(5, 0, 0, 5, 1, 0, Cell.DOOR);
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 0, 0);
        assertTrue(r.ok(), r.failure());
        List<Point> w = r.waypoints();
        assertFalse(w.stream().anyMatch(p -> p.x() == 5 && p.z() == 0), w.toString());
        assertTrue(w.contains(new Point(4, 0, 0, Move.WALK)), w.toString());
        assertTrue(w.stream().anyMatch(p -> p.x() == 6 && p.z() == 0), w.toString());
    }

    @Test
    void climbsStairsAndDropsDown() {
        // A three-step stair up to a platform at height 3, and a 3-block drop off its far side.
        TestGrid g = new TestGrid().set(3, 0, 0, Cell.ROAD).fill(4, 0, 0, 4, 1, 0, Cell.ROAD).fill(5, 0, -2, 8, 2, 2, Cell.ROAD)
                .fill(-40, 0, -3, 40, 3, -3, Cell.WALL).fill(-40, 0, 3, 40, 3, 3, Cell.WALL).fill(4, 0, -2, 4, 3, -1, Cell.WALL)
                .fill(4, 0, 1, 4, 3, 2, Cell.WALL).fill(3, 0, -2, 3, 3, -1, Cell.WALL).fill(3, 0, 1, 3, 3, 2, Cell.WALL);
        Result r = new Surveyor(g).survey(0, 0, 0, 11, 0, 0);
        assertTrue(r.ok(), r.failure());
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.STEP_UP));
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.DROP));
        assertTrue(r.path().stream().anyMatch(p -> p.y() == 3));
    }

    @Test
    void neverDropsMoreThanThree() {
        // Start on a 4-high pillar with no way down but jumping.
        TestGrid g = new TestGrid().fill(0, 0, 0, 0, 3, 0, Cell.GROUND);
        Result r = new Surveyor(g, 10_000).survey(0, 4, 0, 5, 0, 0);
        assertFalse(r.ok());
    }

    @Test
    void climbsALadderToAnUpperFloor() {
        // A tower: walls all round, a floor at y = 4 and a ladder up the inside of the wall at (1, 0..4, 1).
        TestGrid g = new TestGrid().fill(0, 0, 0, 4, 8, 4, Cell.WALL).fill(1, 0, 1, 3, 8, 3, Cell.OPEN)
                .fill(1, 4, 1, 3, 4, 3, Cell.ROAD).fill(1, 0, 1, 1, 4, 1, Cell.LADDER).fill(4, 0, 2, 4, 1, 2, Cell.DOOR);
        Result r = new Surveyor(g).survey(8, 0, 2, 3, 5, 3);
        assertTrue(r.ok(), r.failure());
        assertTrue(r.path().stream().filter(p -> p.move() == Move.LADDER).count() >= 4, r.path().toString());
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.DOOR));
        Point end = r.waypoints().get(r.waypoints().size() - 1);
        assertEquals(5, end.y());
        // And back down the same way.
        Result back = new Surveyor(g).survey(3, 5, 3, 8, 0, 2);
        assertTrue(back.ok(), back.failure());
        assertTrue(back.path().stream().filter(p -> p.move() == Move.LADDER).count() >= 4);
    }

    @Test
    void crossesARiverByItsBridge() {
        TestGrid g = new TestGrid().fill(-20, -1, 5, 20, -1, 7, Cell.WATER).fill(10, -1, 5, 10, -1, 7, Cell.ROAD);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 0, 12);
        assertTrue(r.ok(), r.failure());
        assertTrue(r.path().stream().filter(p -> p.z() >= 5 && p.z() <= 7).allMatch(p -> p.x() == 10), r.path().toString());
    }

    @Test
    void walksAroundAFieldRatherThanThroughIt() {
        TestGrid g = new TestGrid().fill(-3, 0, 4, 3, 0, 8, Cell.CROP);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 0, 12);
        assertTrue(r.ok(), r.failure());
        assertTrue(r.path().stream().noneMatch(p -> g.cell(p.x(), p.y(), p.z()) == Cell.CROP), r.path().toString());
    }

    @Test
    void doesNotCutBetweenDiagonalWalls() {
        TestGrid g = new TestGrid().fill(1, 0, 0, 1, 1, 0, Cell.WALL).fill(0, 0, 1, 0, 1, 1, Cell.WALL);
        Result r = new Surveyor(g).survey(0, 0, 0, 1, 0, 1);
        assertTrue(r.ok(), r.failure());
        assertTrue(r.path().size() > 2, r.path().toString()); // not straight across the corner
    }

    @Test
    void anUnreachablePlaceFailsWithinItsBounds() {
        TestGrid g = new TestGrid().fill(8, 0, 8, 12, 3, 12, Cell.WALL).set(10, 0, 10, Cell.OPEN).set(10, 1, 10, Cell.OPEN);
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 0, 10);
        assertFalse(r.ok());
        assertTrue(r.expanded() <= Surveyor.MAX_EXPANDED);
    }

    @Test
    void prefersTheRoad() {
        // A road that bends out of the way is still cheaper than a short cut over rough ground.
        TestGrid g = new TestGrid().fill(0, -1, 0, 20, -1, 0, Cell.ROAD).fill(1, -1, 1, 19, -1, 3, Cell.ROUGH);
        Result r = new Surveyor(g).survey(0, 0, 0, 20, 0, 0);
        assertTrue(r.ok());
        assertTrue(r.path().stream().allMatch(p -> p.z() == 0), r.path().toString());
    }

    @Test
    void takesTheStairsOntoABridgeRatherThanJumpingOnFromTheSide() {
        // A river (z = 6..8) with a bridge deck one block up (x = 0..1, z = 5..9) and a stair at each end (z = 4, 10).
        // The bank beside the deck's end (z = 5) is a shorter way up, but only by jumping.
        TestGrid g = new TestGrid().fill(-20, -1, 6, 20, -1, 8, Cell.WATER)
                .fill(0, 0, 5, 1, 0, 9, Cell.ROAD).fill(0, 0, 4, 1, 0, 4, Cell.STEP).fill(0, 0, 10, 1, 0, 10, Cell.STEP);
        Result r = new Surveyor(g).survey(-3, 0, 5, 0, 0, 13);
        assertTrue(r.ok(), r.failure());
        Point first_up = r.path().stream().filter(p -> p.y() == 1).findFirst().orElseThrow();
        assertEquals(4, first_up.z(), r.path().toString());
    }

    @Test
    void classifiesBlocksByName() {
        assertEquals(Cell.LADDER, Cell.of("LADDER", false));
        assertEquals(Cell.LADDER, Cell.of("VINE", false));
        assertEquals(Cell.DOOR, Cell.of("OAK_DOOR", false));
        assertEquals(Cell.WALL, Cell.of("IRON_DOOR", true));
        assertEquals(Cell.GATE, Cell.of("SPRUCE_FENCE_GATE", true));
        assertEquals(Cell.WALL, Cell.of("SPRUCE_FENCE", true));
        assertEquals(Cell.WALL, Cell.of("COBBLESTONE_WALL", true));
        assertEquals(Cell.OPEN, Cell.of("OAK_WALL_SIGN", false));
        assertEquals(Cell.OPEN, Cell.of("WALL_TORCH", false));
        assertEquals(Cell.WALL, Cell.of("OAK_LEAVES", true));
        assertEquals(Cell.WALL, Cell.of("CHEST", true));
        assertEquals(Cell.CROP, Cell.of("WHEAT", false));
        assertEquals(Cell.STEP, Cell.of("SPRUCE_STAIRS", true));
        assertEquals(Cell.STEP, Cell.of("STONE_BRICK_SLAB", true));
        assertEquals(Cell.ROAD, Cell.of("STONE_BRICKS", true));
        assertEquals(Cell.ROAD, Cell.of("DIRT_PATH", true));
        assertEquals(Cell.GROUND, Cell.of("GRASS_BLOCK", true));
        assertEquals(Cell.ROUGH, Cell.of("SAND", true));
        assertEquals(Cell.WATER, Cell.of("WATER", false));
        assertEquals(Cell.DANGER, Cell.of("LAVA", false));
        assertEquals(Cell.OPEN, Cell.of("SHORT_GRASS", false));
    }
}
