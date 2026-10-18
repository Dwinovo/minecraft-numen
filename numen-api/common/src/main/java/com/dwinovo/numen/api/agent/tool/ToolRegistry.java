package com.dwinovo.numen.api.agent.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Mod-global registry of {@link NumenTool} instances. Populated once during
 * mod init (see {@code CommonClass.init} / per-loader entry points) and
 * read-only thereafter. Insertion order is preserved so the LLM sees tools
 * in a stable order across requests — useful for prompt caching on backends
 * that hash tool definitions.
 *
 * <h2>Tools owned by one player</h2>
 * 接进来的 MCP 工具有两处来源:服务器的(管理员配的,人人可见)与玩家自己的(只有他的同伴看得到)。后者登记在
 * {@link #register(UUID, NumenTool)} 名下,{@link #all(UUID)}、{@link #resolve(UUID, String)} 把它们接在全局的后面。
 *
 * <h2>Why static singleton instead of per-entity</h2>
 * The tool set is a deployment-level decision (which capabilities does the
 * mod expose?), not a per-entity decision. Putting it on the entity would
 * mean every Numen carries an identical copy, wasting memory and creating
 * a sync question (does each entity get its own ToolRegistry? on world load
 * do they get repopulated?). Global is simpler and matches how vanilla
 * registries work.
 */
public final class ToolRegistry {

    private static final Map<String, NumenTool> TOOLS = new LinkedHashMap<>();
    /** 玩家自己的工具:玩家 → 工具名 → 工具。 */
    private static final Map<UUID, Map<String, NumenTool>> OWNED = new HashMap<>();

    private ToolRegistry() {}

    /** 上游认的函数名形状。名字不合规,被打回的不是这个工具而是整个请求。 */
    private static final java.util.regex.Pattern LEGAL_NAME =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    /**
     * Register a tool. Should only be called during mod init. Throws on
     * duplicate name — silent replacement would mask wiring bugs.
     *
     * <p>名字形状也在这里把关。工具清单每轮都随请求发出去,一个非法名字换来的是整轮 400,
     * 而不是"这个工具用不了"——所以宁可在注册这一刻炸掉,也不能让它混进去。自家工具在初始化
     * 时就会撞上;借来的工具由调用方接住并跳过(见 {@code McpClientManager})。
     */
    public static void register(NumenTool tool) {
        checkName(tool);
        NumenTool prior = TOOLS.put(tool.name(), tool);
        if (prior != null) {
            throw new IllegalStateException(
                    "Duplicate NumenTool name: " + tool.name()
                            + " (was " + prior.getClass().getName()
                            + ", now " + tool.getClass().getName() + ")");
        }
    }

    /**
     * 登记一个只有这位玩家的同伴看得到的工具。名字不能撞全局的,也不能撞他自己已有的——撞了抛出,由调用方接住并跳过
     * (见 {@code McpClientManager})。
     */
    public static void register(UUID owner, NumenTool tool) {
        checkName(tool);
        String name = tool.name();
        if (TOOLS.containsKey(name)) {
            throw new IllegalStateException("Duplicate NumenTool name: " + name + " (a server tool has it)");
        }
        NumenTool prior = OWNED.computeIfAbsent(owner, id -> new LinkedHashMap<>()).put(name, tool);
        if (prior != null) {
            throw new IllegalStateException("Duplicate NumenTool name: " + name + " (was " + prior.getClass().getName()
                    + ", now " + tool.getClass().getName() + ")");
        }
    }

    private static void checkName(NumenTool tool) {
        String name = tool.name();
        if (name == null || !LEGAL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "工具名不合规(只允许 [a-zA-Z0-9_-],1~64 字符): '" + name
                            + "' — " + tool.getClass().getName());
        }
    }

    /**
     * Remove a tool by name; returns the removed tool, or {@code null} if none
     * was registered under that name.
     *
     * <p>Added for the MCP client's live enable/disable: disabling a server pulls
     * its borrowed tools back out so the built-in brain stops seeing them on the
     * next turn ({@code AgentLoop} re-reads {@link #all()} every turn). Must
     * be called on the server main thread — same invariant as {@link #all()},
     * since the backing map is not synchronized.
     */
    public static NumenTool remove(String name) {
        return TOOLS.remove(name);
    }

    /** 撤掉这位玩家自己的一个工具;没有是 {@code null}。 */
    public static NumenTool remove(UUID owner, String name) {
        Map<String, NumenTool> mine = OWNED.get(owner);
        return mine == null ? null : mine.remove(name);
    }

    public static NumenTool get(String name) {
        return TOOLS.get(name);
    }

    /** 这位玩家的同伴看得到的工具里叫这个名字的:全局的先,他自己的在后;宽松匹配同 {@link #resolve(String)}。 */
    public static NumenTool resolve(UUID owner, String name) {
        NumenTool tool = resolve(name);
        if (tool != null || name == null) {
            return tool;
        }
        Map<String, NumenTool> mine = OWNED.get(owner);
        if (mine == null) {
            return null;
        }
        NumenTool exact = mine.get(name);
        return exact != null ? exact : mine.get(name.toLowerCase(Locale.ROOT));
    }

    /** 这位玩家的同伴看得到的全部工具:全局的,接着是他自己的。 */
    public static List<NumenTool> all(UUID owner) {
        List<NumenTool> out = all();
        Map<String, NumenTool> mine = OWNED.get(owner);
        if (mine != null) {
            out.addAll(mine.values());
        }
        return out;
    }

    /**
     * Lenient lookup — exact match first, then case-insensitive fallback.
     * Mirrors opencode's {@code experimental_repairToolCall} pattern
     * ({@code llm.ts:331-350}) where mis-cased tool names from the LLM
     * are silently fixed instead of crashing the turn.
     *
     * <p>Most LLM typo failure modes come from case drift
     * ({@code Move_to} vs {@code move_to}, {@code AssignTask} vs
     * {@code assign_task}); a single lowercase compare catches them all.
     * For names that still don't resolve, callers should surface a
     * structured "unknown tool" error so the LLM can self-correct on
     * the next turn.
     */
    public static NumenTool resolve(String name) {
        if (name == null) return null;
        NumenTool exact = TOOLS.get(name);
        if (exact != null) return exact;
        String lower = name.toLowerCase(Locale.ROOT);   // 工具名是 ASCII 标识符,不能随系统区域变(土耳其语 I)
        if (lower.equals(name)) return null;  // already lowercase, no further fallback
        return TOOLS.get(lower);
    }

    /**
     * All registered tools, in registration order. Fed to each LLM call.
     * A copy, so callers can pass it to APIs expecting a mutable {@link List}.
     */
    public static List<NumenTool> all() {
        return new ArrayList<>(TOOLS.values());
    }


    public static int size() {
        return TOOLS.size();
    }
}
