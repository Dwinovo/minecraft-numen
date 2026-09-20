package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.tools.AgentOps;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/** Client-local tool (raw NumenTool): read the body of one of the companion's own notes. */
public final class RecallTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentOps impl = new AgentOps();

    private record Args(String name) {}

    @Override
    public String name() {
        return "recall";
    }

    @Override
    public String description() {
        return """
                Read the body of one of your notes. The <memory> index already carries each \
                note's description, so only call this when that line points at more you need. \
                A note says what was true when you wrote it — the world may have moved on.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "The note's handle, exactly as it appears in <memory>.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            call.complete(impl.recall(call.ctx().entityUuid(), a.name()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
