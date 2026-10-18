package com.dwinovo.numen.api.platform;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.ServerOperator;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;

/**
 * 她的权限就是主人的权限:Paper 上权限是节点,不是 op 等级,她身上的每一次节点查询都问主人。
 * <ul>
 *   <li>主人在线:问主人的 {@code Permissible}(权限插件已经装好的那个);</li>
 *   <li>主人不在线(外部大脑驾驶时):经 Vault 的权限接口查离线玩家(装了 Vault 的话);</li>
 *   <li>没装 Vault:按主人是不是 op 套 Bukkit 的默认值(Bukkit 对离线玩家的默认语义)。</li>
 * </ul>
 * 换进去的方式与 LuckPerms 注入自己的 {@code Permissible} 相同(反射替换 {@code CraftHumanEntity.perm}),所以要在权限插件注入之后做——
 * 加入之后({@code IBodyAdmission.joined})换,权限插件在登录事件里注入的那个就被取代。这里没有任何权限插件的专属代码。
 */
final class OwnerPermissible extends PermissibleBase {

    private final UUID owner;

    private OwnerPermissible(ServerOperator self, UUID owner) {
        super(self);
        this.owner = owner;
    }

    /** 把 {@code her} 的权限换成委托给 {@code owner} 的。 */
    static void injectInto(Player her, UUID owner) {
        try {
            Field field = CraftHumanEntity.class.getDeclaredField("perm");
            field.setAccessible(true);
            OwnerPermissible delegating = new OwnerPermissible(her, owner);
            field.set(her, delegating);
            delegating.recalculatePermissions();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("没能把 " + her.getName() + " 的权限换成主人的", e);
        }
    }

    @Override
    public boolean hasPermission(String name) {
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            return online.hasPermission(name);
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(owner);
        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            Boolean viaVault = VaultPermissions.has(offline, name);
            if (viaVault != null) {
                return viaVault;
            }
        }
        Permission declared = Bukkit.getPluginManager().getPermission(name);
        return (declared != null ? declared.getDefault() : Permission.DEFAULT_PERMISSION).getValue(offline.isOp());
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return hasPermission(perm.getName());
    }

    @Override
    public boolean isPermissionSet(String name) {
        Player online = Bukkit.getPlayer(owner);
        return online != null ? online.isPermissionSet(name) : super.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return isPermissionSet(perm.getName());
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        Player online = Bukkit.getPlayer(owner);
        return online != null ? online.getEffectivePermissions() : super.getEffectivePermissions();
    }

    /** {@code PermissibleBase} 的构造器里就会算一遍权限、问 {@code isOp},那时主人还没赋值。 */
    @Override
    public boolean isOp() {
        return owner != null && Bukkit.getOfflinePlayer(owner).isOp();
    }
}
