package com.dwinovo.numen.task;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.task.TaskResult;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Mutable descriptor of an in-flight task. The {@link com.dwinovo.numen.agent.tool.NumenTool tool layer}
 * builds one record per LLM {@code tool_call} and enqueues it;
 * {@code CompanionTickDispatcher} picks it up (running the matching
 * {@link CompanionTask}), drives lifecycle, and writes a
 * {@link TaskResult} back before completion.
 *
 * <h2>Type pattern</h2>
 * Concrete subclasses (e.g. {@code MoveToTaskRecord}) carry the typed input
 * parameters as final fields. {@link CompanionTaskFactory} dispatches the queue
 * head against the registered record types — no reflection at runtime, just one
 * {@code instanceof} check per record at the dispatch boundary.
 *
 * <h2>Threading</h2>
 * Records are constructed off-tick (in the LLM async callback) and read on
 * the server tick thread. The "construct off-tick" is followed by a hop
 * through {@code server.execute(...)} into the tick thread before the record
 * is enqueued, so the happens-before is established by the executor's queue —
 * no fields need to be {@code volatile}.
 *
 * <h2>Why not a record (Java {@code record} keyword)</h2>
 * State transitions ({@link TaskState}, {@link TaskResult}) need to be
 * mutable. Subclass-style {@code class} fits.
 */
public abstract class TaskRecord {

    private static final AtomicLong ID_SOURCE = new AtomicLong();

    /** Monotonically increasing internal id; only used for logging / dedup. */
    /**
     * 常驻任务的"期限":一个永远不会到的游戏刻。
     *
     * <p>期限回答的是"这件活该多久干完",而常驻任务<b>没有干完</b>——给它一个真实的
     * 期限就是给它安排一次注定的超时。用 {@code MAX_VALUE/2} 而不是 {@code MAX_VALUE}:
     * 被抢占时期限会 +1(见 {@code TaskSlot.freeze}),留出余量免得溢出成负数。
     */
    public static final long NO_DEADLINE = Long.MAX_VALUE / 2;

    private final long id;
    /**
     * 这件活叫什么:模型调的那个东西——工具派的是工具名({@code NumenTool.name()}),命令派的是
     * {@link ServerSource#taskName()}(快捷工具名,或"组 动作")。回执、{@code task_finished}、{@code <current_task>} 都写它。
     */
    private final String toolName;
    /**
     * The {@code id} field from the LLM's {@code tool_call} — must be echoed
     * verbatim in the {@code tool_call_id} of the role:tool response, or the
     * upstream API responds 400.
     */
    private final String toolCallId;
    /**
     * Game-tick (level.getGameTime()) at which this record times out. Stamped
     * at construction (gameTime is freeze-aware, so {@code /tick freeze} /
     * {@code /tick rate} are accounted for automatically); a goal whose real
     * budget depends on world state only known at start may push it later via
     * {@link #extendDeadlineTo} (e.g. move_to scales with journey distance —
     * the tool layer can't know that, it has no entity position).
     */
    private long deadlineGameTime;

    private TaskState state = TaskState.PENDING;
    private TaskResult result;
    /** 从外面叫停的是谁;任务自己走到 CANCELLED(比如她死了)或没被叫停时为 null。 */
    private StopCause stopCause;
    /** 异步派发的记录:受理时已经回执过 tool_call,收尾改走 task_finished 事件。 */
    private boolean async;
    /** 首次进入 RUNNING 的游戏刻;task_status 用它报已耗时。-1 = 还没开跑。 */
    private long startedGameTime = -1;
    /**
     * 同步动作的回信口:派它的那次调用给的({@link TaskDispatch#runSync} 绑上),结算后的结果只从这里回——模型的调用、
     * {@code /numen drive} 的发令人、主人点过头的调用(回执末尾交代允许了什么)各自拿到自己的那一份。异步的活受理时
     * 已经回执过,收尾走 task_finished,没有它。
     */
    private Consumer<String> reply;

    protected TaskRecord(String toolName, String toolCallId, long deadlineGameTime) {
        this.id = ID_SOURCE.incrementAndGet();
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.deadlineGameTime = deadlineGameTime;
    }

    /** 命令派下的活:名字与调用 id 都取自这次调用的源,交给 {@link TaskDispatch#setTask(ServerSource, TaskRecord)}。 */
    protected TaskRecord(ServerSource source, long deadlineGameTime) {
        this(source.taskName(), source.toolCallId(), deadlineGameTime);
    }

    public final long getId() { return id; }
    public final String getToolName() { return toolName; }
    public final String getToolCallId() { return toolCallId; }
    public final long getDeadlineGameTime() { return deadlineGameTime; }
    public final TaskState getState() { return state; }
    public final TaskResult getResult() { return result; }

