package com.dwinovo.numen.entity;


/**
 * 皮肤数据的形状与命名规则——两侧共用的小契约。
 *
 * <p>查询本身住在客户端({@code MojangSkinLookup}):那里有玩家配的代理,
 * 而服务端的会话服务栈吃 JVM 默认网络,国内经常静默超时。服务端只负责把
 * 客户端备好的签名数据挂上去(Mojang 签名自验证,伪造不了)。
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
