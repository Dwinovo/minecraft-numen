package com.dwinovo.numen.api.mixin.inject;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 原版 {@code Player} 私有 final 的档案字段:换皮肤时整份换掉(见 {@code IPlayerProfiles})。 */
@Mixin(Player.class)
public interface PlayerProfileAccessor {

    @Mutable
    @Accessor("gameProfile")
    void numen$setGameProfile(GameProfile profile);
}
