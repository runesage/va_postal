package com.vodhanel.minecraft.va_postal.navigation.survey;

import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Move;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Point;
import com.vodhanel.minecraft.va_postal.navigation.survey.Surveyor.Result;
import com.vodhanel.minecraft.va_postal.navigation.survey.SurveyorTest.TestGrid;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.vodhanel.minecraft.va_postal.navigation.survey.SurveyorShapesTest.assertWalkable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases a real player or NPC handles in Minecraft: doors, gates, ladders, vines, stairs, slabs, drops, odd
 * ends and the search bounds. A failure here is a surveyor bug or limitation.
 */
class SurveyorEdgeCasesTest {

    // ---- Helpers ----------------------------------------------------------------------------

    private static boolean isDoor(Cell c) {
        return c == Cell.DOOR || c == Cell.GATE;
    }

    private static boolean has(List<Point> pts, int x, int y, int z) {
        return pts.stream().anyMatch(p -> p.x() == x && p.y() == y && p.z() == z);
    }

    /** A wall line along x = x0 (z from -10 to 10, two high) with a two-high door at z = 0. */
    private static TestGrid wallWithDoor(int x0) {
        return new TestGrid().fill(x0, 0, -10, x0, 1, 10, Cell.WALL).fill(x0, 0, 0, x0, 1, 0, Cell.DOOR);
    }

    /** No waypoint but the first and last stands in a door or gate cell. */
    private static void assertNoInnerWaypointOnDoor(Grid g, Result r) {
        List<Point> w = r.waypoints();
        for (int i = 1; i < w.size() - 1; i++) {
            Point p = w.get(i);
            assertFalse(isDoor(g.cell(p.x(), p.y(), p.z())), "waypoint on a door: " + p + " in " + w);
        }
    }

