package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 帮助的样子:API 以类型签名呈现(Lua 是 LuaLS 的注解,由脚本引擎写),按需展开。它是模型读的界面,措辞有单元测试的快照守着。每一层的
 * 文字都取自登记时写的说明、参数表、声明的返回类型({@link Action#doc})与库里函数上面的注释——语法只有这一个来源,技能里不抄。
 *
 * <p>三层,越往下越全:索引(系统提示里的 {@code <api>}:共用的几种值的类声明,加每组一行说明与它的函数名)、组
 * ({@code api.help("move")}:这一组每个函数一行签名)、函数({@code api.help("move.go")}:逐个参数、返回值的字段、例子、注意、相关)。
 * 写错时附上的是那个函数的用法与例子。
 */
final class CommandHelp {

    /** 借了服务器权威的动作,帮助里写明的那一句。 */
    static final String SERVER_ON_HER = "Runs with the server's authority, and only on you.";

    private CommandHelp() {}

    /**
     * 索引:怎么往下要帮助;共用的几种值({@link Shapes#CLASSES})的类声明;每组一行 {@code 组 — 说明: 函数名, …}(库里定义在这一组表里的
     * 函数也列进去),定义在全局的库函数另列一行。组按名字排序。
     */
    static String index(Collection<CommandGroup> groups, Map<String, NumenCli.LibraryFunction> library) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> lines = new ArrayList<>();
        lines.add("Call these from the " + engine.toolName() + " tool. `" + HelpCommands.call("move") + "` lists a "
                + "group's functions with their types; `" + HelpCommands.call("move.go") + "` explains one in full "
                + "(every argument, what it returns, examples).");
        lines.add("Values the groups share:");
        for (ScriptType.Class c : Shapes.CLASSES) {
            lines.add(engine.classText(c));
        }
        lines.add("Groups:");
        for (CommandGroup group : groups) {
            List<String> names = new ArrayList<>();
            group.actions().forEach(a -> names.add(engine.functionName(a.name())));
            String prefix = engine.functionName(group.name()) + ".";
            library.keySet().forEach(name -> {
                if (name.startsWith(prefix)) {
                    names.add(name.substring(prefix.length()));
                }
            });
            lines.add(engine.functionName(group.name()) + " — " + group.summary() + " " + String.join(", ", names));
        }
        List<String> globals = new ArrayList<>();
        library.forEach((name, fn) -> {
            if (!name.contains(".")) {
                globals.add(name);
            }
        });
        if (!globals.isEmpty()) {
            lines.add("library — functions written in " + engine.language() + " that every script has: "
                    + String.join(", ", globals));
        }
        return String.join("\n", lines);
    }

    /**
     * 一组:说明,每个函数一行签名(库里定义在这一组表里的接在动作后面),再是这些签名引用到的类(共用的几种在索引里,不重复)。
     */
    static String group(CommandGroup group, Map<String, NumenCli.LibraryFunction> library) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> lines = new ArrayList<>();
        java.util.Set<String> named = new java.util.LinkedHashSet<>();
        for (Action a : group.actions()) {
            lines.add(engine.functionLine(a.doc()));
            named.addAll(named(a.doc()));
        }
        String prefix = engine.functionName(group.name()) + ".";
        library.forEach((name, fn) -> {
            if (name.startsWith(prefix)) {
                lines.add(engine.libraryLine(fn.defined()));
            }
        });
        return engine.groupText(engine.functionName(group.name()), group.summary(), lines) + classes(named);
    }

    /** 引用到的类的声明,接在后面;共用的几种({@link Shapes#CLASSES})在索引里,不重复。没有是空串。 */
    private static String classes(java.util.Set<String> named) {
        StringBuilder sb = new StringBuilder();
        for (String name : named) {
            ScriptType.Class c = NumenCli.classNamed(name);
            if (c != null && !Shapes.CLASSES.contains(c)) {
                sb.append("\n\n").append(ScriptEngine.IN_USE.classText(c));
            }
        }
        return sb.toString();
    }

    /** 一个函数的参数与返回类型里按名字引用的类,连同这些类的字段里再引用的,按出现的先后。 */
    static java.util.Set<String> named(com.dwinovo.numen.agent.script.FunctionDoc fn) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        fn.params().forEach(p -> out.addAll(named(p.type())));
        out.addAll(named(fn.returns()));
        return out;
    }

    /** 一个类型里按名字引用的类(连同它们字段里再引用的)。 */
    static java.util.Set<String> named(ScriptType type) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        collect(type, out);
        return out;
    }

    private static void collect(ScriptType type, java.util.Set<String> out) {
        switch (type) {
            case ScriptType.Named n -> {
                if (out.add(n.name())) {
                    ScriptType.Class c = NumenCli.classNamed(n.name());
                    if (c != null) {
                        c.fields().forEach(f -> collect(f.type(), out));
                    }
                }
            }
            case ScriptType.ListOf l -> collect(l.item(), out);
            case ScriptType.Union u -> u.options().forEach(o -> collect(o, out));
            case ScriptType.Table t -> t.fields().forEach(f -> collect(f.type(), out));
            case ScriptType.Simple s -> { }
            case ScriptType.Choice c -> { }
        }
    }

    /** 库函数的帮助:它上面的注释(带类型注解)与定义行,它在哪份库里(全文用 {@code script.show} 看)。 */
    static String library(NumenCli.LibraryFunction fn) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        return engine.libraryText(fn.defined()) + "\n" + engine.comment("Written in " + engine.language()
                + " in the built-in library " + fn.library() + ": script.show(\"" + fn.library() + "\") prints it.");
    }

    /** 函数,给全:签名(逐个参数带说明、返回什么)、选项与结果的字段、例子、注意、相关,再是它引用到的类。 */
    static String action(Action action) {
        return ScriptEngine.IN_USE.functionText(action.doc()) + classes(named(action.doc()));
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

    /** 一段帮助作为可翻页的清单:第一行是抬头,其余一行一条。人敲的命令行要帮助经这里,翻页的切法一样。 */
    static Listing listing(String text) {
        String[] lines = text.split("\n");
        return new Listing(lines[0], List.of(lines).subList(1, lines.length), "");
    }
}
