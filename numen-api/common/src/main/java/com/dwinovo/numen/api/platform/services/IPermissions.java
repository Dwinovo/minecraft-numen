package com.dwinovo.numen.api.platform.services;

import com.dwinovo.numen.api.entity.NumenPlayer;
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
     * 她跑命令时的权限等级:只取主人的,她自己的不起作用。按档案算(原版的 {@code getProfilePermissions}),主人离线也成立,
     * 单人游戏房主照原版规则;主人不在线又不在用户缓存里时只凭 uuid 查 op 名单(名单按 uuid 记);没有主人({@code owner} 为 null)是 0。
     * Paper 上她的权限由委托主人的 {@code Permissible} 管,这个等级只是原版指令源要的那个数,所以各平台都用这一个。
     */
    default int commandLevel(MinecraftServer server, UUID owner) {
        return owner == null ? 0 : server.getProfilePermissions(NumenPlayer.profileOf(server, owner));
    }
}
