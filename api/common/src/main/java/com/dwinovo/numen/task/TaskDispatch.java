package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.prompt.NumenPrompts;
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
        CompanionTickDispatcher.syncSlotFor(companion.getUUID()).put(companion, record);
    }

    /**
     * 换掉她当前在做的事:受理即回执 task_id,身体后台执行,收尾经 task_finished 送达。
     *
     * <p>槽里原来那件活会被<b>替换</b>,不拒绝新的——主人改主意是常态,而"她在挖矿所以不理你"是最直观的一种出戏。
     * 新活的受理回执当场说顶掉了谁,被顶掉的那件照常以 stopped 收尾。同一轮里的几件活不会互相顶掉:内脑的派发器等
     * 前一件收尾才派下一件,这里不必猜哪几件是同一批的。
     *
     * <p>这是工具派活的写法:记录以工具名命名,重启后按这个名字找回那个工具、带 {@code args} 重放。
     */
    public static void setTask(NumenPlayer companion, TaskRecord record, JsonObject args,
                               Consumer<String> reply) {
        accept(companion, record, record.getToolName(), args, reply);
    }

    /**
     * 命令派活的写法,规矩同上。记录的名字是给模型看的"组 动作"({@link ServerSource#taskName()}),不是能重放的
     * 工具名,所以重放记的是源给的那次调用({@link ServerSource#replayTool()} 与 {@link ServerSource#replayArgs()}):
     * 默认就是这次调用本身——从 {@code command} 进来就重放那一行指令,从快捷工具进来就重放那次工具调用;处理函数把
     * 只在这一次开服里有效的写法换掉了的,重放换过的那一行({@link ServerSource#replayedWith})。
     */
    public static void setTask(ServerSource source, TaskRecord record) {
        accept(source.companion(), record, source.replayTool(), source.replayArgs(), source::reply);
    }

    /**
     * 派身体任务的每个入口都经过这里,所以"顶掉了谁"只在这一处说:槽里原来的那件在派新活之前取出来,写进新活的受理回执。
     *
     * @param replayTool 重启后重放用的工具名,与 {@code args} 一起就是那次调用
     */
    private static void accept(NumenPlayer companion, TaskRecord record, String replayTool, JsonObject args,
                               Consumer<String> reply) {
        // 已经走到终态、只等这一刻结算的那件(刚被 task_stop 叫停)不是这次顶掉的
        TaskRecord current = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        TaskRecord replaced = current != null && !current.getState().isTerminal() ? current : null;
        record.markAsync();
        CompanionTickDispatcher.assign(companion, record);
        // 记下"她现在在做什么",服务器重启后照着重放一遍(见 TaskPersistence)。
        TaskPersistence.remember(companion, record.getToolName(), replayTool, args);
        // 内置大脑靠 task_finished 事件收尾;外部(MCP)夺舍收不到事件
        // (那条投给内置大脑,不是它),得自己用 task status 轮询到身体空闲,再感知确认。
        // 内置大脑这份回执写事实和接下来能做的事,说法与理由见 NumenPrompts.WHILE_IT_RUNS。
        // 常驻的活没有终点,也就永远不会发 task_finished —— 回执必须说清楚,
        // 否则她会照着"等事件"的指引干等下去。
        boolean standing = record.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE;
        StringBuilder note = new StringBuilder();
        if (standing) {
            note.append("Accepted; it has no finish line, so it never ends on its own and never sends task_finished.");
        } else if (record.isExternalCall()) {
            note.append("Accepted; running in the background. Run the command task status until the body is idle, "
                    + "then perceive to confirm the result; task_stop cancels it.");
        } else {
            note.append("Accepted as ").append(record.publicId()).append("; your body is working on it in the "
                    + "background. ").append(NumenPrompts.WHILE_IT_RUNS);
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

    /** 受理回执 {@code data} 里的键:{@link #accept} 按它们写,{@link #runningTaskOf} 按它们读。 */
    private static final String TASK_ID = "task_id";
    private static final String ASYNC = "async";
    private static final String STANDING = "standing";

    /**
     * 一个调用的结果是不是一件后台活的受理回执、而且那件活会自己收尾(不是常驻的):是就返回它的编号,否则 null。
     * 回执只在 {@link #accept} 一处写成,这里按同一组键读回;内脑的派发器据此等这件活的 task_finished 再派下一个调用。
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
