package com.dwinovo.numen.entity;

import com.dwinovo.numen.event.EventQueue;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主人离线期间攒下的输入——服务端这一侧的 {@link EventQueue},跟着存档落盘。
 *
 * <h2>为什么要有</h2>
 * 大脑跑在<b>主人的客户端</b>上,主人一下线,客户端那个队列就不存在了。而她的身体
 * 还在服务器里干活:任务跑完、被怪打、跨了维度。这些事得有地方躺着等他回来,
 * 否则"我帮你把矿挖完了"这件最值得说的事,恰好是最容易丢的那一件。
 *
 * <h2>跟客户端是同一个队列</h2>
 * 同一个 {@link EventQueue} 类、同一张类型表、同一个 {@value EventQueue#DEFAULT_CAP}
 * 上限、同样的"丢最老的并记账"。区别只在两处:落盘走存档而不是 JSONL(所以注入
 * {@link EventQueue.Journal#NONE},整份状态交给 {@link SavedData});以及它不问
 * {@code shouldDrain} —— 它的排空时机只有一个,主人回来了。
 *
 * <p>服务端专用。
 */
public final class EventOutbox extends SavedData {

    private static final Codec<EventQueue.Entry> ENTRY_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("type").forGetter(EventQueue.Entry::type),
            Codec.STRING.fieldOf("text").forGetter(EventQueue.Entry::text),
            Codec.LONG.optionalFieldOf("ts", 0L).forGetter(EventQueue.Entry::ts),
            Codec.BOOL.optionalFieldOf("urgent", false).forGetter(EventQueue.Entry::urgent)
    ).apply(i, EventQueue.Entry::new));

    // 包内可见:持久化是这个类的全部价值,得让单测够得着。
    static final Codec<EventOutbox> CODEC =
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, ENTRY_CODEC.listOf())
                    .xmap(EventOutbox::fromEntries, EventOutbox::toEntries)
                    .fieldOf("outboxes").codec();

    // 1.21.5 codec 化的 SavedDataType:存储层自己驱动(反)序列化,save()/load() 重写不复存在;
    // 解析失败由存储层兜底(readSavedData 记日志返 null,computeIfAbsent 落回构造器)。
    private static final SavedDataType<EventOutbox> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("numen", "event_outbox"),
            EventOutbox::new, CODEC,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private final Map<UUID, EventQueue> queues;

    EventOutbox() {
        this.queues = new HashMap<>();
    }

    private static EventOutbox fromEntries(Map<UUID, List<EventQueue.Entry>> raw) {
        EventOutbox out = new EventOutbox();
        raw.forEach((uuid, list) -> {
            EventQueue q = new EventQueue(EventQueue.Journal.NONE);
            long now = 0L;   // ts 原样带在条目里,push 的 now 只在 ts<=0 时才有意义
            for (EventQueue.Entry e : list) {
                q.push(e.type(), e.text(), e.ts() > 0 ? e.ts() : now, e.urgent());
            }
            out.queues.put(uuid, q);
        });
        return out;
    }

    private static Map<UUID, List<EventQueue.Entry>> toEntries(EventOutbox box) {
        Map<UUID, List<EventQueue.Entry>> raw = new HashMap<>();
        box.queues.forEach((uuid, q) -> {
            if (!q.isEmpty()) {
                raw.put(uuid, new ArrayList<>(q.entries()));
            }
        });
        return raw;
    }

    public static EventOutbox get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** 这只同伴的暂存队列(没有则建)。 */
    public EventQueue queue(UUID companionUuid) {
        return queues.computeIfAbsent(companionUuid, k -> new EventQueue(EventQueue.Journal.NONE));
    }

    /** 攒一条。 */
    public void put(UUID companionUuid, String type, String text, long now, boolean urgent) {
        queue(companionUuid).push(type, text, now, urgent);
        setDirty();
    }

    /**
     * 取走这只同伴攒的一切并清空(主人登录时补发)。
     *
     * <p>返回<b>原始条目</b>不是渲染后的字符串:类型和时间戳必须原样送到客户端,
     * 否则她会把"你不在时发生的事"当成刚发生的。
     */
    public List<EventQueue.Entry> take(UUID companionUuid, long now) {
        EventQueue q = queues.get(companionUuid);
        if (q == null) {
            return List.of();
        }
        List<EventQueue.Entry> out = q.takeEntries(now);
        queues.remove(companionUuid);
        setDirty();
        return out;
    }

    /** 只看不取(诊断与测试)。 */
    public EventQueue peek(UUID companionUuid) {
        return queues.getOrDefault(companionUuid, new EventQueue(EventQueue.Journal.NONE));
    }

    /** 同伴被遣散:她攒的东西跟着走,没人会再收。 */
    public void forget(UUID companionUuid) {
        if (queues.remove(companionUuid) != null) {
            setDirty();
        }
    }
}
