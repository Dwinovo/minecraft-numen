package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 身体工具在 {@code onServerCall} 里用的三个静态帮手(建议 static import,
 * 调用点保持裸名):{@link #ctx} 造上下文,{@link #runSync} 回合挂着等,
 * {@link #setTask} 换掉她当前在做的事。
 *
 * <h2>选道判据(工具作者的单一真源)</h2>
 * <ul>
 *   <li><b>不占身体</b>(纯查询 / UI / 外部服务 / 登记类如 {@code set_timer})→ 不进任务系统,
 *       invoke 现场 complete;</li>
 *   <li><b>占身体 + 有界短</b>(最坏几秒内保证干完,写得出不冤枉它的固定 deadline)
 *       → {@link #runSync}:回合挂起等结果——短到值得等;</li>
 *   <li><b>占身体 + 无界</b>(时长取决于世界:路程/资源/敌人)→ {@link #setTask}:
 *       受理即回执,收尾走 task_finished 事件——她不必为一件几分钟的活冻结整个回合。
 *       同一轮后面还有调用时,内脑的派发器读受理回执({@link #runningTaskOf}),等这件活收尾
 *       再派下一个。</li>
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
     * 同步动作:回合挂着等它跑完。<b>当场不回执</b>——任务结算时结果经 {@code reply} 送回,这是这次调用唯一的
     * 回信口(谁派的就回给谁:模型的调用、{@code /numen drive} 的发令人)。客户端严格串行的工具派发器因此自然把
     * 同批的同步动作一个接一个排开,这里不需要队列也不会撞车。
     *
     * <p>它排在<b>当前任务之上</b>(见 {@link TaskSelector}):有人挂着等它,
     * 而队首的长活可能几分钟——让它排在后面等于把对话卡到 deadline。
     * 反过来它有界短,插队也饿不死别人。
     */
    public static void runSync(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        record.replyTo(reply);
        CompanionTickDispatcher.syncSlotFor(companion.getUUID()).put(companion, record,
                TaskFactory.create(companion, record));
    }

    /**
     * 换掉她当前在做的事:准备过了才受理(见类注释),受理即回执 task_id,身体后台执行,收尾经 task_finished 送达。
     *
     * <p>槽里原来那件活会被<b>替换</b>,不拒绝新的——主人改主意是常态,而"她在挖矿所以不理你"是最直观的一种出戏。
     * 新活真受理的那一刻才顶掉它,受理回执当场说顶掉了谁,被顶掉的那件照常以 stopped 收尾;被拒的调用不碰它。同一轮里的
     * 几件活不会互相顶掉:内脑的派发器等前一件收尾才派下一件,这里不必猜哪几件是同一批的。
     *
     * <p>这是直接交一件活的写法(测试直接测执行器时用):它不出自哪一次调用,重启后没有可重放的,不记。
     */
    public static void setTask(NumenPlayer companion, TaskRecord record, Consumer<String> reply) {
        accept(companion, record, null, null, reply);
    }

    /**
     * 动作派活的写法,规矩同上。记录的名字是给模型看的函数名({@link ServerSource#taskName()});重放记的是动作的路径与
     * 这次调用读到的参数写回的那一行命令({@link ServerSource#replayLine()}),处理函数把只在这一次开服里有效的写法换掉了的,
     * 是换过的那一行({@link ServerSource#replayedWith})。
     */
    public static void setTask(ServerSource source, TaskRecord record) {
        accept(source.companion(), record, source.actionPath(), source.replayLine(), source::reply);
    }

    /**
     * 派身体任务的每个入口都经过这里:造出跑它的任务,交给这具身体的准备位({@link Preparing})。准备有了结论才受理或回错误;
     * 当场就有结论的(不用搜索的)当场回。
     *
     * @param action     派活的动作的路径({@code move go});不出自动作的是 null
     * @param replayLine 重启后重放的那一行命令;没有可重放的是 null
     */
    private static void accept(NumenPlayer companion, TaskRecord record, String action, String replayLine,
                               Consumer<String> reply) {
        Task runner = TaskFactory.create(companion, record);
        long asked = companion.level().getGameTime();
        CompanionTickDispatcher.prepare(companion, new Preparing.Call(runner.prepare(companion), readiness -> {
            if (readiness.ready()) {
                accepted(companion, record, runner, action, replayLine, reply, readiness.words(), asked);
            } else {
                reply.accept(readiness.refusal().toJson());
            }
        }));
    }

    /**
     * 准备过了,受理:"顶掉了谁"只在这一处说——槽里原来的那件在派新活之前取出来,写进新活的受理回执;准备查到的事实接在
     * 受理那句话后面。
     *
     * @param facts 准备查到、要交代的事实;没有为 null
     * @param asked 调用进来的那一刻(游戏刻):准备花掉的刻不算这件活的期限
     */
    private static void accepted(NumenPlayer companion, TaskRecord record, Task runner, String action,
                                 String replayLine, Consumer<String> reply, String facts, long asked) {
        // 已经走到终态、只等这一刻结算的那件(刚被 task_stop 叫停)不是这次顶掉的
        TaskRecord current = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        TaskRecord replaced = current != null && !current.getState().isTerminal() ? current : null;
        record.markAsync();
        record.extendDeadlineTo(record.getDeadlineGameTime() + (companion.level().getGameTime() - asked));
        CompanionTickDispatcher.assign(companion, record, runner);
        // 记下"她现在在做什么",服务器重启后照着重放一遍(见 TaskPersistence)。
        TaskPersistence.remember(companion, record.getToolName(), action, replayLine);
        // 有终点的活收尾时发 task_finished,派它的程序等的就是这一条(内脑与外接大脑的程序同一个等法)。
        // 常驻的活没有终点,也就永远不会发 task_finished —— 回执必须说清楚:程序不等它,接着往下走。
        boolean standing = record.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE;
        StringBuilder note = new StringBuilder();
        if (standing) {
            note.append("Accepted; it has no finish line, so it never ends on its own and never sends task_finished.");
        } else {
            note.append("Accepted as ").append(record.publicId()).append("; its end arrives as a task_finished "
                    + "event.");
        }
        String told = record.acceptNote();
        if (told != null) {
            note.append(' ').append(told);
        }
        if (facts != null) {
            note.append(' ').append(facts);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(TASK_ID, record.publicId());
        data.put("task", record.getToolName());
        data.put(ASYNC, true);
        data.put(STANDING, standing);
        if (replaced != null) {
            note.append(" It replaced ").append(replaced.publicId()).append(" (").append(replaced.describe())
                    .append("), which is now stopped.");
            data.put("replaced", replaced.publicId());
        }
        reply.accept(TaskResult.ok(note.toString(), data).toJson());
    }

    /** 受理回执 {@code data} 里的键:{@link #accepted} 按它们写,{@link #runningTaskOf} 按它们读。 */
    private static final String TASK_ID = "task_id";
    private static final String ASYNC = "async";
    private static final String STANDING = "standing";

    /**
     * 一个调用的结果是不是一件后台活的受理回执、而且那件活会自己收尾(不是常驻的):是就返回它的编号,否则 null。
     * 回执只在 {@link #accepted} 一处写成,这里按同一组键读回;内脑的派发器据此等这件活的 task_finished 再派下一个调用。
     * 不是 JSON 对象的结果(感知的字符图、接进来的外部工具的原文)不是回执。
     */
    public static String runningTaskOf(String resultJson) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(resultJson);
        } catch (RuntimeException notJson) {
            return null;
        }
        if (!parsed.isJsonObject() || !(parsed.getAsJsonObject().get("data") instanceof JsonObject data)) {
            return null;
        }
        boolean async = data.has(ASYNC) && data.get(ASYNC).getAsBoolean();
        boolean standing = data.has(STANDING) && data.get(STANDING).getAsBoolean();
        return async && !standing && data.has(TASK_ID) ? data.get(TASK_ID).getAsString() : null;
    }
}
