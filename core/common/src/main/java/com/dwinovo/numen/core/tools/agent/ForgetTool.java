package com.dwinovo.numen.core.tools.agent;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.core.tools.AgentOps;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.Map;

/**
 * Client-local tool (raw NumenTool): drop one of the companion's own notes.
 *
 * <p>整理札记是她自己的事:没有淘汰、没有过期回收,条数到顶也只是在索引上摆出余量。
 * 要删就得她自己开口——所以这个动词必须存在,否则"她自己整理"是句空话。
 */
public final class ForgetTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentOps impl = new AgentOps();

    private record Args(String name) {}

    @Override
    public String name() {
        return "forget";
    }

    @Override
    public String description() {
        return """
                Drop one of your notes for good. Use it when a note turned out wrong, or when \
                you merged several into one. Nothing else ever removes a note.""";
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
            call.complete(impl.forget(call.ctx().entityUuid(), a.name()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
