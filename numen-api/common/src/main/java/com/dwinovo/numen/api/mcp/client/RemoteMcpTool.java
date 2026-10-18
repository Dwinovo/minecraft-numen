package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolCall;
import com.dwinovo.numen.api.library.Library;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * One external MCP tool, wrapped as a {@link NumenTool} so the built-in brain
 * sees it alongside native tools. The remote {@code inputSchema} is used
 * verbatim as the parameter schema; the name is namespaced ({@code server__tool})
 * to avoid colliding with native tools ({@link com.dwinovo.numen.api.agent.tool.ToolRegistry}
 * throws on duplicate names).
 *
 * <p>{@link #invoke} forwards a {@code tools/call} on the client's transport and
 * completes the {@link ToolCall} when the reply lands — marshalled back onto the
 * brain's main thread, because completion drives conversation state and the next
 * LLM turn (the dispatcher's completion path is not thread-safe off-main). Any
 * error/timeout comes back as a {@code ToolOutcome.failure} JSON so the agent loop
 * never wedges.
 */
public final class RemoteMcpTool implements NumenTool {

    private final String entryId;
    private final Library.Group group;
    private final McpClient client;
    private final String remoteName;
    private final String qualifiedName;
    private final String description;
    private final Map<String, Object> schema;
    private final long callTimeoutMs;
    private final Executor main;

    /**
     * @param entryId    它所属的那台服务器在库里的名字({@code 命名空间:名字})
     * @param group      那台服务器在内置组还是在我的组:玩家用自己的同名条目盖过内置的、或关掉它,这些工具就不再给他的同伴看
     * @param main       大脑所在的主线程:调用的结果经它回来
     * @param takenNames 本批已用掉的模型侧工具名;构造时会把自己这个加进去(见 {@link McpToolName}）
     */
    public RemoteMcpTool(String serverName, String entryId, Library.Group group, McpClient client,
                         JsonObject toolDef, long callTimeoutMs, Executor main, Set<String> takenNames) {
        this.entryId = entryId;
        this.group = group;
        this.main = main;
        this.client = client;
        this.remoteName = toolDef.get("name").getAsString();
        this.qualifiedName = McpToolName.qualify(serverName, remoteName, takenNames);
        this.description = buildDescription(toolDef);
        this.schema = buildSchema(toolDef);
        this.callTimeoutMs = callTimeoutMs;
    }


    /** 它所属的那台服务器在库里的名字。 */
    public String entryId() {
        return entryId;
    }

    /** 那台服务器在哪一组。 */
    public Library.Group group() {
        return group;
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
            call.complete(com.dwinovo.numen.api.agent.llm.ToolOutcome.failure(ex.getMessage()));
            return;
        }
        client.callTool(remoteName, args, callTimeoutMs).whenComplete((result, err) -> {
            String out = err != null
                    ? com.dwinovo.numen.api.agent.llm.ToolOutcome.failure("mcp tool '" + qualifiedName + "' failed: " + rootMessage(err))
                    : result;
            // Hop to the main thread: completing the call records the result and may
            // start the next LLM turn, which is not safe off the brain's thread.
            main.execute(() -> call.complete(out));
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
     *
     * <h2>只补两样,别的原样转发</h2>
     * 根上的 {@code type} 与 {@code properties} 是上游强制要求的,而 MCP server 漏掉它们很常见
     * ——漏了不是这个工具不能用,是整个请求被打回。所以这两样缺了就补。
     *
     * <p>其余一律不动:{@code $ref}/{@code $defs}/{@code anyOf} 这些看着"多余",但把它们
     * 按白名单重建过一遍的话,一个纯 {@code $ref} 的子 schema 会变成空对象,工具就废了。
     * 宁可原样发上去让上游说不,也不要自作主张把它改坏。
     */
    private static Map<String, Object> buildSchema(JsonObject toolDef) {
        Map<String, Object> map = new LinkedHashMap<>();
        JsonObject input = toolDef.has("inputSchema") && toolDef.get("inputSchema").isJsonObject()
                ? toolDef.getAsJsonObject("inputSchema")
                : null;
        if (input != null) {
            input.entrySet().forEach(e -> map.put(e.getKey(), e.getValue()));
        }
        if (!(map.get("type") instanceof JsonPrimitive p) || !"object".equals(p.getAsString())) {
            map.put("type", "object");
        }
        if (!(map.get("properties") instanceof JsonObject)) {
            map.put("properties", new JsonObject());
        }
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

}
