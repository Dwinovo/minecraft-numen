package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 启动时迁移({@link HostMerge})改动了房主的东西,要告诉他:服务器起来的那一刻他还没进游戏,话先攒着,他的客户端报到时({@link Replies#hello})一次说完。
 *
 * <p>线程:服务端主线程。
 */
final class HostNotices {

    private static final Map<UUID, List<Component>> PENDING = new ConcurrentHashMap<>();

    static {
        ServerLifecycle.onStopped(PENDING::clear);
    }

    private HostNotices() {}

    /** 并库时改了名的条目、被他的钥匙换掉的服主钥匙,各记一句。 */
    static void merged(UUID host, List<HostMerge.Renamed> renamed, List<String> replacedKeys) {
        for (HostMerge.Renamed each : renamed) {
            say(host, Component.translatable(Keys.MERGE_RENAMED, each.kind().dir(), each.from().name(), each.to().name()));
        }
        for (String service : replacedKeys) {
            say(host, Component.translatable(Keys.MERGE_KEY_REPLACED, service));
        }
    }

    private static void say(UUID player, Component line) {
        PENDING.computeIfAbsent(player, id -> new CopyOnWriteArrayList<>()).add(line);
    }

    /** 他报到了:攒下的话说给他,说过就没了。 */
    static void deliver(ServerPlayer player) {
        List<Component> lines = PENDING.remove(player.getUUID());
        if (lines != null) {
            lines.forEach(player::sendSystemMessage);
        }
    }
}
