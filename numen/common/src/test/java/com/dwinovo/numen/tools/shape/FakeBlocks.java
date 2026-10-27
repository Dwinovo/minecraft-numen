package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.build.BuildStates;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** 没有世界的单测用的"世界":底子是一个函数(位置到方块),上面能改几格;读法照 {@link LoadedBlocks},高度是原版主世界的。 */
final class FakeBlocks extends LoadedBlocks {

    private final Map<Long, BlockState> changes = new HashMap<>();
    private final BiFunction<Integer, Integer, Integer> ground;

    /** @param ground 每一列(x、z)地面最高的一格的 y,它和它下面是石头,上面是空气 */
    FakeBlocks(BiFunction<Integer, Integer, Integer> ground) {
        super(null, BlockPos.ZERO);
        this.ground = ground;
    }

    static FakeBlocks flat(int groundY) {
        return new FakeBlocks((x, z) -> groundY);
    }

    @Override
    int minY() {
        return -64;
    }

    @Override
    int maxY() {
        return 319;
    }

    @Override
    void requireLoaded(BlockPos pos) {}

    @Override
    void requireChunks(java.util.Set<Long> chunks) {}

    @Override
    BlockState at(int x, int y, int z) {
        BlockState set = changes.get(BlockPos.asLong(x, y, z));
        if (set != null) {
            return set;
        }
        return y <= ground.apply(x, z) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    FakeBlocks put(int x, int y, int z, String written) {
        changes.put(BlockPos.asLong(x, y, z), BuildStates.parse(written, false).blockState());
        return this;
    }

    FakeBlocks put(int x, int y, int z, BlockState state) {
        changes.put(BlockPos.asLong(x, y, z), state);
        return this;
    }

    /** 方框里(含两端)全是这个方块。 */
    FakeBlocks fill(int x1, int y1, int z1, int x2, int y2, int z2, String written) {
        BlockState state = BuildStates.parse(written, false).blockState();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    changes.put(BlockPos.asLong(x, y, z), state);
                }
            }
        }
        return this;
    }

    /** 把读世界交回的"要写什么"写进这个世界。 */
    FakeBlocks apply(List<BlockAt> writes) {
        for (BlockAt w : writes) {
            put(w.pos().getX(), w.pos().getY(), w.pos().getZ(), w.name());
        }
        return this;
    }
}
