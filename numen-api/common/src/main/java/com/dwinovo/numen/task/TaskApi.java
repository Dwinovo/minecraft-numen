package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code numen.task}:她派出去的东西——身体上那件活({@link TaskDispatch#setTask})和挂着的表({@link TimerRegistry})。
 *
 * <p>三个函数都当场返回、不占身体:{@code status} 查、{@code stop} 撤、{@code timer} 定。两条道共用这一组:"我有什么在跑""停掉它"
 * 各只有一个问法,模型不必记哪一种去哪问。
 */
public final class TaskApi {

    private static final int MAX_REASON_LENGTH = 200;
    /** 不写 {@code after} 时一分钟后提醒:够一炉东西烧上几件、庄稼长一截,又不至于把要看的事搁太久。 */
    private static final int DEFAULT_AFTER_S = 60;

    private TaskApi() {}

    /** 一个挂着的表。 */
    public record Timer(String timerId,
                        @Doc("Seconds of world time until it fires.") int remainingS,
                        String reason) {}

    /** 她有什么在跑。 */
    public record Status(@Doc("The background task, when there is one.") Optional<String> taskId,
                         @Doc("Its function, numen.move.go.") Optional<String> task,
                         Optional<State> state,
                         Optional<Long> elapsedS,
                         Optional<Long> budgetLeftS,
                         @Doc("Your pending timers.") List<Timer> timers) {}

    /** 那件活在跑还是排着。 */
    public enum State { RUNNING, QUEUED }

    @Fn("What you have in flight: the background task and your pending timers.")
    @Example("numen.task.status()")
    @Note("Instant and read-only; it does not touch your body.")
    @Note("Usually not needed: a program waits for each task it starts, a task left running when a program stopped "
            + "ends with a task_finished event, and a timer fires on its own.")
    @SeeAlso("numen.task.stop")
    public static Status status(ServerCall call) {
        NumenPlayer companion = call.her();
        long now = companion.level().getGameTime();
        TaskRecord rec = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        List<Timer> timers = timers(companion, now);
        if (rec == null) {
            return new Status(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), timers);
        }
        long elapsedS = rec.getStartedGameTime() >= 0 ? (now - rec.getStartedGameTime()) / 20 : 0;
        long budgetLeftS = Math.max(0, rec.getDeadlineGameTime() - now) / 20;
        return new Status(Optional.of(rec.publicId()), Optional.of(rec.getToolName()),
                Optional.of(rec.getState() == TaskState.RUNNING ? State.RUNNING : State.QUEUED),
                Optional.of(elapsedS), Optional.of(budgetLeftS), timers);
    }

    /** 撤什么。 */
    public record Stop(@Doc("What to cancel: a task id (e.g. t42) or a timer id (e.g. tm3).")
                       @Omitted("stop the background task, whatever it is") Optional<String> taskId) {}

    /** 撤掉了什么。 */
    public record Stopped(@Doc("The task it stopped.") Optional<String> taskId,
                          @Doc("The timer it cancelled.") Optional<String> timerId) {}

    @Fn("Cancel the background task, or a task or timer by its id.")
    @Example("numen.task.stop()")
    @Example("numen.task.stop({task_id = \"tm3\"})")
    @Note("Instant; does not ask your owner. With no id it stops the background task (the one <current_task> "
            + "shows) so the body frees up; a stopped task winds down and reports as a task_finished event with "
            + "status=stopped.")
    @Note("When nothing matches it fails and lists what is pending.")
    @SeeAlso("numen.task.status")
    public static Stopped stop(ServerCall call, Stop args) {
        NumenPlayer companion = call.her();
        String wanted = args.taskId().map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
        MinecraftServer server = companion.level().getServer();
        long now = companion.level().getGameTime();
        TaskRecord active = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
        // 指名道姓的表:先在表里找,找到就撤
        if (wanted != null && server != null && TimerRegistry.get(server).cancel(companion.getUUID(), wanted)) {
            return new Stopped(Optional.empty(), Optional.of(wanted));
        }
        if (active == null || (wanted != null && !wanted.equals(active.publicId()))) {
            // 没撤成的时候把现状摊开:身体在干嘛、挂着哪些表
            String body = active == null ? "your body is idle"
                    : "your body runs " + active.publicId() + " (" + active.describe() + ")";
            throw new ApiError(ErrorKind.NOT_FOUND, (wanted == null ? "there is no background task to stop"
                    : "there is no " + wanted) + "; now: " + body + "; timers: "
                    + summarize(timers(companion, now)), Call.of("numen.task.status"));
        }
        CompanionTickDispatcher.stopActive(companion, TaskRecord.StopCause.TASK_STOP);
        return new Stopped(Optional.of(active.publicId()), Optional.empty());
    }

    /** 定一个表。 */
    public record SetTimer(@Doc("What to look at or decide when it fires. The owner sees this too, so name the "
            + "thing: \"collect the iron from the furnace\" beats \"check back\".") String reason,
                           @Doc("Delay in world-time seconds (" + TimerRegistry.MIN_SECONDS + "-"
                                   + TimerRegistry.MAX_SECONDS + "; out-of-range values are clamped).")
                           @Omitted("remind you in " + DEFAULT_AFTER_S + " seconds") Optional<Integer> after) {}

    /**
     * 定:不占身体,定完就回,身体照旧干它的活。越界的秒数夹进合法区间,值里是实际定的。定表的那一刻给主人报一句:表是她自己安排的
     * 日程,主人有权知道她十分钟后打算干什么。
     */
    @Fn("Set a one-shot reminder that fires after a delay in world time.")
    @Example("numen.task.timer(\"collect the iron from the furnace\", {after = 300})")
    @Note("Returns at once and never occupies your body; your owner is told when and why.")
    @Note("For what the world will not announce on its own: a furnace finishing, crops growing, daybreak. When it "
            + "fires, look: the reminder is not proof the thing happened.")
    @Note("It only reminds you. Work you dispatched reports its own end (to the program waiting for it, or as a "
            + "task_finished event); don't set a timer to watch it.")
    @Note("At most " + TimerRegistry.MAX_PER_COMPANION + " pending. World time stops while a single-player world "
            + "is paused.")
    @SeeAlso({"numen.task.status", "numen.task.stop"})
    public static Timer timer(ServerCall call, SetTimer args) {
        NumenPlayer companion = call.her();
        String reason = args.reason().strip();
        if (reason.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "reason is empty: when it fires you tell from it why you set "
                    + "it", null);
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            reason = reason.substring(0, MAX_REASON_LENGTH);
        }
        MinecraftServer server = companion.level().getServer();
        int seconds = TimerRegistry.clampSeconds(args.after().orElse(DEFAULT_AFTER_S));
        TimerRegistry registry = TimerRegistry.get(server);
        long now = server.overworld().getGameTime();
        TimerRegistry.Timer timer = registry.set(companion.getUUID(), now, seconds, reason);
        if (timer == null) {
            List<Timer> pending = timers(companion, now);
            throw new ApiError(ErrorKind.FAILED, TimerRegistry.MAX_PER_COMPANION + " timers are pending already; "
                    + "stop one first: " + summarize(pending),
                    Call.of("numen.task.stop", Map.of("task_id", pending.getFirst().timerId())),
                    Map.of("timers", pending));
        }
        announceToOwner(companion, seconds, reason);
        return new Timer(timer.id(), seconds, reason);
    }

    /** 她的日程也是主人的信息:表定在什么时候、为什么定,当场说一句;发的是语言键,主人按他自己的语言看。 */
    private static void announceToOwner(NumenPlayer companion, int seconds, String reason) {
        ServerPlayer owner = companion.resolveOwnerPlayer();
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable(ModLanguageData.Keys.NOTICE_TIMER,
                    companion.getName(), seconds, reason));
        }
    }

    private static List<Timer> timers(NumenPlayer companion, long now) {
        MinecraftServer server = companion.level().getServer();
        if (server == null) {
            return List.of();
        }
        return TimerRegistry.get(server).list(companion.getUUID()).stream()
                .map(t -> new Timer(t.id(), (int) TimerRegistry.remainingSeconds(t, now), t.reason())).toList();
    }

    /** 报错里的一行摘要。 */
    private static String summarize(List<Timer> timers) {
        if (timers.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Timer t : timers) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(t.timerId()).append(" in ").append(t.remainingS()).append(" s: ").append(t.reason());
        }
        return sb.toString();
    }
}
