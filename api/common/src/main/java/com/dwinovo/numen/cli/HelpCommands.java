package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.script.Modules;
import com.dwinovo.numen.task.TaskResult;

/**
 * {@code numen.api}:API 自己的帮助。{@code numen.api.help("numen.work.dig")} 给一个函数的全部帮助,
 * {@code numen.api.help("numen.work")} 列一组;文字全由登记与库里的注释生成({@link CommandHelp}),和系统提示里的索引、写错时附上的
 * 用法是同一份。
 */
public final class HelpCommands {

    static final String GROUP = "api";
    /** 组的全名。 */
    static final String FULL = com.dwinovo.numen.api.NumenPlugins.NUMEN + "." + GROUP;

    private static final Param<String> NAME = Param.required("name", ArgType.word(),
            "A function as the <api> index writes it (numen.work.dig, numen.move.to), a group (numen.work), a "
                    + "namespace (numen) or a module.");

    private HelpCommands() {}

    /** 引擎自己登记这一组:帮助是 API 的一部分,谁登记了动作都指望它在。 */
    public static void install() {
        NumenCli.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, GROUP, "The API itself: the typed signatures of "
                + "a group's or a module's functions, or one function in full.", api ->
                api.client("help", "The help of a function (its signature, every argument, what it returns, examples, "
                                + "notes) or of a group or module (one typed line per function), as text.",
                        (src, args) -> src.reply(help(args.get(NAME),
                                Modules.of(src.companion()))), NAME)
                        .returns(TEXT, ScriptType.STRING)
                        .example("print(" + call("numen.work.dig") + ")")
                        .example("print(" + call("numen.move") + ")")
                        .note("Instant; it reads the API's own declarations and changes nothing."));
    }

    /** 返回的那一项:帮助的全文。 */
    private static final String TEXT = "text";

    /** 要这个名字的帮助怎么写:{@code numen.api.help("numen.work.dig")}。 */
    static String call(String name) {
        return ScriptEngine.IN_USE.function(FULL, "help") + "(\"" + name + "\")";
    }

    /** 列出模块与战绩的那一次调用:{@code numen.module.list()}。 */
    static final String MODULES = ScriptEngine.IN_USE.function(com.dwinovo.numen.api.NumenPlugins.NUMEN + "."
            + com.dwinovo.numen.script.ModuleCommands.GROUP, "list") + "()";

    /** 读一个模块全文的那一次调用:{@code numen.module.show("numen.work")}。 */
    static String showModule(String module) {
        return ScriptEngine.IN_USE.function(com.dwinovo.numen.api.NumenPlugins.NUMEN + "."
                + com.dwinovo.numen.script.ModuleCommands.GROUP, "show") + "(\"" + module + "\")";
    }

    /** 一个函数或一组的帮助,全文;回执那句话是它的第一行。 */
    private static String help(String name, Modules modules) {
        String text = NumenCli.help(name, modules);
        if (text == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, "there is no function, group or module named " + name,
                    "the <api> index lists every group; " + call("numen.move") + " lists one.").toJson();
        }
        return TaskResult.ok(text.lines().findFirst().orElse(""), java.util.Map.of(TEXT, text)).toJson();
    }
}
