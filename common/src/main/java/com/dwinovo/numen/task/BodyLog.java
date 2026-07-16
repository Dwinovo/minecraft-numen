package com.dwinovo.numen.task;

import java.util.ArrayDeque;

/**
 * The body's narrative outlet — the ONE collection point for everything the body
 * does on its own (instinct episodes, preemption stories, wardrobe changes), and
 * the ONLY core-domain producer of {@code body_log} events.
 *
 * <p>即报即发(宪法 §4 修订版):每条叙事到达即打包出货——箱里有几条就合并成
 * 一个 {@code <event kind="body_log">} 发走。什么时候到模型面前不再是这里的
 * 事:客户端收件箱按"发生时大脑的状态"三态路由(回合中贴边界/任务中立刻
 * 开轮/全闲躺着搭车)。旧的双轨路由(忙时扣押、贴工具结果尾巴)已废除——
 * 它的前提"任务链有活 = 模型正阻塞等结果"被异步任务抽掉了。
 *
 * <p>唯一的滞留原因是主人离线(sink 拒收):条目留箱,由 idle-tick 的
 * {@link #flush} 重试。离线累积以 {@link #MAX_ENTRIES} 封顶(最旧的丢弃),
 * 单个事件永远不会刷屏。Tick-thread only;纯 JDK,路由核心可无头测试
 * ({@code BodyLogTest}),Minecraft 传输藏在 {@link EventSink} 后面。
 */
public final class BodyLog {

    /**
     * 事件传输口。返回 {@code true} = 已交给客户端;{@code false} = 此刻没人
     * 收得了(主人离线)——条目留箱,之后的 flush 重试。生产接线是
     * {@code Companions.emitEvent(companion, xml, false)}:身体叙事是事实,
     * 事实不配自定紧急度(principal 恒 false)。
     */
    public interface EventSink {
        boolean tryEmit(String xml);
    }

    static final int MAX_ENTRIES = 6;

    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private final EventSink sink;

    public BodyLog(EventSink sink) {
        this.sink = sink;
    }

    /**
     * Record one completed body episode (one line, no trailing punctuation) and
     * ship it immediately — together with anything an earlier refused flush left
     * in the box.
     */
    public void report(String line) {
        if (line == null || line.isBlank()) return;
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(line);
        flush();
    }

    /**
     * 打包出货:箱内全部条目合并成一个 {@code body_log} 事件交给 sink。拒收
     * (主人离线)则原样留箱。也是 idle-tick 重试与死亡兜底路径的入口——
     * 空箱时是 no-op。
     */
    public void flush() {
        if (entries.isEmpty()) return;
        String xml = "<event kind=\"body_log\">your body handled on its own: "
                + String.join("; ", entries) + "</event>";
        if (sink.tryEmit(xml)) {
            entries.clear();
        }
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }
}
