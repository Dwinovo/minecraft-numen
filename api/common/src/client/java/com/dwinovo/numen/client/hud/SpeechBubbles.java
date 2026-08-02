package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.network.payload.SpeechBubblePayload;

import java.util.HashMap;
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

    /** 网络层入口。文本优先:活着的正文泡不被思考态顶掉;SETTLE(开工)
     *  只收思考泡;TEXT 顶掉一切;CLEAR 收掉一切。 */
    public static void apply(UUID entityUuid, byte kind, String text) {
        if (entityUuid == null) return;
        if (kind != SpeechBubblePayload.KIND_THINKING) {
            LOCAL_THINKING.remove(entityUuid);   // 思考期结束(正文/收工/清场),流一并清
        }
        if (kind == SpeechBubblePayload.KIND_CLEAR) {
            LIVE.remove(entityUuid);
            return;
        }
        long now = System.currentTimeMillis();
        Bubble cur = LIVE.get(entityUuid);
        if (kind == SpeechBubblePayload.KIND_SETTLE) {
            if (cur != null && cur.thinking()) {
                LIVE.remove(entityUuid);
            }
            return;
        }
        if (kind == SpeechBubblePayload.KIND_THINKING) {
            if (cur != null && !cur.thinking() && !cur.expired(now)) {
                return;   // 正文还活着:让她把话说完,不换成省略号
            }
            LIVE.put(entityUuid, new Bubble(kind, "", now, THINKING_LIFE_MS));
            return;
        }
        String shown = text == null ? "" : text.trim();
        if (shown.isEmpty()) {
            LIVE.remove(entityUuid);
            return;
        }
        long life = Math.min(TEXT_LIFE_MAX_MS, TEXT_LIFE_BASE_MS + shown.length() * TEXT_LIFE_PER_CHAR_MS);
        LIVE.put(entityUuid, new Bubble(kind, shown, now, life));
    }

    // ---- 本地思考流(仅同伴主人本机可见,不走网络——逐 chunk 广播是流量灾难) ----

    private static final Map<UUID, StringBuilder> LOCAL_THINKING = new HashMap<>();
    /** 只留尾巴:气泡最多展示两行,存多了也是白存。 */
    private static final int LOCAL_THINKING_CAP = 300;

    /** 思考增量追加(客户端主线程;由回合的 chunk 回调经主线程投递)。 */
    public static void appendLocalThinking(UUID entityUuid, String delta) {
        if (entityUuid == null || delta == null || delta.isEmpty()) return;
        StringBuilder sb = LOCAL_THINKING.computeIfAbsent(entityUuid, k -> new StringBuilder());
        sb.append(delta);
        if (sb.length() > LOCAL_THINKING_CAP) sb.delete(0, sb.length() - LOCAL_THINKING_CAP);
    }

    /** 当前思考流尾巴,没有则空串。 */
    public static String localThinking(UUID entityUuid) {
        StringBuilder sb = LOCAL_THINKING.get(entityUuid);
        return sb == null ? "" : sb.toString();
    }

    /** 某实体此刻的活气泡;过期就地摘除返回 null。渲染方逐实体查询。 */
    public static Bubble live(UUID entityUuid) {
        Bubble b = LIVE.get(entityUuid);
        if (b == null) {
            return null;
        }
        if (b.expired(System.currentTimeMillis())) {
            LIVE.remove(entityUuid);
            return null;
        }
        return b;
    }

    /** 退出世界时清台账(和其他客户端会话态一起挂在断线钩子上)。 */
    public static void clear() {
        LIVE.clear();
    }
}
