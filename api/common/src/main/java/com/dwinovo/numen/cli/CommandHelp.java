package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 帮助的样子,写成脚本里的写法。它是模型读的界面,措辞有单元测试的快照守着。每一层的文字都取自登记时写的说明与参数表、库里
 * 函数上面的注释——语法只有这一个来源,技能里不抄。
 *
 * <p>三层:索引(系统提示里的 {@code <api>}:每组一行说明,其下每个函数一行用法与一句说明)、组(同一种写法,只列这一组)、
 * 函数(用法、说明、逐个参数、例子、注意、相关)。帮助每次都进上下文,所以只有最后一层是全量。写错时附上的就是那个函数的用法
 * 与例子。
 */
final class CommandHelp {

    /** 借了服务器权威的动作,帮助里写明的那一句。 */
    static final String SERVER_ON_HER = "Runs with the server's authority, and only on you.";

    private CommandHelp() {}

    /**
     * 索引:每组一行 {@code 组 — 说明},其下每个函数一行 {@code 用法 — 说明}(声明了直接返回项的接上它);库里定义在这一组表里的函数
     * 接在这一组的动作后面,定义在全局的另列一组。组按名字排序。
     */
    static String index(Collection<CommandGroup> groups, Map<String, NumenCli.LibraryFunction> library) {
        List<String> lines = new ArrayList<>();
        lines.add("Call these from the " + ScriptEngine.IN_USE.toolName() + " tool. Each line: how to call it — what it "
                + "does. `api.help(\"work.dig\")` explains one function in full, `api.help(\"work\")` one group.");
        for (CommandGroup group : groups) {
            lines.add(groupBlock(group, library));
        }
        List<String> globals = new ArrayList<>();
        library.forEach((name, fn) -> {
            if (!name.contains(".")) {
                globals.add("  " + libraryLine(fn));
            }
        });
        if (!globals.isEmpty()) {
            lines.add("library — functions written in " + ScriptEngine.IN_USE.language() + " that every script has:\n"
                    + String.join("\n", globals));
        }
        return String.join("\n", lines);
    }

    /** 一组:{@code 组 — 说明},其下每个函数一行。 */
    static String group(CommandGroup group, Map<String, NumenCli.LibraryFunction> library) {
        return groupBlock(group, library);
    }

    private static String groupBlock(CommandGroup group, Map<String, NumenCli.LibraryFunction> library) {
        StringBuilder sb = new StringBuilder(ScriptEngine.IN_USE.functionName(group.name())).append(" — ")
                .append(group.summary());
        for (Action a : group.actions()) {
            sb.append("\n  ").append(a.usage()).append(" — ").append(a.summary());
            if (a.returns() != null) {
                sb.append(" Returns data.").append(a.returns()).append(", whether it succeeds or not.");
            }
        }
        String prefix = ScriptEngine.IN_USE.functionName(group.name()) + ".";
        library.forEach((name, fn) -> {
            if (name.startsWith(prefix)) {
                sb.append("\n  ").append(libraryLine(fn));
            }
        });
        return sb.toString();
    }

    /** 库函数的一行:{@code work.collect(radius) — 注释}。 */
    private static String libraryLine(NumenCli.LibraryFunction fn) {
        ScriptEngine.Defined d = fn.defined();
        return d.name() + "(" + String.join(", ", d.params()) + ") — " + d.doc() + " (library " + fn.library()
                + ")";
    }

    /** 库函数的帮助:用法、注释、它在哪份库里(全文用 {@code script.show} 看)。 */
    static String library(NumenCli.LibraryFunction fn) {
        ScriptEngine.Defined d = fn.defined();
        return d.name() + "(" + String.join(", ", d.params()) + ")\n  " + d.doc() + "\n  Written in "
                + ScriptEngine.IN_USE.language() + " in the built-in library " + fn.library() + ": `script.show(\""
                + fn.library() + "\")` prints it.";
    }

    /**
     * 函数,给全:用法;缩进一格依次是一句说明、借了服务器的权威时写明这一句、逐个参数(类型的完整称呼,说明接取值提示)、直接返回
     * 什么、例子、注意、相关。没有注意、没有相关时那一块不出现;她自己的权威是默认,不写。归了组的标志排在不归组的参数之后,每组一小节,
     * 标题就是用法里那一格的组名。
     */
    static String action(Action action) {
        StringBuilder sb = new StringBuilder(action.usage()).append("\n  ").append(action.summary());
        if (action.authority() == Authority.SERVER_ON_HER) {
            sb.append("\n  ").append(SERVER_ON_HER);
        }
        List<String> groups = new ArrayList<>();
        for (Param<?> p : action.params()) {
            if (p.group() == null) {
                sb.append("\n  ").append(paramLine(p));
            } else if (!groups.contains(p.group())) {
                groups.add(p.group());
            }
        }
        for (String group : groups) {
            List<String> lines = new ArrayList<>();
            for (Param<?> p : action.params()) {
                if (group.equals(p.group())) {
                    lines.add(paramLine(p));
                }
            }
            block(sb, Character.toUpperCase(group.charAt(0)) + group.substring(1) + " (options):", lines);
        }
        if (action.returns() != null) {
            sb.append("\n  Returns data.").append(action.returns()).append(", whether it succeeds or not.");
        }
        block(sb, "Examples:", action.examples());
        block(sb, "Notes:", action.notes());
        if (!action.seeAlso().isEmpty()) {
            sb.append("\n  See also: ").append(String.join(", ",
                    action.seeAlso().stream().map(path -> path.replace(' ', '.')).toList()));
        }
        return sb.toString();
    }

    /**
     * 写错时 {@code usage:} 那一段:用法,接着缩进列出例子——例子就是正确的写法,照着改比读语法可靠。
     */
    static String usage(Action action) {
        StringBuilder sb = new StringBuilder(action.usage());
        for (String example : action.examples()) {
            sb.append("\n  e.g. ").append(example);
        }
        return sb.toString();
    }

    /**
     * 一个参数的那一行:写法、类型的完整称呼,接说明与取值提示。按顺序的对象写名字;选项写成 {@code 名字=},开关说它是
     * {@code true}/{@code false}。
     */
    private static String paramLine(Param<?> p) {
        String head;
        if (p.positional()) {
            head = p.usage() + " (" + p.type().hint() + (p.required() ? ")" : "; optional)");
        } else if (p.type().isSwitch()) {
            head = p.name() + "=true|false (switch; optional)";
        } else {
            head = p.name() + "= (" + p.type().hint() + "; optional)";
        }
        return head + " — " + p.explained();
    }

    /** 一段帮助作为可翻页的清单:第一行是抬头,其余一行一条。两个前端要帮助都经这里,翻页的切法一样。 */
    static Listing listing(String text) {
        String[] lines = text.split("\n");
        return new Listing(lines[0], List.of(lines).subList(1, lines.length), "");
    }

    /** 带标题的一块,一行一条,再缩进一格;没有条目时整块不出现。 */
    private static void block(StringBuilder sb, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n  ").append(title);
        for (String line : lines) {
            sb.append("\n    ").append(line);
        }
    }
}
