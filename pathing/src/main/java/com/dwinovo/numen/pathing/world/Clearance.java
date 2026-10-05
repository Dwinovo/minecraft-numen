package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
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
        return free(level, body, box(body, pose, x + 0.5, feetY, z + 0.5), feetY);
    }

    /**
     * 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时,碰撞箱与它交叠的那些格,自下而上;放得下时为空。
     * 规划要挖开或打开哪几格才站得进去,问的就是这里。
     */
    public static List<BlockPos> blockers(BlockGetter level, BodyStats body, Pose pose, int x, double feetY, int z) {
        return blockers(level, body, pose, x, feetY, z, 0, 0);
    }

    /**
     * 身体以 {@code pose}、脚一直在 {@code feetY},从 {@code (x, z)} 这一列的中心直走到 {@code (x + dx, z + dz)} 那一列的中心,
     * 途中碰撞箱与它交叠的那些格,自下而上。关着的门只占格边,身体站在门格中心碰不到它,走过格边时才撞上——找挡路的门、
     * 要挖开的格问的是这里。走的是斜线时取两端身体盒的包络,比身体真实扫过的范围略大。
     */
    public static List<BlockPos> blockers(BlockGetter level, BodyStats body, Pose pose, int x, double feetY, int z,
                                          int dx, int dz) {
        AABB swept = box(body, pose, x + 0.5, feetY, z + 0.5).minmax(box(body, pose, x + dx + 0.5, feetY, z + dz + 0.5));
        List<BlockPos> out = new ArrayList<>();
        visit(level, body, swept, feetY, pos -> {
            out.add(pos.immutable());
            return true;
        });
        out.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        return out;
    }

    /**
     * 身体以 {@code pose}、脚在 {@code feetY} 时占到的最高一格(碰撞盒的顶在这一格里)。最低一格就是脚所在的格
     * {@link Footing#cellOf}。身体经过哪几格、碰到哪几格,都按这两头数。
     */
    public static int topCell(BodyStats body, Pose pose, double feetY) {
        return Mth.floor(feetY + body.height(pose) - DEFLATE);
    }

    /** 身体以 {@code pose} 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时占着 {@code cell} 这一格。 */
    public static boolean occupies(BodyStats body, Pose pose, int x, double feetY, int z, BlockPos cell) {
        return cell.getX() == x && cell.getZ() == z && cell.getY() >= Footing.cellOf(feetY)
                && cell.getY() <= topCell(body, pose, feetY);
    }

    /**
     * {@code state} 整块实心:碰撞箱正好是整格,不随世界或身体变。身体哪一部分都进不了这样一格,要进去只能先挖掉它。
     */
    public static boolean solid(BlockState state) {
        return Boxes.whole(state);
    }

    /** 身体以 {@code pose}、脚底中心在 {@code (cx, feetY, cz)} 时的碰撞盒。 */
    static AABB box(BodyStats body, Pose pose, double cx, double feetY, double cz) {
        double half = body.width() / 2;
        return new AABB(cx - half, feetY, cz - half, cx + half, feetY + body.height(pose), cz + half);
    }

    /**
     * 这个盒子与方块碰撞箱有没有交叠。{@code feetY} 是这具身体的脚高,碰撞箱随身体变化的方块按它回答。
     */
    static boolean free(BlockGetter level, BodyStats stats, AABB body, double feetY) {
        return !visit(level, stats, body, feetY, pos -> false);
    }

    /** 与盒子交叠的格逐个交给 {@code hit};它答 false 就停下。有交叠返回 true。 */
    private static boolean visit(BlockGetter level, BodyStats stats, AABB body, double feetY, Predicate<BlockPos> hit) {
        AABB inner = body.deflate(DEFLATE);
        int x0 = Mth.floor(inner.minX);
        int x1 = Mth.floor(inner.maxX);
        int z0 = Mth.floor(inner.minZ);
        int z1 = Mth.floor(inner.maxZ);
        // 下面一格的碰撞箱可能高出它自己(栅栏、墙到 1.5)
        int y0 = Mth.floor(inner.minY) - 1;
        int y1 = Mth.floor(inner.maxY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (AABB box : Boxes.at(level, stats, x, y, z, level.getBlockState(pos.set(x, y, z)), feetY)) {
                        if (inner.intersects(box.minX + x, box.minY + y, box.minZ + z,
                                box.maxX + x, box.maxY + y, box.maxZ + z)) {
                            any = true;
                            if (!hit.test(pos)) {
                                return true;
                            }
                            break;
                        }
                    }
                }
            }
        }
        return any;
    }
}
