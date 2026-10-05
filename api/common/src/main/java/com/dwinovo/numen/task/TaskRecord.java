package com.dwinovo.numen.task;

import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.sdk.ApiFunction;
import com.dwinovo.numen.sdk.ServerCall;

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
     * 这件活叫什么:派它的那个 API 函数的全名({@link ServerCall#fn()},{@code numen.work.dig})。{@code task_finished}、
     * {@code <current_task>} 都写它。
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
    /** 首次进入 RUNNING 的游戏刻;task status 用它报已耗时。-1 = 还没开跑。 */
    private long startedGameTime = -1;
    /**
     * 同步动作的回信口:派它的那次调用给的({@link TaskDispatch#runSync} 绑上),结算后的结果只从这里回。异步的活受理时
     * 已经回过编号,收尾走 task_finished,没有它。
     */
    private Consumer<TaskResult> reply;
    /** 派它的 API 函数:收尾时的值按它的返回类型写;不出自 API 函数的活(测试直接交的、本能)是 null。 */
    private ApiFunction function;

    protected TaskRecord(String toolName, String toolCallId, long deadlineGameTime) {
        this.id = ID_SOURCE.incrementAndGet();
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.deadlineGameTime = deadlineGameTime;
    }

    /** 一次 API 调用派下的活:名字是那个函数的全名,调用 id 是那次调用的。 */
    protected TaskRecord(ServerCall call, long deadlineGameTime) {
        this(call.fn(), call.callId(), deadlineGameTime);
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

    void replyTo(Consumer<TaskResult> reply) { this.reply = reply; }
    Consumer<TaskResult> reply() { return reply; }

    /** 派它的是 {@code fn}:收尾时的值按它的返回类型写。派发受理时绑上。 */
    public final void calledAs(ApiFunction fn) { this.function = fn; }

    /** 派它的 API 函数;不出自 API 函数的是 null。 */
    public final ApiFunction function() { return function; }
    public final boolean isAsync() { return async; }

    /** 首次开跑打点(重复调用不覆盖——抢占恢复不算重新开始)。 */
    public final void markStarted(long gameTime) {
        if (startedGameTime < 0) startedGameTime = gameTime;
    }
    public final long getStartedGameTime() { return startedGameTime; }

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
        TASK_STOP("you stopped it with numen.task.stop", ConsentDesk.Withdrawal.TASK_ENDED),
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
