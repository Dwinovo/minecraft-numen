package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.api.Arg;
import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent-side (client-local) tools authored on the {@link NumenAction} surface.
 * These run on the agent thread with no server body — the adapter infers LOCAL
 * because the method takes neither the live entity nor a reply callback and
 * returns its result directly.
 */
public final class AgentTools {

    @NumenAction(name = "load_skill", description = """
            Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

            Use this tool to inject the skill's instructions and resources into the current conversation. The output contains detailed workflow guidance for the task.

            The skill name must match one of the skills listed in your system prompt's <available_skills> block.""")
    public String loadSkill(@Arg("The skill name from <available_skills> in the system prompt.") String name) {
        SkillRegistry registry = SkillRegistry.instance();
        var maybe = registry.get(name);
        if (maybe.isEmpty()) {
            String available = registry.all().stream()
                    .map(SkillInfo::name)
                    .map(AgentTools::quote)
                    .collect(Collectors.joining(","));
            return "{\"success\":false,\"error\":\"unknown skill: " + escapeJson(name)
                    + "\",\"available\":[" + available + "]}";
        }

        SkillInfo info = maybe.get();
        // Match opencode's <skill_content name="X"># Skill: X\n{content}\n</skill_content>
        StringBuilder out = new StringBuilder(info.content().length() + 128);
        out.append("<skill_content name=\"").append(escapeXmlAttr(info.name())).append("\">\n");
        out.append("# Skill: ").append(info.name()).append("\n\n");
        out.append(info.content().trim());
        out.append("\n</skill_content>");
        return out.toString();
    }

    private static String quote(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    private static final Set<String> ALLOWED_STATUSES =
            Set.of("pending", "in_progress", "completed", "cancelled");
    private static final Set<String> ALLOWED_PRIORITIES =
            Set.of("high", "medium", "low");

    /** One todo entry — its @Arg components become the array item's object schema. */
    public record Todo(
            @Arg("Brief description of the step.") String content,
            @Arg(value = "One of: pending, in_progress, completed, cancelled.",
                    enumValues = {"pending", "in_progress", "completed", "cancelled"}) String status,
            @Arg(value = "One of: high, medium, low.",
                    enumValues = {"high", "medium", "low"}) String priority) {}

    @NumenAction(name = "todowrite", description = """
            Create and maintain a structured task list for the current Numen session. Tracks progress, organizes multi-step work, and lets you keep one step in_progress at a time.

            ## When to use
            Use proactively when:
            - The task requires 3+ distinct steps or actions (not just 3 tool calls for a single conceptual step)
            - The work is non-trivial and benefits from planning
            - The user provides multiple sub-tasks
            - You start a step — mark it `in_progress` (only one at a time) before working
            - You finish a step — mark it `completed` and add any follow-ups discovered during the work

            ## When NOT to use
            Skip when:
            - The work is a single straightforward action (or <3 trivial steps)
            - The request is purely conversational ("hi", "look at me")
            - Tracking adds no organizational value

            ## States
            - `pending` — not started
            - `in_progress` — actively working (exactly ONE at a time)
            - `completed` — finished successfully (only after the work is actually done, never based on intent)
            - `cancelled` — no longer needed

            ## Rules
            - Update status in real time; don't batch completions
            - Mark `completed` only after the actual work is done
            - Keep exactly one `in_progress` while work remains
            - If blocked or partial, keep it `in_progress` and add a follow-up todo describing the blocker

            When in doubt, use it.""")
    public String todowrite(
            @Arg("The complete updated todo list (replaces the previous one).") List<Todo> todos) {
        int inProgressCount = 0;
        // Echo back the canonical JSON; the model reads it next turn as its plan.
        JsonArray echo = new JsonArray();
        for (int i = 0; i < todos.size(); i++) {
            Todo t = todos.get(i);
            if (!ALLOWED_STATUSES.contains(t.status())) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].status must be one of " + ALLOWED_STATUSES + ", got: " + t.status());
            }
            if (!ALLOWED_PRIORITIES.contains(t.priority())) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].priority must be one of " + ALLOWED_PRIORITIES + ", got: " + t.priority());
            }
            if ("in_progress".equals(t.status())) inProgressCount++;
            JsonObject o = new JsonObject();
            o.addProperty("content", t.content());
            o.addProperty("status", t.status());
            o.addProperty("priority", t.priority());
            echo.add(o);
        }
        String echoed = echo.toString();
        if (inProgressCount > 1) {
            return "{\"success\":true,\"warning\":\"more than one todo in_progress ("
                    + inProgressCount + "); keep exactly one\",\"todos\":" + echoed + "}";
        }
        return "{\"success\":true,\"todos\":" + echoed + "}";
    }
}
