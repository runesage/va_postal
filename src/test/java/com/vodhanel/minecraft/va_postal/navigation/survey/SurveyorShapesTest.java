package com.vodhanel.minecraft.va_postal.navigation.survey;

import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Move;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Point;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Result;
import com.vodhanel.minecraft.va_postal.navigation.survey.SurveyorTest.TestGrid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shapes real builds have that the first surveyor tests didn't (issue #24): alleys, tunnels, buildings with
 * floors, water, and long ways round. Each route is checked as a postman would walk it: every hop between
 * waypoints is straight, short, and has somewhere to stand under all four corners of him all the way.
 */
class SurveyorShapesTest {

    /** Solid rock from y = 0 to 3 over the whole area, with passages carved out of it. */
    private static TestGrid rock(int x1, int z1, int x2, int z2) {
        return new TestGrid().fill(x1, 0, z1, x2, 3, z2, Cell.GROUND);
    }

    private static TestGrid carve(TestGrid g, int x1, int y1, int z1, int x2, int y2, int z2) {
        return g.fill(x1, y1, z1, x2, y2, z2, Cell.OPEN);
    }

    /** Every hop is at most MAX_GAP and walkable at the postman's four corners, at a height within the hop's range. */
    static void assertWalkable(Grid g, Result r) {
        assertTrue(r.ok(), r.failure());
        Surveyor s = new Surveyor(g);
        List<Point> w = r.waypoints();
        assertEquals(r.path().get(0), w.get(0));
        assertEquals(r.path().get(r.path().size() - 1), w.get(w.size() - 1));
        for (int i = 1; i < w.size(); i++) {
            Point a = w.get(i - 1), b = w.get(i);
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double len = Math.hypot(dx, dz);
            assertTrue(len <= Surveyor.MAX_GAP + 1e-9, "hop too long " + a + " -> " + b);
            if (b.move() == Move.DOOR || b.move() == Move.LADDER || len <= 1.5D) {
                continue; // doors, ladders and single steps are handled as their own moves
            }
            int ylo = Math.min(a.y(), b.y()), yhi = Math.max(a.y(), b.y());
            int steps = (int) Math.ceil(len * 4);
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                double cx = a.x() + 0.5 + dx * t, cz = a.z() + 0.5 + dz * t;
                for (double ox : new double[]{-0.3, 0.3}) {
                    for (double oz : new double[]{-0.3, 0.3}) {
                        int x = (int) Math.floor(cx + ox), z = (int) Math.floor(cz + oz);
                        boolean ok = false;
                        for (int y = ylo; y <= yhi && !ok; y++) {
                            ok = s.stand(x, y, z);
                        }
                        assertTrue(ok, "hop " + a + " -> " + b + " has nowhere to stand at " + x + "," + z + " (" + w + ")");
                    }
                }
            }
        }
    }

    // ---- Alleys -----------------------------------------------------------------------------

    @Test
    void followsAOneWideAlleyRoundItsTurns() {
        // An S-shaped alley one block wide through solid rock.
        TestGrid g = rock(-2, -2, 22, 22);
        carve(g, 0, 0, 0, 10, 1, 0);
        carve(g, 10, 0, 0, 10, 1, 10);
        carve(g, 10, 0, 10, 20, 1, 10);
        Result r = new Surveyor(g).survey(0, 0, 0, 20, 0, 10);
        assertWalkable(g, r);
        assertTrue(r.path().stream().allMatch(p -> p.z() == 0 || p.x() == 10 || p.z() == 10), r.path().toString());
        // A waypoint at each corner: a straight hop can't cut through the rock.
        assertTrue(r.waypoints().contains(new Point(10, 0, 0, Move.WALK)), r.waypoints().toString());
        assertTrue(r.waypoints().contains(new Point(10, 0, 10, Move.WALK)), r.waypoints().toString());
    }

    @Test
    void climbsStairsRoundACornerOfAnAlley() {
        // An alley going east, then a flight of three stairs up towards the north, then on east at the top.
        TestGrid g = rock(-2, -6, 14, 2);
        carve(g, 0, 0, 0, 5, 2, 0);                        // the lower alley, three high
        g.set(5, 0, -1, Cell.STAIRS_NORTH);                // stairs up the side passage
        g.fill(5, 0, -2, 5, 0, -2, Cell.GROUND).set(5, 1, -2, Cell.STAIRS_NORTH);
        g.fill(5, 0, -3, 5, 1, -3, Cell.GROUND).set(5, 2, -3, Cell.STAIRS_NORTH);
        carve(g, 5, 1, -1, 5, 3, -1);
        carve(g, 5, 2, -2, 5, 4, -2);
        carve(g, 5, 3, -3, 5, 5, -3);
        g.fill(5, 0, -4, 12, 2, -4, Cell.GROUND);          // the upper alley's floor at y = 2
        carve(g, 5, 3, -4, 12, 5, -4);
        Result r = new Surveyor(g).survey(0, 0, 0, 12, 3, -4);
        assertWalkable(g, r);
        assertEquals(3, r.waypoints().get(r.waypoints().size() - 1).y());
        // Up the stairs from their front only: every step up comes from the south.
        for (int i = 1; i < r.path().size(); i++) {
            Point p = r.path().get(i), prev = r.path().get(i - 1);
            if (p.move() == Move.STEP_UP) {
                assertEquals(prev.z() - 1, p.z(), "stepped up a stair from its side: " + r.path());
            }
        }
    }

    // ---- Tunnels ----------------------------------------------------------------------------

    @Test
    void walksATwoHighTunnelWithRailsInTheFloor() {
        TestGrid g = rock(-2, -2, 30, 2);
        carve(g, 0, 0, 0, 28, 1, 0);       // two high: the ceiling is at y = 2
        // Rails are open cells (a postman walks over them).
        Result r = new Surveyor(g).survey(0, 0, 0, 28, 0, 0);
        assertWalkable(g, r);
        assertTrue(r.path().stream().allMatch(p -> p.y() == 0 && p.z() == 0));
    }

    @Test
    void refusesATunnelTooLowToWalk() {
        TestGrid g = rock(-2, -2, 22, 2);
        carve(g, 0, 0, 0, 3, 1, 0);
        carve(g, 4, 0, 0, 16, 0, 0);       // one high: crawl space
        carve(g, 17, 0, 0, 20, 1, 0);
        Result r = new Surveyor(g, 50_000).survey(0, 0, 0, 20, 0, 0);
        assertFalse(r.ok());
        assertTrue(r.failure().contains("got as close as 3,0,0"), r.failure());
    }

    @Test
    void cannotJumpUpAStepInATwoHighTunnel() {
        // A full-block step in a tunnel whose ceiling follows the floor: a player can't jump it (no headroom).
        TestGrid g = rock(-2, -2, 20, 2);
        carve(g, 0, 0, 0, 8, 1, 0);
        g.set(9, 0, 0, Cell.GROUND);
        carve(g, 9, 1, 0, 18, 2, 0);
        Result r = new Surveyor(g, 50_000).survey(0, 0, 0, 18, 1, 0);
        assertFalse(r.ok(), "jumped a step under a low ceiling: " + r.path());
        // With a third block of headroom over the last flat block, the step is fine.
        g.set(8, 2, 0, Cell.OPEN);
        assertWalkable(g, new Surveyor(g).survey(0, 0, 0, 18, 1, 0));
    }

    // ---- Buildings --------------------------------------------------------------------------

    /**
     * A two-storey house: stone walls x = 0..8, z = 0..8, a door in the south wall at (4, 0..1, 8), a floor at
     * y = 3 over the north half, stairs up the west side from z = 6 to z = 4, and a mailbox spot upstairs at (6, 4, 2).
     */
    private static TestGrid house() {
        TestGrid g = new TestGrid().fill(0, 0, 0, 8, 6, 8, Cell.ROAD).fill(1, 0, 1, 7, 6, 7, Cell.OPEN);
        g.fill(4, 0, 8, 4, 1, 8, Cell.DOOR);
        g.fill(1, 3, 1, 7, 3, 4, Cell.ROAD);                 // the upper floor (north half), standing at y = 4
        g.set(1, 0, 6, Cell.STAIRS_NORTH);                   // stairs up the west wall, northwards
        g.fill(1, 0, 5, 1, 0, 5, Cell.ROAD).set(1, 1, 5, Cell.STAIRS_NORTH);
        g.fill(1, 0, 4, 1, 1, 4, Cell.ROAD).set(1, 2, 4, Cell.STAIRS_NORTH);
        g.set(1, 3, 4, Cell.OPEN).set(1, 3, 5, Cell.OPEN).set(1, 3, 6, Cell.OPEN); // stairwell: no floor over the stairs
        g.fill(1, 3, 3, 1, 3, 3, Cell.ROAD);
        return g;
    }

    @Test
    void goesInByTheDoorAndUpTheStairsToAnUpperFloor() {
        TestGrid g = house();
        Result r = new Surveyor(g).survey(4, 0, 12, 6, 4, 2);
        assertWalkable(g, r);
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.DOOR), r.path().toString());
        assertEquals(4, r.waypoints().get(r.waypoints().size() - 1).y());
        assertFalse(r.waypoints().stream().anyMatch(p -> p.x() == 4 && p.z() == 8), "a waypoint on the door: " + r.waypoints());
        // And back down and out.
        assertWalkable(g, new Surveyor(g).survey(6, 4, 2, 4, 0, 12));
    }

    @Test
    void goesThroughTwoDoorsInARow() {
        // A porch: a door, one block, a second door. Each door lies between two waypoints.
        TestGrid g = new TestGrid().fill(5, 0, -10, 5, 1, 10, Cell.WALL).fill(7, 0, -10, 7, 1, 10, Cell.WALL)
                .fill(5, 0, 0, 5, 1, 0, Cell.DOOR).fill(7, 0, 0, 7, 1, 0, Cell.GATE)
                .fill(6, 0, -10, 6, 1, -1, Cell.WALL).fill(6, 0, 1, 6, 1, 10, Cell.WALL);
        Result r = new Surveyor(g).survey(0, 0, 0, 12, 0, 0);
        assertWalkable(g, r);
        List<Point> w = r.waypoints();
        assertTrue(w.stream().anyMatch(p -> p.x() == 4 && p.z() == 0), w.toString());
        assertTrue(w.stream().anyMatch(p -> p.x() == 6 && p.z() == 0), w.toString());
        assertTrue(w.stream().anyMatch(p -> p.x() == 8 && p.z() == 0), w.toString());
    }

    // ---- Water ------------------------------------------------------------------------------

    @Test
    void goesRoundALakeRatherThanThroughIt() {
        TestGrid g = new TestGrid().fill(-6, -1, 4, 6, -1, 10, Cell.WATER);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 0, 14);
        assertWalkable(g, r);
        assertTrue(r.path().stream().noneMatch(p -> Math.abs(p.x()) <= 6 && p.z() >= 4 && p.z() <= 10), r.path().toString());
    }

    @Test
    void sayHowCloseItGotWhenWaterBlocksTheWay() {
        // A river right across the area with no bridge.
        TestGrid g = new TestGrid().fill(-60, -1, 5, 60, -1, 7, Cell.WATER);
        Result r = new Surveyor(g, 200_000).survey(0, 0, 0, 0, 0, 12);
        assertFalse(r.ok());
        assertTrue(r.failure().contains("got as close as") && r.failure().contains(",4"), r.failure());
    }

    @Test
    void neverStandsOnLilyPadsOrInShallowWater() {
        // A shallow ford: water one deep with a floor under it. Still water: not a way across.
        TestGrid g = new TestGrid().fill(-60, -1, 5, 60, -1, 6, Cell.WATER).fill(-60, -2, 5, 60, -2, 6, Cell.GROUND)
                .fill(-3, 0, 5, 3, 0, 6, Cell.OPEN); // lily pads read as open cells over the water
        Result r = new Surveyor(g, 200_000).survey(0, 0, 0, 0, 0, 10);
        assertFalse(r.ok(), r.path().toString());
    }

    // ---- Long routes ------------------------------------------------------------------------

    @Test
    void findsALongWayRoundAWall() {
        // A wall between the two ends whose only gap is 40 blocks to the side: beyond the search's usual margin.
        TestGrid g = new TestGrid().fill(-200, 0, 5, 200, 2, 5, Cell.WALL).fill(-40, 0, 5, -40, 1, 5, Cell.OPEN);
        Result first = new Surveyor(g).survey(0, 0, 0, 0, 0, 10);
        assertFalse(first.ok());
        assertTrue(first.hit_edge(), "should say the box was too small: " + first.failure());
        assertTrue(first.failure().contains("within " + Surveyor.MARGIN + " blocks"), first.failure());
        // The second, wider try (as RouteSurvey makes it) finds the gap.
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 0, 10, Surveyor.WIDE_MARGIN);
        assertWalkable(g, r);
        assertTrue(r.path().stream().anyMatch(p -> p.x() == -40 && p.z() == 5), "went round: " + r.path().size());
    }

    @Test
    void anEnclosedAddressFailsEvenWithTheWiderSearchAndStaysWithinBudget() {
        // A walled yard with no way in: the wider second try floods a bigger box, finds nothing, and stops.
        TestGrid g = new TestGrid().fill(-3, 0, 7, 3, 2, 13, Cell.WALL).fill(-2, 0, 8, 2, 2, 12, Cell.OPEN);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 0, 10, Surveyor.WIDE_MARGIN);
        assertFalse(r.ok());
        assertTrue(r.expanded() <= Surveyor.MAX_EXPANDED, "searched " + r.expanded());
        assertTrue(r.failure().contains("got as close as"), r.failure());
    }

    @Test
    void classifiesNewerAndThinBlocks() {
        assertEquals(Cell.OPEN, Cell.of("SNOW", true));            // thin layers: SurveyGrid makes deep snow ground
        assertEquals(Cell.OPEN, Cell.of("LILY_PAD", true));
        assertEquals(Cell.WALL, Cell.of("IRON_CHAIN", false));
        assertEquals(Cell.WALL, Cell.of("EXPOSED_COPPER_CHAIN", false));
        assertEquals(Cell.WALL, Cell.of("COPPER_BARS", false));
        assertEquals(Cell.WALL, Cell.of("IRON_BARS", false));
        assertEquals(Cell.ROUGH, Cell.of("SNOW_BLOCK", true));
        assertEquals(Cell.WALL, Cell.of("OAK_TRAPDOOR", false));
        assertEquals(Cell.DOOR, Cell.of("COPPER_DOOR", true));
    }
}
