package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

import java.util.function.Consumer;

/**
 * 一个装着任务的位置——身体只有两个:同步动作一个,当前任务一个。
 *
 * <p>从前这套机器长在 {@code LlmTaskChain} 里:它既是一个竞价者,又内部维护一条
 * 队列,还负责 deadline、结算、送结果。拆出来之后<b>它只剩"装着一个任务"</b>,
 * 谁拿身体交给 {@link TaskSelector} 判。
 *
 * <h2>为什么抢占不结算</h2>
 * 只有任务在<b>自己被 tick 的时候</b>走到终态才结算(建结果 → 进出箱 → 腾位)。
 * 被反射抢占从不结算,所以"抢占—恢复"过的任务照样只发<b>恰好一个</b>结果——
 * 客户端那条 tool_call 等的就是这一个。{@link #freeze} 把被抢占那一刻的 deadline
 * 往后推一刻,免得它在别人占着身体时被冤枉成超时。
 */
final class TaskSlot {

    private final Consumer<TaskRecord> outbox;

    private Task task;
    private TaskRecord record;
    /** 它真正被 tick 过几刻——0 表示"刚受理还没动手"。 */
    private int ticksRun;

    TaskSlot(Consumer<TaskRecord> outbox) {
        this.outbox = outbox;
    }

    boolean isEmpty() {
        return record == null;
    }

    /**
     * 刚受理、一刻都还没跑过。
     *
     * <p>用来分开两种"再派一个活":同一批工具调用里的第二个(模型在做计划,
     * 该拒绝——让它拿到第一个的结果再决定),和新回合里的(主人/模型改主意了,
     * 该替换)。判据本地可判,不用把回合 id 穿到服务端。
     */
    boolean freshlyAccepted() {
        return record != null && ticksRun == 0;
    }

    TaskRecord record() {
        return record;
    }

    /** 槽里的任务此刻能不能跑;空槽返回 false。 */
    boolean canRun(NumenPlayer companion) {
        return record != null && record.getState() == TaskState.RUNNING && task.canRun(companion);
    }

    /**
     * 放一个新任务进来。槽里原来那个按<b>被换掉</b>结算并送结果——不能让它悄悄
     * 消失:模型手上握着它的 tool_call,那条调用要么有结果要么永远悬着。
     */
    void put(NumenPlayer companion, TaskRecord rec) {
        if (record != null) {
            task.stop(companion, Task.StopReason.REPLACED);
            if (!record.getState().isTerminal()) {
                record.setState(TaskState.CANCELLED);
            }
            settle();
        }
        rec.setState(TaskState.RUNNING);
        rec.markStarted(companion.level().getGameTime());
        task = TaskFactory.create(companion, rec);
        record = rec;
        ticksRun = 0;
        task.start(companion);
        // start() 里就走到终态的(一次性动作把活全干完了 / 前置条件不通过)当刻结算,
        // 免得它空占一刻 RUNNING —— 那一刻里的一次"停止"会给已经干完的事发中断。
        settleIfTerminal();
    }

    /** 前进一刻:先看 deadline,再跑一刻,走到终态就结算。 */
    void tick(NumenPlayer companion) {
        if (record == null) {
            return;
        }
        ticksRun++;
        if (record.getState() == TaskState.RUNNING) {
            if (companion.level().getGameTime() >= record.getDeadlineGameTime()) {
                record.setState(TaskState.TIMEOUT);
            } else {
                record.setState(task.tick(companion));
            }
        }
        settleIfTerminal();
    }

    /** 丢掉身体但不拆掉任务——被更高层抢占时用,状态全留着,下次接着跑。 */
    void loseBody(NumenPlayer companion) {
        if (task != null) {
            task.stop(companion, Task.StopReason.PREEMPTED);
        }
    }

    /** 被抢占这一刻不算它的预算:deadline 往后推一刻(只会往后,不会往前)。 */
    void freeze() {
        if (record != null && record.getState() == TaskState.RUNNING) {
            record.extendDeadlineTo(record.getDeadlineGameTime() + 1);
        }
    }

    /**
     * 走到终态就结算。<b>每刻都要调</b>,哪怕这一刻是别人拿着身体——主人按停止会
     * 在带外把记录标成 CANCELLED,而客户端严格串行的工具派发器会一直卡到那一个
     * 结果送出为止,不能等这个槽下次赢了才结算。
     */
    void settleIfTerminal() {
        if (record != null && record.getState().isTerminal()) {
            settle();
        }
    }

    /** 主人按停止:标成取消,下一次结算会送出结果。 */
    void cancel() {
        if (record != null && record.getState() == TaskState.RUNNING) {
            record.setState(TaskState.CANCELLED);
        }
    }

    /** 身体要离开世界了:就地结算(它不会再被 tick),让 cleanup 跑完、结果送出。 */
    void finalizeInline() {
        if (record == null) {
            return;
        }
        if (!record.getState().isTerminal()) {
            record.setState(TaskState.CANCELLED);
        }
        settle();
    }

    /**
     * 死亡:丢掉任务但<b>不发工具结果</b>——那条 tool_call 客户端已经用死因结算过了。
     * 但异步任务要补一条收尾事件:派发回执写着"完成会自动收到 task_finished,
     * 不要轮询",丢掉却不发收尾等于毁约。
     */
    void dropNoResult(NumenPlayer companion) {
        if (record != null && record.isAsync() && !record.isExternalCall()) {
            com.dwinovo.numen.event.NumenEvents.taskFinished(companion, record.publicId(),
                    record.getToolName(), "interrupted", "任务因她死亡而中断");
        }
        task = null;
        record = null;
        ticksRun = 0;
    }

    private void settle() {
        record.setResult(task.result(record.getState()));
        outbox.accept(record);
        task = null;
        record = null;
        ticksRun = 0;
    }
}
