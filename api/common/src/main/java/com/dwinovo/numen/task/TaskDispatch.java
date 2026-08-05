package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.function.Consumer;

/**
 * 身体工具在 {@code onServerCall} 里用的三个静态帮手(建议 static import,
 * 调用点保持裸名):{@link #ctx} 造上下文,{@link #runSync} 回合挂着等,
 * {@link #setTask} 换掉她当前在做的事。
 *
 * <h2>选道判据(工具作者的单一真源)</h2>
 * <ul>
 *   <li><b>不占身体</b>(纯查询/UI/外部服务)→ 不进任务系统,invoke 现场 complete;</li>
 *   <li><b>占身体 + 有界短</b>(最坏几秒内保证干完,写得出不冤枉它的固定 deadline)
 *       → {@link #runSync}:回合挂起等结果——短到值得等;</li>
 *   <li><b>占身体 + 无界</b>(时长取决于世界:路程/资源/敌人)→ {@link #setTask}:
 *       受理即回执,收尾走 task_finished 事件——串行的工具派发器不能为一件几分钟的
 *       活冻结整个回合。</li>
 * </ul>
 *
 * <h2>常驻不是另一条路</h2>
 * 「一直钓鱼」和「钓 64 条」走<b>同一个</b> {@link #setTask}:区别只在任务的
 * {@code tick()} 返不返终态——给了 {@code count} 就会返 SUCCESS 干完腾位,
 * 没给就永远 RUNNING 占着槽,直到主人换掉它。工具作者写一次钓鱼逻辑,两种用法白送。
 *
 * <p>(从前这里还有第四条:"没有干完语义的注册成 TaskChain 参与竞价"。那条路
 * <b>一个实现都没有</b>——链是全局注册、每同伴全带、不能带参数也不能开关,
 * 根本挂不上去。收进槽之后它不需要单独存在。)
 */
public final class TaskDispatch {

    private TaskDispatch() {}

    /** 任务上下文:调用 id + 身体当前游戏刻(deadline 的起点)。 */
    public static ToolContext ctx(String toolCallId, NumenPlayer companion) {
        return new ToolContext(toolCallId, companion.level().getGameTime());
    }

    /**
     * 同步动作:回合挂着等它跑完。<b>不回执</b>——结果由任务结算时送回,
     * 客户端严格串行的工具派发器因此自然把同批的同步动作一个接一个排开,
     * 这里不需要队列也不会撞车。
     *
     * <p>它排在<b>当前任务之上</b>(见 {@link TaskSelector}):有人挂着等它,
     * 而队首的长活可能几分钟——让它排在后面等于把对话卡到 deadline。
     * 反过来它有界短,插队也饿不死别人。
     */
    public static void runSync(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        CompanionTickDispatcher.syncSlotFor(companion.getUUID()).put(companion, record);
    }

    /**
     * 换掉她当前在做的事:受理即回执 task_id,身体后台执行,收尾经 task_finished 送达。
     *
     * <p>槽里原来那件活会被<b>替换</b>(收到取消结果),而不是像从前那样拒绝新的——
     * "她在挖矿所以不理你"是主人最直观的一种出戏。主人改主意是常态。
     *
     * <p><b>唯一拒绝的情形</b>:槽里那件是同一批工具调用里刚受理、一刻都还没跑过的。
     * 那说明模型在一个回合里连派两件活——它该拿到第一件的结果、看清状况再决定下一步,
     * 而不是盲目承诺。这条判据本地可判({@code ticksRun == 0}),不用把回合 id
     * 穿到服务端。
     */
    public static void setTask(NumenPlayer companion, TaskRecord record, JsonObject args,
                               Consumer<String> reply) {
        java.util.UUID id = companion.getUUID();
        if (CompanionTickDispatcher.currentFreshlyAccepted(companion)) {
            TaskRecord busy = CompanionTickDispatcher.currentTaskFor(id);
            // 常驻的活永远不会发 task_finished，让模型去等它就是指一个不存在的事件。
            boolean busyStanding = busy.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE;
            reply.accept(TaskResult.fail("身体一次只做一件事。" + busy.publicId()
                    + "(" + busy.describe() + ")就在刚才那一刻受理的"
                    + (busyStanding
                        ? ";它是常驻的活,不会发 task_finished。"
                        : ";等它的 task_finished。")
                    + "先拿到这一件的结果、看清状况再决定下一步;"
                    + "真要换,下一回合直接派新的就是了。").toJson());
            return;
        }
        record.markAsync();
        CompanionTickDispatcher.currentSlotFor(id).put(companion, record);
        // 记下"她现在在做什么",服务器重启后照着重放一遍(见 TaskPersistence)。
        TaskPersistence.remember(companion, record.getToolName(), args);
        // 内置大脑靠 task_finished 事件收尾(别轮询);外部(MCP)夺舍收不到事件
        // (那条投给内置大脑,不是它),得自己轮询 task_status 到身体空闲,再感知确认。
        // 常驻的活没有终点,也就永远不会发 task_finished —— 回执必须说清楚,
        // 否则她会照着"等事件"的指引干等下去。
        boolean standing = record.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE;
        String note;
        if (standing) {
            note = "已受理,持续进行中。这件活没有终点,不会发 task_finished 事件;"
                    + "派别的身体动作就会顶替它,那是让它停下的正常方式。";
        } else if (record.isExternalCall()) {
            note = "已受理,后台执行中。用 task_status 轮询,身体转空闲即为完成,再用感知工具确认结果;task_stop 叫停。";
        } else {
            note = "已受理,后台执行中。完成会自动收到 task_finished 事件,不要轮询;task_status 查进度,task_stop 叫停。";
        }
        reply.accept(TaskResult.ok(
                note,
                java.util.Map.of(
                        "task_id", record.publicId(),
                        "task", record.getToolName(),
                        "async", true,
                        "standing", standing)).toJson());
    }
}
