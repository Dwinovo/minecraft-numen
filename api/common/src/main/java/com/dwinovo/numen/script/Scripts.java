package com.dwinovo.numen.script;

import com.dwinovo.numen.agent.lua.LuaRun;
import com.dwinovo.numen.agent.lua.ScriptCall;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Names;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskResult;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

/**
 * {@code script}:脚本这个名词——有名字的 Lua 程序。内置的随模组发布、只读({@link BuiltinScripts});同伴存下的归主人
 * ({@link ScriptStore}),同一主人的同伴都看得见、都跑得了,存的那只改得了、删得了,改删别的同伴存的那份经权限层
 * ({@code edit_script},出厂要问主人)。每份脚本都记战绩:跑了几次、跑完几次、最近一次在什么时候、最近一次没跑完停在哪一行、
 * 为什么——只给事实,不替她评判。
 *
 * <p>命令层无状态:每一行都点名脚本。看({@code list}、{@code show})当场回;存、删先过权限层再当场回;都不占身体。
 * {@code run} 只按名字找到那份脚本、把正文与参数交回——跑它的是派发这次调用的大脑({@code SerialCalls}),它在命令之间等身体
 * 收尾、被打断时停下,跑完把战绩记回这里({@link #tally})。
 */
public final class Scripts {

    private static final String GROUP = "script";

