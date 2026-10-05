package com.dwinovo.numen.bench;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 一次运行的场地,由代码现搭:{@code size×size} 的石头地板(底下再垫 {@link #DEPTH} 层石头,往下挖不会挖穿),地板以上
 * {@code height} 格空气,四周一圈到顶的屏障。每次运行一块新场地,彼此隔 {@link #SPACING} 格——远超任何扫描半径,
 * 看不见上一次留下的东西。收场后整块清成空气。
 *
 * @param size   可走的地板边长
 * @param height 地板以上的空间高度
 */
public record Arena(int size, int height) {

    public static final Arena DEFAULT = new Arena(20, 12);

    /** 地板下面垫几层石头。 */
    static final int DEPTH = 3;
    /** 相邻两块场地的间距。 */
    static final int SPACING = 512;

    void build(ServerLevel level, BlockPos origin) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState barrier = Blocks.BARRIER.defaultBlockState();
        for (int x = -1; x <= size; x++) {
            for (int z = -1; z <= size; z++) {
                boolean wall = x == -1 || z == -1 || x == size || z == size;
                for (int y = -DEPTH; y <= height; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), wall ? barrier : y <= 0 ? stone : air);
                }
            }
        }
    }

    void clear(ServerLevel level, BlockPos origin) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = -1; x <= size; x++) {
            for (int z = -1; z <= size; z++) {
                for (int y = -DEPTH; y <= height; y++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), air);
                }
            }
        }
    }

    AABB bounds(BlockPos origin) {
        return new AABB(origin.getX() - 1, origin.getY() - DEPTH, origin.getZ() - 1,
                origin.getX() + size + 1, origin.getY() + height + 1, origin.getZ() + size + 1);
    }
}
