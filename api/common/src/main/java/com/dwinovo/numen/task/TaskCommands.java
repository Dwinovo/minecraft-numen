package com.dwinovo.numen.task;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code task}:她派出去的东西——身体上那件活({@link TaskDispatch#setTask})和挂着的表({@link TimerRegistry})。
 *
 * <p>三个动作都当场返回、不占身体:{@code status} 查、{@code stop} 撤、{@code timer} 定。两条道共用这一组:
 * "我有什么在跑""停掉它"各只有一个问法,模型不必记哪一种去哪问。只有 {@code stop} 提升成快捷工具
 * {@code task_stop}:叫停常用。{@code status} 多被拿来轮询,而活干完会以 task_finished 事件叫醒她,不该轮询;
 * {@code timer} 很少用。这两个只作命令。
 */
public final class TaskCommands {

    private static final int MAX_REASON_LENGTH = 200;

    private static final Param<String> TASK_ID = Param.optional("task_id", ArgType.word(),
            "What to cancel: a task id (e.g. t42) or a timer id (e.g. tm3).")
            .whenOmitted("stop the background task, whatever it is");
    private static final Param<Integer> AFTER_S = Param.required("after_s",
            ArgType.integer(TimerRegistry.MIN_SECONDS, TimerRegistry.MAX_SECONDS),
            "Delay in world-time seconds ("
                    + TimerRegistry.MIN_SECONDS + "-" + TimerRegistry.MAX_SECONDS
                    + "; out-of-range values are clamped).");
    private static final Param<String> REASON = Param.required("reason", ArgType.text(),
            "What to look at or decide when it fires. The owner sees this too, "
                    + "so name the thing: \"collect the iron from the furnace\" beats \"check back\".");

    private TaskCommands() {}

    /** 经插件那扇门登记这一组;快捷工具 task_stop 随之进工具表。 */
    public static void install(NumenApi numen) {
        numen.registerCommands("task", "The background task and your pending timers.",
                TaskCommands::actions);
    }

    private static void actions(CommandGroup task) {
        task.server("status", "What you have in flight: the background task and your pending timers.",
                TaskCommands::status)
                .example("task status")
                .note("Instant and read-only; it does not touch your body.")
                .note("Usually not needed: a task ends with its own task_finished event and a timer fires on its own.")
                .seeAlso("task stop");
        task.server("stop", "Cancel the background task, or a task or timer by its id.",
                TaskCommands::stop, TASK_ID)
                .example("task stop")
                .example("task stop --task_id tm3")
                .note("Instant; does not ask your owner. A stopped task winds down and reports as a task_finished "
                        + "event with status=stopped.")
                .note("When nothing matches it fails and lists what is pending.")
                .seeAlso("task status")
                .promote("task_stop", "Cancel something you dispatched. With no id: aborts the background "
                        + "task (the one <current_task> shows) so the body frees up; its "
                        + "wind-down arrives as a task_finished event with status=stopped. With an id: cancels "
                        + "that task or that timer (tm...). Fails, listing what is actually pending, when "
                        + "nothing matches.");
        task.server("timer", "Set a one-shot reminder that fires after a delay in world time.",
                TaskCommands::timer, AFTER_S, REASON)
                .example("task timer 300 collect the iron from the furnace")
                .note("Returns at once and never occupies your body; your owner is told when and why.")
                .note("For what the world will not announce on its own: a furnace finishing, crops growing, "
                        + "daybreak. When it fires, look: the reminder is not proof the thing happened.")
                .note("It only reminds you. Work you dispatched sends its own task_finished; don't set a timer "
                        + "to watch it.")
                .note("At most " + TimerRegistry.MAX_PER_COMPANION + " pending. World time stops while a "
                        + "single-player world is paused.")
                .seeAlso("task status", "task stop");
    }

    /** 查:身体在做的那件活与挂着的表,各报一段。 */
    private static void status(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        long now = companion.level().getGameTime();
        TaskRecord rec = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        MinecraftServer server = companion.level().getServer();
        List<TimerRegistry.Timer> timers = server == null
                ? List.of()
                : TimerRegistry.get(server).list(companion.getUUID());

        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder msg = new StringBuilder();

        if (rec == null) {
            msg.append("身体空闲,没有后台任务。");
        } else {
            long elapsedS = rec.getStartedGameTime() >= 0 ? (now - rec.getStartedGameTime()) / 20 : 0;
            long budgetLeftS = Math.max(0, rec.getDeadlineGameTime() - now) / 20;
            String state = rec.getState() == TaskState.RUNNING ? "running" : "queued";
            msg.append(rec.publicId()).append('(').append(rec.describe()).append(") ").append(state)
                    .append(",已进行 ").append(elapsedS).append("s,时间预算剩 ")
                    .append(budgetLeftS).append("s。");
            data.put("task_id", rec.publicId());
            data.put("task", rec.getToolName());
            data.put("state", state);
            data.put("elapsed_s", elapsedS);
            data.put("budget_left_s", budgetLeftS);
        }

        if (timers.isEmpty()) {
            msg.append("没有挂着的表。");
        } else {
            msg.append("挂着 ").append(timers.size()).append(" 个表:")
                    .append(summarize(timers, now)).append('。');
            data.put("timers", describe(timers, now));
        }

        src.reply(TaskResult.ok(msg.toString(), data).toJson());
    }

    /**
     * 撤:不带 id 停身体上那件活;带 id 则身体槽和挂着的表都查。找不到 id 时回执<b>列出现在有什么</b>——
     * 失败的回执得让模型看得出下一步该怎么改,而不是只说一句没找到。
     */
    private static void stop(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        String asked = args.get(TASK_ID);
        String wanted = asked == null || asked.isBlank() ? null : asked.strip();

        MinecraftServer server = companion.level().getServer();
        long now = companion.level().getGameTime();
        TaskRecord active = CompanionTickDispatcher.currentTaskFor(companion.getUUID());

        // 指名道姓的表:先在表里找,找到就撤。
        if (wanted != null && server != null) {
            TimerRegistry registry = TimerRegistry.get(server);
            if (registry.cancel(companion.getUUID(), wanted)) {
                src.reply(TaskResult.ok("已撤掉表 " + wanted + "。", Map.of("timer_id", wanted)).toJson());
                return;
            }
        }

        if (active == null || (wanted != null && !wanted.equals(active.publicId()))) {
            src.reply(TaskResult.fail(nothingMatched(wanted, active, server, companion, now)).toJson());
            return;
        }

        CompanionTickDispatcher.stopActive(companion, TaskRecord.StopCause.TASK_STOP);
        src.reply(TaskResult.ok("已叫停 " + active.publicId() + "(" + active.describe()
                + ")。收尾结果会以 task_finished(status=stopped) 事件送达。",
                Map.of("task_id", active.publicId())).toJson());
    }

    /** 没撤成的时候把现状摊开:身体在干嘛、挂着哪些表。 */
    private static String nothingMatched(String wanted, TaskRecord active, MinecraftServer server,
                                         NumenPlayer companion, long now) {
        List<TimerRegistry.Timer> timers = server == null
                ? List.of()
                : TimerRegistry.get(server).list(companion.getUUID());
        String body = active == null
                ? "身体空闲"
                : "身体在跑 " + active.publicId() + "(" + active.describe() + ")";
        String pending = body + ";表:" + summarize(timers, now);
        return wanted == null
                ? "没有进行中的后台任务,不需要叫停。当前:" + pending + "。"
                : "没有 " + wanted + " 这个 id。当前:" + pending + "。";
    }

    /**
     * 定:走 {@link TaskDispatch} 的第一条道——不占身体,定完就回,身体照旧干它的活。越界的秒数夹进合法区间,
     * 回执里说清你要的和实际定的。
     *
     * <p>定表的那一刻会给主人报一句:表是她自己安排的日程,主人有权知道她十分钟后打算干什么,而不是只看见她
     * 突然放下矿镐走了。
     */
    private static void timer(ServerSource src, CommandArgs args) {
        NumenPlayer companion = src.companion();
        String reason = args.get(REASON).strip();
        if (reason.isEmpty()) {
            src.reply(TaskResult.fail("reason 不能为空:醒来时你要靠它认出自己为什么定这个表。").toJson());
            return;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            src.reply(TaskResult.fail("服务器不可用,表没定上。").toJson());
            return;
        }

        int asked = args.get(AFTER_S);
        int seconds = TimerRegistry.clampSeconds(asked);
        TimerRegistry registry = TimerRegistry.get(server);
        long now = server.overworld().getGameTime();

        TimerRegistry.Timer timer = registry.set(companion.getUUID(), now, seconds, reason);
        if (timer == null) {
            src.reply(TaskResult.fail(
                    "已经挂了 " + TimerRegistry.MAX_PER_COMPANION + " 个表,先撤一个再定。当前挂着:"
                            + summarize(registry.list(companion.getUUID()), now),
                    Map.of("timers", describe(registry.list(companion.getUUID()), now))).toJson());
            return;
        }

        announceToOwner(companion, seconds, reason);

        String clamped = asked == seconds ? ""
                : "(你要 " + asked + "s,允许区间 " + TimerRegistry.MIN_SECONDS + "-"
                        + TimerRegistry.MAX_SECONDS + "s,已按 " + seconds + "s 定)";
        src.reply(TaskResult.ok(
                "已定表 " + timer.id() + "," + seconds + " 秒后提醒你:" + reason + "。" + clamped
                        + "身体没被占用,接着干别的就行;到点会自动收到 timer 事件,不要轮询。",
                Map.of("timer_id", timer.id(),
                        "after_s", seconds,
                        "reason", reason)).toJson());
    }

    /** 她的日程也是主人的信息:表定在什么时候、为什么定,当场说一句。 */
    private static void announceToOwner(NumenPlayer companion, int seconds, String reason) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner == null) {
            return;
        }
        owner.sendSystemMessage(Component.literal(
                "⏱ " + companion.getName().getString() + ":" + seconds + " 秒后 —— " + reason));
    }

    /** 给模型看的一行摘要。 */
    private static String summarize(List<TimerRegistry.Timer> timers, long nowGameTime) {
        if (timers.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (TimerRegistry.Timer t : timers) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(t.id()).append(' ')
                    .append(TimerRegistry.remainingSeconds(t, nowGameTime)).append("s 后:")
                    .append(t.reason());
        }
        return sb.toString();
    }

    /** 给模型看的结构化版本。 */
    private static List<Map<String, Object>> describe(List<TimerRegistry.Timer> timers, long nowGameTime) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TimerRegistry.Timer t : timers) {
            out.add(Map.of("timer_id", t.id(),
                    "remaining_s", TimerRegistry.remainingSeconds(t, nowGameTime),
                    "reason", t.reason()));
        }
        return out;
    }
}
