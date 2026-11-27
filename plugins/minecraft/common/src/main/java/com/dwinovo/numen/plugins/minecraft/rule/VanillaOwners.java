package com.dwinovo.numen.plugins.minecraft.rule;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.Questions;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 原版里"这只实体是谁的"的回答者:{@code tame} 读原版的驯服记录({@link OwnableEntity} 记的主人:狼、猫、鹦鹉,以及继承原版驯服的
 * 模组宠物)。经插件的门登记({@link #install}),和模组的回答者同一个写法。原版里方块没有主人,所以这里没有方块归属的回答者。
 *
 * <p>只陈述事实,不裁决:这个主人对要动手的同伴算自己、主人还是别人由权限层换算,许不许由规则定。
 */
public final class VanillaOwners {

    private VanillaOwners() {}

    public static void install(NumenApi numen) {
        numen.answer(Questions.ENTITY_OWNER, "tame", VanillaOwners::tamer);
    }

    /**
     * 驯服记录里这只实体的主人;不是可驯服的实体、没有主人为 null。
     *
     * <p>只有人做主人才算有主:记着的是世界里一只活着的非玩家(唤魔者的恼鬼、召唤者的随从)时,它没有人的主人,按没主算。
     * 找不到这个主人(离线的玩家、没加载的区块)按人算——不知道是谁的就当是人的。
     */
    static UUID tamer(Entity entity) {
        if (!(entity instanceof OwnableEntity tamed) || tamed.getOwnerUUID() == null) {
            return null;
        }
        UUID recorded = tamed.getOwnerUUID();
        boolean heldByMob = entity.level() instanceof ServerLevel level
                && level.getEntity(recorded) instanceof Entity holder && !(holder instanceof Player);
        return heldByMob ? null : recorded;
    }
}
