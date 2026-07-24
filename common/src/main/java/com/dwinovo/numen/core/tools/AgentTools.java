package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillRegistry;
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

    public String loadSkill(String name) {
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
String content,
String status,
String priority) {}

    public String todowrite(
List<Todo> todos) {
        if (todos == null) {
            throw new IllegalArgumentException("todos is required");
        }
        int inProgressCount = 0;
        boolean workRemains = false;
        // Echo back the canonical JSON; the model reads it next turn as its plan.
        JsonArray echo = new JsonArray();
        for (int i = 0; i < todos.size(); i++) {
            Todo t = todos.get(i);
            if (t == null || t.content() == null || t.content().isBlank()) {
                throw new IllegalArgumentException("todos[" + i + "].content must not be blank");
            }
            if (!ALLOWED_STATUSES.contains(t.status())) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].status must be one of " + ALLOWED_STATUSES + ", got: " + t.status());
            }
            if (!ALLOWED_PRIORITIES.contains(t.priority())) {
                throw new IllegalArgumentException(
                        "todos[" + i + "].priority must be one of " + ALLOWED_PRIORITIES + ", got: " + t.priority());
            }
            if ("in_progress".equals(t.status())) inProgressCount++;
            if ("in_progress".equals(t.status()) || "pending".equals(t.status())) workRemains = true;
            JsonObject o = new JsonObject();
            o.addProperty("content", t.content());
            o.addProperty("status", t.status());
            o.addProperty("priority", t.priority());
            echo.add(o);
        }
        String echoed = echo.toString();
        if (workRemains && inProgressCount != 1) {
            return "{\"success\":false,\"message\":\"unfinished work requires exactly one todo in_progress; got "
                    + inProgressCount + "\",\"todos\":" + echoed + "}";
        }
        return "{\"success\":true,\"message\":\"plan snapshot replaced; continue only the in_progress item\",\"todos\":"
                + echoed + "}";
    }
}
