package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPlayerProfiles;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;

/**
 * Paper 上的 {@link IPlayerProfiles}:Paper 的服务端是 Mojang 名,按名字找 {@code Player} 私有 final 的 {@code gameProfile},
 * 字段一次查好缓存。找不到(换了版本改了名)是类加载时就抛的错,不是等到有人换皮肤才发现。
 */
public final class PaperPlayerProfiles implements IPlayerProfiles {

    private static final Field GAME_PROFILE;

    static {
        try {
            GAME_PROFILE = Player.class.getDeclaredField("gameProfile");
            GAME_PROFILE.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public void replace(Player player, GameProfile profile) {
        try {
            GAME_PROFILE.set(player, profile);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写不了 Player.gameProfile", e);
        }
    }
}
