package com.dwinovo.numen.task;
import com.dwinovo.numen.task.TaskResult;

import java.util.concurrent.atomic.AtomicLong;

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
    private final long id;
    /** Stable name of the originating tool (matches {@code NumenTool.name()}). */
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
    /** 异步派发的记录:受理时已经回执过 tool_call,收尾改走 task_finished 事件。 */
    private boolean async;
    /** 首次进入 RUNNING 的游戏刻;task_status 用它报已耗时。-1 = 还没开跑。 */
    private long startedGameTime = -1;

    protected TaskRecord(String toolName, String toolCallId, long deadlineGameTime) {
        this.id = ID_SOURCE.incrementAndGet();
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.deadlineGameTime = deadlineGameTime;
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

    /** Called by {@code CompanionTickDispatcher} as the record transitions through lifecycle. */
    public final void setState(TaskState state) { this.state = state; }
    public final void setResult(TaskResult result) { this.result = result; }

    /**
     * Short human-readable description for the {@code /numen debug} head
     * overlay. Defaults to the tool name; subclasses override to append their
     * salient parameters (e.g. {@code MoveToTaskRecord} adds the target coords).
     */
    public String describe() {
        return toolName;
    }
}
