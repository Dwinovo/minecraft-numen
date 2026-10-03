package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code todo}:她做多步的活时写下的计划。{@code numen.todo.write} 每次写整份,一项一个字符串,开头的记号说这一项到了哪一步
 * ({@code "[x] walk to the mine"})。计划不存在别处:每次写下的整份随回执回到她的上下文里,对话流把它画成一条清单消息——
 * 这次调用的参数原样留在脚本回执里({@link Action#echoed}),画清单的一方按这里的写法读回来({@link Item#parse})。
 */
public final class TodoCommands {

    static final String GROUP = "todo";
    private static final String WRITE = "write";

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

    private TodoCommands() {}

    /** 引擎自己登记这一组:计划是大脑的事,不归哪个模组。 */
    public static void install() {
        NumenCli.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, GROUP, "Your plan for multi-step work, shown to your owner as a checklist.", todo ->
                todo.client(WRITE, "Write down the whole plan for work of several physical phases; each call "
                                + "replaces it.", (src, args) -> src.reply(write(args.get(ITEMS))), ITEMS)
                        .echoed()
                        .returns(ScriptType.NOTHING)
                        .example("numen.todo.write({\"[x] walk to the mine\", \"[>] dig the iron\", \"[ ] smelt it\"})")
                        .note("Write it before the first physical step, and again right after each verified result "
                                + "to mark the finished step [x] and move exactly one step to [>]. While work remains "
                                + "exactly one step is [>].")
                        .note("Skip it for single actions and chat. Never mark a step [x] on intent or dispatch, "
                                + "only on a result. When blocked, keep the step [>] and add a concrete recovery "
                                + "step."));
    }

    /**
     * 脚本回执里留下的一次调用({@code {"function": …, "args": {…}}},见 {@code ScriptCall.ECHOED})若是 {@code numen.todo.write},读回它写下的
     * 计划;不是,或参数读不出一份计划,是 null。
     */
    public static List<Item> plan(JsonObject echo) {
        if (!(echo.get("function") instanceof JsonPrimitive function)
                || !ScriptEngine.IN_USE.function(com.dwinovo.numen.api.NumenPlugins.NUMEN + "." + GROUP, WRITE)
                        .equals(function.getAsString())
                || !(echo.get("args") instanceof JsonObject args)
                || !(args.get(ITEMS.name()) instanceof JsonArray items) || items.isEmpty()) {
            return null;
        }
        List<Item> plan = new ArrayList<>(items.size());
        for (JsonElement item : items) {
            if (!(item instanceof JsonPrimitive text)) {
                return null;
            }
            try {
                plan.add(Item.parse(text.getAsString()));
            } catch (IllegalArgumentException notAnItem) {
                return null;
            }
        }
        return List.copyOf(plan);
    }

    /** 收下整份计划:还有没做完的,恰好一项在做。一项都没有是把计划清掉。 */
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
