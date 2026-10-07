package com.dwinovo.numen.debug;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 寻路调试开关簿:按"主人玩家 UUID"记录谁开了调试。开着的玩家会收到同伴正在走的路的快照,客户端把它画在世界里。
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
