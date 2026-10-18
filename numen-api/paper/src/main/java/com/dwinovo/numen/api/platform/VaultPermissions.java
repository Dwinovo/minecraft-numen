package com.dwinovo.numen.api.platform;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Vault 的 API 只在这个类里出现:Vault 没装时这个类不会被加载。 */
final class VaultPermissions {

    private VaultPermissions() {}

    /** Vault 的权限提供者(LuckPerms 等)对这位离线玩家的答复;没有提供者是 {@code null}。 */
    static Boolean has(OfflinePlayer player, String node) {
        RegisteredServiceProvider<Permission> provider = Bukkit.getServicesManager().getRegistration(Permission.class);
        return provider == null ? null : provider.getProvider().playerHas(null, player, node);
    }
}