    /** Push the deadline later (never earlier). Tick-thread only, like all reads. */
    public final void extendDeadlineTo(long gameTime) {
        if (gameTime > deadlineGameTime) deadlineGameTime = gameTime;
    }

    /** LLM 可见的短任务号——受理回执、current_task、task_finished 事件三处共用。 */
    public final String publicId() { return "t" + id; }

    public final void markAsync() { this.async = true; }

    void replyTo(Consumer<String> reply) { this.reply = reply; }
    Consumer<String> reply() { return reply; }
    public final boolean isAsync() { return async; }

    /**
     * Prefix of the synthetic tool-call ids NumenActuator mints for external (MCP)
     * invocations — disjoint from the LLM's ids. The async wind-down keys off this
     * to route completion: internal tasks fire a task_finished event to the built-in
     * brain; external ones don't (their driver polls task_status instead).
     */
    public static final String EXTERNAL_CALL_PREFIX = "mcp-";

    /** True if an external brain (MCP) dispatched this task, not the built-in LLM. */
    public final boolean isExternalCall() {
        return toolCallId != null && toolCallId.startsWith(EXTERNAL_CALL_PREFIX);
    }

    /** 首次开跑打点(重复调用不覆盖——抢占恢复不算重新开始)。 */
    public final void markStarted(long gameTime) {
        if (startedGameTime < 0) startedGameTime = gameTime;
    }
    public final long getStartedGameTime() { return startedGameTime; }

    /**
     * 受理它的那一刻还没过去。
     *
     * <p>用来分开两种"再派一个活":同一批工具调用里的第二个(模型在做计划,该拒绝
     * ——让它拿到第一个的结果再决定),和新回合里派的(改主意了,该直接替换)。
     *
     * <p><b>问的是记录多老,不是任务跑了多少刻。</b>任务会休眠:{@code follow} 在主人
     * 身边时不占身体,槽轮不到 tick,"跑过几刻"就一直是 0——拿它当判据的话,一个跟了你
     * 十分钟的跟随任务会始终自称"刚受理",你让她顺手捡个掉落物都会被拒。
     */
    public final boolean acceptedThisTick(long gameTime) {
        return startedGameTime >= 0 && gameTime <= startedGameTime;
    }

    /** Called by {@code CompanionTickDispatcher} as the record transitions through lifecycle. */
    public final void setState(TaskState state) { this.state = state; }
    public final void setResult(TaskResult result) { this.result = result; }

    /**
     * 从外面叫停这件活。叫停的人写进结算结果({@code TaskSlot} 结算时统一加在消息前面):模型分得清是主人按了
     * 停止、它自己调了 task_stop、还是被新派的活顶掉——任务本身不知道谁叫停的它,这一句只能记在记录上。
     * 已经走到终态的不改。
     */
    public final void stop(StopCause cause) {
        if (!state.isTerminal()) {
            state = TaskState.CANCELLED;
            stopCause = cause;
        }
    }

    public final StopCause getStopCause() { return stopCause; }

    /**
     * 谁叫停的这件活:模型读到的那句话,以及它挂着的征询因此撤回时主人看到的原因。叫停一件活和叫停一条等着主人点头的
     * 指令是同一件事,两处都从这里取。
     */
    public enum StopCause {
        OWNER("the owner pressed Stop", ConsentDesk.Withdrawal.OWNER_STOPPED),
        TASK_STOP("you stopped it with task_stop", ConsentDesk.Withdrawal.TASK_ENDED),
        COMMAND("stopped by a /numen command", ConsentDesk.Withdrawal.TASK_ENDED),
        REPLACED("a newer body action replaced it", ConsentDesk.Withdrawal.TASK_ENDED),
        BODY_LEFT("the body left the world", ConsentDesk.Withdrawal.BODY_LEFT);

        private final String words;
        private final ConsentDesk.Withdrawal withdrawal;

        StopCause(String words, ConsentDesk.Withdrawal withdrawal) {
            this.words = words;
            this.withdrawal = withdrawal;
        }

        public String words() {
            return words;
        }

        /** 被叫停的这一方挂着的征询因此撤回,主人看到的原因。 */
        public ConsentDesk.Withdrawal withdrawal() {
            return withdrawal;
        }
    }

    /**
     * Short human-readable description for the {@code /numen debug} head
     * overlay. Defaults to the tool name; subclasses override to append their
     * salient parameters (e.g. {@code MoveToTaskRecord} adds the target coords).
     */
    public String describe() {
        return toolName;
    }
}
