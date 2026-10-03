package com.dwinovo.numen.script;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.ClientSource;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.task.TaskResult;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code numen.module}:Lua 模块({@link Modules})——每个返回一张函数表,程序里以模块名直接用。模块是主人客户端上目录里的文件,路径就是
 * 名字;随模组发布的那一套装进去(出厂的),她能看、改、存、删任何一份,改坏了的出厂模块 {@code reset} 还原。都在主人客户端执行(她的
 * 大脑与 Lua 虚拟机就在那儿),当场回,不占身体;存、改、删不问主人,每一次写进回执与客户端日志。每份都记战绩:用到它的程序跑了几段、
 * 跑完几段、最近一次在什么时候、最近一段没跑完停在哪一行、为什么——只给事实,不替她评判。战绩由跑程序的大脑在程序结束时记
 * ({@link Modules#tally})。
 */
public final class ModuleCommands {

    public static final String GROUP = "module";

    private static final Param<String> NAME = Param.required("name", ArgType.word(),
            "The module, as numen.module.list lists it (numen.work, my.lumber).")
            .values("a module name, as `numen.module.list()` lists it");
    private static final Param<Boolean> FACTORY = Param.optional("factory", ArgType.bool(),
            "Show the text it shipped with this version, not the file as it is now.")
            .whenOmitted("show the file as it is now");
    private static final Param<String> NEW_NAME = Param.optional("name", ArgType.word(),
            "Name to keep it under, the name programs use it by: namespace.group, each lowercase letters, digits and _ "
                    + "(my.lumber for one of your own; numen.work changes that built-in one).")
            .whenOmitted("give it the next free name my.module_1, my.module_2, …");
    private static final Param<String> CODE = Param.required("code", ArgType.string(),
            "The module: a first comment line saying what it does, functions put in a table, and that table returned "
                    + "(local M = {} … function M.chop(tree) … end … return M).");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    /** 清单里一次失败的原因最多写多长;全文在 {@code numen.module.show} 里。 */
    private static final int WHY_IN_LIST = 100;

    private ModuleCommands() {}

    /** 经插件那扇门登记这一组。 */
    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Modules — functions written in " + ScriptEngine.IN_USE.language()
                + " that programs use by name, kept as files on your owner's computer (numen/work.lua is numen.work): "
                + "the built-in ones, your own, and how the programs that used them went.", ModuleCommands::actions);
    }

    private static void actions(CommandGroup module) {
        module.client("list", "The modules, one line each: what it does, whose it is, how the programs that used it "
                        + "went.", (src, args) -> src.reply(list(modules(src), args)), Listing.PAGE)
                .returns(MODULES, ScriptType.listOf(ScriptType.table(
                        ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.field("summary", ScriptType.STRING, "Its first comment line."),
                        ScriptType.field("whose", ScriptType.STRING, "built in, built in and changed, deleted, or "
                                + "yours."),
                        ScriptType.field("runs", ScriptType.INTEGER, "How many programs used it."),
                        ScriptType.field("finished", ScriptType.INTEGER, "How many of those ran to the end."))))
                .example("numen.module.list()")
                .note("Instant and read-only. numen.api.help(\"<module>\") lists a module's functions with their types.")
                .seeAlso("module show");
        module.client("show", "Show a module in full, with how the programs that used it went.",
                        (src, args) -> src.reply(show(modules(src), args.get(NAME),
                                Boolean.TRUE.equals(args.get(FACTORY)))), NAME, FACTORY)
                .returns(ScriptType.table(ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.field("factory", ScriptType.BOOLEAN, "Whether the text is the one it shipped with."),
                        ScriptType.field("code", ScriptType.STRING, "The whole module.")))
                .example("print(numen.module.show(\"numen.work\").code)")
                .example("print(numen.module.show(\"numen.work\", {factory = true}).code)")
                .note("Instant and read-only. Read one before you change it or write your own: it shows how its "
                        + "functions are put together.")
                .seeAlso("module save");
        module.client("save", "Keep a module under a name: programs then use its functions by that name. Saving under "
                        + "a built-in module's name changes that module.",
                        (src, args) -> src.reply(save(modules(src), args)), CODE, NEW_NAME)
                .returns(ScriptType.table(ScriptType.field("name", ScriptType.STRING, "The name it is kept under.")))
                .example("numen.module.save([[\n-- Clearing a pit.\nlocal M = {}\n---Dig out every block given.\n"
                        + "function M.clear(blocks)\n  numen.work.dig(blocks)\nend\nreturn M\n]], {name = \"my.pit\"})")
                .note("Instant. The module is read and loaded once first, and not kept if it does not compile, does "
                        + "not return a table, or redefines an API function; the error says the line. Its first line "
                        + "is a comment saying what it does, and the comment lines above each function say what that "
                        + "one does: that is how the list and numen.api.help describe them.")
                .note("A module named after an API group (numen.move, numen.work, ...) adds its functions to that "
                        + "group. Saving under a name that has a file replaces it and resets its record. Your owner can "
                        + "edit the files too; the next program reads them as they are.")
                .seeAlso("module delete", "module reset");
        module.client("delete", "Delete a module. A built-in one stays deleted until you reset it.",
                        (src, args) -> src.reply(delete(modules(src), args.get(NAME))), NAME)
                .returns(ScriptType.NOTHING)
                .example("numen.module.delete(\"my.pit\")")
                .note("Instant.")
                .seeAlso("module list", "module reset");
        module.client("reset", "Put a built-in module back as it shipped with this version: your changes go, a "
                        + "deleted one comes back.", (src, args) -> src.reply(reset(modules(src), args.get(NAME))), NAME)
                .returns(ScriptType.NOTHING)
                .example("numen.module.reset(\"numen.work\")")
                .note("Instant. numen.module.list says which built-in ones you changed and which have a newer "
                        + "version you have not taken because you changed yours.")
                .seeAlso("module list");
    }

    private static Modules modules(ClientSource src) {
        return Modules.of(src.companion());
    }

    // ==================== 看 ====================

    /** {@code numen.module.list} 返回的那一项:每份一张表。 */
    private static final String MODULES = "modules";

    private static String list(Modules modules, CommandArgs args) {
        List<String> rows = new ArrayList<>();
        List<Map<String, Object>> listed = new ArrayList<>();
        java.util.SortedSet<String> names = new java.util.TreeSet<>(modules.all().keySet());
        BuiltinModules.all().keySet().stream().filter(modules::deletedFactory).forEach(names::add);
        for (String name : names) {
            Modules.Module m = modules.get(name);
            Modules.Stats stats = modules.stats(name);
            String summary = m != null ? m.summary() : BuiltinModules.get(name).summary();
            rows.add(name + " — " + (summary == null ? "(no first comment line)" : summary) + " [" + whose(m, name)
                    + "] " + record(stats, true));
            listed.add(entry(name, summary, whose(m, name), stats));
        }
        String head = rows.isEmpty() ? "No modules yet. Keep one with numen.module.save(code, {name = \"...\"})."
                : rows.size() + " module" + (rows.size() == 1 ? "" : "s") + " in " + modules.dir() + ". Use one by "
                + "name in a program (numen.work.collect()); numen.api.help(name) lists its functions, "
                + "numen.module.show(name) prints it.";
        return new Listing(head, rows, "").result(args, Map.of(MODULES, listed)).toJson();
    }

    /** 谁的、和出厂的那份是什么关系、读不读得通;{@code m} 是 null 的是删掉的出厂模块。 */
    private static String whose(Modules.Module m, String name) {
        if (m == null) {
            return "built in, deleted; numen.module.reset(\"" + name + "\") brings it back";
        }
        String whose = switch (m.origin()) {
            case FACTORY -> "built in";
            case HERS -> "yours";
            case CHANGED -> "built in, changed" + (m.newer() ? "; a newer built-in version shipped and is not used "
                    + "because yours is changed: numen.module.show(\"" + name + "\", {factory = true}) shows it, "
                    + "numen.module.reset(\"" + name + "\") takes it" : "");
        };
        return m.problem() == null ? whose : whose + "; does not compile: " + m.problem();
    }

    /** 清单里一份的那张表。 */
    private static Map<String, Object> entry(String name, String summary, String whose, Modules.Stats stats) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("summary", summary == null ? "" : summary);
        out.put("whose", whose);
        out.put("runs", stats.runs());
        out.put("finished", stats.ok());
        return out;
    }

    private static String show(Modules modules, String name, boolean factory) {
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        if (factory && builtin == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, "nothing named " + name + " ships with this version",
                    "numen.module.show(\"" + name + "\")").toJson();
        }
        Modules.Module m = modules.get(name);
        if (m == null && !factory) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, missing(name, modules), modules.deletedFactory(name)
                    ? "numen.module.reset(\"" + name + "\")" : "numen.module.list()").toJson();
        }
        String code = factory ? builtin.code() : m.code();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("factory", factory);
        // 正文也在数据里:脚本里读到它就能改了另存(numen.module.save(s.code, ...))
        data.put("code", code);
        String head = "Module " + name + " (" + (factory ? "as it shipped with this version" : whose(m, name)) + "). "
                + record(modules.stats(name), false);
        return TaskResult.ok(head + "\n" + code.stripTrailing(), data).toJson();
    }

    /** 战绩,只给事实。{@code brief} 是清单里那一版:原因截短。 */
    private static String record(Modules.Stats stats, boolean brief) {
        if (stats.runs() == 0) {
            return "No program used it yet.";
        }
        StringBuilder sb = new StringBuilder("Programs that used it: ").append(stats.runs()).append(", ran to the end: ")
                .append(stats.ok()).append(", last ").append(WHEN.format(Instant.ofEpochMilli(stats.lastRun())))
                .append('.');
        if (stats.failedWhy() != null) {
            String why = stats.failedWhy();
            if (brief && why.length() > WHY_IN_LIST) {
                why = why.substring(0, WHY_IN_LIST) + "...";
            }
            sb.append(" The last one that did not finish stopped at its line ").append(stats.failedLine()).append(": ")
                    .append(why);
        }
        return sb.toString();
    }

    // ==================== 存、删、还原 ====================

    private static String save(Modules modules, CommandArgs args) {
        String name = args.get(NEW_NAME) == null ? freeName(modules) : args.get(NEW_NAME);
        String code = args.get(CODE);
        String badName = Modules.problem(name);
        if (badName != null) {
            String own = name.substring(name.lastIndexOf('.') + 1);
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": " + badName,
                    "{name = \"" + Modules.MINE + "." + own.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]",
                            "_") + "\"}").toJson();
        }
        // 读不通、不返回表、撞第 ① 层:和运行时同一个解释器装它一次,规则只在沙箱里那一处
        String problem = ScriptEngine.IN_USE.checkModule(name, code, NumenCli.scriptCatalog(Modules.factory()));
        if (problem != null) {
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": " + problem,
                    "fix that line, then save it again").toJson();
        }
        if (ScriptEngine.IN_USE.summary(code) == null) {
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": start it with a comment line "
                    + "saying what it does", ScriptEngine.IN_USE.comment("Dig every block given, nearest first."))
                    .toJson();
        }
        Modules.Saved saved = modules.save(name, code);
        String said = switch (saved) {
            case NEW -> "Saved module " + name + ".";
            case REPLACED -> "Replaced module " + name + "; its record starts over.";
            case CHANGED -> "Changed the built-in module " + name + "; numen.module.reset(\"" + name + "\") puts it "
                    + "back as it shipped.";
        };
        Constants.LOG.info("[numen-script] {} {} in {}", saved, name, modules.dir());
        return TaskResult.ok(said + " Programs use it by name: " + name + ".<function>(...).", Map.of("name", name))
                .toJson();
    }

    private static String delete(Modules modules, String name) {
        if (modules.delete(name) == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, missing(name, modules), "numen.module.list()").toJson();
        }
        Constants.LOG.info("[numen-script] deleted {} in {}", name, modules.dir());
        return TaskResult.ok(BuiltinModules.get(name) != null ? "Deleted the built-in module " + name + "; it stays "
                + "deleted until numen.module.reset(\"" + name + "\")." : "Deleted module " + name + ".").toJson();
    }

    private static String reset(Modules modules, String name) {
        if (modules.reset(name) == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, "nothing named " + name + " ships with this version; only a "
                    + "built-in module can be reset", "numen.module.list()").toJson();
        }
        Constants.LOG.info("[numen-script] reset {} in {}", name, modules.dir());
        return TaskResult.ok("Put " + name + " back as it shipped with this version; its record starts over.")
                .toJson();
    }

    // ==================== 小件 ====================

    /** 下一个空着的 {@code my.module_N}。 */
    private static String freeName(Modules modules) {
        for (int n = 1; ; n++) {
            String name = Modules.MINE + ".module_" + n;
            if (modules.get(name) == null) {
                return name;
            }
        }
    }

    private static String missing(String name, Modules modules) {
        List<String> names = new ArrayList<>(modules.all().keySet());
        return "there is no module named " + name + (names.isEmpty() ? "; there are none"
                : "; there are: " + String.join(", ", names)) + ".";
    }
}
