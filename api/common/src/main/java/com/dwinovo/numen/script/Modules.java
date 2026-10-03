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
import java.util.Collections;
import java.util.HexFormat;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 她能用的 Lua 模块:每个模块返回一张函数表,程序里以模块名直接用({@code work.collect()}),和第 ① 层的组同名的给那一组加函数。
 *
 * <h2>两种名字,两处文件</h2>
 * 目录按主人分({@code config/numen/lua/<主人>/}),同一主人的同伴共用,主人也能拿编辑器直接改。名字是程序里的写法,文件在哪由名字定,
 * 规矩只在这里({@link #problem}、{@link #file}):
 * <ul>
 *   <li><b>内置的</b>随模组发布、留在 jar 里({@link BuiltinModules});目录顶层一个同名文件 {@code work.lua} 就盖住它(改内置),删掉
 *       回到内置。顶层只认内置有的名字。</li>
 *   <li><b>她自己的库</b>在名字空间 {@code my} 下:模块名 {@code my.lumber},程序里 {@code my.lumber.chop(t)},文件
 *       {@code my/lumber.lua}。</li>
 * </ul>
 *
 * <h2>每次都从磁盘读</h2>
 * 不缓存正文:每次运行、每次看都读最新的文件,所以她存的、主人改的,下一次调用就生效。
 *
 * <h2>账本</h2>
 * 目录里另有一份 {@code modules.json}:每份(连同内置的)跑过的战绩,以及她盖住一份内置时那份内置正文的指纹——内置的后来变了,
 * 清单上就标"内置已有新版"。账本只记事实,读不通就当没有。
 *
 * <p>它就是程序的模块来源({@link ScriptCatalog.ModuleSource}):程序用到一个模块时才来读那个文件。
 *
 * <p>线程:存、删、记战绩在大脑那一侧的线程上;读可以在脚本的线程上。改账本的几处串行。
 */
public final class Modules implements ScriptCatalog.ModuleSource {

    /** 这一份从哪来。 */
    public enum Origin {
        /** 随模组发布的那份。 */
        BUILTIN,
        /** 她的文件,盖住了同名的内置那份。 */
        OVERRIDE,
        /** 她自己的库,在 {@code my} 下。 */
        HERS
    }

    /**
     * 生效的那一份。
     *
     * @param summary      正文开头那行注释;没写是 null
     * @param builtinNewer 盖住的那份内置在她存下之后变了
     * @param problem      名字不合规矩(顶层一个没有同名内置的文件)、或正文读不通时的那句话;都没事是 null
     */
    public record Module(String name, String code, String summary, Origin origin, boolean builtinNewer,
                         String problem) {}

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

    /** 存一份的结局:新的、换掉了她自己的旧文件、盖住了内置的。 */
    public enum Saved { NEW, REPLACED, OVERRODE }

    private static final String LEDGER = "modules.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    /** 每只同伴的模块目录(同一主人的同伴是同一个);由大脑所在的那一侧注入,见 {@link #init}。 */
    private static Function<UUID, Path> dirs;

    private final Path dir;

    private Modules(Path dir) {
        this.dir = dir;
    }

    /**
     * 模块目录在哪:主人客户端给 {@code config/numen/lua/<主人>/};评测与 GameTest 给这一次运行专用、开场清空的目录,只用内置原版。
     */
    public static void init(Function<UUID, Path> companionDirs) {
        dirs = companionDirs;
    }

    /** 这只同伴能用的模块。{@link #init} 之前调用是编程错误。 */
    public static Modules of(UUID companion) {
        if (dirs == null) {
            throw new IllegalStateException("Modules.init(...) 还没被调用——大脑所在的那一侧该在启动时注入模块目录");
        }
        return new Modules(dirs.apply(companion));
    }

    /** 直接给一个目录(单测用)。 */
    static Modules at(Path dir) {
        return new Modules(dir);
    }

    /** 只有内置那一层:读随模组发布的文字(技能、提示里的例子)时用,不看谁的目录。 */
    public static Modules builtin() {
        return new Modules(null);
    }

    /** 目录本身(回执里告诉她文件在哪);只有内置那一层时是 null。 */
    public Path dir() {
        return dir;
    }

    // ==================== 读 ====================

    // ==================== 名字 ====================

    /** 她的名字空间:模块名 {@code my.<名字>}。 */
    public static final String MINE = ScriptCatalog.ModuleSource.HERS + ".";

    /** 是不是她自己的库的名字({@code my.lumber})。 */
    public static boolean hers(String name) {
        return name != null && name.startsWith(MINE);
    }

    /**
     * 一个内置(或插件)模块的名字合不合规矩:它是一个全局名,写得出来、不撞语言与引擎的全局,不叫 {@code my}。能是 null,不能是那句话。
     */
    static String builtinProblem(String name) {
        return ScriptEngine.IN_USE.moduleName(name, true);
    }

    /**
     * 她存一份能不能用这个名字:{@code my.<名字>} 是她自己的库,名字写得出来、不是关键字就行;不带 {@code my.} 的只能是一个内置模块的名字
     * (盖住它)。能是 null,不能是那句话。
     */
    public static String problem(String name) {
        if (hers(name)) {
            return ScriptEngine.IN_USE.moduleName(name.substring(MINE.length()), false);
        }
        String bad = builtinProblem(name);
        if (bad != null) {
            return bad + "; your own modules go under " + ScriptCatalog.ModuleSource.HERS + ": " + MINE + "<name>";
        }
        return BuiltinModules.get(name) == null ? "there is no built-in module " + name + " to override; your own "
                + "modules go under " + ScriptCatalog.ModuleSource.HERS + ": " + MINE + name : null;
    }

    // ==================== 读 ====================

    /** 叫这个名字、此刻生效的那一份;没有是 null。 */
    public Module get(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
            return null;
        }
        if (hers(name)) {
            String code = dir == null ? null : read(file(name));
            if (code == null) {
                return null;
            }
            String problem = problem(name);
            return new Module(name, code, ScriptEngine.IN_USE.summary(code), Origin.HERS, false,
                    problem != null ? problem : ScriptEngine.IN_USE.check(name, code));
        }
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        String hers = dir == null ? null : read(file(name));
        if (hers == null) {
            return builtin == null ? null : new Module(name, builtin.code(), builtin.summary(), Origin.BUILTIN,
                    false, null);
        }
        boolean newer = false;
        if (builtin != null) {
            String base = ledger().get(name) instanceof JsonObject e && e.get("builtin") instanceof JsonElement b
                    ? b.getAsString() : null;
            newer = base != null && !base.equals(fingerprint(builtin.code()));
        }
        String problem = problem(name);
        return new Module(name, hers, ScriptEngine.IN_USE.summary(hers), Origin.OVERRIDE, newer,
                problem != null ? problem : ScriptEngine.IN_USE.check(name, hers));
    }

    /** 程序用到这个名字时装的正文;名字不合规矩(顶层一个没有同名内置的文件)的不装。 */
    @Override
    public String code(String name) {
        Module m = get(name);
        return m == null || problem(name) != null && m.origin() != Origin.BUILTIN ? null : m.code();
    }

    @Override
    public java.util.List<String> names() {
        return java.util.List.copyOf(all().keySet());
    }

    /** 全部生效的,按名字排:内置的(有的被她盖住)与她自己的。 */
    public SortedMap<String, Module> all() {
        SortedMap<String, Module> out = new TreeMap<>();
        for (String name : BuiltinModules.all().keySet()) {
            out.put(name, get(name));
        }
        for (String name : herNames()) {
            out.putIfAbsent(name, get(name));
        }
        return Collections.unmodifiableSortedMap(out);
    }

    /**
     * 她目录里的模块:顶层的文件(盖住内置的)与 {@code my/} 下的文件(名字写成 {@code my.<名字>});名字不合规矩的也在,清单上说它为什么
     * 用不了。
     */
    private java.util.List<String> herNames() {
        if (dir == null) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>(filesIn(dir, ""));
        out.addAll(filesIn(dir.resolve(ScriptCatalog.ModuleSource.HERS), MINE));
        return out;
    }

    private static java.util.List<String> filesIn(Path folder, String prefix) {
        if (!Files.isDirectory(folder)) {
            return java.util.List.of();
        }
        String ext = ScriptEngine.IN_USE.extension();
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(f -> f.endsWith(ext))
                    .map(f -> prefix + f.substring(0, f.length() - ext.length())).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("读不了模块目录 " + folder, e);
        }
    }

    // ==================== 存、删、还原 ====================

    /**
     * 存一份:写成她目录里的文件({@link #file}),同名的换掉;和内置同名就盖住它,记下此刻内置那份的指纹。正文变了,旧战绩说的是
     * 旧正文,一并清掉。正文读不读得通、撞没撞第 ① 层由存的那一方先查({@code Scripts})。
     *
     * @throws IllegalArgumentException 名字不合规矩({@link #problem})
     */
    public Saved save(String name, String code) {
        String bad = problem(name);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        requireDir();
        BuiltinModules.Builtin builtin = BuiltinModules.get(name);
        synchronized (LOCK) {
            boolean existed = Files.exists(file(name));
            write(file(name), code);
            JsonObject ledger = ledger();
            JsonObject entry = new JsonObject();
            if (builtin != null) {
                entry.addProperty("builtin", fingerprint(builtin.code()));
            }
            ledger.add(name, entry);
            writeLedger(ledger);
            return builtin != null ? Saved.OVERRODE : existed ? Saved.REPLACED : Saved.NEW;
        }
    }

    /**
     * 删掉她目录里的那份,连同它的战绩:她自己的就没了,盖住内置的就回到内置。
     *
     * @return 删掉之前的正文;她目录里没有这一份是 null
     */
    public String delete(String name) {
        if (get(name) == null || dir == null) {
            return null;
        }
        synchronized (LOCK) {
            String before = read(file(name));
            if (before == null) {
                return null;
            }
            try {
                Files.delete(file(name));
            } catch (IOException e) {
                throw new UncheckedIOException("删不了 " + file(name), e);
            }
            JsonObject ledger = ledger();
            ledger.remove(name);
            writeLedger(ledger);
            return before;
        }
    }

    // ==================== 战绩 ====================

    /** 这一份的战绩;没跑过是全零。 */
    public Stats stats(String name) {
        JsonObject ledger = ledger();
        if (!(ledger.get(name) instanceof JsonObject e) || !e.has("runs")) {
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
            JsonObject entry = ledger.get(name) instanceof JsonObject e ? e : new JsonObject();
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

    // ==================== 小件 ====================

    private void requireDir() {
        if (dir == null) {
            throw new IllegalStateException("只有内置那一层的模块存不进东西");
        }
    }

    /** 一个模块名的文件:{@code my.lumber} 在 {@code my/lumber.lua},别的在顶层 {@code work.lua}。 */
    private Path file(String name) {
        String ext = ScriptEngine.IN_USE.extension();
        return hers(name) ? dir.resolve(ScriptCatalog.ModuleSource.HERS).resolve(name.substring(MINE.length()) + ext)
                : dir.resolve(name + ext);
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

    private void write(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写不了 " + file, e);
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

    /** 一份正文的指纹:SHA-256 的前 12 位十六进制。 */
    static String fingerprint(String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
