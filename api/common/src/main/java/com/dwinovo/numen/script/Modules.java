package com.dwinovo.numen.script;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 她能用的 Lua 模块:每个模块返回一张函数表,程序里以模块名直接用({@code numen.work.collect()}),和第 ① 层的组同名的给那一组加函数。
 *
 * <h2>名字就是路径,只读磁盘</h2>
 * 目录按主人分({@code config/numen/lua/<主人>/}),同一主人的同伴共用,主人也能拿编辑器直接改。模块名两段 {@code 名字空间.组},文件就在
 * {@code <名字空间>/<组>.lua}:{@code numen/work.lua} 是 {@code numen.work},{@code tlm/skin.lua} 是 {@code tlm.skin},
 * {@code my/lumber.lua} 是 {@code my.lumber}。程序运行时只读这个目录这一个来源;随模组与插件发布的模块({@link BuiltinModules},
 * 下称"出厂的")只是安装包。她能增删改其中任何一个文件,定死不可覆盖的只有 Java 登记的函数(沙箱那一处管)。
 *
 * <h2>安装与升级(照 dpkg 的 conffile)</h2>
 * 这个进程第一次用到一个目录时,把出厂的每一份装进它的文件({@link #install}),账本 {@code modules.json} 记下每个文件上次装进去的出厂
 * 指纹:
 * <ul>
 *   <li>文件还是上次出厂的样子:换成这一版出厂的;</li>
 *   <li>文件被改过:留着她的,出厂那份变了就标"出厂有新版",{@code numen.module.reset} 还原;</li>
 *   <li>文件被删了:尊重删除,不再装回,{@code reset} 装回;</li>
 *   <li>她新建的文件撞上新出厂的同名一份:当作改过处理;</li>
 *   <li>出厂不再发的一份:没改过的删掉,改过的留给她。</li>
 * </ul>
 * 评测与 GameTest 每次给一个全新的空目录,装进去的是没改过的出厂一套。
 *
 * <h2>账本</h2>
 * 账本也记每份跑过的战绩。它只记事实,读不通就当没有。
 *
 * <p>它就是程序的模块来源({@link ScriptCatalog.ModuleSource}):程序用到一个模块时才来读那个文件。
 *
 * <p>线程:装、存、删、记战绩在大脑那一侧的线程上;读可以在脚本的线程上。改文件与账本的几处串行。
 */
public final class Modules implements ScriptCatalog.ModuleSource {

    /** 这一份和出厂的是什么关系。 */
    public enum Origin {
        /** 出厂装进来的,没改过。 */
        FACTORY,
        /** 出厂装进来的,她(或主人)改过。 */
        CHANGED,
        /** 她自己的:出厂没有这一份。 */
        HERS
    }

    /**
     * 磁盘上的一份。
     *
     * @param summary 正文开头那行注释;没写是 null
     * @param newer   出厂的那份在装进来之后变了,她这份是改过的,没跟着换
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

    /** 存一份的结局:新的、换掉了她自己的、改了出厂的。 */
    public enum Saved { NEW, REPLACED, CHANGED }

    /** 她自己的模块习惯放的名字空间:没写名字存成 {@code my.module_N}。 */
    public static final String MINE = "my";

    private static final String LEDGER = "modules.json";
    /** 账本里一份上次装进去的出厂指纹;她新建的撞上出厂同名的,记成空串(没有哪一版是它的底)。 */
    private static final String FACTORY = "factory";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    /** 这个进程里已经装过的目录。 */
    private static final Set<Path> INSTALLED = ConcurrentHashMap.newKeySet();

    /** 模块被存、删、还原的次数,这个进程里一共:变了,说明跑着的程序之后读到的模块可能不是它开跑时的那一份。 */
    private static final AtomicLong REVISION = new AtomicLong();

    /** 每只同伴的模块目录(同一主人的同伴是同一个);由大脑所在的那一侧注入,见 {@link #init}。 */
    private static Function<UUID, Path> dirs;

    private final Path dir;

    private Modules(Path dir) {
        this.dir = dir;
    }

    /**
     * 模块目录在哪:主人客户端给 {@code config/numen/lua/<主人>/};评测与 GameTest 给这一次运行专用、开场清空的目录。
     */
    public static void init(Function<UUID, Path> companionDirs) {
        dirs = companionDirs;
    }

    /** 这只同伴能用的模块;这个进程第一次用到这个目录时先把出厂的装进去。{@link #init} 之前调用是编程错误。 */
    public static Modules of(UUID companion) {
        if (dirs == null) {
            throw new IllegalStateException("Modules.init(...) 还没被调用——大脑所在的那一侧该在启动时注入模块目录");
        }
        return at(dirs.apply(companion));
    }

    /** 直接给一个目录;这个进程第一次用到它时先装出厂的。 */
    static Modules at(Path dir) {
        Modules modules = new Modules(dir);
        if (INSTALLED.add(dir.toAbsolutePath().normalize())) {
            modules.install();
        }
        return modules;
    }

    /** 只有出厂那一套:读随模组发布的文字(技能、提示里的例子)时用,不看谁的目录。 */
    public static Modules factory() {
        return new Modules(null);
    }

    /** 目录本身(回执里告诉她文件在哪);只有出厂那一套时是 null。 */
    public Path dir() {
        return dir;
    }

    // ==================== 名字 ====================

    /** 一个模块名合不合规矩:两段 {@code 名字空间.组},写得出来、不撞语言与引擎的全局。能是 null,不能是那句话。 */
    public static String problem(String name) {
        return ScriptEngine.IN_USE.moduleName(name);
    }

    // ==================== 安装 ====================

    /** 把出厂的一套照上面的升级规则装进目录。 */
    void install() {
        synchronized (LOCK) {
            JsonObject ledger = ledger();
            SortedMap<String, BuiltinModules.Builtin> shipped = BuiltinModules.all();
            shipped.forEach((name, builtin) -> {
                String now = fingerprint(builtin.code());
                JsonObject entry = entry(ledger, name);
                String disk = read(file(name));
                if (!entry.has(FACTORY)) {
                    if (disk == null) {
                        write(file(name), builtin.code());
                        entry.addProperty(FACTORY, now);
                    } else {
                        // 她新建的撞上新出厂的同名一份:当作改过的
                        entry.addProperty(FACTORY, now.equals(fingerprint(disk)) ? now : "");
                    }
                } else if (disk != null) {
                    String base = entry.get(FACTORY).getAsString();
                    String mine = fingerprint(disk);
                    if (mine.equals(now)) {
                        entry.addProperty(FACTORY, now);
                    } else if (mine.equals(base)) {
                        write(file(name), builtin.code());
                        entry.addProperty(FACTORY, now);
                    }
                }
                ledger.add(name, entry);
            });
            for (String name : new ArrayList<>(ledger.keySet())) {
                JsonObject entry = entry(ledger, name);
                if (shipped.containsKey(name) || !entry.has(FACTORY)) {
                    continue;
                }
                // 出厂不再发它:没改过的跟着走,改过的成了她自己的
                String disk = read(file(name));
                if (disk != null && fingerprint(disk).equals(entry.get(FACTORY).getAsString())) {
                    deleteFile(file(name));
                    ledger.remove(name);
                } else if (disk == null) {
                    ledger.remove(name);
                } else {
                    entry.remove(FACTORY);
                }
            }
            writeLedger(ledger);
        }
    }

    // ==================== 读 ====================

    /** 叫这个名字的那一份;磁盘上没有是 null。只有出厂那一套时读出厂的正文。 */
    public Module get(String name) {
        if (problem(name) != null) {
            return null;
        }
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        if (dir == null) {
            return builtin == null ? null : new Module(name, builtin.code(), builtin.summary(), Origin.FACTORY, false,
                    null);
        }
        String code = read(file(name));
        if (code == null) {
            return null;
        }
        JsonObject entry = entry(ledger(), name);
        String base = entry.has(FACTORY) ? entry.get(FACTORY).getAsString() : null;
        Origin origin = base == null || builtin == null ? Origin.HERS
                : fingerprint(code).equals(base) ? Origin.FACTORY : Origin.CHANGED;
        boolean newer = origin == Origin.CHANGED && !base.equals(fingerprint(builtin.code()));
        return new Module(name, code, ScriptEngine.IN_USE.summary(code), origin, newer,
                ScriptEngine.IN_USE.check(name, code));
    }

    /** 出厂的这一份她删掉了(装过、文件不在了),{@code reset} 能装回。 */
    public boolean deletedFactory(String name) {
        return dir != null && BuiltinModules.get(name) != null && read(file(name)) == null
                && entry(ledger(), name).has(FACTORY);
    }

    /** 程序用到这个名字时装的正文:磁盘上此刻的那一份。 */
    @Override
    public String code(String name) {
        Module m = get(name);
        return m == null ? null : m.code();
    }

    @Override
    public List<String> names() {
        return List.copyOf(all().keySet());
    }

    /** 见 {@link #REVISION}。 */
    public static long revision() {
        return REVISION.get();
    }

    /**
     * 每个模块此刻的正文,按名字排:程序开跑时连同指纹一起交给服务端。只有出厂那一套时是出厂的。
     */
    public SortedMap<String, String> sources() {
        SortedMap<String, String> out = new TreeMap<>();
        if (dir == null) {
            BuiltinModules.all().forEach((name, builtin) -> out.put(name, builtin.code()));
            return out;
        }
        for (String name : onDisk()) {
            String code = read(file(name));
            if (code != null) {
                out.put(name, code);
            }
        }
        return out;
    }

    /** 全部的,按名字排:目录里每个 {@code <名字空间>/<组>.lua};只有出厂那一套时是出厂的。 */
    public SortedMap<String, Module> all() {
        SortedMap<String, Module> out = new TreeMap<>();
        if (dir == null) {
            BuiltinModules.all().keySet().forEach(name -> out.put(name, get(name)));
            return Collections.unmodifiableSortedMap(out);
        }
        for (String name : onDisk()) {
            Module m = get(name);
            if (m != null) {
                out.put(name, m);
            }
        }
        return Collections.unmodifiableSortedMap(out);
    }

    /** 目录里的模块文件:每个 {@code <名字空间>/<组>.lua},名字合规矩的。 */
    private List<String> onDisk() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String ext = ScriptEngine.IN_USE.extension();
        try (Stream<Path> spaces = Files.list(dir)) {
            for (Path space : spaces.filter(Files::isDirectory).sorted().toList()) {
                try (Stream<Path> files = Files.list(space)) {
                    for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                        String file = f.getFileName().toString();
                        if (!file.endsWith(ext)) {
                            continue;
                        }
                        String name = space.getFileName() + "." + file.substring(0, file.length() - ext.length());
                        if (problem(name) == null) {
                            out.add(name);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读不了模块目录 " + dir, e);
        }
        return out;
    }

    // ==================== 存、删、还原 ====================

    /**
     * 存一份:写成目录里的文件({@link #file}),同名的换掉。正文变了,旧战绩说的是旧正文,一并清掉。正文读不读得通、撞没撞第 ① 层由存的
     * 那一方先查。
     *
     * @throws IllegalArgumentException 名字不合规矩({@link #problem})
     */
    public Saved save(String name, String code) {
        String bad = problem(name);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        requireDir();
        synchronized (LOCK) {
            boolean existed = Files.exists(file(name));
            write(file(name), code);
            JsonObject ledger = ledger();
            JsonObject entry = entry(ledger, name);
            clearStats(entry);
            ledger.add(name, entry);
            writeLedger(ledger);
            REVISION.incrementAndGet();
            return BuiltinModules.get(name) != null && entry.has(FACTORY) ? Saved.CHANGED
                    : existed ? Saved.REPLACED : Saved.NEW;
        }
    }

    /**
     * 删掉目录里的那一份,连同它的战绩;出厂的那份记着删过,不再装回({@link #reset} 装回)。
     *
     * @return 删掉之前的正文;目录里没有这一份是 null
     */
    public String delete(String name) {
        if (problem(name) != null || dir == null) {
            return null;
        }
        synchronized (LOCK) {
            String before = read(file(name));
            if (before == null) {
                return null;
            }
            deleteFile(file(name));
            JsonObject ledger = ledger();
            JsonObject entry = entry(ledger, name);
            if (entry.has(FACTORY)) {
                clearStats(entry);
                ledger.add(name, entry);
            } else {
                ledger.remove(name);
            }
            writeLedger(ledger);
            REVISION.incrementAndGet();
            return before;
        }
    }

    /**
     * 还原成这一版出厂的:改过的换回去,删掉的装回来,战绩从头记。
     *
     * @return 出厂的正文;出厂没有这一份是 null
     */
    public String reset(String name) {
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        if (builtin == null) {
            return null;
        }
        requireDir();
        synchronized (LOCK) {
            write(file(name), builtin.code());
            JsonObject ledger = ledger();
            JsonObject entry = new JsonObject();
            entry.addProperty(FACTORY, fingerprint(builtin.code()));
            ledger.add(name, entry);
            writeLedger(ledger);
            REVISION.incrementAndGet();
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
        if (dir == null) {
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

    private void requireDir() {
        if (dir == null) {
            throw new IllegalStateException("只有出厂那一套的模块存不进东西");
        }
    }

    /** 一个模块名的文件:{@code my.lumber} 在 {@code my/lumber.lua},{@code numen.work} 在 {@code numen/work.lua}。 */
    private Path file(String name) {
        int dot = name.indexOf('.');
        return dir.resolve(name.substring(0, dot)).resolve(name.substring(dot + 1) + ScriptEngine.IN_USE.extension());
    }

    /** 账本里这一份的那一项;没有是一张新的空表(还没放进账本)。 */
    private static JsonObject entry(JsonObject ledger, String name) {
        return ledger.get(name) instanceof JsonObject e ? e : new JsonObject();
    }

    /** 文件的正文;不在是 null。 */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("读不了 " + file, e);
        }
    }

    private static void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写不了 " + file, e);
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("删不了 " + file, e);
        }
    }

    private JsonObject ledger() {
        if (dir == null) {
            return new JsonObject();
        }
        String text = read(dir.resolve(LEDGER));
        if (text == null) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-script] 模块账本读不通,当作空的: {}", dir.resolve(LEDGER));
            return new JsonObject();
        }
    }

    private void writeLedger(JsonObject ledger) {
        write(dir.resolve(LEDGER), GSON.toJson(ledger));
    }

    /**
     * 一份正文的指纹:SHA-256 的前 12 位十六进制。账本记的出厂指纹、服务端按内容缓存模块时的键都是它。缓存按主人分、里面只有主人
     * 自己送来的正文,没有谁要伪造冲突;48 位对一个主人的几百份正文,偶然撞上的概率可以忽略,所以不为缓存另造一个更长的哈希。
     */
    public static String fingerprint(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
