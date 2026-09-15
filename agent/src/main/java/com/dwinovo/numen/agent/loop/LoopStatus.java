package com.dwinovo.numen.agent.loop;

import java.util.List;

/**
 * 内核此刻的只读快照——"她在不在忙、为什么不动、排着什么"只从这里读。
 *
 * @param phase           在做的那件事;{@code null} = 闲
 * @param hold            停牌;{@code null} = 没有
 * @param bodyTaskRunning 身体手上有后台任务(服务端推来的镜像)
 * @param queuedPreview   排着、进聊天流的条目(主人的话、排上的整理/清空)
 * @param compactProgress 整理记忆的进度 0~1。摘要多长事先不知道,没有分母:这是一条随流回来的字数逼近 1 的
 *                        曲线,给的是"还在动"这个事实;不在整理时为 0
 * @param activity        此刻在干的那件事,给人看的一句;没有具体动作时 {@code null}
 */
public record LoopStatus(Phase phase, Hold hold, boolean bodyTaskRunning, List<String> queuedPreview,
                         double compactProgress, String activity) {

    /** 大脑或身体在干活:等模型、跑工具、整理记忆,或身体有后台任务。 */
    public boolean busy() {
        return phase != null || bodyTaskRunning;
    }

    /** 停止键此刻有东西可停:在干活,或者排着主人的指令。 */
    public boolean canInterrupt() {
        return busy() || !queuedPreview.isEmpty();
    }
}
