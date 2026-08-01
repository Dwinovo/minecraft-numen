package com.dwinovo.numen.core.task;

import net.minecraft.server.level.ServerPlayer;

/**
 * 同伴此刻的工作能力画像——游戏模式(将来还可以叠药水/服主配置)到
 * 能力事实的唯一翻译点。铁律:算法不读模式,只读这里的能力位;
 * {@code isCreative()/instabuild} 只允许出现在本类与快照构造点,
 * 任务循环或 Movement 里出现即为坏味道。
 *
 * <p>零缓存:调用方在每个决策点现取({@link #of}),模式被外部切换时
 * 下一个决策自然用新画像,不存在幽灵状态。
 */
public record WorkProfile(
        boolean freeMaterials,   // 放置/使用不消耗物品
        boolean dropsLoot,       // 破坏方块会产生掉落物
        boolean hasHunger,       // 有饥饿机制(需要进食;疾跑受饱食度门限)
        boolean fearless,        // 摔落/溺水等物理伤害免疫
        boolean instaBreak) {    // 挖掘瞬间完成,且无视工具等级

    public static final WorkProfile SURVIVAL = new WorkProfile(false, true, true, false, false);
    public static final WorkProfile CREATIVE = new WorkProfile(true, false, false, true, true);

    public static WorkProfile of(ServerPlayer body) {
        return body.getAbilities().instabuild ? CREATIVE : SURVIVAL;
    }
}
