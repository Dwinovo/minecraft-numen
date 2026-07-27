package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.task.CompanionTask;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.task.Suspendable;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared skeleton every reactive companion task grows on — the single place
 * the lifecycle ({@code start → tick* → buildResult}), the failure plumbing
 * ({@link FailureType} + a model-facing reason), the result envelope, and
 * {@link Suspendable} preemption all live, so a concrete task only writes the
 * behaviour that is actually specific to it.
 *
 * <h2>Why a base class (the recovery boundary)</h2>
 * The reactive layer's governing rule is that a task is a <em>recovery
 * boundary</em>: it owns the execution of ONE bounded goal and everything that
 * happens inside it — retries, alternative approaches, sub-steps — recovers that
 * same goal without ever expanding its scope or auto-acquiring a prerequisite. A
 * prerequisite gap (no material, wrong tool, target lost) is not recovered here;
 * it is reported via {@link #fail(String, FailureType)} and kicked back to the
 * LLM. This class makes that boundary concrete: the two composition primitives it
 * exposes — {@link #runChild(CompanionTask)} (delegate a bounded SUB-goal) and a
 * {@link RecoveryLadder} driven through it (try alternative EXECUTIONS of the same
 * goal) — can only ever compose bounded goals, never widen one.
 *
 * <h2>Lifecycle (all {@code final}, so subclasses can't break the contract)</h2>
 * <ul>
 *   <li>{@link #start()} runs the {@link #preconditions()} in order; the first
 *       that reports a {@link Precondition.Failure} terminates the task
 *       immediately (via {@link #fail}); otherwise {@link #onStart()} runs.</li>
 *   <li>{@link #tick()} short-circuits to the terminal state a {@code fail(...)}
 *       (or a start-time precondition) parked in {@code pendingTerminal};
 *       otherwise it delegates to {@link #onTick()}.</li>
 *   <li>{@link #buildResult(TaskState)} runs {@link #cleanup()} and then templates
 *       the {@link TaskResult} from the terminal state and the overridable
 *       message / data hooks.</li>
 * </ul>
 *
 * <h2>The onTick fail idiom</h2>
 * A concrete {@link #onTick()} reports a failure with
 * {@snippet : fail(reason, FailureType.SOMETHING); return TaskState.FAILED; }
 * — {@link #fail} records the reason + type (so {@link #buildResult} and any
 * parent ladder can read them) and {@code return FAILED} ends the tick. The two
 * are kept separate (rather than {@code fail} returning {@code FAILED}) so a
 * caller can also stash a failure for the NEXT tick to observe.
 *
 * @param <R> the concrete {@link TaskRecord} subtype carrying this task's typed
 *            input fields.
 */
public abstract class AbstractCompanionTask<R extends TaskRecord>
        implements CompanionTask, Suspendable {

    /** The body this task drives. */
    protected final NumenPlayer player;
    /** The typed input record for this task. */
    protected final R r;
    /** The active navigation, if any — owned here so {@link #stopNav()} / {@link #cleanup()} can release it. */
    protected PlayerNav nav;

    /** Model-facing reason for a terminal FAILED; also the fallback result message. */
    private String doneReason = "done";
    /** Structured cause of the last failure, for a parent ladder to branch on. */
    private FailureType failType = FailureType.UNKNOWN;
    /**
     * A terminal state decided out-of-band (a start-time precondition, or a
     * {@link #fail} called from anywhere): {@link #tick()} returns it verbatim
     * instead of running {@link #onTick()}.
     */
    private TaskState pendingTerminal;

    // ---- sub-task composition state (see runChild) ----
    /** The child sub-goal currently being delegated to, or {@code null}. */
    private CompanionTask child;
    /** Whether {@link #child}'s {@code start()} has been called yet. */
    private boolean childStarted;

    protected AbstractCompanionTask(NumenPlayer player, R record) {
        this.player = player;
        this.r = record;
    }

    // ---------------------------------------------------------------------
    // Lifecycle (final — the frozen contract)
    // ---------------------------------------------------------------------

    @Override
    public final void start() {
        for (Precondition p : preconditions()) {
            Precondition.Failure f = p.check();
            if (f != null) {
                fail(f.message(), f.type());
                r.setState(TaskState.FAILED);   // same-tick finalization (old dispatcher semantics)
                return;
            }
        }
        onStart();
        // A terminal parked DURING start (a fail(...) or succeed() from onStart — the
        // one-shot tasks do their whole job there) is stamped on the record NOW, so
        // the dispatcher finalizes it in the same tick it started. Without this, the
        // record sits RUNNING for one tick with the work already done, and an owner
        // Stop in that window would ship "interrupted" for work that actually
        // happened — diverging the model's world-view from the inventory.
        if (pendingTerminal != null) {
            r.setState(pendingTerminal);
        }
    }

    @Override
    public final TaskState tick() {
        if (pendingTerminal != null) return pendingTerminal;
        // 规划器在飞、身体没有路段可走的等待刻,不烧任务预算:deadline 度量
        // 的是身体干活的刻,异步搜索的墙钟延迟不是任务的错(与调度层被生存
        // 链抢占时的 freezeTick 同一原则)。正常 tick 速率下一次搜索只有几刻,
        // 这里几乎不动;tick 远快于真实时间时(如不限速的测试服),没有这道
        // 冻结,任务会在第一次搜索返回前就被判 TIMEOUT。
        if (nav != null && nav.planningInFlight()) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
        }
        return onTick();
    }

    @Override
    public final TaskResult buildResult(TaskState finalState) {
        cleanup();
        return switch (finalState) {
            case SUCCESS   -> TaskResult.ok(successMessage(), resultData());
            case TIMEOUT   -> new TaskResult(false, timeoutMessage(), true, false, resultData());
            case CANCELLED -> new TaskResult(false, cancelledMessage(), false, true, resultData());
            default        -> TaskResult.fail(doneReason, resultData());   // FAILED and any stray state
        };
    }

    // ---------------------------------------------------------------------
    // Hooks (override the ones a concrete task needs)
    // ---------------------------------------------------------------------

    /** Ordered start-time gates; the first {@link Precondition.Failure} wins. Default: none. */
    protected List<Precondition> preconditions() {
        return List.of();
    }

    /** First-tick setup (build the nav, snapshot baselines, …). Default: no-op. */
    protected void onStart() {}

    /** Advance one tick; return {@link TaskState#RUNNING} or a terminal state. */
    protected abstract TaskState onTick();

    /** Release physical resources on termination. Default: stop nav + clear the path overlay. */
    protected void cleanup() {
        stopNav();
    }

    /** Structured payload for the result envelope. Default: a fresh empty (mutable) map. */
    protected Map<String, Object> resultData() {
        return new HashMap<>();
    }

    /** Message for a SUCCESS result. */
    protected abstract String successMessage();

    /** Message for a TIMEOUT result. Default: {@code "timed out"}. */
    protected String timeoutMessage() {
        return "timed out";
    }

    /** Message for a CANCELLED result. Default: {@code "interrupted"}. */
    protected String cancelledMessage() {
        return "interrupted";
    }

    // ---------------------------------------------------------------------
    // Failure plumbing
    // ---------------------------------------------------------------------

    /**
     * Record a failure: stash the model-facing reason and structured cause, and
     * park a terminal FAILED for {@link #tick()} to surface. Callers in
     * {@link #onTick()} pair this with {@code return TaskState.FAILED;}.
     */
    protected void fail(String why, FailureType t) {
        this.doneReason = why;
        this.failType = t;
        this.pendingTerminal = TaskState.FAILED;
    }

    /**
     * Park a terminal SUCCESS — the mirror of {@link #fail} for one-shot tasks whose
     * whole job happens in {@link #onStart()} (drop, equip): {@link #tick()} surfaces
     * it, and {@link #start()} stamps it on the record for same-tick finalization.
     */
    protected void succeed() {
        this.pendingTerminal = TaskState.SUCCESS;
    }

    /** The structured cause of the most recent failure (or {@link FailureType#UNKNOWN}). */
    protected FailureType lastFailure() {
        return failType;
    }

    /** The model-facing reason recorded by the most recent {@link #fail}. */
    protected String doneReason() {
        return doneReason;
    }

    // ---------------------------------------------------------------------
    // Nav ownership
    // ---------------------------------------------------------------------

    /** Stop and forget the active nav (idempotent). */
    protected void stopNav() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    // ---------------------------------------------------------------------
    // Sub-task composition — one of the two recovery-boundary primitives
    // ---------------------------------------------------------------------

    /**
     * Delegate this tick to a child {@link CompanionTask} representing a bounded
     * SUB-goal, driving its {@code start → tick} lifecycle for the parent.
     *
     * <p>Re-invoking with the SAME child instance continues it; passing a
     * different instance switches to (and starts) the new child. The child's
     * {@code start()} is called lazily on its first tick here, so if the child is
     * itself an {@link AbstractCompanionTask} a start-time terminal (a failed
     * precondition) is observed on the very first {@link #tick()} — no special
     * "state after start" path is needed.
     *
     * @return the child's terminal {@link TaskState} on the tick it finishes (its
     *         structured failure, if it exposes one, is copied up so this task's
     *         {@link #lastFailure()} reflects the child's cause); or {@code null}
     *         while the child is still running.
     */
    protected TaskState runChild(CompanionTask c) {
        if (child != c) {
            child = c;
            childStarted = false;
        }
        if (!childStarted) {
            child.start();
            childStarted = true;
        }
        TaskState st = child.tick();
        if (st.isTerminal()) {
            if (child instanceof AbstractCompanionTask<?> a) {
                this.failType = a.lastFailure();
            }
            child = null;
            childStarted = false;
            return st;
        }
        return null;   // still running
    }

    // ---------------------------------------------------------------------
    // Suspendable (scheduler preemption)
    // ---------------------------------------------------------------------

    /**
     * Preempted by a higher-priority survival chain: release the BODY (zero the
     * locomotion inputs, drop sneak) but keep every logical field — including the
     * nav PLAN — intact. Deliberately does NOT call {@code nav.stop()}: the plan
     * is what lets {@link #resume()} pick straight back up on the next tick.
     */
    @Override
    public void suspend() {
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }

    // resume(): default no-op from Suspendable — the next onTick re-drives from
    // the preserved state, so nothing is required here.
}
