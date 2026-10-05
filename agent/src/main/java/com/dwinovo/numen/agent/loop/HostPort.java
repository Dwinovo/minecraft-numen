package com.dwinovo.numen.agent.loop;

/**
 * 同伴那一侧的事实:时钟、主人设的主动性档位、驾驶席、身体在做什么,以及注入时垫在最前的现场块。
 * 全部现问现答,内核不存副本。
 */
public interface HostPort {

    /** 墙上时钟(毫秒)。游戏刻在单机退出时是冻结的,拿它算"躺了多久"会以为什么都没老。 */
    long now();

    /** 主动性档位 1~10,决定非急件攒多少条、多久才开 run。 */
    int initiativeLevel();

    /** 驾驶席此刻在外接模型手里。现算,不存。 */
    boolean externallyDriven();

    /** 注入输入时垫在最前面、随这条 user 消息一起进历史的现场块;没有返回空串。 */
    String injectionPreamble();

    /** 身体手上有没有后台任务。 */
    boolean bodyTaskRunning();

    /** 此刻在干的那件事,给人看的一句;没有具体动作返回 {@code null}。 */
    String activity();
}
