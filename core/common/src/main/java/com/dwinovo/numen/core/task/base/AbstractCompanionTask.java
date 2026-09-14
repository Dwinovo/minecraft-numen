package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.TaskResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared skeleton every reactive companion task grows on — the single place
 * the lifecycle ({@code start → tick* → buildResult}), the failure plumbing
 * ({@link FailureType} + a model-facing reason), the result envelope, and
 * 被抢占时的让位 all live, so a concrete task only writes the
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
 * exposes — {@link #runChild(Task)} (delegate a bounded SUB-goal) and a
 * {@link RecoveryLadder} driven through it (try alternative EXECUTIONS of the same
 * goal) — can only ever compose bounded goals, never widen one.
 *
 * <h2>Lifecycle (all {@code final}, so subclasses can't break the contract)</h2>
 * <ul>
 *   <li>{@link #start} runs the {@link #preconditions()} in order; the first
 *       that reports a {@link Precondition.Failure} terminates the task
 *       immediately (via {@link #fail}); otherwise {@link #onStart()} runs.</li>
 *   <li>{@link #tick} short-circuits to the terminal state a {@code fail(...)}
 *       (or a start-time precondition) parked in {@code pendingTerminal};
 *       otherwise it delegates to {@link #onTick()}.</li>
 *   <li>{@link #result(TaskState)} runs {@link #cleanup()} and then templates
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
        implements Task {

    /** The body this task drives. */
    protected final NumenPlayer player;
    /** The typed input record for this task. */
    protected final R r;
    /** The active navigation, if any — owned here so {@link #stopNav()} / {@link #cleanup()} can release it. */
    protected PlayerNav nav;
    /**
     * 本任务历次导航累计真动过的地形(每条导航停下时并入)。回执末尾如实相告——
     * 不论成败、不论任务,"路上挖了什么放了什么"只在这一处说一次。
     */
    private final com.dwinovo.numen.core.pathing.execute.TerrainBill journey =
            new com.dwinovo.numen.core.pathing.execute.TerrainBill();

    /** Model-facing reason for a terminal FAILED; also the fallback result message. */
    private String doneReason = "done";
    /** Structured cause of the last failure, for a parent ladder to branch on. */
    private FailureType failType = FailureType.UNKNOWN;
    /**
     * A terminal state decided out-of-band (a start-time precondition, or a
     * {@link #fail} called from anywhere): {@link #tick} returns it verbatim
     * instead of running {@link #onTick()}.
     */
    private TaskState pendingTerminal;

    /** 在等主人答复的那张号;没在问是 null。 */
    private ConsentDesk.Ticket consent;
    /** 主人点头的那几次,回执末尾交代。 */
    private final List<String> allowances = new ArrayList<>();

    // ---- sub-task composition state (see runChild) ----
    /** The child sub-goal currently being delegated to, or {@code null}. */
    private Task child;
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
    public final void start(NumenPlayer companion) {
        for (Precondition p : preconditions()) {
            Precondition.Failure f = p.check();
            if (f != null) {
                fail(f.message(), f.type());
                r.setState(TaskState.FAILED);   // same-tick finalization (old dispatcher semantics)
                return;
            }
        }
        try {
            onStart();
        } catch (RuntimeException e) {
            crashed("start", e);
            r.setState(TaskState.FAILED);
            return;
        }
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
    public final TaskState tick(NumenPlayer companion) {
        if (pendingTerminal != null) return pendingTerminal;
        // 规划器在飞、身体没有路段可走的等待刻,不烧任务预算:deadline 度量
        // 的是身体干活的刻,异步搜索的墙钟延迟不是任务的错(与调度层被生存
        // 链抢占时的 freezeTick 同一原则)。正常 tick 速率下一次搜索只有几刻,
        // 这里几乎不动;tick 远快于真实时间时(如不限速的测试服),没有这道
        // 冻结,任务会在第一次搜索返回前就被判 TIMEOUT。
        // 等主人点头的刻同理:期限度量身体干活,主人想多久不是任务的错。
        if ((nav != null && nav.planningInFlight()) || consent != null) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
        }
        try {
            TaskState routeConsent = awaitRouteConsent();
            return routeConsent != null ? routeConsent : onTick();
        } catch (RuntimeException e) {
            crashed("tick", e);
            return TaskState.FAILED;
        }
    }

    /**
     * 导航扣着一段要问主人的路时,这一刻归征询:身体站住、问主人;答应了放行那段路接着跑
     * {@link #onTick},拒绝了按 {@link FailureType#REFUSED} 收场。任何带导航的任务都一样,
     * 不各写各的。
     *
     * @return 这一刻的终态或 RUNNING;不用等(没有扣着的路,或刚放行)时为 null
     */
    private TaskState awaitRouteConsent() {
        if (nav == null) {
            return null;
        }
        List<ConsentItem> needed = nav.consentNeeded();
        if (needed.isEmpty()) {
            return null;
        }
        InputDriver.halt(player);
        ConsentAnswer answer = consult(needed, r.describe());
        if (answer == null) {
            return TaskState.RUNNING;
        }
        if (!answer.allowed()) {
            fail(answer.refusal(needed), FailureType.REFUSED);
            return TaskState.FAILED;
        }
        nav.consentGranted();
        return null;
    }

    // ---------------------------------------------------------------------
    // Permission — the task proposes actions; the permission layer decides
    // ---------------------------------------------------------------------

    /** 执行开始时一个动作过权限层的结论。 */
    protected enum PermitState { ALLOWED, WAITING, REFUSED }

    /**
     * @param state   放行 / 在等主人 / 不许
     * @param refusal 不许时回执的理由(规则、模式、外部强制的自述,或主人的原话);其余为空串
     */
    protected record Permit(PermitState state, String refusal) {
        static final Permit ALLOWED = new Permit(PermitState.ALLOWED, "");
        static final Permit WAITING = new Permit(PermitState.WAITING, "");

        static Permit refused(String why) {
            return new Permit(PermitState.REFUSED, why);
        }
    }

    /**
     * 执行开始:把要做的一个动作交给权限层。放行就做;拒绝就带着理由收场;要问就发起征询,
     * 等待期间返回 WAITING(调用方让身体站住,每刻再调),主人答应后返回 ALLOWED——同一行规则
     * 问出来的同一种东西从此在本任务内放行,不再问。任务自己不判能不能,只提出动作。
     *
     * @param reason 给主人看的原因
     */
    protected final Permit permit(Action action, String reason) {
        return permitAll(List.of(action), reason).get(0);
    }

    /**
     * 同 {@link #permit},一批动作一起:各自裁决,要问的合成一张征询卡,主人的答复对这一批里要问的
     * 全部生效。结果与 {@code actions} 一一对应。
     */
    protected final List<Permit> permitAll(List<Action> actions, String reason) {
        var gate = Permission.gateFor(player);
        List<Permit> out = new ArrayList<>(actions.size());
        List<ConsentItem> asks = new ArrayList<>();
        List<Integer> askedAt = new ArrayList<>();
        for (Action action : actions) {
            Verdict verdict = gate.judgeLive(action, player.serverLevel());
            switch (verdict.kind()) {
                case ALLOW -> out.add(Permit.ALLOWED);
                case DENY -> out.add(Permit.refused(verdict.reason()));
                case ASK -> {
                    askedAt.add(out.size());
                    asks.add(gate.consentItemLive(action, verdict, player.serverLevel()));
                    out.add(Permit.WAITING);
                }
            }
        }
        if (asks.isEmpty()) {
            settleConsult();
            return out;
        }
        ConsentAnswer answer = consult(asks, reason);
        if (answer != null) {
            Permit settled = answer.allowed() ? Permit.ALLOWED : Permit.refused(answer.refusal(asks));
            for (int i : askedAt) {
                out.set(i, settled);
            }
        }
        return out;
    }

    /**
     * 问主人一批事:第一次调用发起征询,之后每刻读结论;清单变了就重发(新的顶掉旧的)。
     * 主人答应的清单由登记处记成本任务的授权,回执末尾交代。
     *
     * @return 结论;还在等是 null
     */
    protected final ConsentAnswer consult(List<ConsentItem> items, String reason) {
        if (consent != null && !consent.request().items().equals(items)) {
            consent = null;
        }
        if (consent == null) {
            consent = ConsentDesk.of(player).ask(r, items, reason);
        }
        ConsentAnswer answer = consent.poll();
        if (answer == null) {
            return null;
        }
        consent = null;
        if (answer.allowed()) {
            allowances.add(answer.allowance(items));
        }
        return answer;
    }

    /**
     * 要做的事此刻不用问了:主人刚答应(授权已经让裁决变成放行)就把这次允许记进回执;
     * 还没答复就撤回挂着的征询。
     */
    private void settleConsult() {
        if (consent == null) {
            return;
        }
        ConsentAnswer answer = consent.poll();
        if (answer == null) {
            ConsentDesk.of(player).withdraw(consent);
        } else if (answer.allowed()) {
            allowances.add(answer.allowance(consent.request().items()));
        }
        consent = null;
    }

    /**
     * 任务自己抛异常时的收场:<b>这一个任务失败,而不是整个服务端崩掉</b>。
     *
     * <p>任务每刻跑在服务端主循环里,上面那层是 {@code record.setState(task.tick())}
     * ——没有保护。而任务干的事天然是碰运气的:建造每刻要对最多八格<b>任意</b>方块调用
     * 原版回调,矿工要碰任意方块实体,图纸来自玩家目录里可以任意编辑的文件。任何一处
     * 抛出去都会变成一次"Ticking entity"崩服,玩家丢的是整个存档的这一次游玩,而起因
     * 只是一格方块。
     *
     * <p>所以在框架这一层收口,不在每个任务里各写各的:一个任务失败是可交代的
     * (模型收到失败原因、玩家看到已经砌好的部分),崩服不是。异常连同任务名一起进
     * 日志——吞掉症状而不留证据,是比崩溃更难查的病。
     */
    private void crashed(String phase, RuntimeException e) {
        com.dwinovo.numen.core.Constants.LOG.error(
                "[numen-task] {} 在 {} 阶段抛出异常,本任务判失败(服务端不受影响)",
                getClass().getSimpleName(), phase, e);
        fail("the task hit an internal error and stopped: " + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " — " + e.getMessage())
                + ". Anything already built stays; this is a bug worth reporting.",
                FailureType.INTERNAL);
    }

    @Override
    public final TaskResult result(TaskState finalState) {
        cleanup();
        // 路上真动过的地形跟着每一种收场走:成功也好失败也罢,拆了什么就说什么;主人点过头的也说
        String enRoute = (journey.isEmpty() ? "" : " En route I had to " + journey.describe() + ".")
                + (allowances.isEmpty() ? "" : " " + String.join("; ", allowances) + ".");
        return switch (finalState) {
            case SUCCESS   -> TaskResult.ok(successMessage() + enRoute, resultData());
            case TIMEOUT   -> new TaskResult(false, timeoutMessage() + enRoute, true, false, resultData());
            case CANCELLED -> new TaskResult(false, cancelledMessage() + enRoute, false, true, resultData());
            default        -> TaskResult.fail(doneReason + enRoute, resultData());   // FAILED and any stray state
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
     * park a terminal FAILED for {@link #tick} to surface. Callers in
     * {@link #onTick()} pair this with {@code return TaskState.FAILED;}.
     */
    protected void fail(String why, FailureType t) {
        // 终局必须留声:任务凭什么收场是排障的第一现场,不能只活在返回值里
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] {} FAILED({}) {}",
                getClass().getSimpleName(), t, why);
        this.doneReason = why;
        this.failType = t;
        this.pendingTerminal = TaskState.FAILED;
    }

    /**
     * Park a terminal SUCCESS — the mirror of {@link #fail} for one-shot tasks whose
     * whole job happens in {@link #onStart()} (drop, equip): {@link #tick} surfaces
     * it, and {@link #start} stamps it on the record for same-tick finalization.
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

    /** Stop and forget the active nav (idempotent); its terrain ledger joins the task's journey. */
    protected void stopNav() {
        if (nav != null) {
            journey.addAll(nav.ledger());
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
     * precondition) is observed on the very first {@link #tick} — no special
     * "state after start" path is needed.
     *
     * @return the child's terminal {@link TaskState} on the tick it finishes (its
     *         structured failure, if it exposes one, is copied up so this task's
     *         {@link #lastFailure()} reflects the child's cause); or {@code null}
     *         while the child is still running.
     */
    protected TaskState runChild(Task c) {
        if (child != c) {
            child = c;
            childStarted = false;
        }
        if (!childStarted) {
            child.start(player);
            childStarted = true;
        }
        TaskState st = child.tick(player);
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
    public void stop(NumenPlayer companion, StopReason why) {
        // 被抢占:只松开身体(归零移动输入、放开潜行),<b>逻辑字段一个不动</b>——
        // 尤其是寻路计划,它正是下次拿回身体时能接着走的原因。不调 nav.stop()。
        // 被换掉/身体没了不需要额外收尾:buildResult 里的 cleanup() 会跑。
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }
}
