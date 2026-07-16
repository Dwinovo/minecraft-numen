package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家的命名皮肤库,存于 {@code config/numen/skin.json} + 原图
 * {@code config/numen/skins/<id>.png}——设置面板"皮肤"tab 背后的数据层,
 * 形状对齐 {@code VoiceLibrary}(声线库)。条目保存 MineSkin 代签好的
 * Mojang 签名 textures(value+signature):签名发生在<b>保存时</b>,
 * 召唤时直接取用,零等待零网络。客户端单例。
 */
public final class SkinLibrary {

    /** 手臂模型:经典粗手。 */
    public static final String VARIANT_CLASSIC = "classic";
    /** 手臂模型:纤细瘦手。 */
    public static final String VARIANT_SLIM = "slim";

    /**
     * 一条皮肤配置。{@code value}/{@code signature} 是 MineSkin 代签的
     * Mojang 签名 textures;空串 = 尚未签名成功(召唤下拉不展示)。
     */
    public record Entry(String id, String name, String variant, String value, String signature) {
        public boolean signed() {
            return value != null && !value.isBlank();
        }
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static SkinLibrary instance;

    private final Path file;
    private final Path skinDir;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private SkinLibrary(Path file, Path skinDir) {
        this.file = file;
        this.skinDir = skinDir;
        load();
    }

    public static SkinLibrary instance() {
        if (instance == null) {
            Path dir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen");
            instance = new SkinLibrary(dir.resolve("skin.json"), dir.resolve("skins"));
        }
        return instance;
    }

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    public Entry get(String id) {
        return id == null ? null : entries.get(id);
    }

    /** 条目原图的落盘位置(预览与改手臂模型重签时都从这读)。 */
    public Path pngPath(String id) {
        return skinDir.resolve(id + ".png");
    }

    /** 新建/更新条目并持久化;{@code png} 非 null 时一并写盘(新图/换图)。 */
    public void put(Entry e, byte[] png) {
        entries.put(e.id(), e);
        if (png != null) {
            try {
                Files.createDirectories(skinDir);
                Files.write(pngPath(e.id()), png);
            } catch (IOException ex) {
                Constants.LOG.warn("[numen-skin] 皮肤原图写盘失败 {}: {}", e.id(), ex.toString());
            }
        }
        save();
        SkinTextures.evict(e.id());   // 预览纹理按需重建(换图后旧纹理作废)
    }

    public void remove(String id) {
        if (entries.remove(id) == null) return;
        try {
            Files.deleteIfExists(pngPath(id));
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-skin] 皮肤原图删除失败 {}: {}", id, ex.toString());
        }
        SkinTextures.evict(id);
        save();
    }

    public String freshId() {
        return "skin_" + Long.toHexString(System.currentTimeMillis()) + "_" + entries.size();
    }

    // ---- persistence ----

    private void load() {
        entries.clear();
        if (!Files.exists(file)) return;
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("entries") && root.get("entries").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("entries")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    Entry e = new Entry(str(o, "id"), str(o, "name"),
                            str(o, "variant").isBlank() ? VARIANT_CLASSIC : str(o, "variant"),
                            str(o, "value"), str(o, "signature"));
                    if (!e.id().isBlank()) entries.put(e.id(), e);
                }
            }
        } catch (RuntimeException | IOException ex) {
            Constants.LOG.warn("[numen-skin] skin.json 读取失败: {}", ex.toString());
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("name", e.name());
            o.addProperty("variant", e.variant());
            o.addProperty("value", e.value() == null ? "" : e.value());
            o.addProperty("signature", e.signature() == null ? "" : e.signature());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("entries", arr);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-skin] skin.json 写盘失败: {}", ex.toString());
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
