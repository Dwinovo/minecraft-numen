package com.dwinovo.numen.api.script;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.Fingerprint;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.library.Shelf;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.script.ScriptEngine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 她自己的 Lua 模块,在主人的客户端上:文件是它们唯一的真源,文件名就是模块名({@code my/lumber.lua} 是 {@code my.lumber})。
 * 程序在服务端跑,起跑时把这里的清单(名字与内容哈希)报过去,服务端缺哪份来要哪份(见 {@code ModuleCache});模块的存、删、还原、
 * 战绩都在这里,由客户端上的 {@code numen.module} 各函数办。
 *
 * <h2>内置与她的</h2>
 * 内置模块(随模组与插件发布)在服务端;这里只知道它们的名字(目录里有)。她存一份和内置同名的,是在这里存一份同名的盖着它;
 * 她删一份内置的,是对她自己关掉它(记在 {@code modules_disabled.json});{@code reset} 删掉她的那份、打开关掉的。内置的正文要用时
 * 向服务端取({@link Builtins})。
 *
 * <h2>战绩与"内置有新版"</h2>
 * 账本 {@code modules.json} 记每份跑过的战绩,以及她改一份内置时那份内置的指纹——后来内置变了而她这份是改过的,就标"内置有新版"。
 * 账本只记事实,读不通就当没有。
 *
 * <p>线程:任何线程;存、删、记战绩在同一把锁里串行。
 */
public final class HerModules {

    /** 模块这种库:一条一个文件,{@code <根>/modules/<名字空间>/<组>.lua}。 */
    public static final LibraryKind KIND = LibraryKind.files(BuiltinModules.DIR, ScriptEngine.IN_USE.extension());

    /** 内置模块,服务端的:名字在目录里,正文要用时去取。 */
    public interface Builtins {

        /** 内置的模块名。 */
        Set<String> names();

        /** 内置的原文有没有取齐(座位刚坐上、目录刚变的时候还没有)。 */
        boolean loaded();

        /** 这一份内置的正文;没有这一份是 null。只在 {@link #loaded} 之后读。 */
        String code(String name);
    }

    /** 一份跑过的战绩:只记事实。 */
    public record Stats(int runs, int ok, long lastRun, int failedLine, String failedWhy) {

        static final Stats NONE = new Stats(0, 0, 0, 0, null);
    }

    /** 存一份的结局:新的、换掉了她自己的、改了内置的。 */
    public enum Saved { NEW, REPLACED, CHANGED }

    private static final String LEDGER = "modules.json";
    private static final String DISABLED = "modules_disabled.json";
    /** 账本里一份她改的内置在她改它那一刻的内置指纹。 */
    private static final String FACTORY = "factory";

    private final Shelf shelf;
    private final Builtins builtins;
    private final Object lock = new Object();

    public HerModules(Home home, Builtins builtins) {
        this.shelf = new Shelf(KIND, home);
        this.builtins = builtins;
    }

    /** 重扫文件:手改了磁盘上的文件之后。 */
    public void reload() {
        shelf.reload();
    }

    /** 她的模块在磁盘上的目录(回执里告诉她文件在哪)。 */
    public Path dir() {
        return shelf.dir();
    }

    /**
     * 这一份内置的正文。
     *
     * @throws IllegalStateException 内置模块的原文还没从服务端取齐
     */
    private String builtinCode(String name) {
        if (!builtins.loaded()) {
            throw new IllegalStateException("the built-in modules have not arrived from the server yet; try again in a moment");
        }
        return builtins.code(name);
    }

    // ==================== 名字 ====================

    private static EntryId idOf(String name) {
        if (Modules.problem(name) != null) {
            return null;
        }
        int dot = name.indexOf('.');
        return new EntryId(name.substring(0, dot), name.substring(dot + 1));
    }

    private static String nameOf(EntryId id) {
        return id.namespace() + "." + id.name();
    }

    // ==================== 读 ====================

    /** 她自己的模块(含盖着内置的),名字到正文,按名字排。 */
    public Map<String, String> hers() {
        Map<String, String> out = new TreeMap<>();
        for (Shelf.Item item : shelf.items()) {
            String name = nameOf(item.id());
            if (Modules.problem(name) == null) {
                out.put(name, item.text());
            }
        }
        return out;
    }

    /** 她的模块清单:名字到内容哈希。程序起跑时报给服务端的就是它。 */
    public Map<String, String> manifest() {
        Map<String, String> out = new LinkedHashMap<>();
        hers().forEach((name, code) -> out.put(name, Fingerprint.of(code)));
        return out;
    }

    /** 她对自己关掉的内置模块。 */
    public Set<String> disabled() {
        Set<String> out = new TreeSet<>();
        if (readDisabled().get("names") instanceof JsonArray list) {
            list.forEach((JsonElement n) -> out.add(n.getAsString()));
        }
        return out;
    }

    /** 她的模块里叫这个名字的正文;没有是 null。 */
    public String code(String name) {
        EntryId id = idOf(name);
        Shelf.Item item = id == null ? null : shelf.get(id);
        return item == null ? null : item.text();
    }

    /** 叫这个名字的那一份,她看到的样子(她的,没有就是内置的没关掉的);没有或关掉了是 null。 */
    public Modules.Module get(String name) {
        String mine = code(name);
        boolean builtin = builtins.names().contains(name);
        if (mine == null) {
            if (!builtin || disabled().contains(name)) {
                return null;
            }
            return builtin(name);
        }
        Modules.Origin origin = builtin ? Modules.Origin.CHANGED : Modules.Origin.HERS;
        boolean newer = false;
        if (origin == Modules.Origin.CHANGED) {
            JsonObject recorded = entry(ledger(), name);
            String code = builtinCode(name);
            newer = recorded.has(FACTORY) && code != null
                    && !recorded.get(FACTORY).getAsString().equals(Fingerprint.of(code));
        }
        return new Modules.Module(name, mine, ScriptEngine.IN_USE.summary(mine), origin, newer,
                ScriptEngine.IN_USE.check(name, mine));
    }