    /** Nobody enters or leaves a door cell diagonally. */
    private static void assertDoorsEnteredSquarely(Grid g, Result r) {
        List<Point> p = r.path();
        for (int i = 1; i < p.size(); i++) {
            Point a = p.get(i - 1), b = p.get(i);
            if (isDoor(g.cell(a.x(), a.y(), a.z())) || isDoor(g.cell(b.x(), b.y(), b.z()))) {
                assertTrue(Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z()) <= 1, "diagonal at a door: " + a + " -> " + b);
            }
        }
    }

    /** No inner waypoint stands on a stair or slab (Citizens won't take a target there). */
    private static void assertNoInnerWaypointOnStep(Grid g, Result r) {
        List<Point> w = r.waypoints();
        for (int i = 1; i < w.size() - 1; i++) {
            Point p = w.get(i);
            Cell under = g.cell(p.x(), p.y() - 1, p.z());
            assertFalse(under == Cell.STEP || under.ascends() != null, "waypoint on a stair or slab: " + p + " in " + w);
        }
    }

    private static TestGrid rock(int x1, int z1, int x2, int z2) {
        return new TestGrid().fill(x1, 0, z1, x2, 3, z2, Cell.GROUND);
    }

    // ---- Doors and gates --------------------------------------------------------------------

    @Test
    void startAndEndSurveyIsTrivialWhenTheyAreTheSameBlock() {
        TestGrid g = new TestGrid();
        Result r = new Surveyor(g).survey(3, 0, 3, 3, 0, 3);
        assertTrue(r.ok(), r.failure());
        assertEquals(1, r.path().size());
        assertEquals(1, r.waypoints().size());
    }

    @Test
    void canStartStandingInADoorway() {
        TestGrid g = wallWithDoor(5);
        Result r = new Surveyor(g).survey(5, 0, 0, 12, 0, 0);
        assertWalkable(g, r);
    }

    @Test
    void canEndStandingInADoorway() {
        TestGrid g = wallWithDoor(5);
        Result r = new Surveyor(g).survey(-6, 0, 0, 5, 0, 0);
        assertWalkable(g, r);
        Point end = r.waypoints().get(r.waypoints().size() - 1);
        assertEquals(5, end.x());
    }

    @Test
    void startRightBesideADoorGoesThroughIt() {
        TestGrid g = wallWithDoor(5);
        Result r = new Surveyor(g).survey(4, 0, 0, 6, 0, 0);
        assertWalkable(g, r);
        assertTrue(has(r.path(), 5, 0, 0));
    }

    @Test
    void addressRightBehindADoor() {
        // A one-cell-deep room: the address is the cell just inside the door.
        TestGrid g = new TestGrid().fill(3, 0, 0, 7, 2, 4, Cell.WALL).fill(4, 0, 1, 6, 2, 3, Cell.OPEN)
                .fill(5, 0, 0, 5, 1, 0, Cell.DOOR);
        Result r = new Surveyor(g).survey(5, 0, -8, 5, 0, 1);
        assertWalkable(g, r);
        assertNoInnerWaypointOnDoor(g, r);
        assertTrue(has(r.waypoints(), 5, 0, -1), "a waypoint outside the door: " + r.waypoints());
        assertEquals(1, r.waypoints().get(r.waypoints().size() - 1).z());
    }

    @Test
    void aDoorIsNeverEnteredDiagonally() {
        TestGrid g = wallWithDoor(5);
        Result r = new Surveyor(g).survey(2, 0, -4, 9, 0, 4);
        assertWalkable(g, r);
        assertDoorsEnteredSquarely(g, r);
        assertNoInnerWaypointOnDoor(g, r);
    }

    @Test
    void aDoorWhoseFrontCellIsBlockedCannotBeUsed() {
        // The cell in front of the door (west of it) is blocked: only a diagonal approach is left, which isn't walked.
        TestGrid g = new TestGrid().fill(5, 0, -60, 5, 1, 60, Cell.WALL).fill(5, 0, 0, 5, 1, 0, Cell.DOOR)
                .fill(4, 0, 0, 4, 1, 0, Cell.WALL);
        Result r = new Surveyor(g, 50_000).survey(3, 0, 1, 8, 0, 0);
        assertFalse(r.ok(), "should not slip through a door diagonally: " + r.path());
    }

    @Test
    void doubleDoorsSideBySideAreEachOneDoor() {
        TestGrid g = wallWithDoor(5).fill(5, 0, 1, 5, 1, 1, Cell.DOOR);
        Result r = new Surveyor(g).survey(0, 0, 1, 10, 0, 1);
        assertWalkable(g, r);
        assertDoorsEnteredSquarely(g, r);
        assertNoInnerWaypointOnDoor(g, r);
        assertTrue(has(r.waypoints(), 4, 0, 1) && has(r.waypoints(), 6, 0, 1), r.waypoints().toString());
    }

    @Test
    void anAirlockOfTwoDoorsInARowHasWaypointsOutsideBoth() {
        TestGrid g = new TestGrid().fill(5, 0, -10, 6, 1, 10, Cell.WALL).fill(5, 0, 0, 6, 1, 0, Cell.DOOR);
        Result r = new Surveyor(g).survey(0, 0, 0, 12, 0, 0);
        assertWalkable(g, r);
        assertNoInnerWaypointOnDoor(g, r);
        assertTrue(has(r.waypoints(), 4, 0, 0) && has(r.waypoints(), 7, 0, 0), r.waypoints().toString());
    }

    @Test
    void aFenceGateInAFenceLineIsTheOnlyWayAndNoCornerIsCut() {
        // A fence (one high) from x = -10 to 10 at z = 0, a gate in the middle; the posts block the diagonals.
        TestGrid g = new TestGrid().fill(-10, 0, 0, 10, 0, 0, Cell.WALL).set(0, 0, 0, Cell.GATE);
        Result r = new Surveyor(g).survey(-3, 0, -3, 3, 0, 3);
        assertWalkable(g, r);
        assertTrue(has(r.path(), 0, 0, 0), "should use the gate: " + r.path());
        assertDoorsEnteredSquarely(g, r);
        List<Point> w = r.waypoints();
        for (int i = 1; i < w.size(); i++) {
            Point a = w.get(i - 1), b = w.get(i);
            if (a.z() < 0 && b.z() > 0) {
                assertTrue(a.x() == 0 && b.x() == 0, "hop crosses the fence line off the gate: " + a + " -> " + b);
            }
        }
    }

    // ---- Ladders, vines, hatches ------------------------------------------------------------

    /** A ladder up the west face of a block column at x = 0, a platform on top running east (feet y = 4). */
    private static TestGrid ladderBesidePlatform() {
        return new TestGrid().fill(0, 0, 0, 0, 4, 0, Cell.LADDER).fill(1, 0, 0, 5, 3, 0, Cell.GROUND)
                .fill(1, 3, 0, 5, 3, 0, Cell.ROAD);
    }

    @Test
    void ladderTopExitsOntoAFloorBesideIt() {
        TestGrid g = ladderBesidePlatform();
        Result r = new Surveyor(g).survey(0, 0, 3, 5, 4, 0);
        assertWalkable(g, r);
        assertTrue(has(r.waypoints(), 0, 0, 0), "foot of the climb: " + r.waypoints());
        assertTrue(has(r.waypoints(), 0, 4, 0), "top of the climb: " + r.waypoints());
    }

    @Test
    void canStartOnTheLadderItself() {
        TestGrid g = ladderBesidePlatform();
        Result r = new Surveyor(g).survey(0, 0, 0, 5, 4, 0);
        assertWalkable(g, r);
    }

    @Test
    void canStartHalfwayUpTheLadderAndComeDown() {
        TestGrid g = ladderBesidePlatform();
        Result r = new Surveyor(g).survey(0, 2, 0, 0, 0, 5);
        assertWalkable(g, r);
        assertEquals(0, r.waypoints().get(r.waypoints().size() - 1).y());
    }

    @Test
    void twoLaddersWithALandingBetween() {
        // Ladder A up to a landing (feet y = 4), walk three blocks, ladder B up to y = 8.
        TestGrid g = new TestGrid().fill(0, 0, 0, 0, 4, 0, Cell.LADDER).fill(1, 0, 0, 3, 3, 0, Cell.GROUND)
                .fill(1, 3, 0, 3, 3, 0, Cell.ROAD).fill(4, 0, 0, 4, 3, 0, Cell.GROUND).fill(4, 4, 0, 4, 8, 0, Cell.LADDER);
        Result r = new Surveyor(g).survey(0, 0, 4, 4, 8, 0);
        assertWalkable(g, r);
        assertEquals(8, r.waypoints().get(r.waypoints().size() - 1).y());
        assertTrue(r.path().stream().filter(p -> p.move() == Move.LADDER).count() >= 8);
    }

    @Test
    void sameColumnDifferentHeightsIsALadderClimb() {
        TestGrid g = new TestGrid().fill(0, 0, 0, 0, 6, 0, Cell.LADDER);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 6, 0);
        assertWalkable(g, r);
        assertEquals(7, r.path().size());
    }

    @Test
    void aTwentyHighLadderIsWithinTheVerticalMargin() {
        TestGrid g = new TestGrid().fill(0, 0, 0, 0, 20, 0, Cell.LADDER);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 20, 0);
        assertWalkable(g, r);
        assertEquals(21, r.path().size());
    }

    @Test
    void aLongLadderKeepsItsFootTopAndEveryRung() {
        // The design (docs/design/routes.md §8): a waypoint on every ladder rung, so Climb takes it a rung at a time.
        TestGrid g = new TestGrid().fill(0, 0, 0, 0, 20, 0, Cell.LADDER);
        Result r = new Surveyor(g).survey(0, 0, 0, 0, 20, 0);
        assertTrue(r.ok(), r.failure());
        assertEquals(0, r.waypoints().get(0).y());
        assertEquals(20, r.waypoints().get(r.waypoints().size() - 1).y());
        assertTrue(r.waypoints().stream().allMatch(p -> p.x() == 0 && p.z() == 0), r.waypoints().toString());
    }

    @Test
    void ladderUpThroughATrapdoorHatchToAnUpperFloor() {
        TestGrid g = new TestGrid().fill(0, 0, 0, 0, 3, 0, Cell.LADDER).fill(-2, 4, -2, 2, 4, 2, Cell.ROAD)
                .set(0, 4, 0, Cell.HATCH);
        Result r = new Surveyor(g).survey(3, 0, 0, 2, 5, 1);
        assertWalkable(g, r);
        assertEquals(5, r.waypoints().get(r.waypoints().size() - 1).y());
    }

    /** A floating platform (feet y = 6) with vines hanging off its east edge, ending two blocks above the ground. */
    private static TestGrid platformWithHangingVines() {
        return new TestGrid().fill(3, 5, -1, 7, 5, 1, Cell.ROAD).fill(8, 2, 0, 8, 6, 0, Cell.LADDER);
    }

    @Test
    void climbsDownVinesHangingInOpenAirAndDropsFromTheirEnd() {
        TestGrid g = platformWithHangingVines();
        Result r = new Surveyor(g).survey(5, 6, 0, 12, 0, 0);
        assertWalkable(g, r);
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.LADDER));
    }

    @Test
    void cannotClimbUpVinesThatEndTwoBlocksAboveTheGround() {
        TestGrid g = platformWithHangingVines();
        Result r = new Surveyor(g, 50_000).survey(12, 0, 0, 5, 6, 0);
        assertFalse(r.ok(), "the lowest vine is out of reach: " + r.path());
    }

    // ---- Stairs, slabs, drops ---------------------------------------------------------------

    /** Stairs up towards the east at x = 1..3 (feet y = 1..3), a platform from x = 4 (feet y = 3), walled on both sides. */
    private static TestGrid stairFlight() {
        TestGrid g = new TestGrid().set(1, 0, 0, Cell.STAIRS_EAST).set(2, 0, 0, Cell.GROUND).set(2, 1, 0, Cell.STAIRS_EAST)
                .fill(3, 0, 0, 3, 1, 0, Cell.GROUND).set(3, 2, 0, Cell.STAIRS_EAST)
                .fill(4, 0, 0, 8, 1, 0, Cell.GROUND).fill(4, 2, 0, 8, 2, 0, Cell.ROAD);
        return g.fill(-5, 0, -1, 8, 5, -1, Cell.WALL).fill(-5, 0, 1, 8, 5, 1, Cell.WALL);
    }

    @Test
    void walksUpTheStairsFromTheirFront() {
        TestGrid g = stairFlight();
        Result r = new Surveyor(g).survey(-3, 0, 0, 7, 3, 0);
        assertWalkable(g, r);
        assertNoInnerWaypointOnStep(g, r);
        for (int i = 1; i < r.path().size(); i++) {
            if (r.path().get(i).move() == Move.STEP_UP) {
                assertEquals(r.path().get(i - 1).x() + 1, r.path().get(i).x());
            }
        }
    }

    @Test
    void walksDownTheStairsFromTheirTop() {
        TestGrid g = stairFlight();
        Result r = new Surveyor(g).survey(7, 3, 0, -3, 0, 0);
        assertWalkable(g, r);
        assertNoInnerWaypointOnStep(g, r);
        assertTrue(has(r.path(), 2, 2, 0), "should go down the stairs: " + r.path());
    }

    @Test
    void stepsOffTheSideOfAStairIfTheFlightIsBlockedAtTheBottom() {
        // The foot of the flight is walled in; the way out is off the side of the lowest stair (a drop of one).
        TestGrid g = stairFlight().fill(-5, 0, 0, 0, 3, 0, Cell.WALL).fill(1, 0, 1, 1, 1, 1, Cell.OPEN);
        Result r = new Surveyor(g).survey(7, 3, 0, 1, 0, 3);
        assertWalkable(g, r);
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.DROP), r.path().toString());
    }

    @Test
    void climbsASpiralStaircaseRoundAColumn() {
        // Stairs on the edges and full blocks on the corners, round a 3x3 ring, rising one block per cell.
        TestGrid g = new TestGrid()
                .set(0, 0, 0, Cell.STAIRS_EAST).set(1, 1, 0, Cell.STAIRS_EAST).set(2, 1, 0, Cell.ROAD)
                .set(2, 2, 1, Cell.STAIRS_SOUTH).set(2, 2, 2, Cell.ROAD)
                .set(1, 3, 2, Cell.STAIRS_WEST).set(0, 3, 2, Cell.ROAD)
                .set(0, 4, 1, Cell.STAIRS_NORTH).set(0, 4, 0, Cell.ROAD)
                .fill(1, 0, 1, 1, 7, 1, Cell.WALL);
        Result r = new Surveyor(g).survey(-1, 0, 0, 0, 5, 0);
        assertWalkable(g, r);
        assertNoInnerWaypointOnStep(g, r);
        assertEquals(5, r.waypoints().get(r.waypoints().size() - 1).y());
    }

    @Test
    void alternatingSlabsAndFullBlocksInAOneWideLane() {
        TestGrid g = new TestGrid().set(1, 0, 0, Cell.STEP).set(2, 0, 0, Cell.GROUND).set(3, 0, 0, Cell.STEP)
                .set(4, 0, 0, Cell.GROUND).set(5, 0, 0, Cell.STEP)
                .fill(0, 0, -1, 6, 3, -1, Cell.WALL).fill(0, 0, 1, 6, 3, 1, Cell.WALL);
        Result r = new Surveyor(g).survey(0, 0, 0, 6, 0, 0);
        assertWalkable(g, r);
        assertNoInnerWaypointOnStep(g, r);
        assertTrue(has(r.path(), 3, 1, 0));
    }

    @Test
    void dropsThreeBlocksOffAPillarAndNoMore() {
        TestGrid three = new TestGrid().fill(0, 0, 0, 0, 2, 0, Cell.GROUND);
        Result r = new Surveyor(three).survey(0, 3, 0, 6, 0, 0);
        assertWalkable(three, r);
        assertTrue(r.path().stream().anyMatch(p -> p.move() == Move.DROP));
        assertTrue(has(r.waypoints(), 1, 0, 0), "a waypoint where he lands: " + r.waypoints());

        TestGrid four = new TestGrid().fill(0, 0, 0, 0, 3, 0, Cell.GROUND);
        assertFalse(new Surveyor(four, 20_000).survey(0, 4, 0, 6, 0, 0).ok());
    }

    @Test
    void neverDropsIntoWaterOrOntoDanger() {
        for (Cell bad : new Cell[]{Cell.WATER, Cell.DANGER}) {
            TestGrid g = new TestGrid().fill(-4, -1, -4, 4, -1, 4, bad).fill(0, 0, 0, 0, 1, 0, Cell.GROUND);
            Result r = new Surveyor(g, 20_000).survey(0, 2, 0, 9, 0, 0);
            assertFalse(r.ok(), bad + ": " + r.path());
        }
    }

    @Test
    void aOneBlockWideLedgeWithDropsOnBothSides() {
        TestGrid g = new TestGrid().fill(0, 0, 0, 15, 1, 0, Cell.GROUND).fill(0, 2, 0, 15, 2, 0, Cell.ROAD);
        Result r = new Surveyor(g).survey(0, 3, 0, 15, 3, 0);
        assertWalkable(g, r);
        assertTrue(r.path().stream().allMatch(p -> p.z() == 0 && p.y() == 3), r.path().toString());
    }

    @Test
    void cannotJumpAGapOverAPit() {
        for (int depth : new int[]{2, 6}) {
            TestGrid g = new TestGrid().fill(-40, 0, -1, 40, 3, -1, Cell.WALL).fill(-40, 0, 1, 40, 3, 1, Cell.WALL)
                    .fill(5, -depth, 0, 5, -1, 0, Cell.OPEN);
            Result r = new Surveyor(g, 50_000).survey(0, 0, 0, 10, 0, 0);
            assertFalse(r.ok(), "pit " + depth + " deep: " + r.path());
        }
    }

    @Test
    void aOneBlockDitchIsSteppedDownIntoAndOutOf() {
        TestGrid g = new TestGrid().fill(-40, 0, -1, 40, 3, -1, Cell.WALL).fill(-40, 0, 1, 40, 3, 1, Cell.WALL)
                .set(5, -1, 0, Cell.OPEN);
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 0, 0);
        assertWalkable(g, r);
    }

    // ---- Corridors --------------------------------------------------------------------------

    private static void assertCorridorWithBend(int east, int north) {
        TestGrid g = rock(-2, -north - 3, east + 3, 2);
        g.fill(0, 0, 0, east, 1, 0, Cell.OPEN).fill(east, 0, -north, east, 1, 0, Cell.OPEN);
        Result r = new Surveyor(g).survey(0, 0, 0, east, 0, -north);
        assertWalkable(g, r);
        assertTrue(has(r.waypoints(), east, 0, 0), "a waypoint at the bend: " + r.waypoints());
    }

    @Test
    void corridorLegOfExactlyMaxGapThenABend() {
        assertCorridorWithBend(8, 9);
    }

    @Test
    void corridorLegOfMaxGapPlusOneThenABend() {
        assertCorridorWithBend(9, 8);
    }

    // ---- Odd ends ---------------------------------------------------------------------------

    @Test
    void endInsideThinRockSnapsToStandingNextToIt() {
        TestGrid g = new TestGrid().fill(8, 0, -2, 10, 3, 2, Cell.GROUND);
        Result r = new Surveyor(g).survey(0, 0, 0, 9, 1, 0);
        assertWalkable(g, r);
        Point end = r.waypoints().get(r.waypoints().size() - 1);
        assertTrue(Math.abs(end.x() - 9) <= 2 && Math.abs(end.y() - 1) <= 2, end.toString());
    }

    @Test
    void endDeepInsideRockHasNowhereToStand() {
        TestGrid g = new TestGrid().fill(8, 0, -6, 14, 3, 6, Cell.GROUND);
        Result r = new Surveyor(g).survey(0, 0, 0, 11, 1, 0);
        assertFalse(r.ok());
        assertTrue(r.failure().contains("nowhere to stand at the end"), r.failure());
    }

    @Test
    void endTwoBlocksUpInMidAirSnapsDown() {
        TestGrid g = new TestGrid();
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 2, 0);
        assertWalkable(g, r);
        assertEquals(0, r.waypoints().get(r.waypoints().size() - 1).y());
    }

    @Test
    void endFarUpInMidAirIsRefusedCleanly() {
        TestGrid g = new TestGrid();
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 6, 0);
        assertFalse(r.ok());
        assertTrue(r.failure().contains("nowhere to stand"), r.failure());
    }

    @Test
    void aChestMailboxInFrontOfTheEndIsNotWalkedOver() {
        TestGrid g = new TestGrid().set(10, 0, 0, Cell.WALL);
        Result r = new Surveyor(g).survey(0, 0, 0, 10, 0, 0);
        assertWalkable(g, r);
        assertFalse(has(r.path(), 10, 0, 0));
        Point end = r.waypoints().get(r.waypoints().size() - 1);
        assertEquals(1, Math.abs(end.x() - 10) + Math.abs(end.z()));
    }

    @Test
    void aChestInAGardenWallIsReachedFromTheStreetSide() {
        // The chest sits in the east wall of a sealed yard. The postman is in the street, east of it: the end must
        // snap to the street cell, not the (unreachable) yard cell on the other side.
        TestGrid g = new TestGrid().fill(4, 0, -4, 4, 1, 4, Cell.WALL).fill(4, 0, -4, 10, 1, -4, Cell.WALL)
                .fill(4, 0, 4, 10, 1, 4, Cell.WALL).fill(10, 0, -4, 10, 1, 4, Cell.WALL);
        Result r = new Surveyor(g).survey(20, 0, 0, 10, 0, 0);
        assertWalkable(g, r);
    }

    // ---- Preferences and bounds -------------------------------------------------------------

    @Test
    void prefersARoadToSandWhenTheDetourIsShort() {
        TestGrid g = new TestGrid().fill(-2, -1, -4, 22, -1, 6, Cell.ROUGH).fill(0, -1, 2, 20, -1, 2, Cell.ROAD);
        Result r = new Surveyor(g).survey(0, 0, 0, 20, 0, 0);
        assertWalkable(g, r);
        long on_road = r.path().stream().filter(p -> p.z() == 2).count();
        assertTrue(on_road >= 15, "only " + on_road + " of " + r.path().size() + " cells on the road");
    }

    @Test
    void goesThroughACropFieldWhenItIsTheOnlyWay() {
        // A walled three-wide lane whose middle stretch is a field: expensive, but the only way (intent: passable).
        TestGrid g = new TestGrid().fill(-40, 0, -2, 60, 3, -2, Cell.WALL).fill(-40, 0, 2, 60, 3, 2, Cell.WALL)
                .fill(3, -1, -1, 8, -1, 1, Cell.ROUGH).fill(3, 0, -1, 8, 0, 1, Cell.CROP);
        Result r = new Surveyor(g).survey(0, 0, 0, 12, 0, 0);
        assertWalkable(g, r);
        assertTrue(r.path().stream().anyMatch(p -> g.cell(p.x(), p.y(), p.z()) == Cell.CROP));
    }

    @Test
    void aLongWallNeedsTheWideMarginAndTheFirstTryReportsTheEdge() {
        TestGrid g = new TestGrid().fill(5, 0, -50, 5, 1, 50, Cell.WALL);
        Surveyor s = new Surveyor(g);
        Result narrow = s.survey(0, 0, 0, 10, 0, 0);
        assertFalse(narrow.ok());
        assertTrue(narrow.hit_edge(), narrow.failure());
        Result wide = s.survey(0, 0, 0, 10, 0, 0, Surveyor.WIDE_MARGIN);
        assertWalkable(g, wide);
    }

    @Test
    void walksOverAHillTwentyBlocksHigh() {
        // A ridge of one-block steps, 20 high, running forever north-south: the only way is over the top.
        Grid hill = (x, y, z) -> {
            if (y < 0) {
                return Cell.GROUND;
            }
            if (x >= 1 && x <= 40 && y < Math.min(Math.min(x, 41 - x), 20)) {
                return Cell.GROUND;
            }
            return Cell.OPEN;
        };
        // The first try's box only reaches 16 up, and says it ran into its edge; RouteSurvey's wider retry goes higher.
        Result first = new Surveyor(hill).survey(0, 0, 0, 41, 0, 0);
        assertFalse(first.ok());
        assertTrue(first.hit_edge(), first.failure());
        Result r = new Surveyor(hill).survey(0, 0, 0, 41, 0, 0, Surveyor.WIDE_MARGIN);
        assertTrue(r.ok(), r.failure());
    }
}
