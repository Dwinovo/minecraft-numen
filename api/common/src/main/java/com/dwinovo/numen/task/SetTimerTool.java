package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登记工具(当场返回):定一个到点提醒自己的表。
 *
 * <p>走 {@link TaskDispatch} 的第一条道——不占身体,定完就回,身体照旧干它的活。
 * 表本身住在 {@link TimerRegistry}。
 *
 * <p>定表的那一刻会给主人报一句:表是她自己安排的日程,主人有权知道她十分钟后
 * 打算干什么,而不是只看见她突然放下矿镐走了。
 */
public final class SetTimerTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_REASON_LENGTH = 200;

    private record Args(int after_s, String reason) {}

    @Override
    public String name() {
        return "set_timer";
    }

    @Override
    public String description() {
        return "Set a one-shot reminder that fires after a delay in world time. Returns immediately "
                + "and never occupies the body — she keeps doing whatever she is doing. Use it for "
                + "things the world will not announce on its own: a furnace finishing, crops growing, "
                + "waiting for daybreak. Do NOT use it to watch work you dispatched yourself — a "
                + "background task sends its own task_finished event when it ends. The timer only "
                + "reminds you; it is not proof that the thing you waited for happened, so inspect "
                + "the world when it fires. Max " + TimerRegistry.MAX_SECONDS + "s, at most "
                + TimerRegistry.MAX_PER_COMPANION + " pending. World time stops while a "
                + "single-player world is paused. task_status lists your timers; task_stop cancels one.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("after_s", "Delay in world-time seconds ("
                        + TimerRegistry.MIN_SECONDS + "-" + TimerRegistry.MAX_SECONDS
                        + "; out-of-range values are clamped).",
                        TimerRegistry.MIN_SECONDS, TimerRegistry.MAX_SECONDS)
                .string("reason", "What to look at or decide when it fires. The owner sees this too, "
                        + "so name the thing: \"collect the iron from the furnace\" beats \"check back\".")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args,
                             NumenPlayer companion, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        String reason = parsed == null || parsed.reason() == null ? "" : parsed.reason().strip();
        if (reason.isEmpty()) {
            reply.accept(TaskResult.fail("reason 不能为空:醒来时你要靠它认出自己为什么定这个表。").toJson());
            return;
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("服务器不可用,表没定上。").toJson());
            return;
        }

        int asked = parsed.after_s();
        int seconds = TimerRegistry.clampSeconds(asked);
        TimerRegistry registry = TimerRegistry.get(server);
        long now = server.overworld().getGameTime();

        TimerRegistry.Timer timer = registry.set(companion.getUUID(), now, seconds, reason);
        if (timer == null) {
            reply.accept(TaskResult.fail(
                    "已经挂了 " + TimerRegistry.MAX_PER_COMPANION + " 个表,先撤一个再定。当前挂着:"
                            + summarize(registry.list(companion.getUUID()), now),
                    Map.of("timers", describe(registry.list(companion.getUUID()), now))).toJson());
            return;
        }

        announceToOwner(companion, seconds, reason);

        String clamped = asked == seconds ? ""
                : "(你要 " + asked + "s,允许区间 " + TimerRegistry.MIN_SECONDS + "-"
                        + TimerRegistry.MAX_SECONDS + "s,已按 " + seconds + "s 定)";
        reply.accept(TaskResult.ok(
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
    static String summarize(List<TimerRegistry.Timer> timers, long nowGameTime) {
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
    static List<Map<String, Object>> describe(List<TimerRegistry.Timer> timers, long nowGameTime) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TimerRegistry.Timer t : timers) {
            out.add(Map.of("timer_id", t.id(),
                    "remaining_s", TimerRegistry.remainingSeconds(t, nowGameTime),
                    "reason", t.reason()));
        }
        return out;
    }
}
