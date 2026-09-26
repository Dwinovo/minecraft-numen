package com.dwinovo.numen.permission;

import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每维度一份:玩家放过方块的格子,和是谁放的。{@code placed} 信号的来源,也是"这东西是谁的"这个概念在
 * 全仓唯一的落点。
 *
 * <p>记:{@code BlockItem.place} 返回处的 mixin,谁放的就记谁——真玩家、同伴都一样;建造任务收工把成果格
 * 改记到主人名下。这里只记事实,"算不算别人的东西"由 {@code placed} 信号对着要动手的同伴判。
 * 查:格子已是空气视为无记号并顺手清掉——不另挂方块变化钩子,谁挖的都一样。
 *
 * <p>线程:按区块存"格子 → 放的人",每份发布后不再改,改就整个换一份(写时复制,经
 * {@link ConcurrentHashMap#compute}),寻路工作线程无锁读。
 */
public final class PlacedBlocks extends SavedData {

    /**
     * 放下这一格的玩家:{@code id} 认人,{@code name} 是放的那一刻的名字(主人看的"xxx 放的";查不到名字为空串)。
     * 记下放的人之前的存档里的格子不知道是谁放的,是 {@link #UNKNOWN}。
     */
    public record Placer(UUID id, String name) {

        public static final Placer UNKNOWN = new Placer(new UUID(0L, 0L), "");

        /**
         * 这只同伴的主人作为放的人:名字取在线的主人,不在线取服务器记着的档案;都查不到名字为空串(说成"玩家放的")。
         * 没有主人是 {@link #UNKNOWN}。
         */
        public static Placer ownerOf(NumenPlayer companion) {
            UUID id = companion.getOwnerUuid();
            if (id == null) {
                return UNKNOWN;
            }
            ServerPlayer online = companion.resolveOwnerPlayer();
            String name = online != null ? online.getGameProfile().getName()
                    : java.util.Optional.ofNullable(companion.getServer().getProfileCache())
                            .flatMap(cache -> cache.get(id))
                            .map(com.mojang.authlib.GameProfile::getName)
                            .orElse("");
            return new Placer(id, name);
        }

        /** 说得出是谁:有名字。 */
        public boolean known() {
            return !name.isEmpty();
        }
    }

    /** 同一个人放的格子存成一组。 */
    private record PlacerCells(UUID id, String name, List<Long> cells) {
        static final Codec<PlacerCells> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(PlacerCells::id),
                Codec.STRING.fieldOf("name").forGetter(PlacerCells::name),
                Codec.LONG.listOf().fieldOf("cells").forGetter(PlacerCells::cells)
        ).apply(i, PlacerCells::new));
    }

    /**
     * {@code cells}:不知道是谁放的格子(记下放的人之前的存档);{@code placers}:按放的人分组的格子。
     */
    private static final Codec<PlacedBlocks> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.listOf().optionalFieldOf("cells", List.of()).forGetter(PlacedBlocks::unknownCells),
            PlacerCells.CODEC.listOf().optionalFieldOf("placers", List.of()).forGetter(PlacedBlocks::placerCells)
    ).apply(i, PlacedBlocks::new));

    private static final SavedData.Factory<PlacedBlocks> FACTORY = new SavedData.Factory<>(
            PlacedBlocks::new, PlacedBlocks::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /** {@link ChunkPos#asLong} → 该区块内玩家放过的格子({@link BlockPos#asLong})与放的人;值发布后不改。 */
    private final ConcurrentHashMap<Long, Long2ObjectMap<Placer>> byChunk = new ConcurrentHashMap<>();

    public PlacedBlocks() {
    }

    private PlacedBlocks(List<Long> unknown, List<PlacerCells> groups) {
        for (long cell : unknown) {
            put(cell, Placer.UNKNOWN);
        }
        for (PlacerCells group : groups) {
            Placer placer = new Placer(group.id(), group.name());
            for (long cell : group.cells()) {
                put(cell, placer);
            }
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

    /**
     * 双格方块的主半(门的下半、床脚)带出来的另一半在哪;不是双格方块的主半为 null。建造判"放一扇门还要清哪一格"与画设计时
     * "门盖掉哪两格"共用这一处。
     */
    public static BlockPos otherHalfOf(BlockPos pos, BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return pos.above();
        }
        return null;
    }

    /** 记一格和放它的人(主线程)。 */
    public void record(BlockPos pos, Placer placer) {
        put(pos.asLong(), placer);
        setDirty();
    }

    /** 抹掉一格的记号。 */
    public void forget(BlockPos pos) {
        long cell = pos.asLong();
        byChunk.computeIfPresent(chunkKey(pos), (k, old) -> {
            if (!old.containsKey(cell)) {
                return old;
            }
            if (old.size() == 1) {
                return null;
            }
            Long2ObjectOpenHashMap<Placer> next = new Long2ObjectOpenHashMap<>(old);
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
        return placerAt(pos, now) != null;
    }

    /**
     * 放这一格的人;不是玩家放的是 null(已是空气同 {@link #isPlaced},顺手清掉)。不知道是谁放的是
     * {@link Placer#UNKNOWN}。任何线程可调。
     */
    public Placer placerAt(BlockPos pos, BlockState now) {
        Long2ObjectMap<Placer> cells = byChunk.get(chunkKey(pos));
        Placer placer = cells == null ? null : cells.get(pos.asLong());
        if (placer == null) {
            return null;
        }
        if (now.isAir()) {
            forget(pos);
            return null;
        }
        return placer;
    }

    /**
     * {@code center} 周围 {@code radius} 格(切比雪夫)内有没有别人放的方块(含自身)。
     *
     * @param notBy 这个人放的不算(要动手的同伴自己);null = 谁放的都算
     */
    public boolean anyPlacedWithin(BlockPos center, int radius, BlockGetter view, UUID notBy) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Long2ObjectMap<Placer> cells = byChunk.get(ChunkPos.asLong(cursor));
                    if (cells == null || !cells.containsKey(cursor.asLong())) {
                        continue;
                    }
                    Placer placer = placerAt(cursor.immutable(), view.getBlockState(cursor));
                    if (placer != null && !placer.id().equals(notBy)) {
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
        for (Long2ObjectMap<Placer> s : byChunk.values()) {
            n += s.size();
        }
        return n;
    }

    private void put(long cell, Placer placer) {
        byChunk.compute(ChunkPos.asLong(BlockPos.of(cell)), (k, old) -> {
            Long2ObjectOpenHashMap<Placer> next = old == null ? new Long2ObjectOpenHashMap<>()
                    : new Long2ObjectOpenHashMap<>(old);
            next.put(cell, placer);
            return next;
        });
    }

    private List<Long> unknownCells() {
        List<Long> out = new ArrayList<>();
        for (Long2ObjectMap<Placer> cells : byChunk.values()) {
            for (Long2ObjectMap.Entry<Placer> e : cells.long2ObjectEntrySet()) {
                if (e.getValue().equals(Placer.UNKNOWN)) {
                    out.add(e.getLongKey());
                }
            }
        }
        return out;
    }

    private List<PlacerCells> placerCells() {
        Map<Placer, List<Long>> groups = new LinkedHashMap<>();
        for (Long2ObjectMap<Placer> cells : byChunk.values()) {
            for (Long2ObjectMap.Entry<Placer> e : cells.long2ObjectEntrySet()) {
                if (!e.getValue().equals(Placer.UNKNOWN)) {
                    groups.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getLongKey());
                }
            }
        }
        List<PlacerCells> out = new ArrayList<>();
        groups.forEach((placer, cells) -> out.add(new PlacerCells(placer.id(), placer.name(), cells)));
        return out;
    }

    private static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos);
    }
}
