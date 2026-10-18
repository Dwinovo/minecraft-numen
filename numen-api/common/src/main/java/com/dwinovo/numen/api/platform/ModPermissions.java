package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.IntPredicate;

/** {@link IPermissions} 的模组实现(Fabric、NeoForge 共用):房主,或 op 等级。 */
public final class ModPermissions implements IPermissions {

    /** 管理员要的 op 等级:最高一级。key 花服主的钱,所以不放到 2、3 级。 */
    private static final int ADMIN_LEVEL = 4;

    /** 原版"游戏管理"一档:{@code /gamemode}、{@code /give} 的等级。 */
    private static final int GAME_MASTER_LEVEL = 2;

    /** 单人游戏的房主,或 op 等级够。 */
    @Override
    public boolean isAdmin(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        boolean singleplayerOwner = player != null && source.getServer().isSingleplayerOwner(player.getGameProfile());
        return isAdmin(singleplayerOwner, source::hasPermission);
    }

    /** 判据本身:房主,或 op 等级够({@code hasLevel} 问"有没有这一级")。 */
    static boolean isAdmin(boolean singleplayerOwner, IntPredicate hasLevel) {
        return singleplayerOwner || hasLevel.test(ADMIN_LEVEL);
    }

    @Override
    public boolean isGameMaster(CommandSourceStack source) {
        return isGameMaster(source::hasPermission);
    }

    /** 判据本身:op 等级够原版"游戏管理"那一档。 */
    static boolean isGameMaster(IntPredicate hasLevel) {
        return hasLevel.test(GAME_MASTER_LEVEL);
    }
}
