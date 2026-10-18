package com.dwinovo.numen.api.script;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.script.ScriptCatalog;
import com.dwinovo.numen.script.ScriptEngine;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * 她能用的 Lua 模块:每个模块返回一张函数表,程序里以模块名直接用({@code numen.work.collect()}),和第 ① 层的组同名的给那一组加函数。
 *
 * <h2>一个库:内置加我的</h2>
 * 模块是一种库({@link #KIND}):名字两段 {@code 名字空间.组},文件在 {@code <库>/<名字空间>/<组>.lua},{@code numen/work.lua} 是
 * {@code numen.work},{@code my/lumber.lua} 是 {@code my.lumber}。她看到两组叠在一起:<b>内置</b>是服务器目录里的(随模组与插件发布,
 * 管理员管),<b>我的</b>是主人目录里的;同名时我的优先。她不碰服务器目录:存一份和内置同名的,写进主人的"我的"(复制再改),
 * 程序用的就是改过的;{@code reset} 删掉我的那份,内置的重新露出来。她删一份内置的,是对她自己把它关掉(主人的开关),
 * 内置的文件原样在服务器上,{@code reset} 打开。定死不可覆盖的只有 Java 登记的函数(沙箱那一处管)。
 *
 * <h2>战绩与"内置有新版"</h2>
 * 账本 {@code modules.json}(在主人目录的模块库里)记每份跑过的战绩,以及她改一份内置时那份内置的指纹——后来内置变了
 * 而她这份是改过的,就标"内置有新版"({@link Module#newer})。账本只记事实,读不通就当没有。
 *
 * <p>它就是程序的模块来源({@link ScriptCatalog.ModuleSource}):程序用到一个模块时才来读它。读的是库的内存表;手改磁盘上的
 * 文件要 {@code /reload}。
 *
 * <p>线程:存、删、记战绩在服务端主线程上;读可以在脚本的线程上。账本的读写在同一把锁里串行。
 */
public final class Modules implements ScriptCatalog.ModuleSource {

    /** 模块这种库:一条一个文件。 */
    public static final LibraryKind KIND = LibraryKind.files("modules", ScriptEngine.IN_USE.extension());

    /** 这一份和内置的是什么关系。 */
    public enum Origin {
        /** 内置的,没改过。 */
        FACTORY,
        /** 内置的,她(或主人)改过:我的盖着它。 */
        CHANGED,
        /** 她自己的:内置没有这一份。 */
        HERS
    }

    /**
     * 一份模块。
     *
     * @param summary 正文开头那行注释;没写是 null
     * @param newer   内置的那份在她改它之后变了,她这份是改过的,没跟着换
     * @param problem 正文读不通时的那句话;没事是 null
     */
    public record Module(String name, String code, String summary, Origin origin, boolean newer, String problem) {}

    /**
     * 一份跑过的战绩:只记事实。
     *
     * @param runs       跑了几次
     * @param ok         跑到最后的几次
     * @param lastRun    最近一次的时刻,epoch 毫秒;没跑过是 0
     * @param failedLine 最近一次没跑完停在哪一行;没失败过是 0
     * @param failedWhy  最近一次没跑完的原因;没失败过是 null
     */
    public record Stats(int runs, int ok, long lastRun, int failedLine, String failedWhy) {

        static final Stats NONE = new Stats(0, 0, 0, 0, null);
    }

    /** 存一份的结局:新的、换掉了她自己的、改了内置的。 */
    public enum Saved { NEW, REPLACED, CHANGED }

    /** 她自己的模块习惯放的名字空间:没写名字存成 {@code my.module_N}。 */
    public static final String MINE = EntryId.MINE;

    private static final String LEDGER = "modules.json";
    /** 账本里一份她改的内置在她改它那一刻的内置指纹。 */
    private static final String FACTORY = "factory";
    private static final Object LOCK = new Object();

    /** 每只同伴能用的模块库(同一主人的同伴是同一个);由服务端大脑的接线注入,见 {@link #init}。 */
    private static Function<UUID, Library> libraries;

    private final Library library;

    private Modules(Library library) {
        this.library = library;
    }

    /** 模块库在哪:服务器给主人的那一份(内置加我的);评测与 GameTest 给这一次运行专用的。 */
    public static void init(Function<UUID, Library> companionLibraries) {
        libraries = companionLibraries;
    }

    /** 这只同伴能用的模块。{@link #init} 之前调用是编程错误。 */
    public static Modules of(UUID companion) {
        if (libraries == null) {
            throw new IllegalStateException("Modules.init(...) 还没被调用——服务端该在启动时注入模块库");
        }
        return at(libraries.apply(companion));
    }

    /** 直接给一个模块库。 */
    public static Modules at(Library library) {
        return new Modules(library);
    }

    /** 只有随模组发布的那一套:读它们的文字(技能、提示里的例子、写法检查)时用,不看谁的库。 */
    public static Modules factory() {
        return new Modules(null);
    }

    /** 主人的"我的"目录(回执里告诉她文件在哪);只有随模组发布的那一套时是 null。 */
    public Path dir() {
        return library == null ? null : library.mine().dir();
    }

    /** 重扫库:手改了磁盘上的文件之后。 */
    public void reload() {
        requireLibrary();
        library.reload();
    }

    // ==================== 名字 ====================

    /** 一个模块名合不合规矩:两段 {@code 名字空间.组},写得出来、不撞语言与引擎的全局。能是 null,不能是那句话。 */
    public static String problem(String name) {
        return ScriptEngine.IN_USE.moduleName(name);
    }

    /** 模块名对应库里的名字;名字不合规矩是 null。 */
    private static EntryId idOf(String name) {
        if (problem(name) != null) {
            return null;
        }
        int dot = name.indexOf('.');
        return new EntryId(name.substring(0, dot), name.substring(dot + 1));
    }

    private static String nameOf(EntryId id) {
        return id.namespace() + "." + id.name();
    }

    // ==================== 读 ====================

    /** 叫这个名字的那一份;没有或被她关掉了是 null。只有随模组发布的那一套时读发布的正文。 */
    public Module get(String name) {
        EntryId id = idOf(name);
        if (id == null) {
            return null;
        }
        if (library == null) {
            BuiltinModules.Builtin builtin = BuiltinModules.get(name);
            return builtin == null ? null : new Module(name, builtin.code(), builtin.summary(), Origin.FACTORY, false,
                    null);
        }
        Library.Entry entry = library.find(id);
        if (entry == null) {
            return null;
        }
        String code = entry.text();
        Origin origin = entry.group() == Library.Group.BUILTIN ? Origin.FACTORY
                : entry.paired() ? Origin.CHANGED : Origin.HERS;
        boolean newer = false;
        if (origin == Origin.CHANGED) {
            JsonObject recorded = entry(ledger(), name);
            newer = recorded.has(FACTORY)
                    && !recorded.get(FACTORY).getAsString().equals(Fingerprint.of(library.builtin().get(id).text()));
        }
        return new Module(name, code, ScriptEngine.IN_USE.summary(code), origin, newer,
                ScriptEngine.IN_USE.check(name, code));
    }

    /** 内置的这一份(服务器目录里现在的样子);内置没有这一份是 null。只有随模组发布的那一套时是发布的正文。 */
    public Module builtin(String name) {
        EntryId id = idOf(name);
        if (id == null) {
            return null;
        }
        if (library == null) {
            return get(name);
        }
        com.dwinovo.numen.api.library.Shelf.Item item = library.builtin().get(id);
        return item == null ? null : new Module(name, item.text(), ScriptEngine.IN_USE.summary(item.text()),
                Origin.FACTORY, false, ScriptEngine.IN_USE.check(name, item.text()));
    }

    /** 她关掉的内置模块:文件还在服务器上,{@code reset} 打开。 */
    public List<String> deletedFactory() {
        List<String> out = new ArrayList<>();
        if (library != null) {
            library.builtin().items().forEach(item -> {
                if (!library.enabled(item.id()) && problem(nameOf(item.id())) == null) {
                    out.add(nameOf(item.id()));
                }
            });
        }
        return out;
    }

    public boolean deletedFactory(String name) {
        return deletedFactory().contains(name);
    }

    /**
     * 程序用到这个名字时装的正文:库里此刻的那一份。只读——能不能编译、战绩、与内置的关系({@link #get} 才算)与装进虚拟机无关,
     * 读不通就是用它的那个程序的错。
     */
    @Override
    public String code(String name) {
        Module module = get(name);
        return module == null ? null : module.code();
    }

    @Override
    public List<String> names() {
        if (library == null) {
            return List.copyOf(BuiltinModules.all().keySet());
        }
        return library.active().stream().map(e -> nameOf(e.id())).filter(n -> problem(n) == null).sorted().toList();
    }

    /** 全部的,按名字排;只有随模组发布的那一套时是发布的。 */
    public SortedMap<String, Module> all() {
        SortedMap<String, Module> out = new TreeMap<>();
        for (String name : names()) {
            Module m = get(name);
            if (m != null) {
                out.put(name, m);
            }
        }
        return Collections.unmodifiableSortedMap(out);
    }

    // ==================== 存、删、还原 ====================

    /**
     * 存一份:写进主人的"我的"({@link #KIND} 的布局),同名的换掉;和内置同名的,从此她这份盖着内置。正文变了,旧战绩说的是旧正文,
     * 一并清掉。正文读不读得通、撞没撞第 ① 层由存的那一方先查。
     *
     * @throws IllegalArgumentException 名字不合规矩({@link #problem})
     */
    public Saved save(String name, String code) {
        String bad = problem(name);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        requireLibrary();
        EntryId id = idOf(name);
        synchronized (LOCK) {
            boolean existed = library.mine().get(id) != null;
            com.dwinovo.numen.api.library.Shelf.Item builtin = library.builtin().get(id);
            library.mine().write(id, code);
            library.setEnabled(id, true);   // 她关掉的内置,自己存一份同名的就是又要它了
            JsonObject ledger = ledger();
            JsonObject entry = entry(ledger, name);
            clearStats(entry);
            if (builtin != null && !entry.has(FACTORY)) {
                entry.addProperty(FACTORY, Fingerprint.of(builtin.text()));
            }
            ledger.add(name, entry);
            writeLedger(ledger);
            return builtin != null ? Saved.CHANGED : existed ? Saved.REPLACED : Saved.NEW;
        }
    }

    /**
     * 她不要这一份了,连同它的战绩:她自己的删掉;内置的(或她改过的内置)对她关掉——服务器上的内置文件不动,
     * {@link #reset} 打开。
     *
     * @return 删掉之前的正文;她这里没有这一份是 null
     */
    public String delete(String name) {
        EntryId id = idOf(name);
        if (id == null || library == null) {
            return null;
        }
        synchronized (LOCK) {
            Library.Entry entry = library.find(id);
            if (entry == null) {
                return null;
            }
            library.mine().delete(id);
            if (library.builtin().get(id) != null) {
                library.setEnabled(id, false);
            }
            JsonObject ledger = ledger();
            ledger.remove(name);
            writeLedger(ledger);
            return entry.text();
        }
    }

    /**
     * 还原成内置的:她改过的那份删掉,关掉的打开,战绩从头记。
     *
     * @return 内置的正文;内置没有这一份是 null
     */
    public String reset(String name) {
        EntryId id = idOf(name);
        if (id == null) {
            return null;
        }
        Module builtin = builtin(name);
        if (builtin == null) {
            return null;
        }
        requireLibrary();
        synchronized (LOCK) {
            library.mine().delete(id);
            library.setEnabled(id, true);
            JsonObject ledger = ledger();
            ledger.remove(name);
            writeLedger(ledger);
            return builtin.code();
        }
    }

    // ==================== 战绩 ====================

    /** 这一份的战绩;没跑过是全零。 */
    public Stats stats(String name) {
        JsonObject e = entry(ledger(), name);
        if (!e.has("runs")) {
            return Stats.NONE;
        }
        return new Stats(e.get("runs").getAsInt(), e.get("ok").getAsInt(), e.get("last_run").getAsLong(),
                e.has("failed_line") ? e.get("failed_line").getAsInt() : 0,
                e.has("failed_why") ? e.get("failed_why").getAsString() : null);
    }

    /** 记一次运行(跑完、出错或被停下)。 */
    public void tally(String name, boolean ok, int line, String why, long at) {
        if (library == null) {
            return;
        }
        synchronized (LOCK) {
            Stats before = stats(name);
            JsonObject ledger = ledger();
            JsonObject entry = entry(ledger, name);
            entry.addProperty("runs", before.runs() + 1);
            entry.addProperty("ok", before.ok() + (ok ? 1 : 0));
            entry.addProperty("last_run", at);
            if (!ok) {
                entry.addProperty("failed_line", line);
                entry.addProperty("failed_why", why == null ? "" : why);
            }
            ledger.add(name, entry);
            writeLedger(ledger);
        }
    }

    private static void clearStats(JsonObject entry) {
        for (String key : List.of("runs", "ok", "last_run", "failed_line", "failed_why")) {
            entry.remove(key);
        }
    }

    // ==================== 小件 ====================

    private void requireLibrary() {
        if (library == null) {
            throw new IllegalStateException("只有随模组发布的那一套的模块存不进东西");
        }
    }

    /** 账本里这一份的那一项;没有是一张新的空表(还没放进账本)。 */
    private static JsonObject entry(JsonObject ledger, String name) {
        return ledger.get(name) instanceof JsonObject e ? e : new JsonObject();
    }

    private JsonObject ledger() {
        if (library == null) {
            return new JsonObject();
        }
        synchronized (LOCK) {
            try {
                return ledgerDoc().read().orElseGet(JsonObject::new);
            } catch (StoreException unreadable) {
                Constants.LOG.warn("[numen-script] 模块账本读不通,当作空的: {}", ledgerDoc().file());
                return new JsonObject();
            }
        }
    }

    private void writeLedger(JsonObject ledger) {
        ledgerDoc().write(ledger);
    }

    /** 账本写不下去要让调用方知道(模块是她自己改的真源,账本对不上就是悄悄的不一致),所以写失败原样抛出。 */
    private JsonDoc ledgerDoc() {
        return new JsonDoc(library.mine().dir().resolve(LEDGER), JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        }));
    }
}
