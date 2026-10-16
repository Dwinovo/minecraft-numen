package com.dwinovo.numen.api.config;

import net.minecraft.server.level.ServerPlayer;

/**
 * 谁能改服务器上的配置(模型档案与 key、外部大脑、代理、默认人设)——这个判据<b>只在这一处</b>。
 * 单人游戏的房主,或 op 等级 {@value #ADMIN_LEVEL} 的玩家。配置的读写入口、界面都问这里,工具和界面里不另写。
 *
 * <p>和权限层({@code com.dwinovo.numen.api.permission},管她能不能动世界)是两回事:这里管的是人。
 */
public final class Authority {

    /** 管理员要的 op 等级:最高一级。key 花服主的钱,所以不放到 2、3 级。 */
    public static final int ADMIN_LEVEL = 4;

    private Authority() {}

    public static boolean isAdmin(ServerPlayer player) {
        return isAdmin(player.server.isSingleplayerOwner(player.getGameProfile()),
                player.server.getProfilePermissions(player.getGameProfile()));
    }

    /** 判据本身:房主,或等级够。 */
    static boolean isAdmin(boolean singleplayerOwner, int opLevel) {
        return singleplayerOwner || opLevel >= ADMIN_LEVEL;
    }
}
