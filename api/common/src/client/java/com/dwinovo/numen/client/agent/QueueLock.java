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
     * 外接大脑模式开着:身体归外部 MCP 驱动者,内置大脑一轮都不开。
     *
     * <p>这只是个权宜之计——外面那个大脑其实<b>也需要</b>这些世界事件,不然它是瞎的。
     * 正确的形状是给队列换一个流出方向(sink)而不是锁住它。锁和 sink 是正交的两件事,
     * 所以现在这么做不挡住那条路。
     */
    public static final String MCP_MODE = "mcp_mode";

    private QueueLock() {}
}
