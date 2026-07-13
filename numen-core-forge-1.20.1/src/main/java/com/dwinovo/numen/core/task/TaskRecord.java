package com.dwinovo.numen.core.task;
import com.dwinovo.numen.task.TaskResult;

import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

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
    private TaskFailureCode failureCode;
    private String failureDetail;
    private int recoveryAttempts;
    /** Canonical/original tool arguments, captured at the server dispatch boundary. */
    private String argumentsJson = "{}";
    /** True only for records reconstructed from task-state.json after a server restart. */
    private boolean restored;
    /** Distinguishes the former active head from records that were only queued. */
    private boolean activeBeforeRestart;
    /** Server-authoritative inventory totals captured immediately before execution. */
    private Map<String, Integer> inventoryBefore = Map.of();
    private long individuallyPausedAt = -1L;
    private long uiStartedGameTime = -1L;
    private int uiStartedProgress;
    private String uiBlocker = "";

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
    public final TaskFailureCode getFailureCode() { return failureCode; }
    public final String getFailureDetail() { return failureDetail; }
    public final int getRecoveryAttempts() { return recoveryAttempts; }
    public final String getArgumentsJson() { return argumentsJson; }
    public final boolean wasRestored() { return restored; }
    public final boolean wasActiveBeforeRestart() { return activeBeforeRestart; }
    public final Map<String, Integer> getInventoryBefore() { return inventoryBefore; }
    public final long getIndividuallyPausedAt() { return individuallyPausedAt; }
    public final long getUiStartedGameTime() { return uiStartedGameTime; }
    public final int getUiStartedProgress() { return uiStartedProgress; }
    public final String getUiBlocker() { return uiBlocker; }

    /** Shift this record's absolute game-time deadline after a scheduler pause. */
    public final void shiftDeadlineBy(long ticks) {
        if (ticks > 0L && deadlineGameTime <= Long.MAX_VALUE - ticks) {
            deadlineGameTime += ticks;
        }
    }

    /** Push the deadline later (never earlier). Tick-thread only, like all reads. */
    public final void extendDeadlineTo(long gameTime) {
        if (gameTime > deadlineGameTime) deadlineGameTime = gameTime;
    }

    /** Called by {@code CompanionTickDispatcher} as the record transitions through lifecycle. */
    public final void setState(TaskState state) { this.state = state; }
    public final void pauseAt(long gameTime) { individuallyPausedAt = gameTime; state = TaskState.PAUSED; }
    public final void resumeAt(long gameTime) { if (individuallyPausedAt >= 0) shiftDeadlineBy(Math.max(0, gameTime - individuallyPausedAt)); individuallyPausedAt = -1; state = TaskState.PENDING; }
    public final void setResult(TaskResult result) { this.result = result; }

    public final void markFailure(TaskFailureCode code, String detail) {
        this.failureCode = code;
        this.failureDetail = detail;
    }

    public final void clearFailure() {
        this.failureCode = null;
        this.failureDetail = null;
    }

    public final void setRecoveryAttempts(int recoveryAttempts) {
        this.recoveryAttempts = Math.max(0, recoveryAttempts);
    }

    public final void setArgumentsJson(String argumentsJson) {
        this.argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    public final void setInventoryBefore(Map<String, Integer> inventoryBefore) {
        this.inventoryBefore = inventoryBefore == null ? Map.of() : Map.copyOf(inventoryBefore);
    }

    public final void markUiStarted(long gameTime, int progress) {
        if (uiStartedGameTime < 0L) {
            uiStartedGameTime = gameTime;
            uiStartedProgress = Math.max(0, progress);
        }
        uiBlocker = "";
    }

    public final void setUiBlocker(String blocker) {
        uiBlocker = blocker == null ? "" : blocker;
    }

    /** Reset runtime-only lifecycle when a persisted record is reconstructed. */
    public final void markRestored(boolean wasActive) {
        restored = true;
        activeBeforeRestart = wasActive;
        state = TaskState.PENDING;
        individuallyPausedAt = -1L;
        result = null;
        clearFailure();
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
