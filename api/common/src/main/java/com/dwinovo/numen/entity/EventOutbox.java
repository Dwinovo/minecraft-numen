package com.dwinovo.numen.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 主人离线期间攒下的世界事件——跟着存档落盘,他登录时一次性补发。
 *
 * <h2>为什么要有</h2>
 * 大脑跑在<b>主人的客户端</b>上,主人一下线,收件箱就不存在了。而她的身体还在
 * 服务器里继续干活:任务跑完、被怪打、跨了维度。从前这些一律直接丢——
 * {@code drainResults} 里那句 {@code if (owner == null) return} 是先把完成记录
 * 取出队列再扔掉,于是"我帮你把矿挖完了"这件最值得说的事,恰好最容易丢。
 *
 * <h2>上限</h2>
 * 每只同伴 {@value #MAX_PER_COMPANION} 条,满了丢最老的并记账。补发时会补一句
 * "期间还发生了 N 件事,没记下来"——<b>丢弃可以,无声消失不行</b>:主人离线一周
 * 回来看到三条事件,得知道那是全部还是残片。
 *
 * <p>落盘而不是只放内存:多人服务器重启是常事,纯内存的话最有价值的长时段叙事
 * 恰好最容易丢。
 *
 * <p>服务端专用。
 */
public final class EventOutbox extends SavedData {

    /** 每只同伴最多攒多少条。 */
    public static final int MAX_PER_COMPANION = 50;

    /** 一条攒着的事件。{@code urgent} 留着,因为补发时它仍然该立刻开一轮。 */
    public record Pending(String xml, boolean urgent) {
        static final Codec<Pending> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("xml").forGetter(Pending::xml),
                Codec.BOOL.optionalFieldOf("urgent", false).forGetter(Pending::urgent)
        ).apply(i, Pending::new));
    }

    /** 一只同伴的出箱:攒下的条目 + 因为满了被丢掉的条数。 */
    public record Box(List<Pending> pending, int dropped) {
        static final Box EMPTY = new Box(List.of(), 0);

        static final Codec<Box> CODEC = RecordCodecBuilder.create(i -> i.group(
                Pending.CODEC.listOf().fieldOf("pending").forGetter(Box::pending),
                Codec.INT.optionalFieldOf("dropped", 0).forGetter(Box::dropped)
        ).apply(i, Box::new));
    }

    private static final Codec<EventOutbox> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Box.CODEC)
            .xmap(EventOutbox::new, d -> d.boxes)
            .fieldOf("outboxes").codec();

    private static final SavedData.Factory<EventOutbox> FACTORY = new SavedData.Factory<>(
            EventOutbox::new, EventOutbox::load,
            net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    // 包内可见:持久化是这个类的全部价值,得让单测够得着。
    static EventOutbox load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(EventOutbox::new);
    }

    private final Map<UUID, Box> boxes;

    EventOutbox() {
        this.boxes = new HashMap<>();
    }

    private EventOutbox(Map<UUID, Box> boxes) {
        this.boxes = new HashMap<>(boxes);
    }

    public static EventOutbox get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "numen_event_outbox");
    }

    /** 攒一条。满了丢最老的,并把丢弃计入账。 */
    public void put(UUID companionUuid, String xml, boolean urgent) {
        Box box = boxes.getOrDefault(companionUuid, Box.EMPTY);
        List<Pending> next = new ArrayList<>(box.pending());
        int dropped = box.dropped();
        next.add(new Pending(xml, urgent));
        while (next.size() > MAX_PER_COMPANION) {
            next.remove(0);
            dropped++;
        }
        boxes.put(companionUuid, new Box(next, dropped));
        setDirty();
    }

    /** 取走这只同伴攒的一切并清空(主人登录时补发)。 */
    public Box take(UUID companionUuid) {
        Box box = boxes.remove(companionUuid);
        if (box == null) {
            return Box.EMPTY;
        }
        setDirty();
        return box;
    }

    /** 只看不取(主要给测试与诊断)。 */
    public Box peek(UUID companionUuid) {
        return boxes.getOrDefault(companionUuid, Box.EMPTY);
    }

    /** 同伴被遣散:她攒的事件跟着走,没人会再收。 */
    public void forget(UUID companionUuid) {
        if (boxes.remove(companionUuid) != null) {
            setDirty();
        }
    }
}
