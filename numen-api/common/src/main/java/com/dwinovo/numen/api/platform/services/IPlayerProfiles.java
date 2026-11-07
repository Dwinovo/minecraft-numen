package com.dwinovo.numen.api.platform.services;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;

/**
 * 换一个玩家身上的档案。authlib 的 {@code GameProfile} 是不可变的 record,属性(皮肤的 textures)在构造时就定死了,
 * 原地换皮肤只能造一份新档案、换掉身体上的那一份;原版把它放在 {@code Player} 的私有 final 字段里。
 * 私有成员怎么够得着是平台的事(模组用 Mixin 访问器)。
 */
public interface IPlayerProfiles {

    /** 让 {@code player} 从此带着 {@code profile}。调用方负责让客户端重新认她(玩家信息、实体重发)。 */
    void replace(Player player, GameProfile profile);
}
