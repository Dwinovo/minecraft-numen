package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.entity.NumenPlayer;

import java.util.function.Consumer;

/**
 * 身体活进槽的两条路:{@link #runSync} 回合挂着等,{@link #setTask} 换掉她当前在做的事。API 函数不直接调它们:返回
 * {@code Pending<R>}(经 {@code ServerCall.sync})或 {@code Job<R>},由派发({@code com.dwinovo.numen.sdk.Dispatcher})按返回类型走。
 *
 * <h2>选道判据(函数作者的单一真源)</h2>
 * <ul>
 *   <li><b>不占身体</b>(纯查询 / UI / 外部服务 / 登记类如 {@code set_timer})→ 不进任务系统,当场返回;</li>
 *   <li><b>占身体 + 有界短</b>(最坏几秒内保证干完,写得出不冤枉它的固定 deadline)
 *       → {@link #runSync}:调用挂着等结果——短到值得等;</li>
 *   <li><b>占身体 + 无界</b>(时长取决于世界:路程/资源/敌人)→ {@link #setTask}:
 *       受理即回编号,收尾走 task_finished 事件;程序等这一条再往下走。</li>
 * </ul>
 *
 * <h2>受理 = 这件活此刻真能开始</h2>
 * {@link #setTask} 派的活受理之前先准备({@link Task#prepare}、{@link Preparation}):参数的写法由处理函数当场判,
 * 准备再判世界事实与规划(一次有展开预算的后台搜索)。都过了才受理——才换进槽里、顶掉她手上那件、回"已受理"并带上准备
 * 查到的事实;任何一步不过,回错误结果,没有任务编号、没有 task_finished,她手上的活不受影响。能当场判的当场判,
 * 要搜索的结论出来那一刻才回复——调用的回信口晚一点回,和 {@code route plan} 同一种写法;内脑的派发器本来就等这条
 * 回执才派下一个,串行规矩不变。受理之后才冒出来的(路上世界变了、主人拒绝、中途卡住)照旧走 task_finished;
 * 要问主人的不算开始不了,受理之后运行中问。
 *
 * <h2>常驻不是另一条路</h2>
 * 「一直钓鱼」和「钓 64 条」走<b>同一个</b> {@link #setTask}:区别只在任务的
 * {@code tick()} 返不返终态——给了 {@code count} 就会返 SUCCESS 干完腾位,
 * 没给就永远 RUNNING 占着槽,直到主人换掉它。工具作者写一次钓鱼逻辑,两种用法白送。
 *
 * <p>没有第四条。竞价链是本能的场子,工具进不去:链是全局注册、每同伴全带、
 * 不能带参数也不能开关,一次带参的工具调用挂不上去。
 */
public final class TaskDispatch {

    private TaskDispatch() {}

    /** 任务上下文:调用 id + 身体当前游戏刻(deadline 的起点)。 */
    public static ToolContext ctx(String toolCallId, NumenPlayer companion) {
        return new ToolContext(toolCallId, companion.level().getGameTime());
    }

    /**
     * 同步动作:调用挂着等它跑完。<b>当场不回</b>——任务结算时结果经 {@code reply} 送回,这是这次调用唯一的
     * 回信口。客户端严格串行的派发器因此自然把同批的同步动作一个接一个排开,这里不需要队列也不会撞车。
     *
     * <p>它排在<b>当前任务之上</b>(见 {@link TaskSelector}):有人挂着等它,
     * 而队首的长活可能几分钟——让它排在后面等于把对话卡到 deadline。
     * 反过来它有界短,插队也饿不死别人。
     */
    public static void runSync(NumenPlayer companion, TaskRecord record, Consumer<TaskResult> reply) {
        record.replyTo(reply);
        CompanionTickDispatcher.syncSlotFor(companion.getUUID()).put(companion, record,
                TaskFactory.create(companion, record));
    }

    /**
     * 换掉她当前在做的事:准备过了才受理(见类注释),受理即 {@code accepted},身体后台执行,收尾经 task_finished 送达;准备不过
     * {@code refused} 拿到那条失败,没有任务编号、没有 task_finished,她手上的活不受影响。
     *
     * <p>槽里原来那件活会被<b>替换</b>,不拒绝新的——主人改主意是常态,而"她在挖矿所以不理你"是最直观的一种出戏。
     * 新活真受理的那一刻才顶掉它,被顶掉的那件照常以 stopped 收尾。同一轮里的几件活不会互相顶掉:派发器等前一件收尾才派下一件。
     *
     * @param replay 重启后再跑的那一行 Lua;没有可再跑的是 null
     */
    public static void setTask(NumenPlayer companion, TaskRecord record, String replay, Consumer<TaskResult> refused,
                               Runnable accepted) {
        Task runner = TaskFactory.create(companion, record);
        long asked = companion.level().getGameTime();
        CompanionTickDispatcher.prepare(companion, new Preparing.Call(runner.prepare(companion), readiness -> {
            if (readiness.ready()) {
                record.markAsync();
                // 准备花掉的刻不算这件活的期限
                record.extendDeadlineTo(record.getDeadlineGameTime() + (companion.level().getGameTime() - asked));
                CompanionTickDispatcher.assign(companion, record, runner);
                // 记下"她现在在做什么",服务器重启后照着再跑一遍(见 TaskPersistence)
                TaskPersistence.remember(companion, record.getToolName(), replay);
                accepted.run();
            } else {
                refused.accept(readiness.refusal());
            }
        }));
    }

    /** 直接交一件活(测试直接测执行器时用):它不出自哪一次调用,重启后没有可再跑的,受理与否不回。 */
    public static void setTask(NumenPlayer companion, TaskRecord record) {
        setTask(companion, record, null, refused -> { }, () -> { });
    }
}
