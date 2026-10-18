package com.dwinovo.numen.api.platform.services;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * "这人有什么权限"——common 里凡是问这个的地方都经这一个服务,不自己读 op 等级。
 * 平台怎么定由平台说了算:模组按房主与 op 等级,Paper 按权限节点。
 */
public interface IPermissions {

    /** 这个来源是不是 Numen 管理员:能改服务器上的配置(模型档案与 key、外部大脑、代理、默认人设)。 */
    boolean isAdmin(CommandSourceStack source);

    /** 这个来源能不能用原版"游戏管理"那一档指令(改游戏模式、{@code /give} 之类)。 */
    boolean isGameMaster(CommandSourceStack source);

    /**
     * 她跑命令时的权限等级:只取主人的,她自己的不起作用。主人离线也要答得出来;没有主人({@code owner} 为 null)是 0。
     */
    int commandLevel(MinecraftServer server, UUID owner);
}
