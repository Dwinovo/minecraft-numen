package com.dwinovo.numen.core.pathing.exec;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.EngineSearch;
import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.calc.Path;
import com.dwinovo.numen.core.pathing.engine.HLearningTable;
import com.dwinovo.numen.core.pathing.engine.SearchBudget;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.viz.PathVizPublisher;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import com.dwinovo.numen.core.task.FailureType;
import net.minecraft.core.BlockPos;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Plan → execute → replan driver for a companion {@link NumenPlayer} body:
 * a path-while-moving loop — walk
 * the current path; precompute the continuation of a partial path so there's no
 * planning pause at a segment boundary; re-root on a moving goal or after an
 * off-path replan — executing through {@link PlayerPathExecutor}. Planning
 * runs on the v2 engine via the {@link EngineSearch} adapter (body-neutral,
 * Minecraft-free at its core) with an {@link HLearningTable} shared across
 * this navigation's segments.
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
    private final Supplier<NavGoal> goalSupplier;
    private final double speed;
    private final BooleanSupplier reached;

    /** Learned-heuristic table shared across this navigation's search segments (see
     *  {@link HLearningTable} for semantics). Concurrency invariant: at most one LIVE
     *  search at a time; cancelled workers may linger — safe because the table is
     *  synchronized and cancelled searches never write (engine contract). */
    private HLearningTable learning = new HLearningTable();
    /** Dig-capability fingerprint at the last fresh dispatch — a RISE clears the
     *  learned table (better tools → old learned values may over-estimate). */
    private double lastDigFingerprint = -1;

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
        PathVizPublisher.publish(player, cut, targets);
    }

    private int replans = 0;
    private int stalledReplans = 0;
    /** Best (lowest) goal-heuristic the feet have EVER reached — the stalled-replan yardstick. */
    private double bestGoalH = Double.MAX_VALUE;
    private String failReason = "target unreachable";
    private FailureType failType = FailureType.NO_PATH;

    /** Frozen context + start of the most recent search — kept for the no-path autopsy. */
    private NavContext lastCtx;
    private BlockPos lastStart;

    /** Walk to a single cell. */
    public PlayerNav(NumenPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, () -> resolveBlockGoal(player, goal), speed, reached, true);
    }

    /** Walk to a (possibly moving) single cell. */
    public PlayerNav(NumenPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null : resolveBlockGoal(player, g);
        }, speed, reached, true);
    }

    /** Walk toward an arbitrary {@link NavGoal} (composite ore field, mining stance, …). */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, goalSupplier, speed, reached, true);
    }

    private PlayerNav(NumenPlayer player, Supplier<NavGoal> goalSupplier, double speed,
                      BooleanSupplier reached, boolean marker) {
        this.player = player;
        this.goalSupplier = goalSupplier;
        this.speed = speed;
        this.reached = reached;
        startFreshSearch();
    }

    /** A cell goal: exact if standable, else reach within 2 (mirrors move_to arrival). */
    private static NavGoal resolveBlockGoal(NumenPlayer player, BlockPos bp) {
        return BlockHelper.canWalkThrough(player.level(), bp)
                ? NavGoal.exact(bp)
                : NavGoal.near(bp, 2.0);
    }

    public Status tick() {
        if (reached.getAsBoolean()) return Status.ARRIVED;

        if (current == null) {
            return advanceFreshSearch();
        }

        NavGoal liveGoal = goalSupplier.get();
        if (liveGoal == null) {
            failReason = "target lost";
            failType = FailureType.TARGET_LOST;
            return Status.FAILED;
        }
        if (plannedCenter != null && liveGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            discardPrecompute();
            return restartFresh(false);
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
                return restartFresh(true);
            }
            case NEEDS_REPLAN -> {
                discardPrecompute();
                return restartFresh(true);
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
        return NavContext.forSearch(player.level(), player.getInventory());
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
        return NavContext.forExecution(player.level(), player.getInventory());
    }

    private void startFreshSearch() {
        NavGoal g = goalSupplier.get();
        plannedCenter = (g == null) ? null : g.center();
        if (g == null) {
            searchFuture = null;
            searchObj = null;
            return;
        }
        NavContext ctx = searchContext();
        BlockPos startFeet = BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
        refreshLearning(ctx);
        double dist = Math.sqrt(startFeet.distSqr(g.center()));
        EngineSearch s = EngineSearch.create(ctx, startFeet, g, previousPathHashes,
                learning, SearchBudget.scaled(dist));
        lastCtx = ctx;
        lastStart = startFeet;
        searchObj = s;
        searchFuture = dispatch(ctx, s);
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
        if (path == null || path.isEmpty()) {
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

    private Status restartFresh(boolean budgeted) {
        if (current != null) {
            current.stop();
            current = null;
        }
        cancelSearch();       // abandon any in-flight main search before dispatching a new one
        discardPrecompute();  // and any in-flight NEXT segment — it was rooted at a path end
                              // that may no longer exist, and letting it run would put a
                              // second LIVE search on the shared learning table
        if (!budgeted) {
            bestGoalH = Double.MAX_VALUE;      // goal moved / re-rooted → fresh accounting
            // Learned h is goal-relative — invalid for the new goal. SWAP the table,
            // never clear() it: a just-cancelled worker can still be mid-write-back
            // (a milliseconds-wide scan), and clearing would let its OLD-goal values
            // land in the table the NEW goal consults. Swapping orphans those writes.
            learning = new HLearningTable();
        } else {
            // Give up on STALLED effort, never on segment count —
            // a 60-block dig-up legitimately takes dozens of segments, each one a real
            // gain. Progress is judged in the GOAL'S OWN terms (its heuristic at the
            // feet): correct for yLevel (vertical only), column (horizontal only),
            // composite (nearest member) and runAway (negative, drops as we flee) alike.
            NavGoal liveGoal = goalSupplier.get();
            double h = liveGoal == null ? Double.MAX_VALUE
                    : liveGoal.heuristic(player.blockPosition());
            if (bestGoalH - h >= REPLAN_PROGRESS_EPS_H) {
                bestGoalH = h;
                stalledReplans = 0;
            } else if (++stalledReplans >= MAX_STALLED_REPLANS) {
                failReason = "gave up: no real progress toward the target over "
                        + MAX_STALLED_REPLANS + " consecutive attempts";
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
        NavGoal g = goalSupplier.get();
        if (g == null) return;
        plannedCenter = g.center();
        NavContext ctx = searchContext();
        refreshLearning(ctx);   // precompute-chained segments must see tool upgrades too
        double dist = Math.sqrt(current.pathEnd().distSqr(g.center()));
        EngineSearch s = EngineSearch.create(ctx, current.pathEnd(), g, previousPathHashes,
                learning, SearchBudget.scaled(dist));
        nextObj = s;
        nextFuture = dispatch(ctx, s);
    }

    /**
     * Learned-h lifecycle: an IMPROVED dig capability (better tool picked up,
     * scaffolding gained) means previously-learned pessimism may now over-estimate
     * → SWAP in a fresh table (never {@code clear()} the shared one: a stale
     * cancelled worker could still be mid-write-back, and its writes must land in
     * the orphaned instance, not the one live searches consult). Worse or equal
     * capability keeps the still-valid lower bounds.
     */
    private void refreshLearning(NavContext ctx) {
        double fp = ctx.digCapabilityFingerprint();
        if (fp > lastDigFingerprint + 0.01) {
            learning = new HLearningTable();
        }
        lastDigFingerprint = fp;
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
            r.append(String.format("; learned-h consults %d", s.stats().learnedConsultHits()));
            if (s.stats().stoppedAtPrimary()) {
                r.append("; stopped at primary budget");
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

    public void stop() {
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
        PathVizPublisher.clear(player);
    }
}
