package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
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

        String written() {
            return status.mark + " " + content;
        }
    }

    private static final Param<List<Item>> ITEMS = Param.required("items", ArgType.list(ArgType.string().as(
                    "plan item", "a string starting with [ ], [>], [x] or [-], like \"[>] dig the iron\"", Item::parse,
                    Item::written)),
            "The whole plan, one string per step in order, each starting with its mark: [ ] to do, [>] doing now, "
                    + "[x] done, [-] dropped.");
    private static final List<Param<?>> PARAMS = List.of(ITEMS);

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
        return Param.schemaOf(PARAMS);
    }

    @Override
    public void invoke(ToolCall call) {
        try {
            call.complete(write(CommandArgs.fromJson(PARAMS, call.args()).get(ITEMS)));
        } catch (IllegalArgumentException bad) {
            call.complete(TaskResult.fail(ErrorKind.BAD_ARGUMENT, bad.getMessage(), null).toJson());
        }
    }

    /**
     * 一次调用的参数({@code arguments} 是那段 JSON 文本)写下的计划;读不出一份计划(不是 JSON、项的写法不对)或一项都没有时是
     * null。读法与调用时同一处({@link #ITEMS})。
     */
    public static List<Item> plan(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            List<Item> items = CommandArgs.fromJson(PARAMS, args).get(ITEMS);
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
        return TaskResult.ok("plan written: " + done + "/" + items.size() + " done"
                + (current == null ? "; nothing left to do" : "; doing now: " + current.content())).toJson();
    }
}
