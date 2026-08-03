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
 * <h2>状态驱动,不是事件驱动</h2>
 * 渲染方逐帧问 {@link #view(UUID)},由三条判据现算,而不是靠推来的事件
 * 记状态——事件被吞掉就丢了(旧实现里"开轮时正文还活着,思考态被丢弃、
 * 之后再没人补"就是这么来的)。判据顺序:
 * <ol>
 *   <li>正文还没过期 → 显示正文(她刚说的话优先)</li>
 *   <li>否则代理还在忙 → 显示「正在回复中」,附一行当前动作</li>
 *   <li>否则 → 不显示(话说完了,头顶就该干净)</li>
 * </ol>
 * 工具调用不占独立气泡:它是"正在回复"的副文本,不是一句话。
 *
 * <p>客户端主线程专用。
 */
public final class SpeechBubbles {

    /** 正文寿命:保底给短句,按字数加时,封顶防长文霸屏。 */
    private static final long TEXT_LIFE_BASE_MS = 7_000;
    private static final long TEXT_LIFE_PER_CHAR_MS = 55;
    private static final long TEXT_LIFE_MAX_MS = 22_000;
    /** 思考流只留尾巴:气泡最多两行,存多了也是白存。 */
    private static final int THINKING_TAIL_CAP = 300;

    /** 渲染方要画的东西。{@code text} 为空 = 思考态(画省略号/思考流)。 */
    public record View(String text, String activity, boolean thinking) {}

    /** 一句还在生命周期里的话。 */
    private record Said(String text, long bornMs, long lifeMs) {
        boolean expired(long now) {
            return now - bornMs > lifeMs;
        }
    }

    private static final Map<UUID, Said> SAID = new HashMap<>();
    private static final Map<UUID, StringBuilder> THINKING = new HashMap<>();

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
        THINKING.remove(entityUuid);   // 说出口了,思考流没用了
    }

    /** 思考增量(推理模型的 reasoning 流):只留尾巴。 */
    public static void appendThinking(UUID entityUuid, String delta) {
        if (entityUuid == null || delta == null || delta.isEmpty()) return;
        StringBuilder sb = THINKING.computeIfAbsent(entityUuid, k -> new StringBuilder());
        sb.append(delta);
        if (sb.length() > THINKING_TAIL_CAP) sb.delete(0, sb.length() - THINKING_TAIL_CAP);
    }

    /** 打断/死亡/退出:正文与思考流一起清(忙碌态自会随代理循环停下)。 */
    public static void clear(UUID entityUuid) {
        if (entityUuid == null) return;
        SAID.remove(entityUuid);
        THINKING.remove(entityUuid);
    }

    /** 退出世界:清台账(和其他客户端会话态一起挂在断线钩子上)。 */
    public static void clear() {
        SAID.clear();
        THINKING.clear();
    }

    // ---- 读取面(渲染方逐帧调用) ----

    /** 此刻该给这只同伴画什么;不该画就返回 null。过期正文顺手清除。 */
    public static View view(UUID entityUuid) {
        if (entityUuid == null) return null;
        Said said = SAID.get(entityUuid);
        if (said != null) {
            if (!said.expired(System.currentTimeMillis())) {
                return new View(said.text(), null, false);
            }
            SAID.remove(entityUuid);
        }
        return AgentLoopRegistry.get(entityUuid)
                .filter(loop -> loop.isBusy())
                .map(loop -> new View("", loop.currentActivity(), true))
                .orElse(null);
    }

    /** 当前思考流尾巴(思考气泡的内容),没有则空串。 */
    public static String thinkingTail(UUID entityUuid) {
        StringBuilder sb = THINKING.get(entityUuid);
        return sb == null ? "" : sb.toString();
    }
}
