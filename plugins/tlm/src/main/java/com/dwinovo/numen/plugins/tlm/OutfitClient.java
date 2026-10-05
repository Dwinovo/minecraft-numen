package com.dwinovo.numen.plugins.tlm;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * {@link Outfit} 的客户端那一半:把收到的同步记到客户端世界里那个实体上。单拆一个类是为了让 {@link Outfit}
 * (两侧都加载)的方法里一个客户端类型都不出现。
 */
final class OutfitClient {

    private OutfitClient() {}

    /** 下行同步的落点;实体还没进客户端世界或已经走远就什么都不做,下次进入视野时服务端会补发。 */
    static void apply(int entityId, String model) {
        var level = Minecraft.getInstance().level;
        Entity body = level == null ? null : level.getEntity(entityId);
        if (body != null) {
            Outfit.sync(body, model);
        }
    }
}
