package com.dwinovo.numen.core.pathing.bridge;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.goals.GoalBlock;
import com.dwinovo.numen.core.pathing.goals.GoalComposite;
import com.dwinovo.numen.core.pathing.goals.GoalGetToBlock;
import com.dwinovo.numen.core.pathing.goals.GoalNear;
import com.dwinovo.numen.core.pathing.goals.GoalRunAway;
import com.dwinovo.numen.core.pathing.goals.GoalTwoBlocks;
import com.dwinovo.numen.core.pathing.goals.GoalXZ;
import com.dwinovo.numen.core.pathing.goals.GoalYLevel;

import net.minecraft.core.BlockPos;

/**
 * 任务层目标词表({@link NavGoal} 工厂产物)到新内核目标族
 * ({@link Goal})的映射表。工厂产物按具名类型识别、直映射为内核
 * 原生目标;任务层手写的匿名 {@link NavGoal} 走 {@link #wrap} 通用
 * 包装(isInGoal=isAt、heuristic 透传)。
 *
 * <p>映射表(逐工厂):
 * <ul>
 *   <li>exact → {@link GoalBlock}</li>
 *   <li>column(x,z) → {@link GoalXZ}</li>
 *   <li>yLevel → {@link GoalYLevel}</li>
 *   <li>near → {@link GoalNear}(半径平方以 double 保精度的子类)</li>
 *   <li>nearGround → {@link GoalNear} 子类(保留水平半径 + y±1 带,
 *       不退化为三维球——防"垫柱到目标旁悬空算到达"的几何回归)</li>
 *   <li>adjacent → {@link GoalGetToBlock}(成员集置换:同层正交贴邻
 *       保留,目标格本身与正下两格新增,y+1 的贴邻不再是成员;身体级
 *       到达仍由任务层 ArrivalSpec 把关)</li>
 *   <li>getToBlock → {@link GoalGetToBlock}(成员判定逐格一致;启发式
 *       形状不同——旧词表减"一步+一跳"松量,内核按修正 y 差估价)</li>
 *   <li>composite → {@link GoalComposite}(成员递归映射)</li>
 *   <li>mineColumn → GoalTwoBlocks 族:maxBelow=0 → {@link GoalBlock},
 *       =1 → {@link GoalTwoBlocks},≥2 → {@link GoalComposite}(双格 +
 *       逐格补 {@link GoalBlock}),成员集与站位带逐格一致</li>
 *   <li>runAway → {@link GoalRunAway}(旧语义永不到达,新目标用
 *       {@link #NEVER_ARRIVE_DISTANCE} 兜底)</li>
 * </ul>
 */
public final class GoalAdapter {

    private GoalAdapter() {}

    /**
     * 旧 runAway 语义是"永不到达、只管外走";{@link GoalRunAway} 需要
     * 一个到达距离,取 int 距离平方不溢出的最大值(≈46340 格)——单段
     * 导航实际不可能走出这个距离,等效永不到达。
     */
    static final double NEVER_ARRIVE_DISTANCE = 46_340;

    /** 把任务层目标映射/包装为内核目标。 */
    public static Goal toEngineGoal(NavGoal goal) {
        if (goal instanceof NavGoal.Exact g) {
            return new GoalBlock(g.goal);
        }
        if (goal instanceof NavGoal.Column g) {
            return new GoalXZ(g.x, g.z);
        }
        if (goal instanceof NavGoal.YLevel g) {
            return new GoalYLevel(g.level);
        }
        if (goal instanceof NavGoal.Near g) {
            return new RadiusNear(g.goal, g.radiusSqr, false);
        }
        if (goal instanceof NavGoal.NearGround g) {
            return new RadiusNear(g.goal, g.radiusSqr, true);
        }
        if (goal instanceof NavGoal.Adjacent g) {
            return new GoalGetToBlock(g.goal);
        }
        if (goal instanceof NavGoal.GetToBlock g) {
            return new GoalGetToBlock(g.goal);
        }
        if (goal instanceof NavGoal.Composite g) {
            Goal[] members = new Goal[g.members.size()];
            for (int i = 0; i < members.length; i++) {
                members[i] = toEngineGoal(g.members.get(i));
            }
            return new GoalComposite(members);
        }
        if (goal instanceof NavGoal.MineColumn g) {
            return mineColumnGoal(g.ore, g.maxBelow);
        }
        if (goal instanceof NavGoal.RunAway g) {
            return new GoalRunAway(NEVER_ARRIVE_DISTANCE, g.maintainY, g.from);
        }
        return wrap(goal);
    }

    /**
     * 矿柱站位带 → GoalTwoBlocks 族:成员集与
     * {@code NavGoal.mineColumn(ore, maxBelow)} 逐格一致
     * (脚位在 {@code ore.y .. ore.y - maxBelow})。
     */
    private static Goal mineColumnGoal(BlockPos ore, int maxBelow) {
        if (maxBelow <= 0) {
            return new GoalBlock(ore);
        }
        if (maxBelow == 1) {
            return new GoalTwoBlocks(ore);
        }
        List<Goal> members = new ArrayList<>(maxBelow);
        members.add(new GoalTwoBlocks(ore));
        for (int below = 2; below <= maxBelow; below++) {
            members.add(new GoalBlock(ore.getX(), ore.getY() - below, ore.getZ()));
        }
        return new GoalComposite(members.toArray(Goal[]::new));
    }

    /**
     * 匿名/自定义 {@link NavGoal} 的通用包装:成员判定与启发式逐点
     * 透传。每次查询新建一个 {@link BlockPos}(NavGoal 契约禁止实现
     * 保留参数引用,这里给它的恰是一次性对象,天然满足)。
     */
    public static Goal wrap(NavGoal goal) {
        return new WrappedNavGoal(goal);
    }

    /** {@link #wrap} 的产物(具名,便于调试输出)。 */
    static final class WrappedNavGoal implements Goal {

        final NavGoal delegate;

        WrappedNavGoal(NavGoal delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return delegate.isAt(new BlockPos(x, y, z));
        }

        @Override
        public double heuristic(int x, int y, int z) {
            return delegate.heuristic(new BlockPos(x, y, z));
        }

        @Override
        public String toString() {
            return "WrappedNavGoal{" + delegate + "}";
        }
    }

    /**
     * 半径以 double 平方保存的邻域目标。{@code groundBand} 为 true 时
     * 成员判定改为"水平距离平方 ≤ 半径平方且 y 差在 ±1 内"(nearGround
     * 语义);false 时为三维球但半径平方不取整(near 的小数半径不丢)。
     * 启发式沿用父类(到中心的全量估价,不减半径)。
     */
    static final class RadiusNear extends GoalNear {

        private final double radiusSqrExact;
        private final boolean groundBand;

        RadiusNear(BlockPos center, double radiusSqr, boolean groundBand) {
            super(center, (int) Math.ceil(Math.sqrt(radiusSqr)));
            this.radiusSqrExact = radiusSqr;
            this.groundBand = groundBand;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            int xDiff = x - this.x;
            int yDiff = y - this.y;
            int zDiff = z - this.z;
            if (groundBand) {
                if (yDiff < -1 || yDiff > 1) {
                    return false;
                }
                return xDiff * xDiff + zDiff * zDiff <= radiusSqrExact;
            }
            return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= radiusSqrExact;
        }

        @Override
        public String toString() {
            return String.format("RadiusNear{x=%d,y=%d,z=%d,radiusSqr=%s,groundBand=%s}",
                    x, y, z, radiusSqrExact, groundBand);
        }
    }
}
