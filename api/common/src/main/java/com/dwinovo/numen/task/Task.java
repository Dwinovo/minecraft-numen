package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * 一件想占用身体的事——<b>反射、同步动作、指令、常驻行为共用这一个接口</b>。
 *
 * <h2>为什么能共用</h2>
 * 它们的差别一直被当成类型差别去建模(链 / 任务 / 模式 / 本能),于是每加一种就
 * 多一层机制。实际上差别只有两处,而且都是<b>行为</b>不是<b>类型</b>:
 *
 * <ul>
 *   <li>{@link #tick} 返不返终态——<b>返了就是有界的</b>(挖 64 块,干完让位),
 *       <b>永远返 RUNNING 就是常驻的</b>(钓鱼、跟随,直到主人换掉它)。
 *       所以同一段钓鱼代码,{@code fish(count=64)} 和 {@code fish()} 白送两种用法;</li>
 *   <li><b>谁把它放进来的</b>——见 {@link TaskDispatch} 的三个动词,决定它落在哪一层。</li>
 * </ul>
 *
 * <h2>三层</h2>
 * {@link TaskSelector} 每 tick 从上往下问"你能跑吗",第一个说能的拿身体:
 *
 * <pre>
 * 1. 反射     多个,按注册序   —— 等模型就晚了(自卫/自救/摔落缓冲)
 * 2. 同步     0 或 1 个       —— 回合挂着等它,有界短
 * 3. 当前任务 ★一个槽★        —— 主人说什么就是什么;空 = 她站着
 * </pre>
 *
 * <p>没有优先级数字、没有 band、没有链。层的顺序就是全部的排序信息;层内只有
 * 反射需要先后,用注册序。
 */
public interface Task {

    /**
     * 现在能跑吗。反射用它表达触发条件(饿了 / 快淹死了);
     * 常驻任务用它表达"暂时干不了"(主人走太远、鱼竿没了)——那不是失败,是休眠,
     * 条件回来了自己接着干。
     *
     * <p>默认 {@code true}:被派下来的活默认就该干。
     */
    default boolean canRun(NumenPlayer companion) {
        return true;
    }

    /**
     * 拿到身体的第一 tick。被抢占后重新拿到身体<b>不会</b>再调这里(那是恢复,不是重来)。
     *
     * <p>默认什么都不做:反射没有"开始"这回事,它们只有触发。
     */
    default void start(NumenPlayer companion) {
    }

    /** 前进一 tick。{@link TaskState#RUNNING} = 还没完;终态 = 完了(常驻的永远不返终态)。 */
    TaskState tick(NumenPlayer companion);

    /** 丢掉身体:被更高层抢占、被换掉、身体没了。{@code why} 说明是哪种。 */
    void stop(NumenPlayer companion, StopReason why);

    /**
     * 交回给模型的结果信封。只有走到终态才有意义——常驻任务永远不会被调到。
     *
     * <p>任务必须有返回值。没有的话,超时兜底就得每一层自己发明一套,同一个问题
     * 会在几十个文件里各答一次。
     */
    default TaskResult result(TaskState terminal) {
        return null;
    }

    /** 给日志和面板看的短名。 */
    String name();

    /** 丢掉身体的原因。 */
    enum StopReason {
        /** 被更高层抢占(反射插进来 / 同步动作插进来)——任务状态留着,之后接着跑。 */
        PREEMPTED,
        /** 被换掉:主人改主意了,或者模型派了新活。 */
        REPLACED,
        /** 身体没了:死亡、遣散、下线。 */
        BODY_GONE
    }
}
