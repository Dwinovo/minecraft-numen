package com.dwinovo.numen.core;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 同伴此刻的工作能力画像——游戏模式(将来还可以叠药水/服主配置)到能力事实的唯一翻译点。铁律:算法不读模式,只读这里的能力位;
 * 她的游戏模式只从 {@link #mode} 读,{@code status.self} 报的与画像翻译的是同一份。
 *
 * <p>真源是原版 {@code ServerPlayerGameMode} 记的那一份模式:{@code /gamemode}、存档、同步给客户端的都是它,挖掉一格秒不秒破、
 * 掉不掉东西原版也按它判({@code ServerPlayerGameMode.isCreative});能力位({@code Abilities.instabuild} 等)是原版按模式派生的,
 * 不另读。
 *
 * <p>零缓存:调用方在每个决策点现取({@link #of}),模式被外部切换时下一个决策自然用新画像,不存在幽灵状态。
 */
public record WorkProfile(
        boolean freeMaterials,   // 放置/使用不消耗物品
        boolean dropsLoot,       // 破坏方块会产生掉落物
        boolean hasHunger,       // 有饥饿机制(需要进食;疾跑受饱食度门限)
        boolean fearless,        // 摔落/溺水等物理伤害免疫
        boolean instaBreak) {    // 挖掘瞬间完成,且无视工具等级

    public static final WorkProfile SURVIVAL = new WorkProfile(false, true, true, false, false);
    public static final WorkProfile CREATIVE = new WorkProfile(true, false, false, true, true);

    /** 她此刻的游戏模式。 */
    public static GameType mode(ServerPlayer body) {
        return body.gameMode.getGameModeForPlayer();
    }

    public static WorkProfile of(ServerPlayer body) {
        return mode(body).isCreative() ? CREATIVE : SURVIVAL;
    }
}
