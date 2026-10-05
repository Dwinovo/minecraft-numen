package com.dwinovo.numen.agent.loop;

/**
 * 停牌:内脑为什么不开 run。对外只有这一个值;谁解开它写死在 {@link #releasedBy} 这张表里。
 *
 * <pre>
 * Hold        什么时候进入                          什么解开
 * DEAD        身体死亡                              复活
 * EXTERNAL    外接模型驾驶(现算,不存)              驾驶席交还内脑——现算的值自己变回来,不经 Release
 * OWNER_STOP  主人按停止                            主人再说话
 * BLOCKED     模型端点不可用(未绑定、没 key)       主人再说话;或绑定变更
 * FAILED      调用失败且不再重试                    主人说话;或来了急件
 * </pre>
 *
 * <p>DEAD 与 EXTERNAL 优先于其余三种:身体不在、驾驶席不在内脑手里时,别的停牌无意义。存储上是
 * "死没死"一个布尔(身体事实)、后三种一个值、外接驾驶每次现问,由 {@link AgentLoop#hold()} 合成。
 */
public enum Hold {
    DEAD,
    EXTERNAL,
    OWNER_STOP,
    BLOCKED,
    FAILED;

    /** 能解开停牌的事。 */
    public enum Release {
        /** 身体复活了。 */
        RESPAWN,
        /** 主人说话了(类型表里来自主人的插话进了队列)。 */
        OWNER_SPOKE,
        /** 这只同伴改绑了模型档案。 */
        BINDING_CHANGED,
        /** 来了急件。 */
        URGENT
    }

    /** 这件事解不解得开这个停牌。 */
    public boolean releasedBy(Release release) {
        return switch (this) {
            case DEAD -> release == Release.RESPAWN;
            case EXTERNAL -> false;
            case OWNER_STOP -> release == Release.OWNER_SPOKE;
            case BLOCKED -> release == Release.OWNER_SPOKE || release == Release.BINDING_CHANGED;
            case FAILED -> release == Release.OWNER_SPOKE || release == Release.URGENT;
        };
    }
}
