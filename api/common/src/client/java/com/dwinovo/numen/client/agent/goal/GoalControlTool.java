package com.dwinovo.numen.client.agent.goal;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.task.TaskResult;

import java.util.Map;
import java.util.UUID;

/**
 * Client-local lifecycle control for the persistent goal card.
 *
 * <p>{@code todowrite} owns only the model's step list. This tool is the sole
 * agent-facing way to change the goal lifecycle, so a spoken claim of completion
 * cannot leave the timer running in the persisted {@link GoalState}.
 */
public final class GoalControlTool implements NumenTool {

    @Override
    public String name() {
        return "goal_control";
    }

    @Override
    public String description() {
        return """
                Control the current persistent goal lifecycle for this companion. This is separate from todowrite: todowrite only tracks steps and never ends the goal. After the requested work is verified complete, call this tool with action=complete before replying. Use action=pause when work is intentionally stopped for now, action=blocked with a concrete reason when progress cannot continue, action=resume to continue a paused/blocked/failed goal, action=cancel only when the goal is abandoned, and action=status to inspect it. Do not claim a goal is complete until this tool returns success.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "Lifecycle action: complete, pause, blocked, resume, cancel, or status.",
                        "complete", "pause", "blocked", "resume", "cancel", "status")
                .optionalString("reason", "Why the goal is blocked; required for action=blocked.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            String action = call.args().has("action")
                    ? call.args().get("action").getAsString() : "";
            String reason = call.args().has("reason") && !call.args().get("reason").isJsonNull()
                    ? call.args().get("reason").getAsString() : "";
            UUID entityUuid = call.ctx() == null ? null : call.ctx().entityUuid();
            if (entityUuid == null) {
                call.complete(TaskResult.fail("goal_control has no companion context").toJson());
                return;
            }
            EntityAgentLoop loop = AgentLoopRegistry.get(entityUuid).orElse(null);
            if (loop == null) {
                call.complete(TaskResult.fail("companion loop is not loaded").toJson());
                return;
            }
            call.complete(loop.applyGoalControl(action, reason));
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
