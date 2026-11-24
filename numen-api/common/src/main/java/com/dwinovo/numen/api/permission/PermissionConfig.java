package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.brain.ModelServices;
import com.dwinovo.numen.api.config.ServerSettings;
import net.minecraft.server.MinecraftServer;

/**
 * 服主在服务器设置({@link ServerSettings},{@code server.json})里能动的那一样,权限层读到的就是这一个值:硬规则
 * ({@link FactoryRules#HARD},别人的东西碰不得)开不开,默认开。单机就是你自己,想关就关。
 *
 * @param hardRules 硬规则生效
 */
public record PermissionConfig(boolean hardRules) {

    /** 这个世界此刻的配置:从服务器设置现取,服主改了文件、{@code /reload} 之后下一次裁决就用新的。 */
    public static PermissionConfig of(MinecraftServer server) {
        ServerSettings settings = ModelServices.of(server).settings();
        return new PermissionConfig(settings.hardRules());
    }
}
