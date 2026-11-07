package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.agent.llm.ToolOutcome;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolCall;
import com.dwinovo.numen.api.agent.tool.ToolHints;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.mcp.client.McpCaller;
import com.dwinovo.numen.mcp.client.McpToolName;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.modelcontextprotocol.spec.McpSchema;

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
 * <p>{@link #invoke} forwards a {@code tools/call} on the server's connection off the brain's thread and
 * completes the {@link ToolCall} when the reply lands — marshalled back onto the
 * brain's main thread, because completion drives conversation state and the next
 * LLM turn (the dispatcher's completion path is not thread-safe off-main). Any
 * error/timeout comes back as a {@code ToolOutcome.failure} JSON so the agent loop
 * never wedges.
 */
public final class RemoteMcpTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private final String entryId;
    private final Library.Group group;
    private final McpCaller link;
    private final String remoteName;
    private final String qualifiedName;
    private final String description;
    private final Map<String, Object> schema;
    private final Executor main;
    private final ToolHints hints;

    /**
     * @param entryId    它所属的那台服务器在库里的名字({@code 命名空间:名字})
     * @param group      那台服务器在内置组还是在我的组:玩家用自己的同名条目盖过内置的、或关掉它,这些工具就不再给他的同伴看
     * @param main       大脑所在的主线程:调用的结果经它回来
     * @param takenNames 本批已用掉的模型侧工具名;构造时会把自己这个加进去(见 {@link McpToolName}）
     */
    public RemoteMcpTool(String serverName, String entryId, Library.Group group, McpCaller link, McpSchema.Tool tool,
                         Executor main, Set<String> takenNames) {
        this.entryId = entryId;
        this.group = group;
        this.main = main;
        this.link = link;
        this.remoteName = tool.name();
        this.qualifiedName = McpToolName.qualify(serverName, remoteName, takenNames);
        this.description = buildDescription(tool);
        this.schema = buildSchema(tool);
        this.hints = hintsOf(tool);
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

    /** 对方自己声明的性质;没声明的按规范的缺省(会改东西、可能毁东西、不幂等、触及外面)。 */
    @Override
    public ToolHints hints() {
        return hints;
    }

    private static ToolHints hintsOf(McpSchema.Tool tool) {
        McpSchema.ToolAnnotations a = tool.annotations();
        if (a == null) {
            return new ToolHints(tool.title(), false, true, false, true);
        }
        return new ToolHints(tool.title(), Boolean.TRUE.equals(a.readOnlyHint()),
                a.destructiveHint() == null || a.destructiveHint(), Boolean.TRUE.equals(a.idempotentHint()),
                a.openWorldHint() == null || a.openWorldHint());
    }

    @Override
    public void invoke(ToolCall call) {
        JsonObject args;
        try {
            args = call.args();
        } catch (IllegalArgumentException ex) {
            call.complete(ToolOutcome.failure(ex.getMessage()));
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = GSON.fromJson(args, Map.class);
        // SDK 的调用会阻塞到对方答复(或到点);另起一条虚拟线程等,别占着大脑的线程
        Thread.ofVirtual().name("numen-mcp-call-" + qualifiedName).start(() -> {
            String out;
            try {
                McpCaller.Reply reply = link.call(remoteName, arguments);
                out = reply.error() ? ToolOutcome.failure(reply.text()) : reply.text();
            } catch (RuntimeException err) {
                out = ToolOutcome.failure("mcp tool '" + qualifiedName + "' failed: " + rootMessage(err));
            }
            String result = out;
            // Hop to the main thread: completing the call records the result and may
            // start the next LLM turn, which is not safe off the brain's thread.
            main.execute(() -> call.complete(result));
        });
    }

    private static String buildDescription(McpSchema.Tool tool) {
        String base = tool.description() == null ? "" : tool.description();
        List<String> hints = new ArrayList<>();
        McpSchema.ToolAnnotations a = tool.annotations();
        if (a != null) {
            if (Boolean.TRUE.equals(a.readOnlyHint())) hints.add("read-only");
            if (Boolean.TRUE.equals(a.destructiveHint())) hints.add("destructive");
            if (Boolean.TRUE.equals(a.openWorldHint())) hints.add("acts on the outside world");
        }
        if (hints.isEmpty()) return base;
        String note = "Hints: " + String.join(", ", hints) + ".";
        return base.isBlank() ? note : base + "\n\n" + note;
    }

    /**
     * The remote {@code inputSchema}.
     *
     * <h2>只补两样,别的原样转发</h2>
     * 根上的 {@code type} 与 {@code properties} 是上游强制要求的,而 MCP server 漏掉它们很常见
     * ——漏了不是这个工具不能用,是整个请求被打回。所以这两样缺了就补。
     *
     * <p>其余一律不动:{@code $ref}/{@code $defs}/{@code anyOf} 这些看着"多余",但把它们
     * 按白名单重建过一遍的话,一个纯 {@code $ref} 的子 schema 会变成空对象,工具就废了。
     * 宁可原样发上去让上游说不,也不要自作主张把它改坏。
     */
    private static Map<String, Object> buildSchema(McpSchema.Tool tool) {
        Map<String, Object> map = new LinkedHashMap<>(tool.inputSchema());
        if (!"object".equals(map.get("type"))) {
            map.put("type", "object");
        }
        if (!(map.get("properties") instanceof Map)) {
            map.put("properties", new LinkedHashMap<String, Object>());
        }
        return map;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
