package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记计划的工具,照 Claude Code 的 TodoWrite:她做多步的活时写下整份计划,每次调用交整份、替换上一份。一项一个字符串,开头的记号说
 * 这一项到了哪一步({@code "[x] walk to the mine"})。计划不存在别处:这次调用的参数就是计划,随调用留在她的上下文里;对话流从
 * 成功的这次调用的参数里读回来({@link #plan}),画成一条清单消息。管的是大脑自己的事,不碰世界,当场在主人客户端答;外接大脑
 * (MCP)调的是同一个工具。
 */
public final class TodoTool implements NumenTool {

    /** 工具名。 */
    public static final String NAME = "todo";

    /** 一项到了哪一步,写在这一项开头的记号。 */
    public enum Status {
        PENDING("[ ]"), IN_PROGRESS("[>]"), COMPLETED("[x]"), CANCELLED("[-]");

        /** 写在一项开头的记号。 */
        public final String mark;

        Status(String mark) {
            this.mark = mark;
        }
    }

    /** 计划的一项:做什么,到了哪一步。 */
    public record Item(String content, Status status) {

        /**
         * 读一项:记号、一个空格、内容。
         *
         * @throws IllegalArgumentException 开头不是四个记号之一,或记号后面没有内容
         */
        public static Item parse(String text) {
            String t = text.strip();
            for (Status s : Status.values()) {
                if (t.toLowerCase(Locale.ROOT).startsWith(s.mark)) {
                    String content = t.substring(s.mark.length()).strip();
                    if (content.isEmpty()) {
                        throw new IllegalArgumentException("plan item \"" + text + "\" has a mark but nothing to do");
                    }
                    return new Item(content, s);
                }
            }
            throw new IllegalArgumentException("plan item \"" + text + "\" must start with [ ] (to do), [>] (doing), "
                    + "[x] (done) or [-] (dropped)");
        }
    }

    /** 参数里整份计划的那个键。 */
    private static final String ITEMS = "items";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        // 照 Claude Code 的 TodoWrite 写:动词起头,说清什么时候用、什么时候不用、怎么标
        return "Writes down your plan for work of several physical phases; your owner sees it as a checklist. Each "
                + "call gives the whole plan and replaces the one before.\n"
                + "- Write it before the first physical step, and again right after each verified result to mark the "
                + "finished step [x] and move exactly one step to [>]. While work remains exactly one step is [>].\n"
                + "- Skip it for single actions and chat.\n"
                + "- Never mark a step [x] on intent or dispatch, only on a result. When blocked, keep the step [>] "
                + "and add a concrete recovery step.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object().stringArray(ITEMS, "The whole plan, one string per step in order, each starting with "
                + "its mark: [ ] to do, [>] doing now, [x] done, [-] dropped.", 1).build();
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            call.complete(write(items(call.args())));
        } catch (IllegalArgumentException bad) {
            call.complete(ToolOutcome.failure(bad.getMessage()));
        }
    }

    /**
     * 参数里的整份计划:一项一个字符串,读法只在 {@link Item#parse}。
     *
     * @throws IllegalArgumentException 没有 {@code items}、不是一串字符串、一项写法不对、多写了别的参数
     */
    private static List<Item> items(JsonObject args) {
        for (String key : args.keySet()) {
            if (!key.equals(ITEMS)) {
                throw new IllegalArgumentException("unknown argument '" + key + "'; this takes: " + ITEMS);
            }
        }
        if (!(args.get(ITEMS) instanceof com.google.gson.JsonArray array)) {
            throw new IllegalArgumentException("argument '" + ITEMS + "' is missing: the whole plan, a list of strings "
                    + "like \"[>] dig the iron\"");
        }
        List<Item> items = new java.util.ArrayList<>();
        for (JsonElement e : array) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("each plan item is a string like \"[>] dig the iron\"; got " + e);
            }
            items.add(Item.parse(e.getAsString()));
        }
        return items;
    }

    /**
     * 一次调用的参数({@code arguments} 是那段 JSON 文本)写下的计划;读不出一份计划(不是 JSON、项的写法不对)或一项都没有时是
     * null。读法与调用时同一处({@link #ITEMS})。
     */
    public static List<Item> plan(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            List<Item> items = items(args);
            return items.isEmpty() ? null : List.copyOf(items);
        } catch (RuntimeException notAPlan) {
            return null;   // 参数读不成计划:这次调用没有清单可画
        }
    }

    /** 收下整份计划:还有没做完的,恰好一项在做;整份都了结了也收。 */
    static String write(List<Item> items) {
        int doing = 0;
        int done = 0;
        boolean remains = false;
        Item current = null;
        for (Item item : items) {
            switch (item.status()) {
                case IN_PROGRESS -> {
                    doing++;
                    remains = true;
                    current = item;
                }
                case PENDING -> remains = true;
                case COMPLETED -> done++;
                case CANCELLED -> { }
            }
        }
        if (remains && doing != 1) {
            throw new IllegalArgumentException("while work remains exactly one step is [>] (doing now); this plan has "
                    + doing);
        }
        return ToolOutcome.success("plan written: " + done + "/" + items.size() + " done"
                + (current == null ? "; nothing left to do" : "; doing now: " + current.content()));
    }
}
