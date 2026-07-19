package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import net.minecraft.core.BlockPos;

/**
 * What the search is trying to reach. Until
 * now arrival semantics were written five times in five places (the search's
 * tolerance hack, goto's radius, three hand-rolled "stand next to X"
 * pickers); a goal object makes the search terminate, the heuristic aim and
 * the caller assert against the SAME definition.
 *
 * <p>Node-domain only: {@link #isAt} judges feet CELLS during the search.
 * Live-entity arrival predicates (exact doubles, reach distances) remain the
 * task layer's business — they answer a different question ("is my body close
 * enough") than the search's ("may this node end the path").
 *
 * <p>工厂产物均为具名嵌套类(参数以 final 字段暴露):桥接层按类型识别
 * 工厂产物并直映射到新内核目标族;匿名实现(任务层手写的自定义目标)
 * 无法识别,走通用包装。行为与先前的匿名类逐字相同。
 */
public interface NavGoal {

    // ---- 启发式常量(数值与内核成本表同源) ----

    /** 对角步的水平距离系数。 */
    double SQRT_2 = Math.sqrt(2.0);
    /** 加权 A* 沿用的乐观单格成本(≈疾跑单格)。 */
    double COST_HEURISTIC = 3.563;
    /** 升一格的乐观成本(跳跃抛物线差)。 */
    double JUMP_ONE_BLOCK = ActionCosts.JUMP_ONE_BLOCK_COST;
    /** 降一格的乐观成本(坠落两格耗时之半)。 */
    double DESCEND_ONE_BLOCK = ActionCosts.FALL_N_BLOCKS_COST[2] / 2.0;

    /** May a path legitimately end at this feet cell? */
    boolean isAt(BlockPos feet);

    /** Admissible lower bound (ticks) on the remaining cost from {@code from}. */
    double heuristic(BlockPos from);

    /** Representative position — diagnostics, goal-moved checks, locate math. */
    BlockPos center();

    // ---- the shared octile + vertical point bound ----

    /**
     * Octile horizontal distance (× walk cost) plus a vertical term — the
     * admissible point-to-point bound every concrete goal builds on. Downward
     * must cost &gt; 0 (see {@link #DESCEND_ONE_BLOCK}): with a free
     * down-direction every node straight above a deep target scored h == 0
     * and partial paths collapsed to the start node.
     *
     * <p>Known weight inconsistency (deliberate legacy parity): the weighted-A*
     * factor {@code COST_HEURISTIC} (3.563) is folded into the XZ term only,
     * while the vertical terms ({@code JUMP_ONE_BLOCK} / {@code DESCEND_ONE_BLOCK})
     * are unweighted — the split the verified behaviour was tuned on. Normalizing
     * the weights is a flagged follow-up, not something to "fix" in passing.
     *
     * <p>Adapter contract: {@code heuristic()} / {@code isAt()} implementations
     * must not retain the {@code BlockPos} argument (adapters may reuse a
     * mutable cursor across calls); read x/y/z (or compare/measure) and return.
     */
    static double pointBound(BlockPos goal, BlockPos from) {
        double dx = Math.abs(goal.getX() - from.getX());
        double dz = Math.abs(goal.getZ() - from.getZ());
        // Horizontal: (diagonal·√2 + straight) × COST_HEURISTIC. COST_HEURISTIC
        // (≈ sprint cost) IS the per-block weight here — the heap key adds no
        // further multiplier (the weight is folded into the heuristic itself).
        double horizontal = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                * COST_HEURISTIC;
        // Vertical: up costs JUMP per block, down costs DESCEND (fall[2]/2).
        int dy = goal.getY() - from.getY();
        double vertical = dy > 0
                ? dy * JUMP_ONE_BLOCK
                : -dy * DESCEND_ONE_BLOCK;
        return horizontal + vertical;
    }

    // ---- factories ----

    /** Exactly this feet cell. */
    static NavGoal exact(BlockPos pos) {
        return new Exact(pos);
    }

    /**
     * Reach the {@code (x, z)} column at ANY height.
     * The heuristic is the pure horizontal
     * octile term (no vertical), so the search heads for the column and stops at
     * whatever ground exists there. This is the "go to a location" goal: the
     * caller's Y is irrelevant, so a wrong/guessed Y can never make it unreachable.
     */
    static NavGoal column(int x, int z) {
        return new Column(x, z);
    }

