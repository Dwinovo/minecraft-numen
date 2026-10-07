package com.dwinovo.numen.core.debug;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调试模式开关簿:按"主人玩家 UUID"记录谁开了调试。开着的玩家会
 * 收到寻路路径粒子渲染,且其客户端聊天 UI 切到不过滤直出。
 */
public final class PathDebug {

    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();

    private PathDebug() {}

    /** 翻转该玩家的调试开关,返回翻转后的状态。 */
    public static boolean toggle(UUID ownerUuid) {
        if (ENABLED.remove(ownerUuid)) {
            return false;
        }
        ENABLED.add(ownerUuid);
        return true;
    }

    public static boolean isEnabled(UUID ownerUuid) {
        return ENABLED.contains(ownerUuid);
    }

    public static boolean anyEnabled() {
        return !ENABLED.isEmpty();
    }
}
