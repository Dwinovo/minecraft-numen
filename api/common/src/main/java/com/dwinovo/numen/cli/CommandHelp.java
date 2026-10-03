package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.script.Modules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 帮助的样子:API 以类型签名呈现(Lua 是 LuaLS 的注解,由脚本引擎写),按需展开。它是模型读的界面,措辞有单元测试的快照守着。每一层的
 * 文字都取自登记时写的说明、参数表、声明的返回类型({@link Action#doc})与模块里函数上面的注释——语法只有这一个来源,技能里不抄。
 *
 * <p>三层,越往下越全:索引(系统提示里的 {@code <api>}:共用的几种值的类声明,每组一行说明与它的函数名,再是模块一个一行)、组或模块
 * ({@code numen.api.help("numen.move")}:每个函数一行签名)、函数({@code numen.api.help("numen.move.go")}:逐个参数、返回值的字段、例子、注意、相关)。
 * 写错时附上的是那个函数的用法与例子。
 */
final class CommandHelp {

    /**
     * 索引里模块那一段的开头,有没有模块都写:模块按名字直接用,不需要也不能 require。按名字直接用的程序几乎从不写 require,照标准
     * 写法 require 的反而会漏写,所以只有这一种写法,并且明说。
     */
    static final String NO_REQUIRE = "Modules: functions written in " + ScriptEngine.IN_USE.language() + " that a "
            + "program uses by name, like a group: `numen.work.collect()`. You neither need nor can require them; "
            + "there is no require.";

    /** 借了服务器权威的动作,帮助里写明的那一句。 */
    static final String SERVER_ON_HER = "Runs with the server's authority, and only on you.";

    private CommandHelp() {}

    /**
     * 索引:怎么往下要帮助;共用的几种值({@link Shapes#CLASSES})的类声明;每组一行 {@code 组 — 说明 函数名, …}(和组同名的模块的函数
     * 也列进去);再是模块,一个一行 {@code - 名字: 说明 (函数名, …)}——内置的在前(常见的事先用它们),她自己的在后。组按名字排序。
     */
    static String index(Collection<CommandGroup> groups, Map<String, NumenCli.LibraryFunction> library,
                        Modules modules) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> lines = new ArrayList<>();
        lines.add("Call these from the " + engine.toolName() + " tool, always written in full: namespace.group.function. `"
                + HelpCommands.call("numen.move") + "` lists a group's or a module's functions with their types; `"
                + HelpCommands.call("numen.move.go") + "` explains one in full (every argument, what it returns, "
                + "examples).");
        lines.add("Values the groups share:");
        for (ScriptType.Class c : Shapes.CLASSES) {
            lines.add(engine.classText(c));
        }
        lines.add("Groups:");
        java.util.Set<String> groupNames = new java.util.HashSet<>();
        for (CommandGroup group : groups) {
            groupNames.add(group.fullName());
            lines.add(groupLine(group, library));
        }
        List<String> builtin = new ArrayList<>();
        List<String> hers = new ArrayList<>();
        modules.all().forEach((name, m) -> {
            String line = "- " + name + ": " + (m.summary() == null ? "" : m.summary()) + " ("
                    + String.join(", ", functionsOf(name, library)) + ")"
                    + (groupNames.contains(name) ? " — adds to the " + name + " group" : "");
            (m.origin() == Modules.Origin.HERS ? hers : builtin).add(m.origin() == Modules.Origin.CHANGED
                    ? line + " — changed by you" : line);
        });
        lines.add(NO_REQUIRE);
        if (!builtin.isEmpty()) {
            lines.add("Built in, first: use these for common jobs before writing your own.");
            lines.addAll(builtin);
        }
        if (!hers.isEmpty()) {
            lines.add("Yours (" + HelpCommands.MODULES + " shows how the programs that used them went):");
            lines.addAll(hers);
        }
        return String.join("\n", lines);
    }

    /** 索引里一组的那一行:{@code numen.work — 说明 dig, fish, collect, mine}(和组同名的模块的函数接在动作后面)。 */
    private static String groupLine(CommandGroup group, Map<String, NumenCli.LibraryFunction> library) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> names = new ArrayList<>();
        group.actions().forEach(a -> names.add(engine.functionName(a.name())));
        names.addAll(functionsOf(engine.pathName(group.fullName()), library));
        return engine.pathName(group.fullName()) + " — " + group.summary() + " " + String.join(", ", names);
    }

    /** 一个名字空间:它的每一组一行,和索引里同一种样子。 */
    static String namespace(String namespace, List<CommandGroup> groups, Map<String, NumenCli.LibraryFunction> library) {
        List<String> lines = new ArrayList<>();
        lines.add(namespace + ": " + groups.size() + " group" + (groups.size() == 1 ? "" : "s") + ". `"
                + HelpCommands.call(namespace + "." + groups.get(0).name()) + "` lists one with its types.");
        groups.forEach(g -> lines.add(groupLine(g, library)));
        return String.join("\n", lines);
    }

    /** 一个模块(或和它同名的组)在 {@code library} 里的函数名,按出现的先后。 */
    private static List<String> functionsOf(String module, Map<String, NumenCli.LibraryFunction> library) {
        List<String> names = new ArrayList<>();
        String prefix = module + ".";
        library.forEach((name, fn) -> {
            if (fn.module().equals(module)) {
                names.add(name.substring(prefix.length()));
            }
        });
        return names;
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
        String module = engine.pathName(group.fullName());
        library.forEach((name, fn) -> {
            if (fn.module().equals(module)) {
                lines.add(engine.libraryLine(fn.defined()));
            }
        });
        return engine.groupText(module, group.summary(), lines) + classes(named);
    }

    /** 一个不和组同名的模块:说明,每个函数一行签名,和组同一种样子。 */
    static String module(Modules.Module module, Map<String, NumenCli.LibraryFunction> library) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        List<String> lines = new ArrayList<>();
        library.forEach((name, fn) -> {
            if (fn.module().equals(module.name())) {
                lines.add(engine.libraryLine(fn.defined()));
            }
        });
        return engine.groupText(module.name(), module.summary() == null ? "" : module.summary(), lines);
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

    /** 模块函数的帮助:它上面的注释(带类型注解)与定义行,它在哪个模块里(全文用 {@code numen.module.show} 看)。 */
    static String library(NumenCli.LibraryFunction fn) {
        ScriptEngine engine = ScriptEngine.IN_USE;
        return engine.libraryText(fn.defined()) + "\n" + engine.comment("Written in " + engine.language()
                + " in the module " + fn.module() + ": " + HelpCommands.showModule(fn.module()) + " prints it.");
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