    /** 内置的这一份(随模组发布的样子);内置没有这一份是 null。 */
    public Modules.Module builtin(String name) {
        if (idOf(name) == null || !builtins.names().contains(name)) {
            return null;
        }
        String code = builtinCode(name);
        return code == null ? null : new Modules.Module(name, code, ScriptEngine.IN_USE.summary(code),
                Modules.Origin.FACTORY, false, ScriptEngine.IN_USE.check(name, code));
    }

    /** 她能用的全部模块名:她自己的加上内置里没关掉的,按名字排。 */
    public List<String> names() {
        Set<String> all = new TreeSet<>(builtins.names());
        all.removeAll(disabled());
        all.addAll(hers().keySet());
        return List.copyOf(all);
    }

    /** 她关掉的内置模块名。 */
    public List<String> deletedFactory() {
        return List.copyOf(disabled());
    }

    // ==================== 存、删、还原 ====================

    /**
     * 存一份:写进她的文件,同名的换掉;和内置同名的,从此她这份盖着内置。正文变了,旧战绩说的是旧正文,一并清掉。正文读不读得通、
     * 撞没撞第 ① 层由存的那一方先查。
     *
     * @throws IllegalArgumentException 名字不合规矩({@link Modules#problem})
     */
    public Saved save(String name, String code) {
        String bad = Modules.problem(name);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        EntryId id = idOf(name);
        synchronized (lock) {
            boolean existed = shelf.get(id) != null;
            String builtin = builtins.names().contains(name) ? builtinCode(name) : null;
            shelf.write(id, code);
            setDisabled(name, false);   // 她关掉的内置,自己存一份同名的就是又要它了
            JsonObject ledger = ledger();
            JsonObject entry = entry(ledger, name);
            clearStats(entry);
            if (builtin != null && !entry.has(FACTORY)) {
                entry.addProperty(FACTORY, Fingerprint.of(builtin));
            }
            ledger.add(name, entry);
            writeLedger(ledger);
            return builtin != null ? Saved.CHANGED : existed ? Saved.REPLACED : Saved.NEW;
        }
    }

    /**
     * 她不要这一份了,连同它的战绩:她自己的删掉;内置的(或她改过的内置)对她关掉,{@link #reset} 打开。
     *
     * @return 删掉之前的正文;她这里没有这一份是 null
     */
    public String delete(String name) {
        EntryId id = idOf(name);
        if (id == null) {
            return null;
        }
        synchronized (lock) {
            String mine = code(name);
            String text = mine != null ? mine : disabled().contains(name) ? null : builtinCode(name);
            if (text == null) {
                return null;
            }
            if (mine != null) {
                shelf.delete(id);
            }
            if (builtins.names().contains(name)) {
                setDisabled(name, true);
            }
            JsonObject ledger = ledger();
            ledger.remove(name);
            writeLedger(ledger);
            return text;
        }
    }

    /**
     * 还原成内置的:她改过的那份删掉,关掉的打开,战绩从头记。
     *
     * @return 内置的正文;内置没有这一份是 null
     */
    public String reset(String name) {
        EntryId id = idOf(name);
        if (id == null || !builtins.names().contains(name)) {
            return null;
        }
        String code = builtinCode(name);
        if (code == null) {
            return null;
        }
        synchronized (lock) {
            shelf.delete(id);
            setDisabled(name, false);
            JsonObject ledger = ledger();
            ledger.remove(name);
            writeLedger(ledger);
            return code;
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
        synchronized (lock) {
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

    /** 账本里这一份的那一项;没有是一张新的空表(还没放进账本)。 */
    private static JsonObject entry(JsonObject ledger, String name) {
        return ledger.get(name) instanceof JsonObject e ? e : new JsonObject();
    }

    private JsonObject ledger() {
        synchronized (lock) {
            try {
                return doc(LEDGER).read().orElseGet(JsonObject::new);
            } catch (StoreException unreadable) {
                Constants.LOG.warn("[numen-script] the module ledger cannot be parsed, treated as empty: {}",
                        doc(LEDGER).file());
                return new JsonObject();
            }
        }
    }

    private void writeLedger(JsonObject ledger) {
        doc(LEDGER).write(ledger);
    }

    private JsonObject readDisabled() {
        try {
            return doc(DISABLED).read().orElseGet(JsonObject::new);
        } catch (StoreException unreadable) {
            Constants.LOG.warn("[numen-script] the disabled built-in modules cannot be parsed, treated as none: {}",
                    doc(DISABLED).file());
            return new JsonObject();
        }
    }

    private void setDisabled(String name, boolean off) {
        Set<String> names = disabled();
        if (off ? !names.add(name) : !names.remove(name)) {
            return;
        }
        JsonArray list = new JsonArray();
        names.forEach(list::add);
        JsonObject doc = new JsonObject();
        doc.add("names", list);
        doc(DISABLED).write(doc);
    }

    /** 写不下去要让调用方知道(模块是她自己改的真源,账本对不上就是悄悄的不一致),所以写失败原样抛出。 */
    private JsonDoc doc(String file) {
        return new JsonDoc(shelf.dir().resolve(file), JsonDoc.Format.of(1), Io.direct(e -> {
            throw e;
        }));
    }
}
