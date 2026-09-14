package com.dwinovo.numen.permission;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每维度一份:玩家放过方块的格子。{@code placed} 信号的来源,也是"这东西是谁的"这个概念在
 * 全仓唯一的落点。
 *
 * <p>记:{@code BlockItem.place} 返回处的 mixin,放的人是真玩家就记,是同伴就把这一格的旧记号
 * 抹掉(她垫的路、她盖的墙是她的,不是别人留下的);建造任务收工另把成果格登记回来。
 * 查:格子已是空气视为无记号并顺手清掉——不另挂方块变化钩子,谁挖的都一样。
 *
 * <p>线程:按区块存位集合,每个集合发布后不再改,改就整个换一份(写时复制,经
 * {@link ConcurrentHashMap#compute}),寻路工作线程无锁读。
 */
public final class PlacedBlocks extends SavedData {

    private static final Codec<PlacedBlocks> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.listOf().fieldOf("cells").forGetter(PlacedBlocks::cells)
    ).apply(i, PlacedBlocks::new));

    private static final SavedData.Factory<PlacedBlocks> FACTORY = new SavedData.Factory<>(
            PlacedBlocks::new, PlacedBlocks::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /** {@link ChunkPos#asLong} → 该区块内玩家放过的格子({@link BlockPos#asLong});值发布后不改。 */
    private final ConcurrentHashMap<Long, LongSet> byChunk = new ConcurrentHashMap<>();

    public PlacedBlocks() {
    }

    private PlacedBlocks(List<Long> cells) {
        for (long cell : cells) {
            add(cell);
        }
    }

    public static PlacedBlocks of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "numen_placed");
    }

    static PlacedBlocks load(CompoundTag tag, HolderLookup.Provider registries) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(PlacedBlocks::new);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    /** 记一格(主线程)。 */
    public void record(BlockPos pos) {
        add(pos.asLong());
        setDirty();
    }

    /** 抹掉一格的记号。 */
    public void forget(BlockPos pos) {
        long cell = pos.asLong();
        byChunk.computeIfPresent(chunkKey(pos), (k, old) -> {
            if (!old.contains(cell)) {
                return old;
            }
            if (old.size() == 1) {
                return null;
            }
            LongOpenHashSet next = new LongOpenHashSet(old);
            next.remove(cell);
            return next;
        });
        setDirty();
    }

    /**
     * 这一格是玩家放的吗。{@code now} 是调用方读到的这一格此刻的状态:已是空气就当没有记号,
     * 并顺手把记号清掉。任何线程可调。
     */
    public boolean isPlaced(BlockPos pos, BlockState now) {
        LongSet cells = byChunk.get(chunkKey(pos));
        if (cells == null || !cells.contains(pos.asLong())) {
            return false;
        }
        if (now.isAir()) {
            forget(pos);
            return false;
        }
        return true;
    }

    /** {@code center} 周围 {@code radius} 格(切比雪夫)内有没有玩家放的方块(含自身)。 */
    public boolean anyPlacedWithin(BlockPos center, int radius, BlockGetter view) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    LongSet cells = byChunk.get(ChunkPos.asLong(cursor));
                    if (cells != null && cells.contains(cursor.asLong())
                            && isPlaced(cursor.immutable(), view.getBlockState(cursor))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 记着的格子总数(测试与调试用)。 */
    public int size() {
        int n = 0;
        for (LongSet s : byChunk.values()) {
            n += s.size();
        }
        return n;
    }

    private void add(long cell) {
        byChunk.compute(ChunkPos.asLong(BlockPos.of(cell)), (k, old) -> {
            LongOpenHashSet next = old == null ? new LongOpenHashSet() : new LongOpenHashSet(old);
            next.add(cell);
            return next;
        });
    }

    private List<Long> cells() {
        List<Long> out = new ArrayList<>();
        for (Map.Entry<Long, LongSet> e : byChunk.entrySet()) {
            e.getValue().forEach((long c) -> out.add(c));
        }
        return out;
    }

    private static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos);
    }
}
