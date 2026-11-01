package com.dwinovo.numen.pathing.benchmark;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.world.Kinematics;

import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * 物理口径的目标:标准答案只算物理耗时(刻),不含偏好。路线规格里的罚分在 {@link Spec#physicalRoute} 里清零;目标这边剩一项偏好——
 * 挖一格时每隔着一格硬遮挡加的几格平走的耗时({@code Goals} 的 {@code SIGHT_BLOCKER_BLOCKS}),在 {@link Goal#arrival} 里。
 * 这里把有限的停点价清零,其余照原目标:停在哪儿办得成(无穷)仍由原目标说了算。
 */
final class Physical implements Goal {

    private final Goal goal;

    private Physical(Goal goal) {
        this.goal = goal;
    }

    static Goal of(Goal goal) {
        return new Physical(goal);
    }

    @Override
    public boolean contains(int x, int y, int z, Stance stance) {
        return goal.contains(x, y, z, stance);
    }

    @Override
    public double estimate(int x, int y, int z, Kinematics.Pace pace) {
        return goal.estimate(x, y, z, pace);
    }

    @Override
    public double arrival(WorldView level, int x, int y, int z, Stance stance) {
        return Double.isFinite(goal.arrival(level, x, y, z, stance)) ? 0 : Double.POSITIVE_INFINITY;
    }

    @Override
    public LongSet endCells() {
        return goal.endCells();
    }

    @Override
    public Heights heights() {
        return goal.heights();
    }

    @Override
    public PositionCosts protection() {
        return goal.protection();
    }

    @Override
    public Sighting sight(int x, int y, int z, Stance stance) {
        return goal.sight(x, y, z, stance);
    }
}
