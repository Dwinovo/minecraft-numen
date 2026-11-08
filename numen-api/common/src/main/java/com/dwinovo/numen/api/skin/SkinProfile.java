package com.dwinovo.numen.api.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 一张签好的皮肤:手臂模型、Mojang 签名的皮肤数据({@code value} 与 {@code signature},签一次存永久,召唤和换肤时直接取用)和原图
 * (png 的字节,换手臂模型时用它重签;借来的正版皮肤取到的是图床上的图)。存在签名缓存里({@link SkinCache},键是 {@link SkinRef});
 * 旧版本的皮肤库条目也是这个形状,{@code SkinMigration} 读它。
 *
 * <p>{@code png} 是原图的字节;空数组 = 没有(只有旧版本借来的正版皮肤取不到图时才会没有)。
 */
public record SkinProfile(String variant, String value, String signature, byte[] png) {

    /** 手臂模型:经典粗手。 */
    public static final String VARIANT_CLASSIC = "classic";
    /** 手臂模型:纤细瘦手。 */
    public static final String VARIANT_SLIM = "slim";

    public SkinProfile {
        variant = VARIANT_SLIM.equals(variant) ? VARIANT_SLIM : VARIANT_CLASSIC;
        value = value == null ? "" : value;
        signature = signature == null ? "" : signature;
        png = png == null ? new byte[0] : png;
    }

    /** 签过名:有 Mojang 的 textures 数据可以挂到身体上。 */
    public boolean signed() {
        return !value.isBlank();
    }

    /** 读一份存下来的正文;写法不对抛 {@link RuntimeException}。 */
    public static SkinProfile read(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        return new SkinProfile(string(o, "variant"), string(o, "value"), string(o, "signature"),
                string(o, "png").isEmpty() ? null : Base64.getDecoder().decode(string(o, "png")));
    }

    /** 存下来的正文。 */
    public String write() {
        JsonObject o = new JsonObject();
        o.addProperty("variant", variant);
        o.addProperty("value", value);
        o.addProperty("signature", signature);
        if (png.length > 0) {
            o.addProperty("png", Base64.getEncoder().encodeToString(png));
        }
        return o.toString();
    }

    /** 签名数据({@code value})里写的皮肤图网址与手臂模型。 */
    public record Textures(String url, String variant) {}

    /** 读 Mojang 签名的 textures 数据里的皮肤图网址和手臂模型;读不通是 {@code null}。 */
    public static Textures texturesOf(String value) {
        try {
            JsonObject root = JsonParser.parseString(new String(Base64.getDecoder().decode(value),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
            boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                    && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
            return new Textures(skin.get("url").getAsString(), slim ? VARIANT_SLIM : VARIANT_CLASSIC);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
