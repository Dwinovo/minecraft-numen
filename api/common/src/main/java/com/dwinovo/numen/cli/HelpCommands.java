package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.task.TaskResult;

/**
 * {@code api}:API 自己的帮助。{@code api.help("work.dig")} 给一个函数的全部帮助,{@code api.help("work")} 列一组;文字全由登记与
 * 库里的注释生成({@link CommandHelp}),和系统提示里的索引、写错时附上的用法是同一份。
 */
public final class HelpCommands {

    static final String GROUP = "api";

    private static final Param<String> NAME = Param.required("name", ArgType.word(),
            "A function, as the <api> index writes it (work.dig, move.goto_), or a group (work).");

    private HelpCommands() {}

    /** 引擎自己登记这一组:帮助是 API 的一部分,谁登记了动作都指望它在。 */
    public static void install() {
        NumenCli.register(GROUP, "The API itself: the full help of one function or one group.", api ->
                api.client("help", "Show the full help of a function (how to call it, every argument, examples, notes) "
                                + "or list a group's functions.",
                        (src, args) -> src.reply(help(args.get(NAME), args)), NAME, Listing.PAGE)
                        .example(call("work.dig"))
                        .example(call("move"))
                        .note("Instant; it reads the API's own declarations and changes nothing."));
    }

    /** 要这个名字的帮助怎么写:{@code api.help("work.dig")}。 */
    static String call(String name) {
        return ScriptEngine.IN_USE.function(GROUP, "help") + "(\"" + name + "\")";
    }

    /** 一个函数的帮助整份一页;一组的清单一行一个函数,长了按输出预算分页。 */
    private static String help(String name, CommandArgs args) {
        String text = NumenCli.help(name);
        if (text == null) {
            return TaskResult.fail(Problem.of("there is no function or group named " + name, null,
                    "the <api> index lists every function, by group.")).toJson();
        }
        return CommandHelp.listing(text).result(args).toJson();
    }
}
