package com.dwinovo.numen.api.agent.inbox;

/**
 * 给模型看的条目怎么写下"这是游戏里什么时候":{@code day="3" t="21:15"}。世界的事({@code <event>})、主人的话({@code <query>})、
 * 系统条目都从这一处盖,模型读到的每一条都带着发生那一刻的游戏日期与钟点,和请求里的 {@code <now>} 是同一种写法。
 *
 * <p>纯 JVM:游戏里的时刻({@code dayTime},主世界的一天 24000 刻)由创建条目的一方传进来。
 */
public final class EventClock {

    private EventClock() {}

    /** 第几天(从 0 数)。 */
    public static long day(long dayTime) {
        return Math.floorDiv(dayTime, 24000L);
    }

    /** 游戏内钟点 HH:mm。原版 0 刻 = 早上 6 点。 */
    public static String clockOf(long dayTime) {
        long inDay = Math.floorMod(dayTime, 24000L);
        long minutes = (inDay * 60L / 1000L + 6L * 60L) % (24L * 60L);
        return String.format("%02d:%02d", minutes / 60L, minutes % 60L);
    }

    /** 标签里的两个属性,前面带空格:{@code  day="3" t="21:15"}。 */
    public static String stamp(long dayTime) {
        return " day=\"" + day(dayTime) + "\" t=\"" + clockOf(dayTime) + "\"";
    }
}
