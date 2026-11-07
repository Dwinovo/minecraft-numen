package com.dwinovo.numen.api.permission;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 地形自己会长出来的方块:没人放过、也不属于哪个结构的格子,种类在这张表里才算 {@code nature}(没人的),不在表里的说不清是谁的,
 * 落到 {@code unknown} 去问。表是数据标签 {@value #TAG_ID}(随模组发,整合包与主人都能用数据包改):原版的石头泥土沙子、
 * 矿石、洞穴与下界的植被,加各模组通用的 {@code c:} 标签,模组矿石与模组石头不用逐个适配。
 *
 * <p>标签只看种类,原木与树叶还要看状态:玩家放下的树叶带持久标记({@link LeavesBlock#PERSISTENT}),自然长出的不带;
 * 原木连着不持久的树叶才是野树,木屋的原木连着的是持久树叶或根本没有树叶。不靠"看形状像不像建筑"去猜。
 *
 * <p>表不全的后果是"多问",不是"误挖":漏掉的种类落到 {@code unknown},主人答"以后都允许"就记住这一种。
 */
final class NaturalBlocks {

    static final String TAG_ID = "numen:natural";
    static final TagKey<Block> TAG = TagKey.create(Registries.BLOCK, ResourceLocation.parse(TAG_ID));

    /** 从一根原木出发沿原木顺藤摸瓜,最多看这么多根。 */
    private static final int LOGS_LOOKED_AT = 64;

    private NaturalBlocks() {}

    /** 这一格的方块是不是地形自己长的。任何线程:只读视图。 */
    static boolean is(BlockState state, BlockPos pos, BlockGetter view) {
        if (!state.is(TAG)) {
            return false;
        }
        if (state.getBlock() instanceof LeavesBlock) {
            return !state.getValue(LeavesBlock.PERSISTENT);
        }
        if (state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
            return hangsFromLeaves(pos, view);
        }
        return true;
    }

    /** 这根原木所在的一串原木,有没有哪一根贴着不持久的树叶。 */
    private static boolean hangsFromLeaves(BlockPos start, BlockGetter view) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(start.asLong());
        queue.enqueue(start.asLong());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && seen.size() <= LOGS_LOOKED_AT) {
            BlockPos log = BlockPos.of(queue.dequeueLong());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        cursor.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                        BlockState next = view.getBlockState(cursor);
                        boolean face = Math.abs(dx) + Math.abs(dy) + Math.abs(dz) == 1;
                        if (face && next.getBlock() instanceof LeavesBlock && !next.getValue(LeavesBlock.PERSISTENT)) {
                            return true;
                        }
                        if (next.is(BlockTags.LOGS) && seen.add(cursor.asLong())) {
                            queue.enqueue(cursor.asLong());
                        }
                    }
                }
            }
        }
        return false;
    }
}
