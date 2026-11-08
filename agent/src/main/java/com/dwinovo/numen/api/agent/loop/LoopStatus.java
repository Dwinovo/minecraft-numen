package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.inbox.EventQueue;

import java.util.List;

/**
 * 内核此刻的只读快照——"她在不在忙、为什么不动、排着什么"只从这里读。
 *
 * @param phase           在做的那件事;{@code null} = 闲
 * @param hold            停牌;{@code null} = 没有
 * @param holdReason      停牌是带着原因进来的(端点不可用、调用失败)就是那个原因;其余为 {@code null}
 * @param bodyTaskRunning 身体手上有后台任务(服务端推来的镜像)
 * @param queuedPreview   排着、进聊天流的条目(主人的话、排上的整理/清空)
 * @param compactProgress 整理记忆的进度 0~1。摘要多长事先不知道,没有分母:这是一条随流回来的字数逼近 1 的
 *                        曲线,给的是"还在动"这个事实;不在整理时为 0
 * @param activity        此刻在干的那件事(语言键加现场的词,界面按主人的语言翻);没有具体动作时 {@code null}
 */
public record LoopStatus(Phase phase, Hold hold, Cause holdReason, boolean bodyTaskRunning,
                         List<EventQueue.Preview> queuedPreview, double compactProgress, Doing activity) {

    /**
     * 她此刻在干什么:语言键加现场的词。任务有自己的说法(键由所属模组的语言文件给);没有任务、只有手上这个工具调用时,
     * 键是 {@code numen.tool.<工具名>},没有译文的(接进来的外部工具)界面退回 {@code args[0]} 的工具名。
     */
    public record Doing(String key, List<String> args) {

        public Doing {
            args = List.copyOf(args);
        }
    }

    /** 大脑或身体在干活:等模型、跑工具、整理记忆,或身体有后台任务。 */
    public boolean busy() {
        return phase != null || bodyTaskRunning;
    }

    /** 停止键此刻有东西可停:在干活,或者排着主人的指令。 */
    public boolean canInterrupt() {
        return busy() || !queuedPreview.isEmpty();
    }
}
