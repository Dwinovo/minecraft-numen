package com.dwinovo.numen.core.follow;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;

public final class FollowOwnerTool implements NumenTool {
    private static final long TIMEOUT_TICKS = 2_400L;

    @Override
    public String name() {
        return FollowOwnerTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Come to the owner's CURRENT position. Use this when the owner says "
            + "\u8fc7\u6765, \u56de\u5230\u6211\u8eab\u8fb9, \u5230\u6211\u8fd9\u91cc, "
            + "come here, or return to me. The target follows the owner's live position while travelling; do not "
            + "combine get_owner_status with goto for this request. The task finishes as soon as you are within a "
            + "4-block horizontal X/Z radius and on the same floor (feet Y differs by at most 1 block).";
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
        FollowOwnerTaskRecord task = new FollowOwnerTaskRecord(
            context.toolCallId(),
            context.deadline(TIMEOUT_TICKS)
        );
        TaskDispatch.dispatchAsync(player, task, reply);
    }
}
