package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IPermissions;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * {@link IPermissions} 的 Paper 实现:权限是节点,谁有由权限插件说了算,默认给 op。来源在 Bukkit 眼里是谁,
 * 看它的发话人({@code getBukkitSender}:玩家、控制台,她这具身体经委托主人的 {@code Permissible} 问主人)。
 */
public final class PaperPermissions implements IPermissions {

    /** Numen 管理员:能改服务器上的配置(模型档案与 key、外部大脑、代理、默认人设)。 */
    public static final String ADMIN = "numen.admin";

    /** 原版"游戏管理"那一档指令(如 {@code /numen drive}、召唤时选创造)。 */
    public static final String GAME_MASTER = "numen.gamemaster";

    /** 把两个节点登记给 Bukkit,默认给 op;插件启用时调一次。 */
    public static void declare() {
        Bukkit.getPluginManager().addPermission(new Permission(ADMIN,
                "Change Numen's server configuration: model profiles and keys, external brain, proxy, default persona",
                PermissionDefault.OP));
        Bukkit.getPluginManager().addPermission(new Permission(GAME_MASTER,
                "Use Numen's game-master commands and let companions be summoned in creative mode",
                PermissionDefault.OP));
    }

    @Override
    public boolean isAdmin(CommandSourceStack source) {
        return source.getBukkitSender().hasPermission(ADMIN);
    }

    @Override
    public boolean isGameMaster(CommandSourceStack source) {
        return source.getBukkitSender().hasPermission(GAME_MASTER);
    }
}
