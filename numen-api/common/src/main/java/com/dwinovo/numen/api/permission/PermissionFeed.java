package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.platform.ServerLifecycle;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主人的规则表变了(她加了一行、他在命令里改了、命中计数涨了),推给在线的他的面板。存档任何一处变都会叫一声
 * ({@link PermissionStore#of}),这里只记下是谁的,每 {@link #EVERY_TICKS} 刻合并成一包——挖一格就命中一次,
 * 不能挖一格推一包。主人不在线或没装客户端的,{@link Replies#pushTo} 不发;他下次打开面板自己会来要最新的。
 *
 * <p>线程:{@link #changed} 任何线程;{@link #tick} 服务端主线程。
 */
public final class PermissionFeed {

    /** 合并的窗口:半秒,面板上的计数看着是活的,挖矿时也不到每刻一包。 */
    static final int EVERY_TICKS = 10;

    private static final Set<UUID> CHANGED = ConcurrentHashMap.newKeySet();

    static {
        ServerLifecycle.onStopped(CHANGED::clear);
    }

    private PermissionFeed() {}

    static void changed(UUID owner) {
        CHANGED.add(owner);
    }

    /** 每个服务端刻调一次。 */
    public static void tick(MinecraftServer server) {
        if (CHANGED.isEmpty() || server.getTickCount() % EVERY_TICKS != 0) {
            return;
        }
        List<UUID> owners = new ArrayList<>(CHANGED);
        owners.forEach(CHANGED::remove);
        for (UUID owner : owners) {
            Replies.pushTo(server, owner, Libraries.PERMISSION);
        }
    }
}
