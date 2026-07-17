package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.EngineSearch;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.engine.SearchBudget;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import com.dwinovo.numen.core.task.FailureType;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Plan → execute → replan driver for a companion {@link NumenPlayer} body:
 * a path-while-moving loop — walk
 * the current path; precompute the continuation of a partial path so there's no
 * planning pause at a segment boundary; re-root on a moving goal or after an
 * off-path replan — executing through {@link PlayerPathExecutor}. Planning
 * runs on the engine via the {@link EngineSearch} adapter (body-neutral,
 * Minecraft-free at its core). Every search is deterministic — the only
 * memory it consults is the world itself.
 *
 * <p>Internally the goal is a {@link NavGoal} so callers can target a single
 * cell ({@link #PlayerNav(NumenPlayer, BlockPos, double, BooleanSupplier)}) or
 * a richer goal — a {@link NavGoal#composite composite} ore field, a mining
 * stance — via {@link #toGoal}.
 */
public final class PlayerNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    /** Absolute replan backstop — a sanity fuse, not the real give-up condition. */
    private static final int MAX_REPLANS = 200;
    /** Real give-up: this many CONSECUTIVE replans without genuinely nearing the goal. */
    private static final int MAX_STALLED_REPLANS = 6;
    /**
     * Heuristic-units drop that counts as "genuinely nearing" — ≈ one block of
     * real progress in ANY goal's own terms (walking ≈3.56 weighted, climbing
     * ≈3.16, descending ≈3.89). Progress is measured by the GOAL'S HEURISTIC at
     * the feet, not euclidean distance to {@code center()}: a yLevel goal's
     * center is (0, level, 0), so euclidean distance is dominated by meaningless
     * horizontal offset — a 51-block climb once read as "5 blocks of progress"
     * and got the attempt spuriously stalled-out.
     */
    private static final double REPLAN_PROGRESS_EPS_H = 4.0;
    private static final double GOAL_MOVED_SQR = 4.0;

    private final NumenPlayer player;
    private final Supplier<GoalCompiler.Compiled> compiledSupplier;
    private final double speed;
    private final BooleanSupplier reached;
    /** Sacred cells of the most recently pulled goal ({@code BlockPos.asLong()} keys) —
     *  threaded into every {@link NavContext} this nav builds, so neither the search
     *  nor the executor's per-tick re-costing may break or bury the objective.
     *  Refreshed with every goal pull (a shrinking ore field stays current). */
    private LongSet sacred = LongSets.emptySet();
    /** Cells the executor proved unplaceable (NO_SUPPORT) during THIS navigation —
     *  denied in every subsequent search so a deterministic re-search can't keep
     *  planning the same impossible scaffold (the rim-standing loop). Same
     *  navigation-internal lifecycle as the favoring set. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet deniedPlace =
            new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /**
     * The search's goal membership is satisfied at the very cell we stand on (a
     * zero-movement COMPLETE search), but the caller's richer {@code reached}
     * still says no. Held STABLE so {@link #tick()} keeps reporting
     * {@link Status#ARRIVED} — the task layer reads the persistent
     * arrived-but-unsatisfied as a stance dud and reacts (reposition, blacklist
     * the member) instead of receiving the lying one-tick "target lost" the old
     * flow decayed into. Cleared by any restart or {@link #stop()}.
     */
    private boolean searchSatisfied;
    /** May this navigation break blocks it can't harvest (move_to's modify_terrain:true)?
     *  Threaded into every {@link NavContext} it builds — search AND execution re-costing,
     *  so a tool breaking mid-route re-vetoes the remaining grinds live. Default false:
     *  only harvestable digs are planned; a route that would require a no-drop grind
     *  fails clean with a diagnosis instead. */
    private final boolean forceBreak;

    private BlockPos plannedCenter;
    private PlayerPathExecutor current;

    // The search runs on the planner pool, not stepped on the tick thread. We hold the future (polled
    // each tick) plus the search object itself (to cancel it on replan/stop so a stale worker stops
    // wasting CPU). One in-flight search at a time for the main path, one for the precomputed next
    // segment.
    private java.util.concurrent.CompletableFuture<Path> searchFuture;
    private EngineSearch searchObj;
    private java.util.concurrent.CompletableFuture<Path> nextFuture;
    private EngineSearch nextObj;
    private PlayerPathExecutor pendingNext;
    private Path pendingPathForViz;

    /** Packed positions of the path we're currently executing — fed to the next
     *  search as a cost favoring so a replan reuses this route (damps
     *  the flip-flopping a from-scratch replan would otherwise cause). */
    private it.unimi.dsi.fastutil.longs.LongSet previousPathHashes =
            it.unimi.dsi.fastutil.longs.LongSets.emptySet();

    /** Cells to highlight in the overlay; null → just the path's destination.
     *  The mining task sets this to its whole known-ore field (every
     *  composite-goal member gets a box). */
    private Supplier<java.util.List<BlockPos>> highlights;

    /** Highlight these cells in the path overlay (e.g. the full ore field). */
    public void setHighlights(Supplier<java.util.List<BlockPos>> highlights) {
        this.highlights = highlights;
    }

    private void publishViz(Path cut) {
        java.util.List<BlockPos> targets =
                highlights != null ? highlights.get() : java.util.List.of(cut.end);
    }

    private int replans = 0;
    private int stalledReplans = 0;
    /** Best (lowest) goal-heuristic the feet have EVER reached — the stalled-replan yardstick. */
    private double bestGoalH = Double.MAX_VALUE;
    private String failReason = "target unreachable";
    private FailureType failType = FailureType.NO_PATH;
    /** The most recent executor replan cause ("movement timeout (PILLAR …)") — names the
     *  recurring maneuver in a BOXED_IN give-up so the task layer can change strategy. */
    private String lastExecFailure;

    /** Frozen context + start of the most recent search — kept for the no-path autopsy. */
    private NavContext lastCtx;
    private BlockPos lastStart;
    /** Coarse-eligibility of the most recently pulled goal (see {@link GoalCompiler.Compiled}). */
    private boolean coarseEligible;
    /** The frozen coarse guidance field for this navigation's current goal, or null
     *  (short range / bare goal / build declined). Rebuilt with each fresh search. */
    private com.dwinovo.numen.core.pathing.hier.CoarseField coarseField;
    /** A coarse SEALED verdict produced at dispatch — surfaced by
     *  {@link #advanceFreshSearch} as the structured no-path reason. */
    private String sealedVerdict;
    /** Whether the body stood on solid ground when the search launched — a search
     *  started mid-jump/mid-fall has an unstandable start cell and dies at 0 expansions. */
    private boolean lastStartGrounded = true;

    /** Walk to a single cell — compiled by intent ({@link GoalCompiler#block}: a
     *  walkable cell is a place to STAND ON, an occupied one a block to GET TO and
     *  not consume; never the old {@code near(2.0)} sphere whose elevated members
     *  made "scaffold up one and count it arrived" a legal completion). */
    public PlayerNav(NumenPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, () -> GoalCompiler.block(player.level(), goal), speed, reached, false);
    }

    /** Walk to a (possibly moving) single cell — same intent compilation, re-run
     *  per pull so a cell that opens up tightens back to a stand-on goal. */
    public PlayerNav(NumenPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null : GoalCompiler.block(player.level(), g);
        }, speed, reached, false);
    }

    /** Walk toward a compiled navigation contract (goal + sacred cells + arrival
     *  ingredients) — the {@link GoalCompiler} front door. */
    public static PlayerNav to(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached, boolean forceBreak) {
        return new PlayerNav(player, compiled, speed, reached, forceBreak);
    }

    /** Walk toward an arbitrary {@link NavGoal} (custom goals: runAway, column, …).
     *  Carries NO sacred cells — intents with a block objective should come through
     *  {@link #to} / {@link GoalCompiler} so the objective is protected. */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, bare(goalSupplier), speed, reached, false);
    }

    /** As {@link #toGoal(NumenPlayer, Supplier, double, BooleanSupplier)} with an explicit
     *  force-break gate: {@code forceBreak:true} also plans (and executes) breaks that
     *  harvest nothing — the slow wrong-tool grind behind move_to's modify_terrain:true. */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached, boolean forceBreak) {
        return new PlayerNav(player, bare(goalSupplier), speed, reached, forceBreak);
    }

    /** Wrap a bare goal supplier as a compiled contract with no sacred cells.
     *  Bare custom goals are NOT coarse-eligible: their center may be what they
     *  flee/hold (runAway), so a guidance field toward it would point backwards. */
    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        return () -> {
            NavGoal g = goals.get();
            return g == null ? null
                    : new GoalCompiler.Compiled(g, LongSets.emptySet(), null, false);
        };
    }

    private PlayerNav(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiledSupplier,
                      double speed, BooleanSupplier reached, boolean forceBreak) {
        this.player = player;
        this.compiledSupplier = compiledSupplier;
        this.speed = speed;
        this.reached = reached;
        this.forceBreak = forceBreak;
        startFreshSearch();
    }

    /** Pull the live goal, refreshing {@link #sacred} alongside it — the two are
     *  one contract and must never be read separately. */
    private NavGoal pullGoal() {
        GoalCompiler.Compiled c = compiledSupplier.get();
        if (c == null) {
            return null;
        }
        sacred = c.sacred();
        coarseEligible = c.coarseEligible();
        return c.goal();
    }

    public Status tick() {
        if (reached.getAsBoolean()) return Status.ARRIVED;

        if (searchSatisfied) {
            // Standing where the search's goal membership is satisfied, caller
            // still unsatisfied: hold the ARRIVED verdict stable (a stance dud
            // for the task layer to act on) instead of decaying into a lying
            // "target lost". A goal that actually moves re-roots as usual.
            NavGoal live = pullGoal();
            if (live == null) {
                failReason = "target lost";
                failType = FailureType.TARGET_LOST;
                return Status.FAILED;
            }
            if (plannedCenter != null && live.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
                return restartFresh(Restart.GOAL_MOVED);
            }
            return Status.ARRIVED;
        }

        if (current == null) {
            return advanceFreshSearch();
        }

        NavGoal liveGoal = pullGoal();
        if (liveGoal == null) {
            failReason = "target lost";
            failType = FailureType.TARGET_LOST;
            return Status.FAILED;
        }
        if (plannedCenter != null && liveGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            discardPrecompute();
            return restartFresh(Restart.GOAL_MOVED);
        }

        maybePrecompute();
        advancePrecompute();

        switch (current.tick()) {
            case RUNNING -> { return Status.RUNNING; }
            case ARRIVED -> {
                replans = 0;
                if (reached.getAsBoolean()) return Status.ARRIVED;
                if (pendingNext != null) {
                    // Hand off to the precomputed segment WITHOUT halting — calling
                    // current.stop() zeroes the inputs for a tick and causes a visible
                    // hitch at every segment boundary. pendingNext takes over the
                    // inputs on its first tick, so motion stays continuous.
                    current = pendingNext;
                    pendingNext = null;
                    if (pendingPathForViz != null) {
                        publishViz(pendingPathForViz);
                        pendingPathForViz = null;
                    }
                    return Status.RUNNING;
                }
                return restartFresh(Restart.SEGMENT_DONE);
            }
            case NEEDS_REPLAN -> {
                lastExecFailure = current.replanCause();
                BlockPos noSupport = current.lastNoSupportPlace();
                if (noSupport != null && deniedPlace.add(noSupport.asLong())) {
                    com.dwinovo.numen.Constants.LOG.info(
                            "[numen-path] DENY-PLACE {} (NO_SUPPORT proven at execution;"
                                    + " {} denied this navigation)",
                            noSupport.toShortString(), deniedPlace.size());
                }
                discardPrecompute();
                return restartFresh(Restart.EXEC_FAILURE);
            }
            case FAILED -> {
                return Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    /** Frozen context for a SEARCH — snapshot inventory + an immutable loaded-chunk view, safe to read
     *  off the tick thread. Ensure the level's snapshot exists first so the view is never the live
     *  read-through fallback (which a worker thread mustn't touch). */
    private NavContext searchContext() {
        if (player.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            com.dwinovo.numen.core.pathing.cache.PathCaches.ensureSnapshot(sl, player.blockPosition());
        }
        return NavContext.forSearch(player.level(), player.getInventory(), forceBreak, sacred,
                deniedPlace);
    }

    /** Off-thread when the context is frozen (the normal case); on the main thread otherwise — a
     *  context whose view is the live read-through ({@code safeForThreadedUse == false}) must NOT run
     *  on a worker. The latter is a rare safety net (e.g. no chunk snapshot yet); it returns an
     *  already-completed future so the polling code is identical. */
    private java.util.concurrent.CompletableFuture<Path> dispatch(NavContext ctx, EngineSearch s) {
        return ctx.safeForThreadedUse ? runAsync(s) : java.util.concurrent.CompletableFuture.completedFuture(runToCompletion(s));
    }

    /** Run a search to completion on the planner pool (off the tick thread). The engine's budget
     *  bounds it, so one {@code run} call runs the whole thing. */
    private static java.util.concurrent.CompletableFuture<Path> runAsync(EngineSearch s) {
        return com.dwinovo.numen.core.pathing.calc.PathPlannerPool.submit(() -> runToCompletion(s));
    }

    /** One budget-bounded {@code run}; a thrown planner bug yields no path rather than wedging the
     *  companion (or, off-thread, completing the future exceptionally). */
    private static Path runToCompletion(EngineSearch s) {
        try {
            return s.run();
        } catch (Throwable t) {
            com.dwinovo.numen.Constants.LOG.error("path search failed", t);
            return null;
        }
    }

    /** Live context for EXECUTION re-costing (main thread; reads current world + inventory). */
    private NavContext executionContext() {
        return NavContext.forExecution(player.level(), player.getInventory(), forceBreak, sacred,
                deniedPlace);
    }

    private void startFreshSearch() {
        NavGoal g = pullGoal();
        plannedCenter = (g == null) ? null : g.center();
        if (g == null) {
            searchFuture = null;
            searchObj = null;
            return;
        }
        NavContext ctx = searchContext();
        BlockPos startFeet = standableStart(BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ()));
        lastCtx = ctx;
        lastStart = startFeet;
        lastStartGrounded = player.onGround();
        // Long-range approach goals get a frozen coarse guidance field (and its
        // reachability probe) built against the SAME snapshot this search reads.
        // Built ONCE per navigation (goal unchanged ⇒ the guidance barely moves;
        // rebuilding on every exec-failure replan bought 30-65ms a pop for nothing)
        // — a GOAL_MOVED restart clears it and rebuilds toward the new center.
        if (coarseEligible && coarseField == null) {
            coarseField = com.dwinovo.numen.core.pathing.hier.CoarsePlanner.fieldFor(
                    player.level(), ctx.view, startFeet, g.center());
        }
        if (coarseField != null && coarseField.sealed()) {
            // Sound sealed verdict (exhausted sweep, exact scans only): skip the
            // fine search entirely — its whole failure budget would buy the same
            // answer slower. advanceFreshSearch surfaces the structured reason.
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-path] COARSE-SEALED start={} goal-center={} — skipping fine search",
                    startFeet.toShortString(), g.center().toShortString());
            sealedVerdict = "no path to target (coarse reachability: no crossable or"
                    + " diggable face chain connects here to there — the region around"
                    + " the target is sealed off even allowing digging)";
            searchObj = null;
            searchFuture = java.util.concurrent.CompletableFuture.completedFuture(null);
            return;
        }
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-path] DISPATCH start={} goal-center={} sacred={} forceBreak={} coarse={}",
                startFeet.toShortString(), g.center().toShortString(), sacred.size(), forceBreak,
                coarseField == null ? (coarseEligible ? "short-range" : "ineligible")
                        : coarseField.summary());
        EngineSearch s = EngineSearch.create(ctx, startFeet, g, previousPathHashes,
                liveBudget(), coarseField);
        searchObj = s;
        searchFuture = dispatch(ctx, s);
    }

    /**
     * A rim-standing body (feet cell hanging over air, box supported by a neighbouring
     * column) launches a search whose start node has no floor — it dies at 0 expansions
     * and reads as "sealed in". Re-anchor the start on the closest standable cell the
     * bounding box actually overlaps. Grounded good starts and mid-air launches pass
     * through unchanged (the ZERO-EXPANSION autopsy still covers the latter).
     */
    private BlockPos standableStart(BlockPos feet) {
        var level = player.level();
        if (!player.onGround() || BlockHelper.isStandable(level, feet)) {
            return feet;
        }
        var box = player.getBoundingBox();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos n = feet.offset(dx, 0, dz);
                boolean overlaps = box.maxX > n.getX() && box.minX < n.getX() + 1
                        && box.maxZ > n.getZ() && box.minZ < n.getZ() + 1;
                if (!overlaps || !BlockHelper.isStandable(level, n)) continue;
                double d = net.minecraft.world.phys.Vec3.atBottomCenterOf(n)
                        .distanceToSqr(player.position());
                if (d < bestD) {
                    bestD = d;
                    best = n;
                }
            }
        }
        return best != null ? best : feet;
    }

    /** The live wall-clock budget: the same time buys whatever this machine can
     *  explore — no per-machine expansion tuning (see {@link SearchBudget#timed}). */
    private static SearchBudget liveBudget() {
        return SearchBudget.timed(PathSettings.SEARCH_PRIMARY_MS,
                PathSettings.SEARCH_FAILURE_MS, PathSettings.SEARCH_EXPANSION_FUSE);
    }

    /** Cancel and forget the in-flight main search (so a stale worker stops and its result is ignored). */
    private void cancelSearch() {
        if (searchObj != null) {
            searchObj.cancel();
            searchObj = null;
        }
        searchFuture = null;
    }

    private Status advanceFreshSearch() {
        if (searchFuture == null) {
            failReason = "target lost";
            failType = FailureType.TARGET_LOST;
            return Status.FAILED;
        }
        if (!searchFuture.isDone()) {
            return Status.RUNNING;   // worker still planning — body waits (it was idle anyway)
        }
        EngineSearch finished = searchObj;
        Path path = searchFuture.getNow(null);
        searchFuture = null;
        searchObj = null;
        if (path != null && path.isEmpty() && !path.partial) {
            // COMPLETE with zero movements: the search's goal test is satisfied by
            // the very cell we stand on — there is nothing to walk, and that IS
            // arrival for the navigation. A caller whose own arrival predicate is
            // richer (reach + line of sight on top of the stance) must react to
            // "arrived but not satisfied" itself — reporting this as NO-PATH sent
            // the mining loop blacklisting perfectly good ores ("sealed in;
            // explored 0 positions" was this case's lying autopsy). The flag keeps
            // the verdict STABLE across ticks (see its declaration).
            searchSatisfied = true;
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-path] ARRIVED-IN-PLACE feet={} goal-center={} — search goal is"
                            + " satisfied where we stand; holding the verdict for the task layer",
                    lastStart != null ? lastStart.toShortString() : "?",
                    plannedCenter != null ? plannedCenter.toShortString() : "?");
            return Status.ARRIVED;
        }
        if (path == null || path.isEmpty()) {
            if (sealedVerdict != null) {
                failReason = sealedVerdict;
                sealedVerdict = null;
                failType = FailureType.NO_PATH;
                return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
            }
            failReason = noPathAutopsy(finished);
            failType = FailureType.NO_PATH;
            return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
        }
        Path cut = path.staticCutoff();
        current = new PlayerPathExecutor(player, cut, speed, this::executionContext);
        previousPathHashes = EngineSearch.favoring(cut);   // favor this route on the next replan
        publishViz(cut);
        return Status.RUNNING;
    }

    /**
     * Why a navigation goes back to planning. Every restart re-searches
     * deterministically from the current world — the distinctions carry the
     * bookkeeping, not the search:
     * <ul>
     *   <li>{@link #GOAL_MOVED} — the target itself changed: the progress
     *       accounting (bestGoalH) resets with it;</li>
     *   <li>{@link #SEGMENT_DONE} — a partial segment was walked to its end,
     *       the journey continues under the stall accounting;</li>
     *   <li>{@link #EXEC_FAILURE} — the body could not execute what the plan
     *       said; the executor's failure cause is captured for the give-up
     *       report, and the re-search prices the world as the failed attempt
     *       left it (its scaffolds are real terrain now — the built route wins
     *       on cost, so the same attempt resumes instead of drifting).</li>
     * </ul>
     */
    private enum Restart { GOAL_MOVED, SEGMENT_DONE, EXEC_FAILURE }

    private Status restartFresh(Restart why) {
        searchSatisfied = false;   // re-rooting — the standing-in-goal verdict is void
        sealedVerdict = null;
        if (current != null) {
            current.stop();
            current = null;
        }
        cancelSearch();       // abandon any in-flight main search before dispatching a new one
        discardPrecompute();  // and any in-flight NEXT segment — it was rooted at a path end
                              // that may no longer exist
        if (why == Restart.GOAL_MOVED) {
            bestGoalH = Double.MAX_VALUE;      // goal moved / re-rooted → fresh accounting
            coarseField = null;                // and the guidance field re-aims with it
        } else {
            // Give up on STALLED effort, never on segment count —
            // a 60-block dig-up legitimately takes dozens of segments, each one a real
            // gain. Progress is judged in the GOAL'S OWN terms (its heuristic at the
            // feet): correct for yLevel (vertical only), column (horizontal only),
            // composite (nearest member) and runAway (negative, drops as we flee) alike.
            NavGoal liveGoal = pullGoal();
            double h = liveGoal == null ? Double.MAX_VALUE
                    : liveGoal.heuristic(player.blockPosition());
            if (bestGoalH - h >= REPLAN_PROGRESS_EPS_H) {
                bestGoalH = h;
                stalledReplans = 0;
            } else if (++stalledReplans >= MAX_STALLED_REPLANS) {
                failReason = "gave up: no real progress toward the target over "
                        + MAX_STALLED_REPLANS + " consecutive attempts"
                        + (lastExecFailure != null
                                ? "; the recurring failure: " + lastExecFailure : "");
                failType = FailureType.BOXED_IN;
                return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
            }
            if (replans++ >= MAX_REPLANS) {
                failReason = "gave up after " + MAX_REPLANS + " replans";
                failType = FailureType.BOXED_IN;
                return reached.getAsBoolean() ? Status.ARRIVED : Status.FAILED;
            }
        }
        startFreshSearch();
        return Status.RUNNING;
    }

    private void maybePrecompute() {
        if (nextFuture != null || pendingNext != null) return;
        if (current == null || !current.isPartial()) return;
        // Plan ahead: start the next segment once the current one has
        // fewer than PLANNING_TICK_LOOKAHEAD (150) ticks of travel left.
        if (current.remainingCost() > PathSettings.PLANNING_TICK_LOOKAHEAD) return;
        NavGoal g = pullGoal();
        if (g == null) return;
        plannedCenter = g.center();
        NavContext ctx = searchContext();
        // The continuation reuses this navigation's frozen field (same goal): the
        // guidance is section-granular, a segment of staleness is harmless, and a
        // fresh restart rebuilds it anyway.
        EngineSearch s = EngineSearch.create(ctx, current.pathEnd(), g, previousPathHashes,
                liveBudget(), coarseField);
        nextObj = s;
        nextFuture = dispatch(ctx, s);
    }

    private void advancePrecompute() {
        if (nextFuture == null) return;
        if (!nextFuture.isDone()) return;
        Path np = nextFuture.getNow(null);
        nextFuture = null;
        nextObj = null;
        if (np != null && !np.isEmpty()) {
            Path cut = np.staticCutoff();
            pendingNext = new PlayerPathExecutor(player, cut, speed, this::executionContext);
            pendingPathForViz = cut;
            previousPathHashes = EngineSearch.favoring(cut);   // the next segment becomes the favored route
        }
    }

    private void discardPrecompute() {
        if (nextObj != null) {
            nextObj.cancel();
            nextObj = null;
        }
        nextFuture = null;
        pendingPathForViz = null;
        if (pendingNext != null) {
            pendingNext.stop();
            pendingNext = null;
        }
    }

    /**
     * Rich post-mortem for an EMPTY search result — for the model and the log both:
     * was the region fully explored (sealed in) or did the budget run out, how far
     * did the search really get, was scaffolding available, and what is the first
     * concrete break-veto (water / protected block / bedrock …) on the straight
     * line to the goal, when there is one.
     */
    private String noPathAutopsy(EngineSearch s) {
        StringBuilder r = new StringBuilder("no path to target");
        if (s != null) {
            r.append(" (").append(s.frontierExhausted()
                            ? "every reachable spot explored — sealed in"
                            : "search budget exhausted")
                    .append(String.format("; explored %d positions, up to %.1f blocks out",
                            s.expansionsDone(), Math.sqrt(s.bestProgressSq())));
            if (lastCtx != null && !lastCtx.hasScaffold) {
                r.append("; carrying no scaffolding blocks to bridge or pillar with");
            }
            if (s.stats().stoppedAtPrimary()) {
                r.append("; stopped at primary budget");
            }
            if (s.expansionsDone() == 0 && lastCtx != null && lastStart != null) {
                // The search died AT THE START NODE — the "sealed in" verdict above is
                // meaningless. Name the start cell's real condition so the failure can be
                // typed: rim standing (feet cell over air), a search launched mid-jump/fall,
                // or a snapshot hole (chunk not captured).
                var below = lastCtx.view.getBlockState(lastStart.below());
                r.append(String.format(
                        "; ZERO-EXPANSION start diagnosis: feet=%s grounded-at-launch=%b below=%s chunk-captured=%b",
                        lastStart.toShortString(), lastStartGrounded,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath(),
                        lastCtx.isLoadedAt(lastStart)));
            }
            r.append(')');
        } else {
            r.append(" (obstructed or out of bridging blocks)");
        }
        if (lastCtx != null && lastStart != null && plannedCenter != null) {
            String veto = lastCtx.diagnoseObstruction(lastStart, plannedCenter);
            if (veto != null) {
                r.append("; first hard obstruction toward it: ").append(veto);
            }
        }
        String reason = r.toString();
        com.dwinovo.numen.Constants.LOG.info("[numen-path] NO-PATH start={} goal={} | {}",
                lastStart, plannedCenter, reason);
        return reason;
    }

    public String failReason() {
        return failReason;
    }

    /** Structured cause of a {@link Status#FAILED}, for the reactive task layer to branch on. */
    public FailureType failType() {
        return failType;
    }

    /**
     * Ticks since the navigation last made real progress (plan-step consumption or an
     * active dig) — the liveness signal for progress-lease task deadlines. Planning
     * gaps (no executor yet / between segments) read 0: a budgeted search in flight
     * IS progress, just not the walking kind.
     */
    public int stallTicks() {
        return current == null ? 0 : current.ticksSinceProgress();
    }

    public void stop() {
        searchSatisfied = false;
        if (current != null) {
            current.stop();
            current = null;
        }
        discardPrecompute();
        cancelSearch();
        InputDriver.halt(player);
        // Release sneak too — a pillar holds it every tick, and nothing else clears it
        // when the path ends (inputs aren't auto-reset per tick), so the body
        // would stay crouched after arriving.
        player.setShiftKeyDown(false);
    }
}
