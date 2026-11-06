package com.dwinovo.numen.pathing.api;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 宿主收寻路这一类事的那一头：一趟导航是怎么收的场、卡在哪、为什么反复重搜。
 *
 * <p>模块里只有这一条出口，而且只有值：{@link com.dwinovo.numen.pathing.plan.Reason}、{@link Outcome} 与
 * {@link com.dwinovo.numen.pathing.drive.Journal} 的账也已经算好了，缺的只是有人存。怎么格式化、存到哪、
 * 存给谁看，全是宿主的事——模块不认识日志，也不认识落盘。
 *
 * <p>只在世界所在的线程上被调用。
 */
public interface Trace {

    void path(PathEvent event);

    /** 没人听：评测、单测与不关心轨迹的宿主用它。 */
    Trace NONE = event -> { };

    /**
     * 一趟导航收场。与 {@link Navigation} 那一行收场日志同源，报在同一处：全流程里唯一既拿得到
     * {@link Outcome}、又拿得到整趟行程账的就是门面。
     *
     * @param outcome 结局那一类的名字（{@code Arrived}、{@code NoRoute}……）；被叫停为 {@code Stopped}
     * @param at      收场时身体在哪
     * @param cell    卡在哪一格；结局里没有卡点为 {@code null}
     * @param reason  {@link com.dwinovo.numen.pathing.plan.Reason} 的 {@code name()}；执行期的事（卡住、看不见）
     *                没有前提上的原因，为 {@code null}
     * @param ticks   这一趟用了几刻
     * @param steps   实际走完几步
     * @param replans 按原因分的重规划次数，键是 {@link com.dwinovo.numen.pathing.drive.Journal.Replan} 的
     *                {@code name()}；一次都没重搜时是空表
     */
    record PathEvent(String outcome, Vec3 at, BlockPos cell, String reason, int ticks, int steps,
                     Map<String, Integer> replans) {

        public PathEvent {
            replans = Map.copyOf(replans);
        }
    }
}
