package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.services.IPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

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
        return singleplayerOwner || source.hasPermission(ADMIN_LEVEL);
    }

    @Override
    public boolean isGameMaster(CommandSourceStack source) {
        return source.hasPermission(GAME_MASTER_LEVEL);
    }

    /** 按档案算({@code getProfilePermissions}),主人离线也成立,单人游戏房主照原版规则;主人不在线又不在用户缓存里时只凭 uuid 查 op 名单(名单按 uuid 记)。 */
    @Override
    public int commandLevel(MinecraftServer server, UUID owner) {
        if (owner == null) {
            return 0;
        }
        return server.getProfilePermissions(NumenPlayer.profileOf(server, owner));
    }
}
