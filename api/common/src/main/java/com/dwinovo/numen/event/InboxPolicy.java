package com.dwinovo.numen.event;

/**
 * 收件箱什么时候该主动倒出去——"她多久开一次口"这个问题的唯一答案出处。
 *
 * <h2>三条倾倒理由</h2>
 * <ol>
 *   <li><b>有 urgent</b> —— 她不知道就会做错事,立刻开轮,把队列里攒的一切一起带走;</li>
 *   <li><b>攒够了</b> —— 非 urgent 事件累计到档位阈值;</li>
 *   <li><b>攒久了</b> —— 够不到阈值但躺太久了。少了这条,高档位就退化成永久沉默:
 *       阈值 10 而只发生了 3 件事,那 3 条会一直躺到主人下次开口。</li>
 * </ol>
 *
 * <p>以上只在她<b>完全空闲</b>时问。回合进行中不需要决策——事件到工具批结算的
 * 边界自然一次倒箱,那是协议决定的,不是策略决定的。
 *
 * <h2>档位</h2>
 * 主动性 1~10 由主人自己拉。这是**及时性**的旋钮,不是"话多话少"的旋钮:
 * 数值越小,模型知道世界变化越及时,token 烧得越快;越大知道得越晚,越省。
 * 硬编码任何一边都是错的——打末影龙的人和要陪伴的人,正确答案本来就不一样。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class InboxPolicy {

    /** 主动性档位的取值范围。 */
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;
    /** 缺省档位:攒够几条说一次,既不聒噪也不装死。 */
    public static final int DEFAULT_LEVEL = 3;

    private InboxPolicy() {}

    /** 档位 → 攒够几条就说。就是档位本身:1 = 一有动静就说,10 = 攒够十条。 */
    public static int threshold(int level) {
        return clampLevel(level);
    }

    /**
     * 档位 → 攒到多久也要说(毫秒)。随档位线性拉长:1 档一分钟,10 档半小时。
     * 1 档其实用不上(阈值就是 1,一来就走),留着是为了让规则没有缺口。
     */
    public static long maxWaitMs(int level) {
        int lv = clampLevel(level);
        return (60_000L * lv * lv) / 3L;   // 1档≈20s,3档=3min,10档≈33min
    }

    public static int clampLevel(int level) {
        return Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
    }

    /**
     * 完全空闲时,现在该不该主动开一轮。
     *
     * @param hasUrgent      队列里有没有 urgent 事件
     * @param queuedEvents   队列里攒了几条事件
     * @param oldestAgeMs    最老那条躺了多久;队列空时传任意值
     * @param level          主动性档位
     */
    public static boolean shouldDrain(boolean hasUrgent, int queuedEvents, long oldestAgeMs, int level) {
        if (queuedEvents <= 0) {
            return false;
        }
        if (hasUrgent) {
            return true;
        }
        return queuedEvents >= threshold(level) || oldestAgeMs >= maxWaitMs(level);
    }
}
