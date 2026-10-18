package com.dwinovo.numen.api.agent.loop;

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

    /**
     * 主人在场:人在游戏里,或他的外部大脑连着——怎么算是宿主的事,只在宿主那一处。不在场时内脑待命({@link Hold#AWAY}):
     * 输入照收进收件箱,等他回来再处理。现算,不存。缺省恒在场——主人本来就在身边的宿主(评测)不用管。
     */
    default boolean ownerPresent() {
        return true;
    }

    /** 注入输入时垫在最前面、随这条 user 消息一起进历史的现场块;没有返回空串。 */
    String injectionPreamble();

    /** 身体手上有没有后台任务。 */
    boolean bodyTaskRunning();

    /** 此刻在干的那件事;没有具体动作返回 {@code null}。 */
    LoopStatus.Doing activity();
}
