package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.skill.SkillInfo;
import com.dwinovo.numen.agent.skill.SkillInjection;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent-side (client-local) tool implementations — the business half of
 * {@code LoadSkillTool} and {@code TodoWriteTool}. These run on the agent thread
 * with no server body and return their result directly.
 */
public final class AgentOps {

    public String loadSkill(String name, String file) {
        SkillRegistry registry = SkillRegistry.instance();
        if (file != null && !file.isBlank()) {
            // 三级披露:正文引用的附属文件按需拉取
            try {
                String text = registry.readSupportFile(name, file);
                return SkillInjection.supportFile(name, file, text);
            } catch (IllegalArgumentException ex) {
                return "{\"success\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}";
            }
        }
        var maybe = registry.get(name);
        if (maybe.isEmpty()) {
            String available = registry.all().stream()
                    .map(SkillInfo::name)
                    .map(AgentOps::quote)
                    .collect(Collectors.joining(","));
            return "{\"success\":false,\"error\":\"unknown skill: " + escapeJson(name)
                    + "\",\"available\":[" + available + "]}";
        }

        // 成型交给 SkillInjection:主人打斜杠命令走的是另一条路,进上下文的东西必须一样。
        return SkillInjection.body(maybe.get(), null);
    }

    private static String quote(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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
