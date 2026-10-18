package com.dwinovo.numen.api.config;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.Services;
import net.minecraft.server.level.ServerPlayer;

/**
 * 谁能改服务器上的配置(模型档案与 key、外部大脑、代理、默认人设)。配置的读写入口、界面都问这里,工具和界面里不另写;
 * 判据本身由平台服务给({@link com.dwinovo.numen.api.platform.services.IPermissions}):模组是单人游戏的房主或 op 4 级,
 * Paper 是权限节点 {@code numen.admin}。
 *
 * <p>和权限层({@code com.dwinovo.numen.api.permission},管她能不能动世界)是两回事:这里管的是人。
 */
public final class Authority {

    private Authority() {}

    public static boolean isAdmin(ServerPlayer player) {
        return Services.permissions().isAdmin(player.createCommandSourceStack());
    }

    /**
     * 她跑命令时的权限等级:只取主人的,她自己在不在 op 名单里不起作用——否则服主给她开了 op,
     * 谁都能借她跑 op 命令。主人离线也成立;没有主人是 0。她的指令源({@link NumenPlayer#createCommandSourceStack})、
     * 指令解析与补全都取这个等级,判据只在这里(平台服务 {@code IPermissions.commandLevel})。
     */
    public static int commandLevel(NumenPlayer her) {
        return Services.permissions().commandLevel(her.server, her.getOwnerUuid());
    }
}