    /**
     * Reach a Y level at ANY X/Z: "change elevation to this height" (climb to the surface,
     * descend to a mining depth). Heuristic is the pure vertical term — up costs
     * {@link #JUMP_ONE_BLOCK} per block, down {@link #DESCEND_ONE_BLOCK}.
     */
    static NavGoal yLevel(int level) {
        return new YLevel(level);
    }

    /** Any feet cell within {@code radius} (Euclidean, blocks) of {@code pos}. */
    static NavGoal near(BlockPos pos, double radius) {
        return new Near(pos, radius);
    }

    /**
     * Any feet cell within {@code radius} (Euclidean, blocks) of {@code pos}
     * HORIZONTALLY, with the feet at the target's height ±1. This is the
     * vicinity goal for chasing/following things that live on the ground: unlike
     * the raw 3D {@link #near} sphere it admits no elevated cell, so "place a
     * scaffold, stand on it, count as arrived" is not a satisfying completion —
     * the geometry that once made an approach finish by frantically pillaring
     * beside its target.
     */
    static NavGoal nearGround(BlockPos pos, double radius) {
        return new NearGround(pos, radius);
    }

    /**
     * Any feet cell horizontally adjacent to {@code target} (±1 step on one
     * axis), at the target's height ±1 — "stand next to this block so you can
     * work on it". The standability of the ending cell is the graph's own
     * guarantee (only occupiable cells become nodes).
     */
    static NavGoal adjacent(BlockPos target) {
        return new Adjacent(target);
    }

    /**
     * Any feet cell TOUCHING {@code target}: beside it, on top of it, up to two
     * below it (the two-block body still reaches its underside) — or in it, if
     * the cell is enterable. Membership is a Manhattan bound with the body-height
     * correction: {@code |dx| + |dy'| + |dz| <= 1} where {@code dy' = dy < 0 ?
     * dy+1 : dy}. This is the "get to this block to use it" goal — the target
     * cell itself stays untouched (a chest, a crafting table), the path ends on
     * whichever neighbouring cell the graph can actually stand on.
     */
    static NavGoal getToBlock(BlockPos target) {
        return new GetToBlock(target);
    }

    /**
     * Any of several goals. Satisfied by reaching
     * ANY member; the heuristic is the minimum over members, so a single A* search
     * naturally heads for the CLOSEST reachable one. This is how mining targets a
     * whole field of ore at once instead of greedily picking the nearest (which is
     * often the one walled in and unreachable).
     */
    static NavGoal composite(java.util.List<NavGoal> goals) {
        return new Composite(goals);
    }

    /**
     * Stand in the ore's own column to mine it — a family of mining stance
     * goals, parameterised by how far BELOW the ore the feet may be:
     * <ul>
     *   <li>{@code maxBelow == 0} → feet exactly at the ore;</li>
     *   <li>{@code maxBelow == 1} → feet at the ore or one below;</li>
     *   <li>{@code maxBelow == 2} → feet at the ore, one, or two below.</li>
     * </ul>
     * Which one a given ore gets is decided by {@code MineCompanionTask.coalesce}:
     * the bottom
     * of a vertical run gets the exact ({@code maxBelow == 0}) stance so the body
     * mines it in place rather
     * than tunnelling under it. The vertical term in the heuristic folds the whole
     * accepted band to zero cost.
     */
    static NavGoal mineColumn(BlockPos ore, int maxBelow) {
        return new MineColumn(ore, maxBelow);
    }

    /** Loosest-stance shorthand (feet at the ore, one, or two below). */
    static NavGoal mine(BlockPos ore) {
        return mineColumn(ore, 2);
    }

    /**
     * Get as FAR as possible from {@code from} while holding a y-level —
     * used for branch mining: when no ore is
     * known, head out along the level to dig fresh tunnel and expose more. Never
     * "arrived" (isAt always false) so the search returns a best-effort partial that
     * walks outward; the next replan continues exploring.
     */
    static NavGoal runAway(BlockPos from, int maintainY) {
        return new RunAway(from, maintainY);
    }

    // ---- 工厂产物(具名,参数可读;行为与原匿名类逐字一致) ----

    /** {@link #exact} 的产物:三轴全等才到达。 */
    final class Exact implements NavGoal {
        public final BlockPos goal;

        Exact(BlockPos pos) {
            this.goal = pos.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.equals(goal);
        }

