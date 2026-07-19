package com.dwinovo.numen.client.debug;

import com.dwinovo.numen.network.payload.PathDebugPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端寻路调试快照仓:每同伴保留最近一份 {@link PathDebugPayload},
 * 供世界渲染钩子逐帧取画。快照超过 {@link #STALE_MILLIS} 未更新即视为
 * 过期丢弃(服务端停发 = 调试关闭/任务结束,线条自然消失)。
 */
public final class PathDebugState {

    private static final long STALE_MILLIS = 3000;

    private record Snapshot(PathDebugPayload data, long receivedMillis) {}

    private static final Map<UUID, Snapshot> LATEST = new ConcurrentHashMap<>();

    private PathDebugState() {}

    public static void accept(PathDebugPayload payload) {
        LATEST.put(payload.companionId(), new Snapshot(payload, System.currentTimeMillis()));
    }

    /** 未过期的全部快照(顺手清掉过期项)。 */
    public static List<PathDebugPayload> live() {
        long now = System.currentTimeMillis();
        LATEST.values().removeIf(s -> now - s.receivedMillis > STALE_MILLIS);
        List<PathDebugPayload> out = new ArrayList<>(LATEST.size());
        for (Snapshot s : LATEST.values()) {
            out.add(s.data());
        }
        return out;
    }

    public static void clear() {
        LATEST.clear();
    }
}
