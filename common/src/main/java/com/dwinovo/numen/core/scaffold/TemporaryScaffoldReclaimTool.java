package com.dwinovo.numen.core.scaffold;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.function.Consumer;

public final class TemporaryScaffoldReclaimTool implements NumenTool {
    private static final long TIMEOUT_TICKS = 1_200L;

    @Override
    public String name() {
        return TemporaryScaffoldReclaimTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Reclaim navigation-created temporary scaffolds using only exact coordinates recorded in "
            + "this companion's temporary-scaffold ledger. It rechecks current path use, support, landing "
            + "hazards, fall safety, and reach before every removal, and reports each retained block with "
            + "its reason. Never use mine for this: mine searches by block type and may target unrelated "
            + "terrain or construction. BACKGROUND task: completion arrives in task_finished.";
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
        TaskDispatch.dispatchAsync(
            player,
            new TemporaryScaffoldReclaimTaskRecord(
                context.toolCallId(),
                context.deadline(TIMEOUT_TICKS)
            ),
            reply
        );
    }
}
