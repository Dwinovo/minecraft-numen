package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import com.dwinovo.numen.core.task.base.ToolSelect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-search snapshot binding the world, the entity's capabilities, and a
 * frozen view of its inventory (scaffolding gate + best-tool source):
 * every cost-returning helper reads
 * only from this immutable snapshot, so an A* search is deterministic and
 * doesn't see mid-search inventory mutation.
 *
 * <h2>Inventory gating (the key behaviour)</h2>
 * {@link #hasScaffold} is a boolean: does the entity hold ANY block in the
 * {@link InitTag#SCAFFOLDS} tag? When false, {@link #costOfPlacing} returns
 * {@link ActionCosts#COST_INF} for every position, so all bridge/step-up
 * moves become impossible and A* routes around (or fails with "out of
 * material"). Actual depletion is handled at execution time — when the
 * executor runs the inventory dry mid-path it triggers a replan, and the
 * fresh snapshot then has {@code hasScaffold == false}. The boolean gate +
 * event-driven replan is a long-proven model in open-source pathfinding
 * practice, avoiding an explosive per-node "remaining blocks" search state.
 */
public final class NavContext {

    public final Level level;

    /**
     * World view for this search/execution. All terrain queries (here and in
     * {@link com.dwinovo.numen.core.pathing.movement.Moves}) go through it. For EXECUTION it's a
     * live read-through of {@link #level} ({@link NavSnapshot}); for a SEARCH it will become the
     * {@code CompactSection}-backed off-thread view (P-B) — both implement {@link BlockGetter}, so
     * {@link com.dwinovo.numen.core.pathing.util.BlockHelper} reads either unchanged.
     */
    public final BlockGetter view;

    /** True if the entity holds at least one scaffolding block. */
    public final boolean hasScaffold;

    /**
     * May this search/execution modify terrain at all (break or place blocks)?
     * {@code false} makes {@link #costOfPlacing} and {@link #costOfBreaking}
     * return {@link ActionCosts#COST_INF} unconditionally, so A* only produces
     * pure-traversal routes (existing ground and openings) — the planning-side
     * switch behind {@code move_to}'s {@code modify_terrain:false} ("walk
     * through someone's build without touching a block"). Default {@code true}.
     */
    public final boolean terrainMods;

    /** Max blocks the entity may fall without taking dangerous damage. */
    public final int maxFallHeight;

    /** Whether sprinting is allowed; drives the sprint cost discount. */
    public final boolean canSprint;

    /**
     * Whether this context may be handed to a worker thread.
     * A search context ({@link #forSearch}) is frozen — snapshot inventory + (from P-B) an immutable
     * world view — so the async planner can read it off the tick thread; an execution context
     * ({@link #forExecution}) reads the live world/inventory and is main-thread only.
     */
    public final boolean safeForThreadedUse;

    /**
     * Frozen view of the body's inventory, for best-tool-aware mining duration:
     * a break is costed with the BEST tool the body
     * has, not just the one currently held, because the body auto-switches to it
     * before mining ({@code MineCompanionTask.switchToBestTool}). Costing with the
     * held item instead made A* price an oak log at bare-hand speed when no axe
     * was selected, so "dig under through soft dirt" beat "break the logs" and the
     * body tunnelled under trees.
     */
    private final Container inventory;
    private final java.util.Map<net.minecraft.world.level.block.Block, BestTool> toolCache =
            new java.util.HashMap<>();

    /** Best inventory tool for a block: its destroy speed and whether it harvests drops. */
    private record BestTool(float speed, boolean canHarvest) {}

    private NavContext(Level level, BlockGetter view, Container inventory, boolean safeForThreadedUse,
                       boolean terrainMods) {
        this.level = level;
        this.view = view;
        this.inventory = inventory;
        this.safeForThreadedUse = safeForThreadedUse;
        this.terrainMods = terrainMods;
        this.hasScaffold = hasAnyScaffold(inventory);

        // Survivable fall: 3 blocks — vanilla fall
        // damage starts at 3.5 blocks; cap conservatively so the bot never hurts itself.
        this.maxFallHeight = PathSettings.MAX_FALL_HEIGHT_NO_WATER;
        this.canSprint = PathSettings.ALLOW_SPRINT;
    }

    /**
     * Live read-through context for EXECUTION (main thread). The executor re-costs the next move
     * each tick against the CURRENT world + live inventory (scaffold depletion, terrain the body just
     * changed), so this reads live and is NOT safe to hand to a worker thread.
     */
    public static NavContext forExecution(Level level, Container liveInventory) {
        return forExecution(level, liveInventory, true);
    }

    /** As {@link #forExecution(Level, Container)} with an explicit terrain-modification
     *  gate — {@code terrainMods:false} prices every break/place {@code COST_INF}. */
    public static NavContext forExecution(Level level, Container liveInventory, boolean terrainMods) {
        return new NavContext(level, new NavSnapshot(level), liveInventory, false, terrainMods);
    }

    /**
     * Frozen context for one A* SEARCH. The inventory is snapshotted so tool/scaffold costs can't
     * shift mid-search (the {@code toolCache} reads it lazily across the search), making the
     * "doesn't see mid-search inventory mutation" contract actually true. In P-A the world view is
     * still the live read-through and the search still runs on the main thread (time-sliced); P-B
     * swaps in the {@code CompactSection}-backed off-thread view so the search can move to a worker.
     */
    public static NavContext forSearch(Level level, Container liveInventory) {
        return forSearch(level, liveInventory, true);
    }

    /** As {@link #forSearch(Level, Container)} with an explicit terrain-modification
     *  gate — {@code terrainMods:false} plans pure-traversal routes only. */
    public static NavContext forSearch(Level level, Container liveInventory, boolean terrainMods) {
        // Read loaded chunks LIVE through the per-tick snapshot; before the
        // snapshot exists (a level's first companion tick) fall back to the live read-through so the
        // first search is still correct.
        com.dwinovo.numen.core.pathing.cache.LoadedChunks loaded =
                com.dwinovo.numen.core.pathing.cache.PathCaches.peek(level);
        // Thread-safe ONLY with the immutable CachedNavView. If there's no snapshot (the fallback
        // NavSnapshot reads the live level), the context is NOT safe for a worker — flag it so the
        // caller runs it on the main thread instead of racing off-thread.
        BlockGetter view = loaded != null
                ? new com.dwinovo.numen.core.pathing.cache.CachedNavView(loaded, level)
                : new NavSnapshot(level);
        return new NavContext(level, view, snapshotInventory(liveInventory), loaded != null, terrainMods);
    }

    /** A point-in-time copy of {@code live} (same slot layout, copied stacks) — read-only fodder for
     *  the scaffold gate + tool cache, frozen for the search's lifetime. */
    private static Container snapshotInventory(Container live) {
        SimpleContainer copy = new SimpleContainer(live.getContainerSize());
        for (int i = 0; i < live.getContainerSize(); i++) {
            copy.setItem(i, live.getItem(i).copy());
        }
        return copy;
    }

    /**
     * Was {@code pos}'s chunk actually captured when this context's world view was built?
     * {@code false} means reads there were the optimistic AIR miss — a cost computed from
     * them is a guess. Live read-through views are always exact ({@code true}).
     */
    public boolean isLoadedAt(BlockPos pos) {
        return !(view instanceof com.dwinovo.numen.core.pathing.cache.CachedNavView cached)
                || cached.isLoaded(pos.getX(), pos.getZ());
    }

    /** Best inventory tool for the block,
     *  memoised per block-type for the search. */
    private BestTool bestTool(BlockState state) {
        return toolCache.computeIfAbsent(state.getBlock(), b -> scanBestTool(state));
    }

    private BestTool scanBestTool(BlockState state) {
        float bestSpeed = 1.0f;                                   // bare hand baseline
        // Whole inventory, NOT just the hotbar: execution (ToolSelect.holdBestTool) can
        // swap a backpack tool into the hand, so the cost model prices breaks with that
        // same tool — deliberately kept consistent with execution. Nearly-broken tools
        // are skipped for the same reason: execution refuses to dig with them, so pricing
        // a break with one would make the plan cheaper than reality.
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            float spd = s.getDestroySpeed(state);
            if (spd > bestSpeed) bestSpeed = spd;
        }
        return new BestTool(bestSpeed, BlockHelper.canHarvest(inventory, state));
    }

    /** Coarse fingerprint of dig capability for h-learning lifecycle: best inventory
     *  destroy-speed vs plain stone (hand 1.0 &lt; wood &lt; ... &lt; netherite, ×efficiency),
     *  plus 0.5 when scaffolding is carried. Monotone-comparable: a HIGHER value
     *  means previously-learned pessimism may now over-estimate → clear the table. */
    public double digCapabilityFingerprint() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        float best = 1.0f;                                    // bare hand baseline
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) continue;
            float spd = s.getDestroySpeed(stone);
            if (spd > best) best = spd;
        }
        return best + (hasScaffold ? 0.5 : 0.0);
    }

    private static boolean hasAnyScaffold(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isScaffold(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cost of placing a scaffolding block at {@code pos} (to bridge / step on).
     * {@link ActionCosts#COST_INF} when the entity has no scaffolding, the
     * cell isn't replaceable, or placing there would touch a hazard.
     */
    public double costOfPlacing(BlockPos pos) {
        if (!terrainMods) return ActionCosts.COST_INF;   // modify_terrain:false — no block may be placed
        if (!hasScaffold) return ActionCosts.COST_INF;
        if (!BlockHelper.isReplaceableForPlacement(view, pos)) return ActionCosts.COST_INF;
        // Never place INTO a fluid (source or flowing) — a bridge can't be planned
        // straight into a water source.
        if (!view.getBlockState(pos).getFluidState().isEmpty()) return ActionCosts.COST_INF;
        if (BlockHelper.isHazard(view, pos)) return ActionCosts.COST_INF;
        return PathSettings.BLOCK_PLACEMENT_PENALTY;
    }

    /**
     * Cost of breaking the block at {@code pos}: tool-aware mining duration in
     * ticks plus {@link PathSettings#BLOCK_BREAK_ADDITIONAL_PENALTY}. {@code COST_INF} (→ A*
     * routes around, or the search fails clean) when the break is impossible,
     * unsafe, or ineffective:
     * <ul>
     *   <li>unbreakable (bedrock/barrier), a hazard (lava/fire), or would unleash
     *       a fluid flow;</li>
     *   <li>{@link BlockHelper#shouldAvoidBreaking} — a player-placed functional
     *       block (chest, furnace, bed, …): don't grief it;</li>
     * </ul>
     */
    public double costOfBreaking(BlockPos pos) {
        return costOfBreaking(pos, false);
    }

    /**
     * As {@link #costOfBreaking(BlockPos)} but, when {@code includeFalling}, folds in
     * the cost of the FallingBlock (sand/gravel) stack directly above {@code pos}.
     * Passed for the
     * TOP cell a move breaks: breaking under sand makes it cascade down and we break
     * each one as it lands. Breaking under sand is PRICED, not vetoed — we pay for
     * the cascade, so sand/gravel terrain stays routable instead of walling off.
     */
    public double costOfBreaking(BlockPos pos, boolean includeFalling) {
        if (!terrainMods) return ActionCosts.COST_INF;   // modify_terrain:false — no block may be broken
        if (!BlockHelper.isBreakable(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.isHazard(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.breakWouldCreateFlow(view, pos)) return ActionCosts.COST_INF;
        if (BlockHelper.shouldAvoidBreaking(view, pos)) return ActionCosts.COST_INF;

        // A break with the wrong (or no) tool is EXPENSIVE, never impossible: traversal
        // only needs the block GONE, not its drops — a bare hand grinds through stone in
        // ~seconds-per-block, and {@link #miningTicks} already prices that grind (divisor
        // 100 when the tool can't harvest). The old COST_INF veto here sealed every
        // enclosed pocket the moment the pickaxe broke — turning "a slow way out" into
        // "no path" — which is strictly worse than an honest, costly route.
        double cost = miningTicks(pos) + PathSettings.BLOCK_BREAK_ADDITIONAL_PENALTY;
        if (includeFalling) {
            BlockPos above = pos.above();
            while (view.getBlockState(above).getBlock()
                    instanceof net.minecraft.world.level.block.FallingBlock) {
                cost += miningTicks(above) + PathSettings.BLOCK_BREAK_ADDITIONAL_PENALTY;
                above = above.above();
            }
        }
        return cost;
    }

    /**
     * Tool-aware mining duration in ticks, replicating the formula in
     * {@code BlockMiningProgress.tryStart} so search cost matches execution
     * reality.
     */
    public double miningTicks(BlockPos pos) {
        BlockState state = view.getBlockState(pos);
        float hardness = state.getDestroySpeed(view, pos);
        if (hardness <= 0.0f) return 0.0;   // instabreak costs ~0 (the +penalty is added by the caller)
        BestTool best = bestTool(state);
        boolean correct = !state.requiresCorrectToolForDrops() || best.canHarvest();
        float toolSpeed = best.speed();
        if (toolSpeed <= 0.0f) toolSpeed = 1.0f;
        float divisor = correct ? 30.0f : 100.0f;
        // ticks = hardness*divisor/speed (the inverse of vanilla's per-tick destroy
        // fraction) — a continuous value (no ceil). NOTE deliberately no underwater/
        // airborne ÷5 penalty (assume best-case mining). Efficiency enchant (+eff²+1)
        // is folded into vanilla getDestroySpeed where present.
        return hardness * divisor / toolSpeed;
    }

    /**
     * Post-mortem for a failed search: walk the straight start→goal line and
     * name the first break-veto on it, in words the LLM can act on. The real
     * A* frontier may have died elsewhere, but the straight line is what the
     * model pictures when it asks "why can't you just go there" — "stone but
     * I'm holding a sword" or "water behind it" turns a dead "obstructed"
     * into a next step. Returns {@code null} when the line is clean (the
     * failure was geometric: gaps, fall limits, search budget).
     */
    public String diagnoseObstruction(BlockPos from, BlockPos to) {
        int steps = (int) Math.ceil(Math.sqrt(from.distSqr(to)));
        BlockPos last = null;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            BlockPos feet = BlockPos.containing(
                    from.getX() + 0.5 + (to.getX() - from.getX()) * t,
                    from.getY() + (to.getY() - from.getY()) * t,
                    from.getZ() + 0.5 + (to.getZ() - from.getZ()) * t);
            if (feet.equals(last)) continue;
            last = feet;
            for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
                if (BlockHelper.canWalkThrough(view, cell)) continue;
                String veto = explainBreakVeto(cell);
                if (veto != null) {
                    return veto + " at (" + cell.getX() + ", " + cell.getY()
                            + ", " + cell.getZ() + ")";
                }
            }
        }
        return null;
    }

    /**
     * Why {@link #costOfBreaking} returns {@code COST_INF} for this cell, as a
     * sentence fragment for the LLM — or {@code null} if the break is actually
     * allowed (finite cost). Mirrors the veto order in {@code costOfBreaking}.
     */
    private String explainBreakVeto(BlockPos pos) {
        BlockState state = view.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return state.getFluidState().is(FluidTags.LAVA)
                    ? "lava" : "water (I can't swim or mine through fluids)";
        }
        if (!terrainMods) {
            // Mirrors the unconditional gate at the top of costOfBreaking.
            return "solid " + blockId(state) + " (terrain modification is disabled — modify_terrain:false)";
        }
        if (!BlockHelper.isBreakable(view, pos)) {
            return "unbreakable " + blockId(state);
        }
        if (BlockHelper.isHazard(view, pos)) {
            return "hazardous " + blockId(state);
        }
        if (BlockHelper.breakWouldCreateFlow(view, pos)) {
            return blockId(state) + " (breaking it would release "
                    + (touchesLava(pos) ? "lava" : "water") + " from an adjacent cell)";
        }
        if (BlockHelper.shouldAvoidBreaking(view, pos)) {
            return blockId(state) + " (a functional block I won't destroy)";
        }
        // Wrong-tool breaks are priced, not vetoed (see costOfBreaking) — no branch here.
        return null;
    }

    private boolean touchesLava(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            if (view.getBlockState(pos.relative(dir)).getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /** Pick a scaffolding stack the entity currently holds, or null. */
    public static ItemStack firstScaffoldItem(Container inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (isScaffold(s)) {
                return s;
            }
        }
        return null;
    }

    /**
     * True if {@code stack} is a placeable scaffolding block: a {@link BlockItem}
     * tagged {@link InitTag#SCAFFOLDS}. The {@code BlockItem} check guarantees
     * the executor can actually place it even if a pack tags an odd item.
     */
    public static boolean isScaffold(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && stack.is(InitTag.SCAFFOLDS);
    }
}