    private static final Param<String> NAME = Param.required("name", ArgType.word(),
            "The script, as `script list` lists it.").values("a script name, as `script list` lists it");
    private static final Param<String> NEW_NAME = Param.required("name", ArgType.word(),
            "Name to keep it under: lowercase letters, digits, _ and -.");
    private static final Param<String> CODE = Param.required("code", ArgType.text(),
            "The Lua program, the same as you would give the lua tool. Its first line is a -- comment saying what it "
                    + "does.");
    private static final Param<String> RUN = Param.required("script", ArgType.text(),
            "The script's name, then its arguments: mine ores.");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);
    /** 清单里一次失败的原因最多写多长;全文在 {@code script show} 里。 */
    private static final int WHY_IN_LIST = 100;

    private Scripts() {}

    /** 经插件那扇门登记这一组。 */
    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Lua scripts kept by name — built in, and saved by your owner's companions — "
                + "and how their runs went.", Scripts::actions);
        numen.contributeBodyState(Scripts::savedIndex);
    }

    private static void actions(CommandGroup script) {
        script.server("list", "The scripts you can run, one line each: what it does, whose it is, how its runs went.",
                        (src, args) -> src.reply(list(src.companion(), args)), Listing.PAGE)
                .example("script list")
                .note("Instant and read-only.")
                .seeAlso("script show", "script run");
        script.server("show", "Show a script in full, with how its runs went.",
                        (src, args) -> src.reply(show(src.companion(), args.get(NAME))), NAME)
                .example("script show mine")
                .note("Instant and read-only. Read one before running it for the first time, and before you write "
                        + "your own version of it.")
                .seeAlso("script run", "script save");
        script.server("run", "Run a script by name with arguments; its receipt says how it ended, line by line.",
                        Scripts::run, RUN)
                .example("script run mine ores")
                .note("Runs like a program you give the lua tool: one command at a time, each waiting for the work "
                        + "it starts to finish, stopping between commands when your owner speaks or something urgent "
                        + "comes up. In a Lua program, script.run(\"mine\", \"ores\") runs it the same way.")
                .note("The script reads its arguments as ... and arg.")
                .seeAlso("script show", "script list");
        script.server("save", "Keep a Lua program under a name so you can run it again.",
                        Scripts::save, NEW_NAME, CODE)
                .example("script save sweep -- Dig every part of an area, nearest first.\n"
                        + "for _, p in ipairs(area.parts(...)) do work.dig(p) end")
                .note("Instant. The program is read first and not kept if it does not compile; the error says the "
                        + "line. Its first line is a -- comment saying what it does: that is how the list describes "
                        + "it.")
                .note("Saving under the name of one you saved replaces it and resets its record. Built-in scripts "
                        + "are read-only: save your own version under another name. Changing one another companion "
                        + "saved asks your owner first.")
                .seeAlso("script run", "script delete");
        script.server("delete", "Delete a script you or another companion saved.",
                        (src, args) -> delete(src, args.get(NAME)), NAME)
                .example("script delete sweep")
                .note("Instant. Built-in scripts cannot be deleted. Deleting one another companion saved asks your "
                        + "owner first.")
                .seeAlso("script list");
    }

    // ==================== 看 ====================

    private static String list(NumenPlayer her, CommandArgs args) {
        ScriptStore store = store(her);
        List<String> rows = new ArrayList<>();
        BuiltinScripts.all().forEach((name, b) -> rows.add(name + " — " + b.summary() + " [built in] "
                + record(store.stats(name), true)));
        store.saved().forEach((name, s) -> rows.add(name + " — " + s.summary() + " [saved by " + s.authorName()
                + "] " + record(store.stats(name), true)));
        String head = rows.isEmpty() ? "No scripts yet. Write a Lua program with the lua tool, then keep it with "
                + "script save <name> <code>." : rows.size() + " script" + (rows.size() == 1 ? "" : "s")
                + ". script show <name> prints one; script run <name> [args] runs it.";
        return new Listing(head, rows, "", GROUP + " list").result(args).toJson();
    }

    private static String show(NumenPlayer her, String name) {
        ScriptStore store = store(her);
        BuiltinScripts.Builtin builtin = BuiltinScripts.get(name);
        ScriptStore.Saved saved = store.get(name);
        if (builtin == null && saved == null) {
            return TaskResult.fail(missing(name, store)).toJson();
        }
        String whose = builtin != null ? "built in, read-only"
                : "saved by " + saved.authorName() + " on " + WHEN.format(Instant.ofEpochMilli(saved.savedAt()));
        String code = builtin != null ? builtin.code() : saved.code();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("script", name);
        data.put("builtin", builtin != null);
        return TaskResult.ok("Script " + name + " (" + whose + "). " + record(store.stats(name), false) + "\n"
                + code.stripTrailing(), data).toJson();
    }

    /** 战绩,只给事实。{@code brief} 是清单里那一版:原因截短。 */
    private static String record(ScriptStore.Stats stats, boolean brief) {
        if (stats.runs() == 0) {
            return "Never run.";
        }
        StringBuilder sb = new StringBuilder("Runs: ").append(stats.runs()).append(", ran to the end: ")
                .append(stats.ok()).append(", last run ").append(WHEN.format(Instant.ofEpochMilli(stats.lastRun())))
                .append('.');
        if (stats.failedWhy() != null) {
            String why = stats.failedWhy();
            if (brief && why.length() > WHY_IN_LIST) {
                why = why.substring(0, WHY_IN_LIST) + "...";
            }
            sb.append(" Last unfinished run stopped at line ").append(stats.failedLine()).append(": ").append(why);
        }
        return sb.toString();
    }

    // ==================== 跑 ====================

    /** 按名字找到那份脚本,把正文与参数交回给派发这次调用的大脑去跑(回执的形状见 {@link ScriptCall#toRun})。 */
    private static void run(ServerSource src, CommandArgs args) {
        List<String> words = Arrays.stream(args.get(RUN).strip().split("\\s+")).toList();
        String name = words.get(0);
        ScriptStore store = store(src.companion());
        BuiltinScripts.Builtin builtin = BuiltinScripts.get(name);
        ScriptStore.Saved saved = store.get(name);
        if (builtin == null && saved == null) {
            src.reply(TaskResult.fail(missing(name, store)).toJson());
            return;
        }
        Map<String, Object> run = new LinkedHashMap<>();
        run.put(ScriptCall.RUN_SCRIPT, name);
        run.put(ScriptCall.RUN_CODE, builtin != null ? builtin.code() : saved.code());
        run.put(ScriptCall.RUN_ARGS, words.subList(1, words.size()));
        src.reply(TaskResult.ok("Script " + name + " is handed to the brain that runs scripts; it has not run yet.",
                Map.of(ScriptCall.RUN, run)).toJson());
    }

    /**
     * 记一次运行:大脑跑完一份有名字的脚本(跑完、出错或被停下)时经网络送来。记在她主人的那一份里;她没有主人就不记。
     */
    public static void tally(NumenPlayer her, String name, boolean ok, int line, String why) {
        UUID owner = her.getOwnerUuid();
        if (owner == null || !Names.valid(name)) {
            return;
        }
        ScriptStore.of(her.getServer(), owner).tally(name, ok, line, why, System.currentTimeMillis());
    }

    // ==================== 存与删 ====================

    private static void save(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        String name = Names.checked("script", args.get(NEW_NAME));
        String code = args.get(CODE);
        if (BuiltinScripts.get(name) != null) {
            src.reply(TaskResult.fail(name + " is a built-in script and read-only; save your version under another "
                    + "name, e.g. script save my-" + name + " <code>.").toJson());
            return;
        }
        String problem = LuaRun.check(name, code);
        if (problem != null) {
            src.reply(TaskResult.fail("did not save " + name + ": it does not compile: " + problem).toJson());
            return;
        }
        String summary = LuaRun.summary(code);
        if (summary == null) {
            src.reply(TaskResult.fail("did not save " + name + ": start it with a -- comment line saying what it "
                    + "does, e.g. -- Dig every part of an area, nearest first.").toJson());
            return;
        }
        ScriptStore store = store(her);
        ScriptStore.Saved before = store.get(name);
        src.authorize(Action.editScript(name, before == null ? null : before.author()), "script save " + name,
                allowed -> {
                    store.put(name, new ScriptStore.Saved(code, summary, her.getUUID(),
                            her.getGameProfile().getName(), System.currentTimeMillis()));
                    allowed.reply(TaskResult.ok((before == null ? "Saved script " : "Replaced script ") + name + ": "
                            + summary + " Run it with script run " + name + " [args], or script.run(\"" + name
                            + "\", ...) in a Lua program.").toJson());
                });
    }

    private static void delete(ServerSource src, String name) {
        NumenPlayer her = src.companion();
        if (BuiltinScripts.get(name) != null) {
            src.reply(TaskResult.fail(name + " is a built-in script; built-in scripts cannot be deleted.").toJson());
            return;
        }
        ScriptStore store = store(her);
        ScriptStore.Saved saved = store.get(name);
        if (saved == null) {
            src.reply(TaskResult.fail(missing(name, store)).toJson());
            return;
        }
        src.authorize(Action.editScript(name, saved.author()), "script delete " + name, allowed -> {
            store.delete(name);
            allowed.reply(TaskResult.ok("Deleted script " + name + ", saved by " + saved.authorName() + ".").toJson());
        });
    }

    // ==================== 索引 ====================

    /**
     * 系统提示里的内置脚本索引,和技能表同一种写法;没有内置脚本是空串。只随登记变,按名字排好,字节稳定。
     */
    public static String builtinIndex() {
        SortedMap<String, BuiltinScripts.Builtin> all = BuiltinScripts.all();
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<scripts>\nThe following built-in scripts are available for use with "
                + "`script run <name> [args]` (`script show <name>` prints one):");
        all.forEach((name, b) -> sb.append("\n- ").append(name).append(": ").append(b.summary()));
        return sb.append("\n</scripts>").toString();
    }

    /**
     * 她身上状态里的已存脚本索引:主人名下同伴们存的,一份一行,同一种写法;一份都没有、她没有主人是空串。只随存、删变。
     */
    public static String savedIndex(NumenPlayer her) {
        UUID owner = her.getOwnerUuid();
        if (owner == null) {
            return "";
        }
        SortedMap<String, ScriptStore.Saved> saved = ScriptStore.of(her.getServer(), owner).saved();
        if (saved.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<saved_scripts>\nThe following scripts your owner's companions saved "
                + "are available for use with `script run <name> [args]`:");
        saved.forEach((name, s) -> sb.append("\n- ").append(name).append(": ").append(s.summary()));
        return sb.append("\n</saved_scripts>").toString();
    }

    // ==================== 小件 ====================

    /**
     * 主人名下的脚本。
     *
     * @throws IllegalArgumentException 她还没有主人:存下的脚本归主人
     */
    private static ScriptStore store(NumenPlayer her) {
        UUID owner = her.getOwnerUuid();
        if (owner == null) {
            throw new IllegalArgumentException("saved scripts belong to your owner, and you have no owner yet");
        }
        return ScriptStore.of(her.getServer(), owner);
    }

    private static String missing(String name, ScriptStore store) {
        List<String> names = new ArrayList<>(BuiltinScripts.all().keySet());
        names.addAll(store.saved().keySet());
        return "there is no script named " + name + (names.isEmpty() ? "; none are saved yet"
                : "; there are: " + String.join(", ", names)) + ".";
    }
}
