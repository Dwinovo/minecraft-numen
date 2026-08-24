package com.dwinovo.numen.task;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 她给自己定的表——到点发一条事件,仅此而已。
 *
 * <h2>它属于哪条道</h2>
 * {@link TaskDispatch} 的第一条:<b>不占身体</b>。定表是当场干完的登记动作,不进任务槽、
 * 不参与替换、不上头顶气泡——身体该干嘛干嘛。所以「她在挖矿」和「她十分钟后要看炉子」
 * 可以同时成立。
 *
 * <h2>为什么用游戏刻</h2>
 * 到期时刻记的是主世界的 {@code gameTime}:世界不跑,表就不走。单机退出存档一整夜,
 * 回来时炉子里的矿也没烧,表跟着一起停才是对的。
 *
 * <p>用 {@code getGameTime()} 而不是 {@code getDayTime()}——后者能被 {@code /time set}
 * 改,一条指令就能让全部的表提前炸或者永不到期。
 *
 * <h2>存的是绝对到期刻</h2>
 * 不能存「还剩多少秒」:那是相对量,重启一次就从头开始算。落盘走存档
 * ({@link SavedData}),跟 {@code EventOutbox} / {@link CompanionRegistry} 同一制式。
 *
 * <p>也<b>不能</b>走 {@link TaskPersistence}——它靠重放那次工具调用来恢复,
 * 而重放 {@code set_timer(after_s=60)} 等于把表按回 60 秒重新计时。
 *
 * <h2>到点之后</h2>
 * 一句 {@link NumenEvents#emit} 就够:主人在线立刻送达并开一轮,主人离线进出箱等他回来,
 * 她死着则队列锁住、复活解锁时一起走。这三件事各自已有归属,这里不重复实现。
 *
 * <p>服务端专用。
 */
public final class TimerRegistry extends SavedData {

    /** 每只同伴最多挂几个表。表会自己响,攒着的意义有限,留个数防跑飞。 */
    public static final int MAX_PER_COMPANION = 8;
    /** 最短 1 秒。 */
    public static final int MIN_SECONDS = 1;
    /** 最长一个 MC 日(现实 20 分钟)。熔炼、酿造、等天亮都在这个尺度内。 */
    public static final int MAX_SECONDS = 1200;

    private static final String DATA_NAME = "numen_timers";
    /** 一秒扫一次。表的精度是秒级,没必要每刻遍历。 */
    private static final long SWEEP_INTERVAL_TICKS = 20L;

    /** 一个表。{@code id} 跟着存档走,重启后模型手上那个 id 还认得。 */
    public record Timer(String id, UUID companion, long dueGameTime, String reason) {}

    private static final Codec<Timer> TIMER_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(Timer::id),
            UUIDUtil.STRING_CODEC.fieldOf("companion").forGetter(Timer::companion),
            Codec.LONG.fieldOf("due").forGetter(Timer::dueGameTime),
            Codec.STRING.fieldOf("reason").forGetter(Timer::reason)
    ).apply(i, Timer::new));

    // 包内可见:持久化是这个类的全部价值,得让单测够得着。
    static final Codec<TimerRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
            TIMER_CODEC.listOf().optionalFieldOf("timers", List.of())
                    .forGetter(r -> new ArrayList<>(r.timers.values())),
            Codec.LONG.optionalFieldOf("nextId", 1L).forGetter(r -> r.nextId)
    ).apply(i, TimerRegistry::new));

    // 1.21.5 codec 化的 SavedDataType:存储层自己驱动(反)序列化,save()/load() 重写不复存在;
    // 解析失败由存储层兜底(readSavedData 记日志返 null,computeIfAbsent 落回构造器)。
    private static final SavedDataType<TimerRegistry> TYPE = new SavedDataType<>(
            DATA_NAME, TimerRegistry::new, CODEC,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private long nextId;
    private long nextSweepGameTime = Long.MIN_VALUE;

    TimerRegistry() {
        this.nextId = 1L;
    }

    private TimerRegistry(List<Timer> loaded, long nextId) {
        loaded.forEach(t -> timers.put(t.id(), t));
        this.nextId = Math.max(1L, nextId);
    }

    public static TimerRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // ---- 定 / 查 / 撤 ----

    /** 把请求的秒数夹进合法区间。工具据此告诉模型「你要 X,按 Y 定的」。 */
    public static int clampSeconds(int requested) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, requested));
    }

    /**
     * 定一个表。{@code seconds} 应当已经过 {@link #clampSeconds}。
     *
     * @return 定好的表;已经挂满 {@value #MAX_PER_COMPANION} 个则返回 null
     */
    public Timer set(UUID companion, long nowGameTime, int seconds, String reason) {
        if (list(companion).size() >= MAX_PER_COMPANION) {
            return null;
        }
        Timer t = new Timer("tm" + nextId++, companion, nowGameTime + seconds * 20L, reason);
        timers.put(t.id(), t);
        setDirty();
        return t;
    }

    /** 这只同伴挂着的表,按到期先后排。 */
    public List<Timer> list(UUID companion) {
        return timers.values().stream()
                .filter(t -> t.companion().equals(companion))
                .sorted(Comparator.comparingLong(Timer::dueGameTime))
                .toList();
    }

    /** 撤一个表。只认自己的表——别的同伴的 id 撤不动。 */
    public boolean cancel(UUID companion, String id) {
        Timer t = timers.get(id);
        if (t == null || !t.companion().equals(companion)) {
            return false;
        }
        timers.remove(id);
        setDirty();
        return true;
    }

    /** 还剩几秒(向上取整);已经到期为 0。 */
    public static long remainingSeconds(Timer timer, long nowGameTime) {
        return (Math.max(0L, timer.dueGameTime() - nowGameTime) + 19L) / 20L;
    }

    // ---- 到点 ----

    /** 排程机器每刻调一次;内部按 {@value #SWEEP_INTERVAL_TICKS} 刻降频。 */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        TimerRegistry registry = get(server);
        if (registry.nextSweepGameTime != Long.MIN_VALUE && now < registry.nextSweepGameTime) {
            return;
        }
        registry.nextSweepGameTime = now + SWEEP_INTERVAL_TICKS;
        registry.fireDue(server, now);
    }

    /** 到期的表,按到期先后排(包内可见:单测直接验它,不必起服务器)。 */
    List<Timer> dueAt(long nowGameTime) {
        return timers.values().stream()
                .filter(t -> t.dueGameTime() <= nowGameTime)
                .sorted(Comparator.comparingLong(Timer::dueGameTime))
                .toList();
    }

    private void fireDue(MinecraftServer server, long nowGameTime) {
        if (timers.isEmpty()) {
            return;
        }
        List<String> spent = new ArrayList<>();
        for (Timer t : dueAt(nowGameTime)) {
            NumenPlayer body = NumenPlayer.findByUuid(server, t.companion());
            if (body == null) {
                // 身体这会儿不在世界里。她要是已经被遣散,表跟着消失;只是休眠/没加载,
                // 就留着等她回来——表的到期时刻是绝对的,晚响不会响错。
                if (CompanionRegistry.get(server).find(t.companion()) == null) {
                    spent.add(t.id());
                }
                continue;
            }
            NumenEvents.emit(body, NumenEvents.Kind.TIMER, Map.of("id", t.id()),
                    "你定的表到点了:" + t.reason()
                            + "。表只负责提醒,不代表那件事已经完成——先看清现在的状况再决定下一步。",
                    true);
            spent.add(t.id());
        }
        if (!spent.isEmpty()) {
            spent.forEach(timers::remove);
            setDirty();
            Constants.LOG.info("[numen-task] {} 个表到点", spent.size());
        }
    }

}
