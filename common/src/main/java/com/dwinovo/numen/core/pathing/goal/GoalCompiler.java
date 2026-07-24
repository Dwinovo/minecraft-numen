package com.dwinovo.numen.core.pathing.goal;

import com.dwinovo.numen.core.pathing.bridge.GoalAdapter;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a task's INTENT into the full navigation contract. Each static
 * factory IS an intent — there is deliberately no intent enum, the method
 * names are the vocabulary — and each returns the three things the navigation
 * and its task must agree on, derived together so they can never drift:
 *
 * <ul>
 *   <li>{@link Compiled#goal()} — the search goal (which feet cells may end
 *       the path);</li>
 *   <li>{@link Compiled#sacred()} — the cells the route itself must leave
 *       untouched (may neither break nor bury: the table it travels to use,
 *       the ore its task will mine);</li>
 *   <li>{@link Compiled#arrival()} — the body-level arrival ingredients the
 *       task's {@code reached} predicate builds on.</li>
 * </ul>
 *
 * <p>The load-bearing entry is {@link #block}: the replacement for the old
 * {@code resolveBlockGoal} fallback whose Euclidean {@code near(2.0)} sphere
 * admitted elevated cells (the geometry that let "place a scaffold, stand on
 * it" finish an approach) and marked nothing sacred (so a route could dig
 * through the very block it was travelling to).
 */
public final class GoalCompiler {

    private GoalCompiler() {}

    /**
     * The compiled navigation contract.
     *
     * @param goal           search goal — node-domain arrival
     * @param engineGoal     the SAME arrival semantics as a new-kernel
     *                       {@link Goal} — derived from {@code goal} through
     *                       {@link GoalAdapter}'s per-factory mapping table, so the
     *                       two goal domains can never drift apart
     * @param sacred         {@link BlockPos#asLong()} keys of cells the route must not
     *                       break or bury (the {@code CalculationContext} domain);
     *                       empty when the intent has no block objective
     * @param arrival        body-level arrival ingredients (see {@link ArrivalSpec})
     * @param coarseEligible whether the goal is an APPROACH toward its center, so a
     *                       coarse guidance field pointed at {@code goal.center()} makes
     *                       sense (false for run-away/bare custom goals, whose center is
     *                       what they flee or hold — a field toward it points backwards)
     */
    public record Compiled(NavGoal goal, Goal engineGoal, LongSet sacred, ArrivalSpec arrival,
                           boolean coarseEligible) {
        /** engineGoal 从 goal 经映射表派生(既有调用方签名不变)。 */
        public Compiled(NavGoal goal, LongSet sacred, ArrivalSpec arrival,
                        boolean coarseEligible) {
            this(goal, GoalAdapter.toEngineGoal(goal), sacred, arrival, coarseEligible);
        }

        /** Compiler-made contracts are approach goals — coarse-eligible. */
        public Compiled(NavGoal goal, LongSet sacred, ArrivalSpec arrival) {
            this(goal, sacred, arrival, true);
        }
    }

    /**
     * One mining stance: the ore (what becomes sacred), the stance BASE the feet
     * band hangs from (usually the ore itself, but a run's top block anchors one
     * lower — see {@code MineCompanionTask.coalesce}), and how far below that
     * base the feet may end ({@link NavGoal#mineColumn}).
     */
    public record Stance(BlockPos ore, BlockPos stanceBase, int maxBelow) {
        /** The common case: the stance band hangs from the ore itself. */
        public static Stance at(BlockPos ore, int maxBelow) {
            return new Stance(ore, ore, maxBelow);
        }
    }

    /**
     * Use/open/work at a block (crafting table, chest, furnace, door): end
     * TOUCHING it ({@link NavGoal#getToBlock} — y-anchored, no elevated cell
     * satisfies), the target itself sacred, arrival = grounded within reach.
     */
    public static Compiled interact(BlockPos target) {
        BlockPos t = target.immutable();
        return new Compiled(NavGoal.getToBlock(t), single(t), ArrivalSpec.interact(t));
    }

    /** Occupy exactly this cell. Nothing sacred; arrival = grounded membership
     *  of the same goal (one definition of "there"). */
    public static Compiled standOn(BlockPos cell) {
        BlockPos c = cell.immutable();
        NavGoal g = NavGoal.exact(c);
        return new Compiled(g, LongSets.emptySet(), ArrivalSpec.standOn(g));
    }

    /** Stand orthogonally beside {@code target} (a placement stance): the
     *  target cell is sacred — the route may not scaffold into the cell the
     *  task is about to fill. */
    public static Compiled standAdjacent(BlockPos target) {
        BlockPos t = target.immutable();
        return new Compiled(NavGoal.adjacent(t), single(t), ArrivalSpec.interact(t));
    }

    /**
     * Vicinity of a (usually moving) ground-dwelling point: horizontal radius
     * at the target's height ±1 ({@link NavGoal#nearGround} — the raw 3D sphere
     * is deliberately NOT used here). Nothing sacred.
     */
    public static Compiled near(BlockPos center, double radius) {
        BlockPos c = center.immutable();
        return new Compiled(NavGoal.nearGround(c, radius), LongSets.emptySet(),
                ArrivalSpec.near(c, radius));
    }

    /** The {@code resolveBlockGoal} replacement: a walkable cell is a place to
     *  stand, an occupied one is a block to get to (and not consume). */
    public static Compiled block(Level level, BlockPos cell) {
        return block(BlockHelper.canWalkThrough(level, cell), cell);
    }

    /** Pure core of {@link #block(Level, BlockPos)} (headless-testable). */
    public static Compiled block(boolean cellWalkable, BlockPos cell) {
        return cellWalkable ? standOn(cell) : interact(cell);
    }

    /**
     * A whole mining objective in one search: composite of per-ore stances
     * (plus a loose member per nearby drop, so the same walk collects them),
     * with EVERY ore cell sacred — the route gets the body to the ore; digging
     * it is the task's job, with the task's bookkeeping. A path may not consume
     * the objective on the way past.
     */
    public static Compiled mineField(List<Stance> stances, List<BlockPos> drops) {
        List<NavGoal> members = new ArrayList<>(stances.size() + drops.size());
        LongSet sacred = new LongOpenHashSet(stances.size());
        for (Stance s : stances) {
            members.add(NavGoal.mineColumn(s.stanceBase(), s.maxBelow()));
            sacred.add(s.ore().asLong());
        }
        for (BlockPos drop : drops) {
            members.add(NavGoal.exact(drop));     // items, not blocks — nothing sacred
        }
        NavGoal goal = NavGoal.composite(members);
        return new Compiled(goal, sacred, ArrivalSpec.standOn(goal));
    }

    /**
     * Get beside ANY of these same-kind blocks (a "walk to the nearest X"
     * objective): composite of per-candidate {@link NavGoal#getToBlock}
     * members, EVERY candidate sacred — the route may neither break nor bury
     * the very blocks it is travelling to; whichever ends up cheapest wins.
     */
    public static Compiled anyOf(List<BlockPos> candidates) {
        List<NavGoal> members = new ArrayList<>(candidates.size());
        LongSet sacred = new LongOpenHashSet(candidates.size());
        for (BlockPos c : candidates) {
            BlockPos t = c.immutable();
            members.add(NavGoal.getToBlock(t));
            sacred.add(t.asLong());
        }
        NavGoal goal = NavGoal.composite(members);
        return new Compiled(goal, sacred, ArrivalSpec.standOn(goal));
    }

    private static LongSet single(BlockPos pos) {
        LongSet set = new LongOpenHashSet(1);
        set.add(pos.asLong());
        return set;
    }
}
