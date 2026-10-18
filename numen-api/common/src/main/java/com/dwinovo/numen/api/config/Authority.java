package com.dwinovo.numen.api.config;

import com.dwinovo.numen.api.entity.NumenPlayer;
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

    /**
     * 她跑命令时的权限等级:只取主人的,她自己在不在 op 名单里不起作用——否则服主给她开了 op,
     * 谁都能借她跑 op 命令。按档案算({@code getProfilePermissions}),主人离线也成立,单人游戏房主照原版规则;
     * 没有主人、或查不到主人的档案是 0。她的指令源({@link NumenPlayer#createCommandSourceStack})、
     * 指令解析与补全都取这个等级,判据只在这里。
     */
    public static int commandLevel(NumenPlayer her) {
        if (her.getOwnerUuid() == null) {
            return 0;
        }
        return NumenPlayer.profileOf(her.server, her.getOwnerUuid())
                .map(her.server::getProfilePermissions).orElse(0);
    }

    /** 判据本身:房主,或等级够。 */
    static boolean isAdmin(boolean singleplayerOwner, int opLevel) {
        return singleplayerOwner || opLevel >= ADMIN_LEVEL;
    }
}
