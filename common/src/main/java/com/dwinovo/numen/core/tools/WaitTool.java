package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.task.TaskRecord;

import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.core.task.WaitTaskRecord;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * SAMPLE (raw-NumenTool style, no @NumenAction). A world-action tool: parse args
 * with Gson, build a TaskRecord, hand it to core's per-companion queue; the
 * result returns later via the task lifecycle. Schema is written explicitly.
 */
public final class WaitTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_SECONDS = 60;
    private static final long DEADLINE_MARGIN_TICKS = 40L;

    /** The model-facing arguments — Gson fills this straight from the JSON. */
    private record Args(int seconds, String reason) {}

    @Override
    public String name() {
        return "wait";
    }

    @Override
    public String description() {
        return "Set a timer: stay put for the given number of seconds. Use it when the next "
                + "step depends on time passing (a furnace batch, nightfall, an owner who said "
                + "\"wait here\"). Max " + MAX_SECONDS + "s per call — re-dispatch after re-checking "
                + "for longer waits. BACKGROUND task: returns a task_id at once and the elapsed "
                + "timer arrives as a task_finished event — you can keep chatting meanwhile.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("seconds", "How long to wait, in real seconds (1-" + MAX_SECONDS + ").", 1, MAX_SECONDS)
                .optionalString("reason", "Optional: why you're waiting (shown on the debug overlay).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        int seconds = Math.clamp(a.seconds(), 1, MAX_SECONDS);
        String reason = a.reason() != null ? a.reason() : "";
        long deadline = companion.level().getGameTime() + seconds * 20L + DEADLINE_MARGIN_TICKS;
        // 异步定时器:受理即回执,到点的 task_finished 事件就是闹钟响——等待期间可以聊天。
        dispatchAsync(companion, new WaitTaskRecord(toolCallId, deadline, seconds, reason), reply);
    }
}
