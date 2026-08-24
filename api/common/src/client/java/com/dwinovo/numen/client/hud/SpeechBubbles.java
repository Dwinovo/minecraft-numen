package com.dwinovo.numen.client.hud;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 头顶气泡的状态源——纯本机、不走网络:同伴的思考与发言是主人的私事,
 * 别人不该看见(对话内容本来就只在主人客户端,气泡曾是唯一泄露的通道)。
 * 代理循环就跑在主人这台机器上,状态随手可查,广播纯属多余。
 *
 * <h2>两条线各自生灭,不互斥</h2>
 * 说话和干活是同时发生的两件事,凭什么互相遮挡:
 * <ul>
 *   <li><b>正文行</b>:她说的最后一句,有自己的生命周期(按字数),到点消失。</li>
 *   <li><b>状态行</b>:此刻在干什么——有工具在跑就是「正在 xxx」,只是在等
 *       模型回复就是「正在思考中」,都没有就没有这一行。</li>
 * </ul>
 * 两条线独立:话还在时来了工具,气泡就是"话 + 正在挖矿";话过期了工具还没完,
 * 只剩状态行;工具先完而话还没过期,状态行消失、话继续待着。两条都空 = 不显示。
 *
 * <h2>状态驱动,不是事件驱动</h2>
 * 渲染方逐帧问 {@link #view(UUID)} 现算,而不是靠推来的事件记状态——事件被
 * 吞掉就丢了(旧实现里"开轮时正文还活着,思考态被丢弃、之后再没人补"就是
 * 这么来的)。
 *
 * <p>客户端主线程专用。
 */
public final class SpeechBubbles {

    /** 正文寿命:保底给短句,按字数加时,封顶防长文霸屏。 */
    private static final long TEXT_LIFE_BASE_MS = 7_000;
    private static final long TEXT_LIFE_PER_CHAR_MS = 55;
    private static final long TEXT_LIFE_MAX_MS = 22_000;

    /**
     * 渲染方要画的东西——两条线可同时在场。
     *
     * @param text     未过期的正文;null = 这会儿没话
     * @param activity 正在执行的工具名;null = 没有工具在跑
     * @param waiting  在等模型回复(且没有工具在跑)——画「正在思考中」
     */
    public record View(String text, String activity, boolean waiting) {
        public boolean hasText() {
            return text != null && !text.isEmpty();
        }

        /** 有状态行要画吗(正在 xxx / 正在思考中)。 */
        public boolean hasStatus() {
            return activity != null || waiting;
        }
    }

    /** 一句还在生命周期里的话。 */
    private record Said(String text, long bornMs, long lifeMs) {
        boolean expired(long now) {
            return now - bornMs > lifeMs;
        }
    }

    private static final Map<UUID, Said> SAID = new HashMap<>();

    private SpeechBubbles() {}

    // ---- 写入面(代理循环调用) ----

    /** 她说了一句话:顶掉上一句,按字数给寿命。空串 = 没话说,清掉当前正文。 */
    public static void say(UUID entityUuid, String text) {
        if (entityUuid == null) return;
        String shown = text == null ? "" : text.trim();
        if (shown.isEmpty()) {
            SAID.remove(entityUuid);
            return;
        }
        long life = Math.min(TEXT_LIFE_MAX_MS,
                TEXT_LIFE_BASE_MS + (long) shown.length() * TEXT_LIFE_PER_CHAR_MS);
        SAID.put(entityUuid, new Said(shown, System.currentTimeMillis(), life));
    }

    /** 打断/死亡/退出:清掉这只的正文(忙碌态自会随代理循环停下)。 */
    public static void clear(UUID entityUuid) {
        if (entityUuid == null) return;
        SAID.remove(entityUuid);
    }

    /** 退出世界:清台账(和其他客户端会话态一起挂在断线钩子上)。 */
    public static void clear() {
        SAID.clear();
    }

    // ---- 读取面(渲染方逐帧调用) ----

    /** 此刻该给这只同伴画什么;两条线都空就返回 null。过期正文顺手清除。 */
    public static View view(UUID entityUuid) {
        if (entityUuid == null) return null;

        // 第一条线:正文(到点自己消失,与在不在忙无关)
        String text = null;
        Said said = SAID.get(entityUuid);
        if (said != null) {
            if (said.expired(System.currentTimeMillis())) {
                SAID.remove(entityUuid);
            } else {
                text = said.text();
            }
        }

        // 第二条线:此刻在干什么(工具优先于"在等回复"——具体的事比笼统的忙有信息量)
        String activity = null;
        boolean waiting = false;
        var loop = AgentLoopRegistry.get(entityUuid).orElse(null);
        if (loop != null) {
            activity = loop.currentActivity();
            waiting = activity == null && loop.isBusy();
        }

        if (text == null && activity == null && !waiting) {
            return null;   // 话说完了、活干完了:头顶就该干净
        }
        return new View(text, activity, waiting);
    }
}
