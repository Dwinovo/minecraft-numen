package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.data.JsonLibrary;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 玩家的命名皮肤库,存于 {@code config/numen/skin.json} + 原图
 * {@code config/numen/skins/<id>.png}——设置面板"皮肤"tab 背后的数据层,
 * 形状对齐 {@code VoiceLibrary}(声线库)。条目保存 MineSkin 代签好的
 * Mojang 签名 textures(value+signature):签名发生在<b>保存时</b>,
 * 召唤时直接取用,零等待零网络。客户端单例。
 */
public final class SkinLibrary extends JsonLibrary<SkinLibrary.Entry> {

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

    private static SkinLibrary instance;

    private final Path skinDir;

    private SkinLibrary(Path file, Path skinDir) {
        super(file);
        this.skinDir = skinDir;
        load();
    }

    public static SkinLibrary instance() {
        if (instance == null) {
            instance = new SkinLibrary(configDir().resolve("skin.json"),
                    configDir().resolve("skins"));
        }
        return instance;
    }

    /** 条目原图的落盘位置(预览与改手臂模型重签时都从这读)。 */
    public Path pngPath(String id) {
        return skinDir.resolve(id + ".png");
    }

    /** 新建/更新条目并持久化;{@code png} 非 null 时一并写盘(新图/换图)。 */
    public void put(Entry e, byte[] png) {
        if (png != null) {
            try {
                Files.createDirectories(skinDir);
                Files.write(pngPath(e.id()), png);
            } catch (IOException ex) {
                Constants.LOG.warn("[numen-skin] 皮肤原图写盘失败 {}: {}", e.id(), ex.toString());
            }
        }
        putAndSave(e);
        SkinTextures.evict(e.id());   // 预览纹理按需重建(换图后旧纹理作废)
    }

    @Override
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
        return freshId("skin");
    }

    // ---- persistence hooks ----

    @Override
    protected String logTag() {
        return "numen-skin";
    }

    @Override
    protected String idOf(Entry e) {
        return e.id();
    }

    @Override
    protected Entry readEntry(JsonObject o) {
        return new Entry(str(o, "id"), str(o, "name"),
                str(o, "variant").isBlank() ? VARIANT_CLASSIC : str(o, "variant"),
                str(o, "value"), str(o, "signature"));
    }

    @Override
    protected JsonObject writeEntry(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("name", e.name());
        o.addProperty("variant", e.variant());
        o.addProperty("value", e.value() == null ? "" : e.value());
        o.addProperty("signature", e.signature() == null ? "" : e.signature());
        return o;
    }
}
