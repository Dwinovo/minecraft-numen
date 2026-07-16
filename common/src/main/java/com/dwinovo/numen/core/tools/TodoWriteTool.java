package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;

import java.util.List;
import java.util.Map;

/** Client-local tool (raw NumenTool): maintain the agent's structured task list. */
public final class TodoWriteTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AgentTools impl = new AgentTools();

    private record Args(List<AgentTools.Todo> todos) {}

    @Override
    public String name() {
        return "todowrite";
    }

    @Override
    public String description() {
        return """
                Maintain a structured todo list for multi-step work (3+ distinct steps or several sub-tasks); skip it for single actions and chit-chat. Each call replaces the whole list. Keep exactly ONE item in_progress while work remains; mark completed only after the work is actually done (never on intent), in real time rather than batched; if blocked, keep it in_progress and add a follow-up describing the blocker.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .objectArray("todos", "The complete updated todo list (replaces the previous one).", item -> item
                        .string("content", "Brief description of the step.")
                        .enumStr("status", "One of: pending, in_progress, completed, cancelled.",
                                "pending", "in_progress", "completed", "cancelled")
                        .enumStr("priority", "One of: high, medium, low.", "high", "medium", "low"))
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            Args a = GSON.fromJson(call.rawArgs(), Args.class);
            call.complete(impl.todowrite(a.todos()));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
