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
 * {@code script}:Lua 模块({@link Modules})——每个返回一张函数表,程序里以模块名直接用。内置的随模组发布、留在 jar 里;她存的是主人
 * 客户端上目录里的文件,同名的盖住内置那份,删掉就回到内置。都在主人客户端执行(她的大脑与 Lua 虚拟机就在那儿),当场回,不占身体;
 * 存、改、删不问主人,每一次写进回执。每个模块(连同内置的)都记战绩:用到它的程序跑了几段、跑完几段、最近一次在什么时候、最近一段
 * 没跑完停在哪一行、为什么——只给事实,不替她评判。战绩由跑程序的大脑在程序结束时记({@link Modules#tally})。
 */
public final class Scripts {

    private static final String GROUP = "script";

    private static final Param<String> NAME = Param.required("name", ArgType.word(),
            "The module, as script.list lists it.").values("a module name, as `script.list()` lists it");
    private static final Param<Boolean> BUILTIN = Param.optional("builtin", ArgType.bool(),
            "Show the built-in original, not the copy of yours that overrides it.")
            .whenOmitted("show the one in use");
    private static final Param<String> NEW_NAME = Param.optional("name", ArgType.word(),
            "Name to keep it under, the name programs use it by: my.<name> for a module of your own (lowercase "
                    + "letters, digits and _, starting with a letter), or a built-in module's name to use yours "
                    + "instead of it.")
            .whenOmitted("give it the next free name my.module_1, my.module_2, …");
    private static final Param<String> CODE = Param.required("code", ArgType.string(),
            "The module: a first comment line saying what it does, functions put in a table, and that table returned "
                    + "(local M = {} … function M.chop(tree) … end … return M).");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    /** 清单里一次失败的原因最多写多长;全文在 {@code script show} 里。 */
    private static final int WHY_IN_LIST = 100;

    private Scripts() {}

    /** 经插件那扇门登记这一组。 */
    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Modules — functions written in " + ScriptEngine.IN_USE.language()
                + " that programs use by name: the built-in ones (work.collect()), your own under my "
                + "(my.lumber.chop(); saved on your owner's computer), your versions of built-in ones, and how the "
                + "programs that used them went.", Scripts::actions);
    }

    private static void actions(CommandGroup script) {
        script.client("list", "The modules, one line each: what it does, whose it is, how the programs that used it "
                        + "went.", (src, args) -> src.reply(list(modules(src), args)), Listing.PAGE)
                .returns(SCRIPTS, ScriptType.listOf(ScriptType.table(
                        ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.field("summary", ScriptType.STRING, "Its first comment line."),
                        ScriptType.field("whose", ScriptType.STRING, "built in, yours, or yours overriding the "
                                + "built-in."),
                        ScriptType.field("runs", ScriptType.INTEGER, "How many programs used it."),
                        ScriptType.field("finished", ScriptType.INTEGER, "How many of those ran to the end."))))
                .example("script.list()")
                .note("Instant and read-only. api.help(\"<module>\") lists a module's functions with their types.")
                .seeAlso("script show");
        script.client("show", "Show a module in full, with how the programs that used it went.",
                        (src, args) -> src.reply(show(modules(src), args.get(NAME),
                                Boolean.TRUE.equals(args.get(BUILTIN)))), NAME, BUILTIN)
                .returns(ScriptType.table(ScriptType.field("script", ScriptType.STRING, null),
                        ScriptType.field("builtin", ScriptType.BOOLEAN, "Whether the text is the built-in one."),
                        ScriptType.field("code", ScriptType.STRING, "The whole module.")))
                .example("print(script.show(\"work\").code)")
                .example("print(script.show(\"work\", {builtin = true}).code)")
                .note("Instant and read-only. Read one before you write your own version of it: it shows how its "
                        + "functions are put together. `script.save(script.show(\"work\").code, {name = \"work\"})` "
                        + "makes the built-in work module yours to change.")
                .seeAlso("script save");
        script.client("save", "Keep a module under a name: programs then use its functions by that name; under a "
                        + "built-in's name, yours is used instead of the built-in.",
                        (src, args) -> src.reply(save(modules(src), args)), CODE, NEW_NAME)
                .returns(ScriptType.table(ScriptType.field("name", ScriptType.STRING, "The name it is kept under.")))
                .example("script.save([[\n-- Clearing a pit.\nlocal M = {}\n---Dig out the pit area.\n"
                        + "function M.clear()\n  work.dig(\"pit\")\nend\nreturn M\n]], {name = \"my.pit\"})")
                .note("Instant. The module is read and loaded once first, and not kept if it does not compile, does "
                        + "not return a table, or redefines an API function; the error says the line. Its first line "
                        + "is a comment saying what it does, and the comment lines above each function say what that "
                        + "one does: that is how the list and api.help describe them.")
                .note("A module named after an API group (move, work, ...) adds its functions to that group. Saving "
                        + "under a name you used replaces it and resets its record. Saving under a built-in's name "
                        + "overrides the built-in until you delete yours. Your owner can edit the files too; the next "
                        + "program reads them as they are.")
                .seeAlso("script delete");
        script.client("delete", "Delete a module of yours; one that overrides a built-in gives the built-in back.",
                        (src, args) -> src.reply(delete(modules(src), args.get(NAME))), NAME)
                .returns(ScriptType.NOTHING)
                .example("script.delete(\"my.pit\")")
                .note("Instant. A built-in module you did not override has nothing of yours to delete.")
                .seeAlso("script list");
    }

    private static Modules modules(ClientSource src) {
        return Modules.of(src.companion());
    }

    // ==================== 看 ====================

    /** {@code script.list} 返回的那一项:每份一张表。 */
    private static final String SCRIPTS = "scripts";

    private static String list(Modules modules, CommandArgs args) {
        List<String> rows = new ArrayList<>();
        List<Map<String, Object>> scripts = new ArrayList<>();
        modules.all().forEach((name, m) -> {
            Modules.Stats stats = modules.stats(name);
            rows.add(name + " — " + (m.summary() == null ? "(no first comment line)" : m.summary()) + " ["
                    + whose(m) + "] " + record(stats, true));
            scripts.add(entry(name, m.summary(), whose(m), stats));
        });
        String head = rows.isEmpty() ? "No modules yet. Keep one with script.save(code, {name = \"...\"})."
                : rows.size() + " module" + (rows.size() == 1 ? "" : "s") + ". Use one by name in a program "
                + "(work.collect()); api.help(name) lists its functions, script.show(name) prints it.";
        return new Listing(head, rows, "").result(args, Map.of(SCRIPTS, scripts)).toJson();
    }

    /** 谁的、和内置那份是什么关系、读不读得通。 */
    private static String whose(Modules.Module m) {
        String whose = switch (m.origin()) {
            case BUILTIN -> "built in";
            case HERS -> "yours";
            case OVERRIDE -> "yours, used instead of the built-in one"
                    + (m.builtinNewer() ? "; the built-in changed since you saved yours: script.show(\"" + m.name()
                    + "\", {builtin = true}) shows it" : "");
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

    private static String show(Modules modules, String name, boolean original) {
        Modules.Module m = modules.get(name);
        if (m == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, missing(name, modules), "script.list()").toJson();
        }
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        if (original && builtin == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, name + " is yours; there is no built-in " + name,
                    "script.show(\"" + name + "\")").toJson();
        }
        boolean showsBuiltin = original || m.origin() == Modules.Origin.BUILTIN;
        String code = showsBuiltin ? builtin.code() : m.code();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("script", name);
        data.put("builtin", showsBuiltin);
        // 正文也在数据里:脚本里读到它就能照抄一份改了另存(script.save(s.code, ...))
        data.put("code", code);
        String head = "Module " + name + " (" + (showsBuiltin ? "the built-in text" : whose(m)) + "). "
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

    // ==================== 存与删 ====================

    private static String save(Modules modules, CommandArgs args) {
        String name = args.get(NEW_NAME) == null ? freeName(modules) : args.get(NEW_NAME);
        String code = args.get(CODE);
        String badName = Modules.problem(name);
        if (badName != null) {
            String own = name.startsWith(Modules.MINE) ? name.substring(Modules.MINE.length()) : name;
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": " + badName,
                    "{name = \"" + Modules.MINE + own.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                            + "\"}").toJson();
        }
        // 读不通、不返回表、撞第 ① 层:和运行时同一个解释器装它一次,规则只在沙箱里那一处
        String problem = ScriptEngine.IN_USE.checkModule(name, code, NumenCli.scriptCatalog(Modules.builtin()));
        if (problem != null) {
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": " + problem,
                    "fix that line, then save it again").toJson();
        }
        if (ScriptEngine.IN_USE.summary(code) == null) {
            return TaskResult.fail(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": start it with a comment line "
                    + "saying what it does", ScriptEngine.IN_USE.comment("Dig every part of an area, nearest first."))
                    .toJson();
        }
        Modules.Saved saved = modules.save(name, code);
        String said = switch (saved) {
            case NEW -> "Saved module " + name + ".";
            case REPLACED -> "Replaced your module " + name + "; its record starts over.";
            case OVERRODE -> "Saved your " + name + "; it is used instead of the built-in " + name + " from now on "
                    + "(script.delete(\"" + name + "\") gives the built-in back).";
        };
        Constants.LOG.info("[numen-script] {} {} in {}", saved, name, modules.dir());
        return TaskResult.ok(said + " Programs use it by name: " + name + ".<function>(...).", Map.of("name", name))
                .toJson();
    }

    private static String delete(Modules modules, String name) {
        boolean builtin = BuiltinModules.get(name) != null;
        if (modules.delete(name) == null) {
            return TaskResult.fail(ErrorKind.NOT_FOUND, builtin ? name + " is built in and you have no version of "
                    + "your own to delete" : missing(name, modules), "script.list()").toJson();
        }
        Constants.LOG.info("[numen-script] deleted {} in {}", name, modules.dir());
        return TaskResult.ok(builtin ? "Deleted your " + name + "; the built-in " + name + " is used again."
                : "Deleted module " + name + ".").toJson();
    }

    // ==================== 小件 ====================

    /** 下一个空着的 {@code my.module_N}。 */
    private static String freeName(Modules modules) {
        for (int n = 1; ; n++) {
            String name = Modules.MINE + "module_" + n;
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
