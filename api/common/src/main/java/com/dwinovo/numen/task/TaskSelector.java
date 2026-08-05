package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.List;

/**
 * 谁拿身体——每 tick 从上往下问一句"你能跑吗",第一个说能的赢。
 *
 * <pre>
 * 1. 反射     多个,按注册序   等模型就晚了(摔落/换气/自卫/进食/脱困)
 * 2. 同步     0 或 1 个       回合挂着等它,有界短(放个方块)
 * 3. 当前任务 一个槽          主人说什么就是什么
 * 4. 空闲姿态 多个,按注册序   没别的事可做时(说话时看着主人)
 * </pre>
 *
 * <p>第 4 层不是"常驻行为"——那是第 3 层的槽。它是<b>连身体都不算真正占用</b>的
 * 姿态动作:她在挖矿时跟你说话不该停下回头(那条今天就在,{@code SpeakingLookChain}
 * 的出价本来就在任务之下),闲着的时候才转过来。全空 = 她站着,那没关系。
 *
 * <h2>为什么没有优先级数字</h2>
 * 一个槽,主人说什么就是什么。层与层的先后是固定的,层内除了反射都最多一个候选,
 * <b>没有任何需要比较的地方</b>。用分数表达"跟随在主人走远时才该压过钓鱼"这类
 * 连续变化的相对顺序,换来的是主人<b>预测不了</b>她下一秒干什么——那些分数他看不见。
 *
 * <p>常驻任务"暂时干不了"(主人走出 60 格、鱼竿没了)靠 {@link Task#canRun} 表达:
 * 那不是失败也不是让位,是休眠——条件回来了它自己接着干。跟原版 {@code Goal.canUse()}
 * 一个道理。
 *
 * <p>纯选择逻辑,不改任何状态;抢占/恢复的副作用由调用方按新旧赢家的差异去做。
 */
public final class TaskSelector {

    private TaskSelector() {}

    /**
     * 这一 tick 谁拿身体;三层都没人能跑则 {@code null}(她站着)。
     *
     * @param reflexes 反射,按注册序(先注册的先问)
     * @param sync     回合挂着等的同步动作,没有则 null
     * @param current  当前任务槽,空则 null
     * @param idle     空闲姿态,按注册序
     */
    public static Task select(List<Task> reflexes, Task sync, Task current,
                              List<Task> idle, NumenPlayer companion) {
        if (reflexes != null) {
            for (Task reflex : reflexes) {
                if (reflex != null && reflex.canRun(companion)) {
                    return reflex;
                }
            }
        }
        if (sync != null && sync.canRun(companion)) {
            return sync;
        }
        if (current != null && current.canRun(companion)) {
            return current;
        }
        if (idle != null) {
            for (Task pose : idle) {
                if (pose != null && pose.canRun(companion)) {
                    return pose;
                }
            }
        }
        return null;
    }
}
