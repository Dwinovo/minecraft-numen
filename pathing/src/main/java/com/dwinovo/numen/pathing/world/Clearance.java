package com.dwinovo.numen.pathing.world;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;

/**
 * 净空:身体以某个姿势、脚在某个高度站在一列上,它的碰撞盒(宽 {@link BodyStats#width()},站立或潜行的高)和周围方块的
 * 碰撞箱有没有交叠。头顶是楼梯、活板门、半砖时就按它们真实的碰撞箱判,没有"楼梯可穿"这类分类。
 *
 * <p>交叠的口径照原版:身体的盒先向内收 {@code 1e-7}({@code Player.canPlayerFitWithinBlocksAndEntitiesWhen} 同样收),
 * 贴着的面不算撞上。碰撞箱最高伸到 1.5 格(栅栏、墙),所以脚下那一格也要看。
 */
public final class Clearance {

    /** 原版判"身体放不放得下"时把身体的盒向内收的量。落脚、迈步判脚底与碰撞箱交不交叠用同一个量。 */
    static final double DEFLATE = 1.0E-7;

    private Clearance() {}

    /** 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时放不放得下。 */
    public static boolean fits(BlockGetter level, BodyStats body, Pose pose, int x, double feetY, int z) {
        return free(level, box(body, pose, x + 0.5, feetY, z + 0.5), feetY);
    }

    /** 身体以 {@code pose}、脚底中心在 {@code (cx, feetY, cz)} 时的碰撞盒。 */
    static AABB box(BodyStats body, Pose pose, double cx, double feetY, double cz) {
        double half = body.width() / 2;
        return new AABB(cx - half, feetY, cz - half, cx + half, feetY + body.height(pose), cz + half);
    }

    /**
     * 这个盒子与方块碰撞箱有没有交叠。{@code feetY} 是这具身体的脚高,碰撞箱随身体变化的方块按它回答。
     */
    static boolean free(BlockGetter level, AABB body, double feetY) {
        AABB inner = body.deflate(DEFLATE);
        int x0 = Mth.floor(inner.minX);
        int x1 = Mth.floor(inner.maxX);
        int z0 = Mth.floor(inner.minZ);
        int z1 = Mth.floor(inner.maxZ);
        // 下面一格的碰撞箱可能高出它自己(栅栏、墙到 1.5)
        int y0 = Mth.floor(inner.minY) - 1;
        int y1 = Mth.floor(inner.maxY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (AABB box : Boxes.at(level, x, y, z, level.getBlockState(pos.set(x, y, z)), feetY)) {
                        if (inner.intersects(box.minX + x, box.minY + y, box.minZ + z,
                                box.maxX + x, box.maxY + y, box.maxZ + z)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
