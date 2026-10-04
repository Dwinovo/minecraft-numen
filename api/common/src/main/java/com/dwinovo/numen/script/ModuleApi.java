package com.dwinovo.numen.script;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.sdk.ApiRegistry;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.ClientCall;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Flatten;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code numen.module}:Lua 模块({@link Modules})——每个返回一张函数表,程序里以模块名直接用。模块是主人客户端上目录里的文件,路径就是
 * 名字;随模组发布的那一套装进去(出厂的),她能看、改、存、删任何一份,改坏了的出厂模块 {@code reset} 还原。都在主人客户端执行(她的
 * 大脑与 Lua 虚拟机就在那儿),当场回,不占身体;存、改、删不问主人,每一次写进回执与客户端日志。每份都记战绩:用到它的程序跑了几段、
 * 跑完几段、最近一次在什么时候、最近一段没跑完停在哪一行、为什么——只给事实,不替她评判。战绩由跑程序的大脑在程序结束时记
 * ({@link Modules#tally})。
 */
public final class ModuleApi {

    /** 组名。 */
    public static final String GROUP = "module";

    private ModuleApi() {}

    /** 清单里的一份。 */
    public record ModuleInfo(String name,
                         @Doc("Its first comment line.") String summary,
                         @Doc("built in, built in and changed, deleted, or yours; and whether it compiles.")
                         String whose,
                         @Doc("How many programs used it.") int runs,
                         @Doc("How many of those ran to the end.") int finished,
                         @Doc("When a program last used it, epoch milliseconds.") Optional<Long> lastRun,
                         @Doc("The line the last unfinished program stopped at.") Optional<Integer> failedLine,
                         @Doc("Why it stopped there.") Optional<String> failedWhy) {}

    @Fn("The modules: what each does, whose it is, how the programs that used it went.")
    @Example("numen.module.list()")
    @Example("for _, m in ipairs(numen.module.list()) do print(m.name, m.whose, m.finished .. \"/\" .. m.runs) end")
    @Note("Instant and read-only. numen.api.help(\"<module>\") lists a module's functions with their types.")
    @SeeAlso("numen.module.show")
    public static List<ModuleInfo> list(ClientCall call) {
        Modules modules = Modules.of(call.companion());
        List<ModuleInfo> out = new ArrayList<>();
        java.util.SortedSet<String> names = new java.util.TreeSet<>(modules.all().keySet());
        BuiltinModules.all().keySet().stream().filter(modules::deletedFactory).forEach(names::add);
        for (String name : names) {
            Modules.Module m = modules.get(name);
            String summary = m != null ? m.summary() : BuiltinModules.get(name).summary();
            out.add(listed(name, summary == null ? "" : summary, whose(m, name), modules.stats(name)));
        }
        return out;
    }

    private static ModuleInfo listed(String name, String summary, String whose, Modules.Stats stats) {
        return new ModuleInfo(name, summary, whose, stats.runs(), stats.ok(),
                stats.runs() == 0 ? Optional.empty() : Optional.of(stats.lastRun()),
                stats.failedWhy() == null ? Optional.empty() : Optional.of(stats.failedLine()),
                Optional.ofNullable(stats.failedWhy()));
    }

    /** 谁的、和出厂的那份是什么关系、读不读得通;{@code m} 是 null 的是删掉的出厂模块。 */
    private static String whose(Modules.Module m, String name) {
        if (m == null) {
            return "built in, deleted; " + Call.of("numen.module.reset", name) + " brings it back";
        }
        String whose = switch (m.origin()) {
            case FACTORY -> "built in";
            case HERS -> "yours";
            case CHANGED -> "built in, changed" + (m.newer() ? "; a newer built-in version shipped and is not used "
                    + "because yours is changed: " + Call.of("numen.module.show", name, Map.of("factory", true))
                    + " shows it, " + Call.of("numen.module.reset", name) + " takes it" : "");
        };
        return m.problem() == null ? whose : whose + "; does not compile: " + m.problem();
    }

    /** 点名一份模块。 */
    public record Named(@Doc("The module, as numen.module.list lists it (numen.work, my.lumber).") String name) {}

    /** 看哪一份。 */
    public record Show(@Doc("The module, as numen.module.list lists it (numen.work, my.lumber).") String name,
                       @Doc("Show the text it shipped with this version, not the file as it is now.")
                       @Omitted("show the file as it is now") Optional<Boolean> factory) {}

    /** 一份模块的全文与战绩。 */
    public record Shown(@Flatten ModuleInfo module,
                        @Doc("Whether the text is the one it shipped with.") boolean factory,
                        @Doc("The whole module.") String code) {}

    @Fn("Show a module in full, with how the programs that used it went.")
    @Example("print(numen.module.show(\"numen.work\").code)")
    @Example("print(numen.module.show(\"numen.work\", {factory = true}).code)")
    @Note("Instant and read-only. Read one before you change it or write your own: it shows how its functions are "
            + "put together.")
    @SeeAlso("numen.module.save")
    public static Shown show(ClientCall call, Show args) {
        Modules modules = Modules.of(call.companion());
        String name = args.name();
        boolean factory = args.factory().orElse(false);
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        if (factory && builtin == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "nothing named " + name + " ships with this version",
                    Call.of("numen.module.show", name));
        }
        Modules.Module m = modules.get(name);
        if (m == null && !factory) {
            throw new ApiError(ErrorKind.NOT_FOUND, missing(name, modules), modules.deletedFactory(name)
                    ? Call.of("numen.module.reset", name) : Call.of("numen.module.list"));
        }
        String summary = factory ? builtin.summary() : m.summary();
        return new Shown(listed(name, summary == null ? "" : summary, factory ? "as it shipped with this version"
                : whose(m, name), modules.stats(name)), factory, factory ? builtin.code() : m.code());
    }

    /** 存什么、存在哪个名字下。 */
    public record Save(@Doc("The module: a first comment line saying what it does, functions put in a table, and "
            + "that table returned (local M = {} … function M.chop(tree) … end … return M).") String code,
                       @Doc("Name to keep it under, the name programs use it by: namespace.group, each lowercase "
                               + "letters, digits and _ (my.lumber for one of your own; numen.work changes that "
                               + "built-in one).")
                       @Omitted("give it the next free name my.module_1, my.module_2, …") Optional<String> name) {}

    /** 存下的那一份。 */
    public record Saved(@Doc("The name it is kept under.") String name,
                        @Doc("new, replaced (a file of that name was there; its record starts over), or changed (a "
                                + "built-in module: numen.module.reset(name) puts it back).") Modules.Saved how) {}

    @Fn("Keep a module under a name: programs then use its functions by that name. Saving under a built-in module's "
            + "name changes that module.")
    @Example("numen.module.save([[\n-- Clearing a pit.\nlocal M = {}\n---Dig out every block given.\n"
            + "function M.clear(blocks)\n  numen.work.dig(blocks)\nend\nreturn M\n]], {name = \"my.pit\"})")
    @Note("Instant. The module is read and loaded once first, and not kept if it does not compile, does not return a "
            + "table, or redefines an API function; the error says the line. Its first line is a comment saying what "
            + "it does, and the comment lines above each function say what that one does: that is how the list and "
            + "numen.api.help describe them.")
    @Note("A module named after an API group (numen.move, numen.work, ...) adds its functions to that group. Saving "
            + "under a name that has a file replaces it and resets its record. Your owner can edit the files too; the "
            + "next program reads them as they are.")
    @SeeAlso({"numen.module.delete", "numen.module.reset"})
    public static Saved save(ClientCall call, Save args) {
        Modules modules = Modules.of(call.companion());
        String name = args.name().orElseGet(() -> freeName(modules));
        String code = args.code();
        String badName = Modules.problem(name);
        if (badName != null) {
            String own = name.substring(name.lastIndexOf('.') + 1);
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": " + badName,
                    Call.of("numen.module.save", code, Map.of("name", Modules.MINE + "."
                            + own.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"))));
        }
        // 读不通、不返回表、撞第 ① 层:和运行时同一个解释器装它一次,规则只在沙箱里那一处
        String problem = ScriptEngine.IN_USE.checkModule(name, code, ApiRegistry.catalog(Modules.factory()));
        if (problem != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": " + problem,
                    "fix that line, then save it again");
        }
        if (ScriptEngine.IN_USE.summary(code) == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "did not save " + name + ": start it with a comment line "
                    + "saying what it does", ScriptEngine.IN_USE.comment("Dig every block given, nearest first."));
        }
        Modules.Saved saved = modules.save(name, code);
        Constants.LOG.info("[numen-script] {} {} in {}", saved, name, modules.dir());
        return new Saved(name, saved);
    }

    @Fn("Delete a module. A built-in one stays deleted until you reset it.")
    @Example("numen.module.delete(\"my.pit\")")
    @Note("Instant.")
    @SeeAlso({"numen.module.list", "numen.module.reset"})
    public static void delete(ClientCall call, Named args) {
        Modules modules = Modules.of(call.companion());
        if (modules.delete(args.name()) == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, missing(args.name(), modules), Call.of("numen.module.list"));
        }
        Constants.LOG.info("[numen-script] deleted {} in {}", args.name(), modules.dir());
    }

    @Fn("Put a built-in module back as it shipped with this version: your changes go, a deleted one comes back.")
    @Example("numen.module.reset(\"numen.work\")")
    @Note("Instant. numen.module.list says which built-in ones you changed and which have a newer version you have "
            + "not taken because you changed yours.")
    @SeeAlso("numen.module.list")
    public static void reset(ClientCall call, Named args) {
        Modules modules = Modules.of(call.companion());
        if (modules.reset(args.name()) == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "nothing named " + args.name() + " ships with this version; only "
                    + "a built-in module can be reset", Call.of("numen.module.list"));
        }
        Constants.LOG.info("[numen-script] reset {} in {}", args.name(), modules.dir());
    }

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
