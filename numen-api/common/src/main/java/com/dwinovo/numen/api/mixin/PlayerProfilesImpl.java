package com.dwinovo.numen.api.mixin;

import com.dwinovo.numen.api.mixin.inject.PlayerProfileAccessor;
import com.dwinovo.numen.api.platform.services.IPlayerProfiles;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;

/**
 * {@link IPlayerProfiles} 的模组实现:经 {@link PlayerProfileAccessor} 写原版私有的档案字段。
 * Mixin 配置的包是下一层的 {@code mixin.inject},这里不是 Mixin 声明的包,可以被 ServiceLoader 正常加载。
 */
public final class PlayerProfilesImpl implements IPlayerProfiles {

    @Override
    public void replace(Player player, GameProfile profile) {
        ((PlayerProfileAccessor) player).numen$setGameProfile(profile);
    }
}
