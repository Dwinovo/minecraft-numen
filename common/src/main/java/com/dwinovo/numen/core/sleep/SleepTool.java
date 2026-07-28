package com.dwinovo.numen.core.sleep;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;

public final class SleepTool implements NumenTool {
    private static final long TIMEOUT_TICKS = 2_400L;

    @Override
    public String name() {
        return SleepTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Sleep in a nearby bed. Use this whenever the primary requested outcome is sleeping, resting, "
            + "going to bed, coming to sleep, laishuijiao, or qushuijiao. This task finds the bed and travels "
            + "there itself; do not call follow_owner, scan_blocks, goto, or interact_at first. It succeeds only "
            + "when the vanilla server confirms that you are actually sleeping, and otherwise returns the exact "
            + "rejection reason.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object().build();
    }

    @Override
    public void onServerCall(
        String toolCallId,
        JsonObject args,
        NumenPlayer player,
        Consumer<String> reply
    ) {
        ToolContext context = TaskDispatch.ctx(toolCallId, player);
        SleepTaskRecord task = new SleepTaskRecord(
            context.toolCallId(),
            context.deadline(TIMEOUT_TICKS)
        );
        TaskDispatch.dispatchAsync(player, task, reply);
    }
}
