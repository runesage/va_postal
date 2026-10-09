package com.vodhanel.minecraft.va_postal.navigation;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.PathPoint;
import net.citizensnpcs.api.astar.pathfinder.SwimmingExaminer;
import org.bukkit.Material;
import org.bukkit.util.Vector;

/**
 * Citizens' pathfinder, but postmen keep their feet dry. Citizens counts the surface of water as ground to stand on
 * (its {@link MinecraftBlockExaminer} does by design, and the {@link SwimmingExaminer} it always adds does too), and
 * {@code avoidWater} only makes water dearer. As diagonals cost Citizens no more than straight steps, a short hop
 * along a canal bank could plan its way out over the water for free: the postman sank to the bottom, two blocks
 * down, and couldn't climb out. Surveyed routes never touch water, so with avoidWater on, Postal swaps in this.
 *
 * Citizens has no veto (the first examiner to call a spot standable decides), so this wraps the block examiner:
 * standing on or in liquid is never standable, anything else is up to the examiner it wraps.
 */
final class DryGround implements BlockExaminer {
    private final BlockExaminer inner;

    DryGround(BlockExaminer inner) {
        this.inner = inner;
    }

    /** Swaps Citizens' block examiner for a dry one and drops its swimming examiner. Safe to call more than once. */
    static void apply(NavigatorParameters params) {
        BlockExaminer[] had = params.examiners();
        params.clearExaminers();
        for (BlockExaminer examiner : had) {
            if (examiner instanceof SwimmingExaminer) {
                continue;
            }
            params.examiner(examiner instanceof MinecraftBlockExaminer ? new DryGround(examiner) : examiner);
        }
    }

    static boolean wet(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    @Override
    public StandableState canStandAt(BlockSource source, PathPoint point) {
        Vector v = point.getVector();
        if (wet(source.getMaterialAt(v.getBlockX(), v.getBlockY() - 1, v.getBlockZ()))
                || wet(source.getMaterialAt(v.getBlockX(), v.getBlockY(), v.getBlockZ()))) {
            return StandableState.NOT_STANDABLE;
        }
        return inner.canStandAt(source, point);
    }

    @Override
    public float getCost(BlockSource source, PathPoint point) {
        return inner.getCost(source, point);
    }

    @Override
    public PassableState isPassable(BlockSource source, PathPoint point) {
        return inner.isPassable(source, point);
    }

    @Override
    public String toString() {
        return "DryGround(" + inner + ")";
    }
}
