package com.dwinovo.numen.core.task.base;


import com.dwinovo.numen.core.nav.Journey;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.BodyDelta;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

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
 *   <li>{@link #prepare} (before the work is accepted, see {@link Preparation}) runs the {@link #preconditions()} in
 *       order; the first that reports a {@link Precondition.Failure} refuses the call with its message, no task id,
 *       no task_finished. Then {@link #preparation()} judges the rest (world facts, a plan).</li>
 *   <li>{@link #start} runs the {@link #preconditions()} itself when nothing prepared the task (a synchronous action,
 *       a child sub-goal); the first failure terminates the task immediately (via {@link #fail}); otherwise
 *       {@link #onStart()} runs.</li>
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
    /** 正在走的那一趟路;没有为 null。挂在这里,{@link #stopNav()} / {@link #cleanup()} 才收得了它。 */
    protected Trip nav;
    /**
     * 本任务历次导航的实际账与身体动作(每一趟停下时并入)。回执末尾如实相告——不论成败、不论任务,"路上挖了什么、放了什么、
     * 身体做了什么"只在这一处说一次。
     */
    private final Journey journey = new Journey();

    /** Model-facing reason for a terminal FAILED; also the fallback result message. */
    private String doneReason = "done";
    /** Structured cause of the last failure, for a parent ladder to branch on. */
    private FailureType failType = FailureType.UNKNOWN;
    /** 最近一次 {@link #fail} 给的下一步;没给是 null。 */
    private String failHint;
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

    /** 开工那一刻的身体;收场时据此说这件活里有没有装备用坏。没开过工是 null。 */
    private BodyDelta bodyAtStart;

    /** 受理之前准备过,见 {@link #prepare}。 */
    private boolean prepared;

    /** 身体真在干活的刻数,见 {@link #workTicks()}。 */
    private long workTicks;
    /** 这一刻任务说它在等一次后台搜索({@link #awaitSearch});每刻开头清掉。 */
    private boolean awaitingSearch;

    // ---- sub-task composition state (see runChild) ----
    /** The child sub-goal currently being delegated to, or {@code null}. */
    private Task child;
    /** Whether {@link #child}'s {@code start()} has been called yet. */
    private boolean childStarted;
    /**
     * 征询记在谁名下:自己的任务记录;作为子活跑时是派它的那件活的(它收尾时一并清掉,主人答应过的对整件活都算)。
     */
    private Object consentScope;

    protected AbstractCompanionTask(NumenPlayer player, R record) {
        this.player = player;
        this.r = record;
    }

    // ---------------------------------------------------------------------
    // Lifecycle (final — the frozen contract)
    // ---------------------------------------------------------------------

    /**
     * 受理之前的准备:前置条件按顺序判,第一条不过就是这次调用的错误结果;都过了再交给 {@link #preparation()}。准备过的任务
     * 开工时不再判前置条件。
     */
    @Override
    public final Preparation prepare(NumenPlayer companion) {
        Precondition.Failure f = firstFailure();
        if (f != null) {
            // 前提不成立时这件活还没开始,没有进度可交:只有种类、那句话与下一步
            return Preparation.refused(TaskResult.fail(f.type().kind(), f.message(), f.hint()));
        }
        prepared = true;
        return preparation();
    }

    @Override
    public final void start(NumenPlayer companion) {
        Precondition.Failure f = prepared ? null : firstFailure();
        if (f != null) {
            fail(f.message(), f.type(), f.hint());
            r.setState(TaskState.FAILED);   // same-tick finalization (old dispatcher semantics)
            return;
        }
        bodyAtStart = BodyDelta.open(player);
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
        awaitingSearch = false;
        TaskState state;
        try {
            TaskState routeConsent = awaitRouteConsent();
            state = routeConsent != null ? routeConsent : onTick();
        } catch (RuntimeException e) {
            crashed("tick", e);
            return TaskState.FAILED;
        }
        // 这一刻身体在等就不算干活:期限往后推一刻(与调度层被生存链抢占时的 freeze 同一原则),
        // 干活的刻数不走。子活在跑时它按自己的期限走,这件活的期限同样冻住
        if (waiting() || child != null) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
        } else {
            workTicks++;
        }
        return state;
    }

    /**
     * 身体真在干活的刻数——任务里一切"干了多久"的量尺:超时、卡死判定、重算间隔都拿它量,不自己数刻。
     *
     * <p>等的刻不算:导航在等规划、在等主人答复、任务说它在等一次后台搜索。这些刻有多少取决于机器快慢
     * (搜索在后台线程上、按每刻的时间上限分摊,花的是真实时间)和主人,而刻数跑得比真实时间快多少并不
     * 固定——tick 远快于真实时间时(/tick rate、不限速的测试服),拿游戏刻去量等待,预算会在第一次搜索
     * 返回前就烧光。拿这把尺子量,结论只看身体干了多少活。任务期限也是这样冻结的。
     */
    protected final long workTicks() {
        return workTicks;
    }

    /** 这一刻身体站着等一次后台搜索(找方块、定位)出结论:不算干活,期限不走。每刻要等就每刻调。 */
    protected final void awaitSearch() {
        awaitingSearch = true;
    }

    /** 身体这一刻在等,不在干活。见 {@link #workTicks()}。 */
    private boolean waiting() {
        return (nav != null && nav.waiting()) || consent != null || awaitingSearch;
    }

    /** 征询记在谁名下,见 {@link #consentScope}。 */
    private Object scope() {
        return consentScope != null ? consentScope : r;
    }

    /**
     * 导航走到一格要问主人的地方停下时,这一刻归征询:身体站住、问主人;答应了那一格放行、接着跑
     * {@link #onTick},拒绝了按 {@link FailureType#REFUSED} 收场,下一步由 {@link #refusedHint} 给。任何带导航的任务都一样,
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
        player.controls().stop();
        ConsentAnswer answer = consult(needed);
        if (answer == null) {
            return TaskState.RUNNING;
        }
        if (!answer.allowed()) {
            fail(answer.refusal(needed), FailureType.REFUSED, refusedHint(needed));
            return TaskState.FAILED;
        }
        nav.consentGranted();
        return null;
    }

    /** 主人不答应路上那一格时,能照抄的下一步;没有为 null。 */
    protected String refusedHint(List<ConsentItem> refused) {
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
     */
    protected final Permit permit(Action action) {
        return permitAll(List.of(action)).get(0);
    }

    /**
     * 同 {@link #permit},一批动作一起:各自裁决,要问的合成一次征询,主人的答复对这一批里要问的
     * 全部生效。结果与 {@code actions} 一一对应。
     */
    protected final List<Permit> permitAll(List<Action> actions) {
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
        ConsentAnswer answer = consult(asks);
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
    protected final ConsentAnswer consult(List<ConsentItem> items) {
        if (consent != null && !consent.request().items().equals(items)) {
            consent = null;
        }
        if (consent == null) {
            consent = ConsentDesk.of(player).ask(scope(), items);
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
        if (child != null) {
            endChild(TaskState.CANCELLED);
        }
        cleanup();
        // 路上真动过的地形跟着每一种收场走:成功也好失败也罢,挖了什么、放了什么就说什么;主人点过头的也说
        String travelled = journey.describe();
        String broken = bodyAtStart == null ? "" : bodyAtStart.broken(player);
        String enRoute = (travelled.isEmpty() ? "" : " " + travelled)
                + (allowances.isEmpty() ? "" : " " + String.join("; ", allowances) + ".")
                + (broken.isEmpty() ? "" : " " + broken);
        return outcome(finalState, enRoute);
    }

    /** 收场的回执:按终态取那一句,后面接上 {@code tail}。 */
    private TaskResult outcome(TaskState finalState, String tail) {
        return switch (finalState) {
            case SUCCESS   -> TaskResult.ok(successMessage() + tail, value());
            case TIMEOUT   -> TaskResult.timeout(timeoutMessage() + tail, value());
            case CANCELLED -> TaskResult.cancelled(cancelledMessage() + tail, value());
            default        -> TaskResult.fail(failType.kind(), doneReason + tail, failHint, value());
        };
    }

    // ---------------------------------------------------------------------
    // Hooks (override the ones a concrete task needs)
    // ---------------------------------------------------------------------

    /** Ordered gates checked before the work begins; the first {@link Precondition.Failure} wins. Default: none. */
    protected List<Precondition> preconditions() {
        return List.of();
    }

    /** 第一条不过的前置条件;都过了为 null。 */
    private Precondition.Failure firstFailure() {
        for (Precondition p : preconditions()) {
            Precondition.Failure f = p.check();
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /**
     * 前置条件之后、受理之前还要判的:世界事实、一次只搜不走的规划(见 {@link Preparation})。结论就绪时查到的东西存在
     * 任务上,开工({@link #onStart()})接着用;准备得出的错误结果与开工后才冒出来时说的是同一句话。默认当场就绪。
     */
    protected Preparation preparation() {
        return Preparation.READY;
    }

    /** 这件活受理之前准备过({@link #prepare}):开工时准备查到的东西已经在任务上了。 */
    protected final boolean prepared() {
        return prepared;
    }

    /** First-tick setup (build the nav, snapshot baselines, …). Default: no-op. */
    protected void onStart() {}

    /** Advance one tick; return {@link TaskState#RUNNING} or a terminal state. */
    protected abstract TaskState onTick();

    /** Release physical resources on termination. Default: stop nav + clear the path overlay. */
    protected void cleanup() {
        stopNav();
    }

    /**
     * 交给程序的值:类型是派它的 API 函数声明的返回类型({@code Job<R>} 的 {@code R});失败时它是错误值的 {@code data}(做到了哪)。
     * 不交回值是 null(默认)。
     */
    protected Object value() {
        return null;
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
        fail(why, t, null);
    }

    /** 同上,另给脚本一个能照抄的下一步({@link TaskResult#hint}),比如走过去的那一行。 */
    protected void fail(String why, FailureType t, String hint) {
        // 终局必须留声:任务凭什么收场是排障的第一现场,不能只活在返回值里
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] {} FAILED({}) {}",
                getClass().getSimpleName(), t, why);
        this.doneReason = why;
        this.failType = t;
        this.failHint = hint;
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

    /**
     * 这件活替目标之外挖掉的一格记进旅程账(比如为了拉出射线挖掉的遮挡物)。回执末尾和导航挖的一起交代,
     * {@link #brokeOnTheWay} 也认它。
     */
    protected final void recordAction(com.dwinovo.numen.pathing.body.BodyAction action) {
        journey.did(action);
    }

    protected final void recordBreak(com.dwinovo.numen.core.act.BlockDigger.Broken broken) {
        journey.dug(broken.pos(), broken.was());
    }

    /**
     * 这一格是她这件活里顺路挖掉的吗:历次导航与 {@link #recordBreak} 记下的旅程账,加上还在跑的这条导航的账。
     * 账本是"她挖了什么"的唯一出处,任务要分清"她挖的"和"别人动的"时问这里。
     */
    protected final boolean brokeOnTheWay(BlockPos pos) {
        return soFar().broke(pos);
    }

    /** 旅程账加上还在走的这一趟。 */
    private Journey soFar() {
        return nav == null ? journey : journey.plus(nav.reports());
    }

    /** 叫停并放下在走的这一趟(可重复调);它的实际账并进旅程账。 */
    protected void stopNav() {
        if (nav != null) {
            for (Report report : nav.stop()) {
                journey.add(report);
            }
            nav = null;
        }
    }

    // ---------------------------------------------------------------------
    // Sub-task composition — one of the two recovery-boundary primitives
    // ---------------------------------------------------------------------

    /**
     * Delegate this tick to a child {@link Task} representing a bounded
     * SUB-goal, driving its {@code start → tick} lifecycle for the parent.
     *
     * <p>Re-invoking with the SAME child instance continues it; passing a
     * different instance switches to (and starts) the new child. The child's
     * {@code start()} is called lazily on its first tick here, so if the child is
     * itself an {@link AbstractCompanionTask} a start-time terminal (a failed
     * precondition) is observed on the very first {@link #tick} — no special
     * "state after start" path is needed.
     *
     * <p>子活是这件活的一部分:它问主人的记在这件活名下;它跑的时候这件活的期限冻住,它按自己记录上的期限走,到了就按超时
     * 收场;它收场时它的实际账(路上挖的放的、身体做的)与主人点过的头并进这件活,由这件活收场时说一次——所以交回的那句话
     * 不带它自己的路上那一段。
     *
     * @return 子活收场的那一刻交回它的回执(它的失败类型抄上来,{@link #lastFailure()} 说的就是它的原因);还在跑是 null
     */
    protected TaskResult runChild(Task c) {
        if (child != c) {
            child = c;
            childStarted = false;
            if (c instanceof AbstractCompanionTask<?> a) {
                a.consentScope = scope();
            }
        }
        if (!childStarted) {
            child.start(player);
            childStarted = true;
        }
        TaskState st = child.tick(player);
        if (!st.isTerminal() && child instanceof AbstractCompanionTask<?> a
                && player.level().getGameTime() >= a.r.getDeadlineGameTime()) {
            st = TaskState.TIMEOUT;
        }
        return st.isTerminal() ? endChild(st) : null;
    }

    /** 子活收场:它自己收尾,账并进这件活,交回它不带路上那一段的回执。 */
    private TaskResult endChild(TaskState st) {
        Task ended = child;
        child = null;
        childStarted = false;
        if (!(ended instanceof AbstractCompanionTask<?> a)) {
            return ended.result(st);
        }
        a.cleanup();
        this.failType = a.lastFailure();
        journey.add(a.journey);
        allowances.addAll(a.allowances);
        return a.outcome(st, "");
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
        player.controls().releaseAll();
    }

    @Override
    public String name() {
        return getClass().getSimpleName();
    }
}
