package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 任务会话结束的回调口:引擎在"这一次任务会话确定结束"的四个时点触发——
 * 主人 Stop、task_stop 叫停、死亡丢弃、LLM 车道空闲超过宽限期(任务结束
 * 边沿)。内容包挂钩子做自己的收尾(numen-core 在此释放任务作用域的
 * MAINHAND 意图钉,宪法 §5)。引擎自己不知道"钉"是什么——它只报时点。
 */
public final class TaskSessionHooks {

    private static final List<Consumer<NumenPlayer>> HOOKS = new ArrayList<>();

    private TaskSessionHooks() {}

    /** Init-time only,与其余登记口同一约定。 */
    public static synchronized void onSessionEnd(Consumer<NumenPlayer> hook) {
        HOOKS.add(hook);
    }

    static void fireSessionEnd(NumenPlayer companion) {
        for (Consumer<NumenPlayer> h : HOOKS) {
            h.accept(companion);
        }
    }
}
