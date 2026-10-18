package com.dwinovo.numen.api.entity;


/**
 * 皮肤数据的形状与命名规则——Mojang 签名的 textures 与合法的正版玩家名。
 *
 * <p>签名只在服务端做({@code SkinSigning}:MineSkin 代签,或向 Mojang 借正版玩家的),结果存进皮肤库的条目;
 * 绑定时从条目取出来挂到身体上。Mojang 的签名自验证,伪造不了。
 */
public final class MojangSkins {

    /** Mojang 签名的 textures 属性对。{@code signature} 可为空串(理论上不该发生)。 */
    public record Skin(String value, String signature) {}

    private MojangSkins() {}

    /** 合法的正版玩家名:3~16 位字母/数字/下划线。 */
    public static boolean validName(String s) {
        return s != null && s.matches("[A-Za-z0-9_]{3,16}");
    }

}
