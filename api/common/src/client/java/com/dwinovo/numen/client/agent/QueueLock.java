package com.dwinovo.numen.client.agent;

/**
 * 谁会锁住同伴的输入队列。
 *
 * <p>队列本身收的是<b>不透明字符串</b>——它不知道 {@code "death"} 是什么意思,只知道
 * 有人锁着、全部松手才算开。名字集中放在这里,是为了不让同一把锁在两处写成不同的
 * 字面量(那样上锁的和松手的会对不上,队列就永远开不了)。
 *
 * <p>加一种新的暂停理由 = 这里加一个常量 + 在状态变化处上锁松手。<b>不是</b>在
 * 排空路径上加一个 if —— 这一轮清掉的三个特例({@code principal}、{@code duringTask}、
 * {@code hadSuspendedTurn})都是那么长出来的。
 */
public final class QueueLock {

    /** 她死了,还没复活。死着的时候再急的事也做不了。 */
    public static final String DEATH = "death";

    /**
     * 外接大脑驱动中:身体归外部 MCP 驱动者,内置大脑一轮都不开。
     *
     * <p>锁只拦内脑的"出"——事件照进不误,流出方向换成了外脑:它经 get_events
     * 整批取走({@code EntityAgentLoop.takeEventsForExternal}),主人在游戏里说的话
     * 也走这条线。这把锁因此就是"同一时刻一个脑答话"的仲裁本身,不是权宜之计。
     * 按 {@code McpMode.driving()} 每刻同步(失联回退开着时,外脑安静超时锁自动开)。
     */
    public static final String MCP_MODE = "mcp_mode";

    private QueueLock() {}
}
