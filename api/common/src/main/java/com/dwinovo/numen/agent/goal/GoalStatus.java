package com.dwinovo.numen.agent.goal;

import java.util.Locale;

/**
 * 长期目标的生命周期。
 *
 * <p>只有 {@link #ACTIVE} 会驱动续跑;其余四种都是"停下了",区别只在<b>为什么停</b>——
 * 主人得知道是他自己按的、她撞墙了、还是跑够了轮次。
 */
public enum GoalStatus {

    /** 在跑。每轮收尾都会自动续上。 */
    ACTIVE("active", "进行中"),
    /** 主人按了暂停,或者断线/打断自动暂停。{@code resume} 能接上。 */
    PAUSED("paused", "已暂停"),
    /** 连着撞了几次同样的墙。等主人给条件,{@code resume} 接上。 */
    BLOCKED("blocked", "卡住了"),
    /** 续跑轮次到顶。{@code continue} 能再放一轮额度。 */
    MAX_TURNS("max_turns", "跑够轮次了"),
    /** 做完了。终点。 */
    COMPLETE("complete", "已完成");

    private final String key;
    private final String label;

    GoalStatus(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 落盘用的字符串。 */
    public String key() {
        return key;
    }

    /** 给主人看的中文。 */
    public String label() {
        return label;
    }

    public static GoalStatus parse(String raw, GoalStatus fallback) {
        if (raw == null) {
            return fallback;
        }
        String k = raw.trim().toLowerCase(Locale.ROOT);
        for (GoalStatus s : values()) {
            if (s.key.equals(k)) {
                return s;
            }
        }
        return fallback;
    }
}
