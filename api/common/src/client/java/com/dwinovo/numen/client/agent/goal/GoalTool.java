package com.dwinovo.numen.client.agent.goal;

import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.agent.goal.GoalPrompts;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;

/**
 * 模型这一侧的目标口子。
 *
 * <h2>她只能做两件事:报完成、报卡住</h2>
 * 暂停、恢复、换目标、清掉——<b>只有主人能做</b>,走 {@code /goal}。这条界线是有意的:
 * 目标是主人下的,模型可以说"做完了"或者"我过不去",但不能自己改主人要什么。
 *
 * <p>客户端本地工具:目标状态住在客户端的 loop 里,不走网络。
 */
public final class GoalTool implements NumenTool {

    @Override
    public String name() {
        return "goal";
    }

    @Override
    public String description() {
        return """
                Read or update the active long-term goal. You may only report it "complete" or \
                "blocked" — replacing or clearing a goal is the owner's call via /goal.

                Use action=get to read the current objective and progress.
                Use status=complete only after the Completion Audit in the goal-steering block passes; \
                the goal is then cleared.
                Use status=blocked with a concrete reason when no path forward exists. Reporting the \
                same reason three times in a row gives the goal up and tells the owner why.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action",
                        "\"get\" to read status, \"update\" to report completion or being blocked. "
                                + "Defaults to update when status is given, otherwise get.",
                        "get", "update")
                .optionalEnum("status",
                        "Required for update. Only \"complete\" or \"blocked\" are accepted.",
                        "complete", "blocked")
                .optionalString("reason", "Why. Required for update.")
                .build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            JsonObject args = call.args();
            String status = str(args, "status");
            String action = str(args, "action");
            if (action.isEmpty()) {
                action = status.isEmpty() ? "get" : "update";
            }
            UUID uuid = call.ctx() == null ? null : call.ctx().entityUuid();
            EntityAgentLoop loop = uuid == null ? null : AgentLoopRegistry.get(uuid).orElse(null);
            if (loop == null) {
                call.complete(fail("no companion context"));
                return;
            }
            GoalState goal = loop.goal();
            if (goal == null) {
                call.complete(fail("there is no active goal"));
                return;
            }
            if ("get".equals(action)) {
                call.complete(report(goal, "").toString());
                return;
            }
            call.complete(update(loop, goal, status, str(args, "reason")));
        } catch (RuntimeException ex) {
            call.complete(fail(String.valueOf(ex.getMessage())));
        }
    }

    private static String update(EntityAgentLoop loop, GoalState goal, String status, String reason) {
        if (reason.isBlank()) {
            return fail("reason is required when updating a goal");
        }
        switch (status) {
            case "complete" -> {
                JsonObject done = report(goal, "goal complete — it has been cleared");
                loop.clearGoal("做完了:" + reason);
                return done.toString();
            }
            case "blocked" -> {
                // 一次挡住不算卡住:换个法子还能继续,每次都停下来问主人才是烦人。
                if (!goal.reportBlocked(reason)) {
                    loop.goalChanged();
                    return report(goal, "noted; keep trying other approaches").toString();
                }
                JsonObject gaveUp = report(goal, "goal given up — the owner has been told why");
                loop.clearGoal("卡住了:" + reason);
                return gaveUp.toString();
            }
            default -> {
                return fail("status must be \"complete\" or \"blocked\"");
            }
        }
    }

    private static JsonObject report(GoalState goal, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("objective", goal.objective());
        o.addProperty("elapsed", GoalPrompts.elapsed(goal.elapsedMs(System.currentTimeMillis())));
        o.addProperty("turnsExecuted", goal.turnsExecuted());
        o.addProperty("tokensUsed", goal.tokensUsed());
        if (!message.isBlank()) {
            o.addProperty("message", message);
        }
        return o;
    }

    private static String fail(String error) {
        JsonObject o = new JsonObject();
        o.addProperty("success", false);
        o.addProperty("error", error == null ? "" : error);
        return o.toString();
    }

    private static String str(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull()
                ? args.get(key).getAsString().trim() : "";
    }
}
