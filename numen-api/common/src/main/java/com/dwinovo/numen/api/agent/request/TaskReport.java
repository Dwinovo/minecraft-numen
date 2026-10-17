package com.dwinovo.numen.api.agent.request;

import com.dwinovo.numen.api.task.Activity;

/**
 * 她此刻在做什么。<b>这是「她在做什么」的唯一真源</b>:任务槽一变({@code CompanionBrain} 一处),服务端大脑就收到一份,
 * 派发、重放、被顶替、干完、死亡清空走的是同一个出口;{@link RuntimeState} 照抄,不推断。
 *
 * <p>活不都是大脑派起来的:服务器重启后的重放、死亡复活后的重放都不经过它的工具口,所以不能靠"我派出去过什么"自己记账。
 *
 * @param taskId    公开任务 id;<b>空串 = 她现在闲着</b>
 * @param tool      任务名:函数全名(numen.move.to / numen.work.dig / numen.move.follow …)
 * @param describe  给模型看的一句描述,由任务记录自己给出
 * @param activity  给主人看的一句({@link Activity}:语言键 + 现场的词),界面按当前语言翻
 * @param standing  常驻(没有终点,不会发 task_finished)
 * @param elapsedMs 已经干了多久——重放回来的活也有正确的起点,不会从收到这一刻重新计时
 */
public record TaskReport(String taskId, String tool, String describe, Activity activity, boolean standing,
                         long elapsedMs) {

    /** 她闲着。 */
    public static TaskReport none() {
        return new TaskReport("", "", "", Activity.of(Activity.DEFAULT_KEY, ""), false, 0L);
    }

    public boolean isIdle() {
        return taskId.isEmpty();
    }
}