        @Override public double heuristic(BlockPos from) {
            return pointBound(goal, from);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #column} 的产物:任意高度的 XZ 列。 */
    final class Column implements NavGoal {
        public final int x;
        public final int z;

        Column(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.getX() == x && feet.getZ() == z;
        }

        @Override public double heuristic(BlockPos from) {
            double dx = Math.abs(x - from.getX());
            double dz = Math.abs(z - from.getZ());
            return (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                    * COST_HEURISTIC;
        }

        @Override public BlockPos center() {
            return new BlockPos(x, 0, z);   // y irrelevant — goal is XZ-only
        }
    }

    /** {@link #yLevel} 的产物:任意 XZ 的 Y 层。 */
    final class YLevel implements NavGoal {
        public final int level;

        YLevel(int level) {
            this.level = level;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.getY() == level;
        }

        @Override public double heuristic(BlockPos from) {
            int cy = from.getY();
            if (cy > level) return DESCEND_ONE_BLOCK * (cy - level);
            if (cy < level) return (level - cy) * JUMP_ONE_BLOCK;
            return 0.0;
        }

        @Override public BlockPos center() {
            return new BlockPos(0, level, 0);   // x/z irrelevant — goal is Y-only
        }
    }

    /** {@link #near} 的产物:三维欧氏球邻域。 */
    final class Near implements NavGoal {
        public final BlockPos goal;
        public final double radius;
        public final double radiusSqr;

        Near(BlockPos pos, double radius) {
            this.goal = pos.immutable();
            this.radius = radius;
            this.radiusSqr = radius * radius;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.distSqr(goal) <= radiusSqr;
        }

        @Override public double heuristic(BlockPos from) {
            // The heuristic IS the full point bound — the radius
            // only relaxes isAt, it is NOT subtracted from the aim. (Slightly
            // inadmissible, deliberately: aiming at the centre keeps node ordering
            // and the best-so-far partial stable.)
            return pointBound(goal, from);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #nearGround} 的产物:水平半径 + 目标高度 ±1 的地面邻域。 */
    final class NearGround implements NavGoal {
        public final BlockPos goal;
        public final double radius;
        public final double radiusSqr;

        NearGround(BlockPos pos, double radius) {
            this.goal = pos.immutable();
            this.radius = radius;
            this.radiusSqr = radius * radius;
        }

        @Override public boolean isAt(BlockPos feet) {
            int dy = feet.getY() - goal.getY();
            if (dy < -1 || dy > 1) return false;
            double dx = feet.getX() - goal.getX();
            double dz = feet.getZ() - goal.getZ();
            return dx * dx + dz * dz <= radiusSqr;
        }

        @Override public double heuristic(BlockPos from) {
            // Full point bound, radius not subtracted — same deliberate slight
            // inadmissibility as near(): aim at the centre for stable ordering.
            return pointBound(goal, from);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #adjacent} 的产物:水平正交贴邻、目标高度 ±1。 */
    final class Adjacent implements NavGoal {
        public final BlockPos goal;

        Adjacent(BlockPos target) {
            this.goal = target.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            int dx = Math.abs(feet.getX() - goal.getX());
            int dz = Math.abs(feet.getZ() - goal.getZ());
            int dy = Math.abs(feet.getY() - goal.getY());
            return dx + dz == 1 && dy <= 1;
        }

        @Override public double heuristic(BlockPos from) {
            // One step + one jump of slack vs the point bound.
            return Math.max(0.0, pointBound(goal, from)
                    - COST_HEURISTIC - JUMP_ONE_BLOCK);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #getToBlock} 的产物:身高修正的 Manhattan 贴脸邻域。 */
    final class GetToBlock implements NavGoal {
        public final BlockPos goal;

        GetToBlock(BlockPos target) {
            this.goal = target.immutable();
        }

        @Override public boolean isAt(BlockPos feet) {
            int dx = Math.abs(feet.getX() - goal.getX());
            int dz = Math.abs(feet.getZ() - goal.getZ());
            int dy = feet.getY() - goal.getY();
            int bodyDy = dy < 0 ? dy + 1 : dy;
            return dx + dz + Math.abs(bodyDy) <= 1;
        }

        @Override public double heuristic(BlockPos from) {
            // One step + one jump of slack vs the point bound (same slack as
            // adjacent(): any accepted cell is at most that much off-centre).
            return Math.max(0.0, pointBound(goal, from)
                    - COST_HEURISTIC - JUMP_ONE_BLOCK);
        }

        @Override public BlockPos center() {
            return goal;
        }
    }

    /** {@link #composite} 的产物:任一成员满足即到达,h 取成员最小值。 */
    final class Composite implements NavGoal {
        public final java.util.List<NavGoal> members;
        private final BlockPos centroid;

        Composite(java.util.List<NavGoal> goals) {
            java.util.List<NavGoal> gs = java.util.List.copyOf(goals);
            if (gs.isEmpty()) {
                throw new IllegalArgumentException("composite goal needs at least one member");
            }
            // Centre = centroid of the members, NOT gs.get(0). The member list is
            // rebuilt every tick (ores re-sorted by distance as the body moves), so a
            // first-member centre would jitter and trip PlayerNav's goal-moved replan
            // every tick. The centroid only shifts when the SET changes (an ore mined
            // or found), which is what "the goal moved" should actually mean.
            long sx = 0, sy = 0, sz = 0;
            for (NavGoal g : gs) {
                BlockPos c = g.center();
                sx += c.getX();
                sy += c.getY();
                sz += c.getZ();
            }
            this.members = gs;
            this.centroid = new BlockPos(
                    (int) (sx / gs.size()), (int) (sy / gs.size()), (int) (sz / gs.size()));
        }

        @Override public boolean isAt(BlockPos feet) {
            for (NavGoal g : members) {
                if (g.isAt(feet)) return true;
            }
            return false;
        }

        @Override public double heuristic(BlockPos from) {
            double min = Double.MAX_VALUE;
            for (NavGoal g : members) {
                min = Math.min(min, g.heuristic(from));
            }
            return min;
        }

        @Override public BlockPos center() {
            return centroid;
        }
    }

    /** {@link #mineColumn} 的产物:矿柱站位带(脚位在矿至矿下 maxBelow 格)。 */
    final class MineColumn implements NavGoal {
        public final BlockPos ore;
        public final int maxBelow;

        MineColumn(BlockPos ore, int maxBelow) {
            this.ore = ore.immutable();
            this.maxBelow = maxBelow;
        }

        @Override public boolean isAt(BlockPos feet) {
            return feet.getX() == ore.getX() && feet.getZ() == ore.getZ()
                    && feet.getY() <= ore.getY() && feet.getY() >= ore.getY() - maxBelow;
        }

        @Override public double heuristic(BlockPos from) {
            double dx = Math.abs(ore.getX() - from.getX());
            double dz = Math.abs(ore.getZ() - from.getZ());
            double horizontal = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                    * COST_HEURISTIC;
            // Feet anywhere in {o.y .. o.y-maxBelow} count as arrived: fold that
            // band to zero.
            int yDiff = from.getY() - ore.getY();
            int adj = yDiff >= 0 ? yDiff : Math.min(0, yDiff + maxBelow);
            // Above the goal (adj>0) we DESCEND to it,
            // below it (adj<0) we ASCEND. (The old mine() had these two swapped,
            // overestimating descents — an inadmissible heuristic.)
            double vertical = adj > 0
                    ? adj * DESCEND_ONE_BLOCK
                    : -adj * JUMP_ONE_BLOCK;
            return horizontal + vertical;
        }

        @Override public BlockPos center() {
            return ore;
        }
    }

    /** {@link #runAway} 的产物:持高度外逃,永不"到达"。 */
    final class RunAway implements NavGoal {
        public final BlockPos from;
        public final int maintainY;

        RunAway(BlockPos from, int maintainY) {
            this.from = from.immutable();
            this.maintainY = maintainY;
        }

        @Override public boolean isAt(BlockPos feet) {
            return false;   // never done — keep exploring outward
        }

        @Override public double heuristic(BlockPos fromPos) {
            // Run-away heuristic: −(octile×weight) — negated so farther = lower h
            // = preferred — then blended with the y-hold term:
            // min*0.6 + yLevelTerm*1.5.
            double dx = Math.abs(from.getX() - fromPos.getX());
            double dz = Math.abs(from.getZ() - fromPos.getZ());
            double xz = (Math.min(dx, dz) * SQRT_2 + Math.abs(dx - dz))
                    * COST_HEURISTIC;
            double min = -xz;
            int cy = fromPos.getY();
            double yLevel = cy > maintainY ? (cy - maintainY) * DESCEND_ONE_BLOCK
                    : cy < maintainY ? (maintainY - cy) * JUMP_ONE_BLOCK : 0.0;
            return min * 0.6 + yLevel * 1.5;
        }

        @Override public BlockPos center() {
            return from;
        }
    }
}
