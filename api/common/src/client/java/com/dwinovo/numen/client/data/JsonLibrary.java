package com.dwinovo.numen.client.data;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/numen} 下"命名条目库"的共享底盘:保序条目表、
 * {@code {"entries":[...]}} 的 JSON 落盘(损坏时告警并从空开始)、
 * list/get/remove 与 id 生成。模型配置库/声线库/皮肤库三库共用——
 * 此前三份各自手抄同一套单例+CRUD+落盘。人设库是"一个 .md 一个人设"
 * 的目录库,不在此列。
 *
 * <p>条目之外的段(全局开关等)由子类经 {@link #readExtra}/{@link #writeExtra}
 * 挂进同一个文件。<b>库只装配置,不装绑定</b>——"哪只同伴用哪个条目"住在
 * 同伴自己的家里({@code companions/<uuid>/binding.json}),跟着同伴生灭;
 * 于是这些库文件可以原样分享给别人,里面没有别人看不懂的 UUID。
 */
public abstract class JsonLibrary<E> {

    protected static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();

    protected final Path file;
    protected final Map<String, E> entries = new LinkedHashMap<>();

    protected JsonLibrary(Path file) {
        this.file = file;
    }

    /** 客户端配置目录 {@code config/numen}。 */
    protected static Path configDir() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(com.dwinovo.numen.Constants.CONFIG_ROOT);
    }

    // ---- 子类钩子 ----

    /** 日志标签(如 {@code numen-voice})。 */
    protected abstract String logTag();

    /** 条目的 id。 */
    protected abstract String idOf(E entry);

    /** 读一条;返回 null 即跳过该条。 */
    protected abstract E readEntry(JsonObject o);

    /** 写一条。 */
    protected abstract JsonObject writeEntry(E entry);

    /** entries 之外的段(全局开关等)。缺省无。 */
    protected void readExtra(JsonObject root) {}

    protected void writeExtra(JsonObject root) {}

    /** 文件缺失、从空开始时的额外复位(全新安装的缺省态)。缺省无。 */
    protected void resetExtra() {}

    /** 文件损坏时的额外复位。缺省同 {@link #resetExtra},需要更保守的
     *  缺省态(如坏文件即静音)的库单独覆写。 */
    protected void resetOnCorrupt() {
        resetExtra();
    }

    // ---- 条目表 ----

    public List<E> list() {
        return new ArrayList<>(entries.values());
    }

    public E get(String id) {
        return id == null ? null : entries.get(id);
    }

    /**
     * 删条目。<b>不管绑定</b>——绑定住在同伴自己的家里
     * ({@code companions/<uuid>/binding.json}),跟着同伴生灭,不跟着条目;
     * 指向已删条目的绑定解析为 null,各消费点自有回落(全局配置/静音/默认人设)。
     */
    public void remove(String id) {
        if (entries.remove(id) != null) save();
    }

    protected String freshId(String prefix) {
        return prefix + "_" + Long.toHexString(System.currentTimeMillis()) + "_" + entries.size();
    }

    protected void putAndSave(E entry) {
        entries.put(idOf(entry), entry);
        save();
    }

    // ---- 落盘 ----

    protected final void load() {
        entries.clear();
        resetExtra();
        if (!Files.exists(file)) {
            return;   // 全新安装:库从空开始,玩家自己创建
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("entries")) {
                    if (!el.isJsonObject()) continue;
                    E e = readEntry(el.getAsJsonObject());
                    if (e != null && idOf(e) != null && !idOf(e).isBlank()) {
                        entries.put(idOf(e), e);
                    }
                }
            }
            readExtra(root);
        } catch (RuntimeException | IOException ex) {
            Constants.LOG.warn("[{}] unreadable {} — starting empty ({})",
                    logTag(), file, ex.getMessage());
            entries.clear();
            resetOnCorrupt();
        }
    }

    protected final void save() {
        JsonArray arr = new JsonArray();
        for (E e : entries.values()) {
            arr.add(writeEntry(e));
        }
        JsonObject root = new JsonObject();
        root.add("entries", arr);
        writeExtra(root);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[{}] can't save {}: {}", logTag(), file, ex.getMessage());
        }
    }

    /** 字符串字段;缺失/非原始值给 ""。 */
    protected static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    /** 缺失/非原始值给 null(条目字段以 null 表示"未填"的库用这个)。 */
    protected static String strOrNull(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    protected static boolean nb(String s) {
        return s != null && !s.isBlank();
    }
}
