package com.vodhanel.minecraft.va_postal.navigation;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.PassableState;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.StandableState;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.citizensnpcs.api.astar.pathfinder.SwimmingExaminer;
import net.citizensnpcs.api.util.BoundingBox;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DryGroundTest {
    /** Says every spot is standable and passable, as Citizens' block examiner does for the surface of water. */
    private static final BlockExaminer ANYWHERE = new BlockExaminer() {
        @Override
        public StandableState canStandAt(BlockSource source, PathPoint point) {
            return StandableState.STANDABLE;
        }

        @Override
        public float getCost(BlockSource source, PathPoint point) {
            return 1.5F;
        }

        @Override
        public PassableState isPassable(BlockSource source, PathPoint point) {
            return PassableState.PASSABLE;
        }
    };

    @Test
    void neverStandsOnOrInWater() {
        Blocks b = new Blocks();
        b.put(0, 64, 0, Material.WATER);          // a canal: water underfoot
        b.put(5, 64, 0, Material.WATER);          // standing in water
        b.put(5, 65, 0, Material.WATER);
        b.put(9, 64, 0, Material.LAVA);
        DryGround dry = new DryGround(ANYWHERE);
        assertEquals(StandableState.NOT_STANDABLE, dry.canStandAt(b, at(0, 65, 0)));
        assertEquals(StandableState.NOT_STANDABLE, dry.canStandAt(b, at(5, 65, 0)));
        assertEquals(StandableState.NOT_STANDABLE, dry.canStandAt(b, at(9, 65, 0)));
    }

    @Test
    void leavesDryGroundToCitizens() {
        Blocks b = new Blocks();
        b.put(0, 64, 0, Material.GRASS_BLOCK);
        DryGround dry = new DryGround(ANYWHERE);
        assertEquals(StandableState.STANDABLE, dry.canStandAt(b, at(0, 65, 0)));
        assertEquals(PassableState.PASSABLE, dry.isPassable(b, at(0, 65, 0)));
        assertEquals(1.5F, dry.getCost(b, at(0, 65, 0)));
    }

    @Test
    void dropsSwimmingAndKeepsTheRest() {
        // (Citizens' own block examiner can't be made without a server; the towns soak covers swapping it.)
        NavigatorParameters params = new NavigatorParameters();
        params.examiner(new SwimmingExaminer());
        params.examiner(ANYWHERE);

        DryGround.apply(params);
        DryGround.apply(params);   // again, as for a postman whose navigator is set up twice

        List<BlockExaminer> now = List.of(params.examiners());
        assertEquals(List.of(ANYWHERE), now);
    }

    private static PathPoint at(int x, int y, int z) {
        Vector v = new Vector(x, y, z);
        return new PathPoint() {
            @Override
            public void addCallback(PathCallback callback) {
            }

            @Override
            public PathPoint createChild(int dx, int dy, int dz) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PathPoint createChild(int dx, int dy, int dz, float cost) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Vector getGoal() {
                return v;
            }

            @Override
            public PathPoint getParentPoint() {
                return null;
            }

            @Override
            public List<Vector> getPathVectors() {
                return List.of();
            }

            @Override
            public Vector getVector() {
                return v;
            }

            @Override
            public void setPathVectors(List<Vector> vectors) {
            }

            @Override
            public void setVector(Vector vector) {
            }
        };
    }

    /** Air everywhere except the blocks put. */
    private static final class Blocks extends BlockSource {
        private final Map<String, Material> blocks = new HashMap<>();

        void put(int x, int y, int z, Material m) {
            blocks.put(x + "," + y + "," + z, m);
        }

        @Override
        public BlockData getBlockDataAt(int x, int y, int z) {
            return null;
        }

        @Override
        public BoundingBox getCollisionBox(int x, int y, int z) {
            return null;
        }

        @Override
        public Material getMaterialAt(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, Material.AIR);
        }

        @Override
        public boolean isYWithinBounds(int y) {
            return true;
        }
    }
}
