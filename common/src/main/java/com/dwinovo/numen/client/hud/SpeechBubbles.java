package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.network.payload.SpeechBubblePayload;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端的头顶气泡台账:每个同伴同一时刻最多一只气泡(新话顶掉旧话,
 * 像人说话一样——不排队不堆叠)。数据由 {@code SpeechBubbleSyncPayload}
 * 喂进来,渲染方 {@link SpeechBubbleRenderer} 逐帧读;过期由读方顺手
 * 清除,没有独立的 tick。客户端主线程专用。
 */
public final class SpeechBubbles {

    /** 思考气泡的兜底寿命:回合再长也不至于挂一块化石在头顶。 */
    private static final long THINKING_LIFE_MS = 120_000;
    /** 正文寿命:保底给短句,按字数加时,封顶防长文霸屏。 */
    private static final long TEXT_LIFE_BASE_MS = 7_000;
    private static final long TEXT_LIFE_PER_CHAR_MS = 55;
    private static final long TEXT_LIFE_MAX_MS = 22_000;

    /** 一只活着的气泡。{@code kind} 取 {@link SpeechBubblePayload} 的 KIND_*。 */
    public record Bubble(byte kind, String text, long bornMs, long lifeMs) {
        public boolean expired(long now) {
            return now - bornMs > lifeMs;
        }
        public boolean thinking() {
            return kind == SpeechBubblePayload.KIND_THINKING;
        }
    }

    private static final Map<UUID, Bubble> LIVE = new HashMap<>();

    private SpeechBubbles() {}

    /** 网络层入口:清除/思考/正文三态,新状态直接覆盖旧气泡。 */
    public static void apply(UUID entityUuid, byte kind, String text) {
        if (entityUuid == null) return;
        if (kind == SpeechBubblePayload.KIND_CLEAR) {
            LIVE.remove(entityUuid);
            return;
        }
        String shown = text == null ? "" : text.trim();
        if (kind == SpeechBubblePayload.KIND_TEXT && shown.isEmpty()) {
            LIVE.remove(entityUuid);
            return;
        }
        long life = kind == SpeechBubblePayload.KIND_THINKING
                ? THINKING_LIFE_MS
                : Math.min(TEXT_LIFE_MAX_MS, TEXT_LIFE_BASE_MS + shown.length() * TEXT_LIFE_PER_CHAR_MS);
        LIVE.put(entityUuid, new Bubble(kind, shown, System.currentTimeMillis(), life));
    }

    /** 渲染方逐帧迭代;过期条目就地摘除。 */
    public static Iterator<Map.Entry<UUID, Bubble>> drainLive() {
        long now = System.currentTimeMillis();
        LIVE.values().removeIf(b -> b.expired(now));
        return LIVE.entrySet().iterator();
    }

    public static boolean isEmpty() {
        return LIVE.isEmpty();
    }

    /** 退出世界时清台账(和其他客户端会话态一起挂在断线钩子上)。 */
    public static void clear() {
        LIVE.clear();
    }
}
