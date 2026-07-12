package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One external MCP tool, wrapped as a {@link NumenTool} so the built-in brain
 * sees it alongside native tools. The remote {@code inputSchema} is used
 * verbatim as the parameter schema; the name is namespaced ({@code server__tool})
 * to avoid colliding with native tools ({@link com.dwinovo.numen.agent.tool.ToolRegistry}
 * throws on duplicate names).
 *
 * <p>{@link #invoke} forwards a {@code tools/call} on the client's transport and
 * completes the {@link ToolCall} when the reply lands — marshalled back onto the
 * game main thread, because completion drives conversation state and the next
 * LLM turn (the dispatcher's completion path is not thread-safe off-main). Any
 * error/timeout comes back as a {@code TaskResult.fail} JSON so the agent loop
 * never wedges.
 */
public final class RemoteMcpTool implements NumenTool {

    private final McpClient client;
    private final String remoteName;
    private final String qualifiedName;
    private final String description;
    private final Map<String, Object> schema;
    private final long callTimeoutMs;

    public RemoteMcpTool(String serverName, McpClient client, JsonObject toolDef, long callTimeoutMs) {
        this.client = client;
        this.remoteName = toolDef.get("name").getAsString();
        this.qualifiedName = sanitize(serverName) + "__" + remoteName;
        this.description = buildDescription(toolDef);
        this.schema = buildSchema(toolDef);
        this.callTimeoutMs = callTimeoutMs;
    }

    @Override
    public String name() {
        return qualifiedName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return schema;
    }

    @Override
    public void invoke(ToolCall call) {
        JsonObject args;
        try {
            args = call.args();
        } catch (IllegalArgumentException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
            return;
        }
        client.callTool(remoteName, args, callTimeoutMs).whenComplete((result, err) -> {
            String out = err != null
                    ? TaskResult.fail("mcp tool '" + qualifiedName + "' failed: " + rootMessage(err)).toJson()
                    : result;
            // Hop to the main thread: completing the call records the result and may
            // start the next LLM turn, which is not safe off the client thread.
            Minecraft.getInstance().execute(() -> call.complete(out));
        });
    }

    private static String buildDescription(JsonObject toolDef) {
        String base = toolDef.has("description") && !toolDef.get("description").isJsonNull()
                ? toolDef.get("description").getAsString()
                : "";
        List<String> hints = new ArrayList<>();
        if (toolDef.has("annotations") && toolDef.get("annotations").isJsonObject()) {
            JsonObject a = toolDef.getAsJsonObject("annotations");
            if (flag(a, "readOnlyHint")) hints.add("read-only");
            if (flag(a, "destructiveHint")) hints.add("destructive");
            if (flag(a, "openWorldHint")) hints.add("acts on the outside world");
        }
        if (hints.isEmpty()) return base;
        String note = "Hints: " + String.join(", ", hints) + ".";
        return base.isBlank() ? note : base + "\n\n" + note;
    }

    /**
     * The remote {@code inputSchema} as a {@code Map<String,Object>}. Values are
     * kept as Gson {@link com.google.gson.JsonElement}s and stored directly —
     * {@code OpenAIProvider.mapToJson} does {@code GSON.toJsonTree(map)}, which
     * reproduces JsonElement values losslessly (no int→double drift).
     */
    private static Map<String, Object> buildSchema(JsonObject toolDef) {
        Map<String, Object> map = new LinkedHashMap<>();
        JsonObject input = toolDef.has("inputSchema") && toolDef.get("inputSchema").isJsonObject()
                ? toolDef.getAsJsonObject("inputSchema")
                : null;
        if (input == null || input.entrySet().isEmpty()) {
            map.put("type", "object");
            map.put("properties", new JsonObject());
            return map;
        }
        input.entrySet().forEach(e -> map.put(e.getKey(), e.getValue()));
        return map;
    }

    private static boolean flag(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    /** Lowercase, keep only {@code [a-z0-9_]}, so the qualified name stays a valid snake_case tool name. */
    private static String sanitize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9_]", "_");
    }
}
