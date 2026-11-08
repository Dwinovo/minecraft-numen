package com.dwinovo.numen.pathing.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
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

    /** 身体站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时放不放得下。 */
    public static boolean fits(BlockGetter level, BodyStats body, int x, double feetY, int z) {
        double half = body.width() / 2;
        return !visit(level, body, x + 0.5 - half, feetY, z + 0.5 - half, x + 0.5 + half, feetY + body.height(), z + 0.5 + half, feetY,
                pos -> false);
    }

    /**
     * 身体站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时,碰撞箱与它交叠的那些格,自下而上;放得下时为空。
     * 规划要挖开或打开哪几格才站得进去,问的就是这里。
     */
    public static List<BlockPos> blockers(BlockGetter level, BodyStats body, int x, double feetY, int z) {
        return blockers(level, body, x, feetY, z, 0, 0);
    }

    /**
     * 身体脚一直在 {@code feetY},从 {@code (x, z)} 这一列的中心直走到 {@code (x + dx, z + dz)} 那一列的中心,
     * 途中碰撞箱与它交叠的那些格,自下而上。关着的门只占格边,身体站在门格中心碰不到它,走过格边时才撞上——找挡路的门、
     * 要挖开的格问的是这里。走的是斜线时取两端身体盒的包络,比身体真实扫过的范围略大。
     */
    public static List<BlockPos> blockers(BlockGetter level, BodyStats body, int x, double feetY, int z,
                                          int dx, int dz) {
        double half = body.width() / 2;
        List<BlockPos> out = new ArrayList<>(4);
        visit(level, body, Math.min(x + 0.5, x + dx + 0.5) - half, feetY, Math.min(z + 0.5, z + dz + 0.5) - half,
                Math.max(x + 0.5, x + dx + 0.5) + half, feetY + body.height(), Math.max(z + 0.5, z + dz + 0.5) + half, feetY, pos -> {
            out.add(pos.immutable());
            return true;
        });
        out.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        return out;
    }

    /**
     * 身体从脚底中心在 {@code (x0, y0, z0)} 的位置直飞到 {@code (x1, y1, z1)}(都是连续的位置),沿途碰撞箱与它交叠的那些格,自下而上;一路放得下为空。
     * 取起点与终点两个身体盒的包络:沿一条轴飞,包络就是扫过的体积;斜着飞比实际扫过的略大,宁可多挡。
     */
    public static List<BlockPos> blockersAlong(BlockGetter level, BodyStats body, double x0, double y0, double z0, double x1,
                                               double y1, double z1) {
        double half = body.width() / 2;
        double low = Math.min(y0, y1);
        List<BlockPos> out = new ArrayList<>(4);
        visit(level, body, Math.min(x0, x1) - half, low, Math.min(z0, z1) - half, Math.max(x0, x1) + half,
                Math.max(y0, y1) + body.height(), Math.max(z0, z1) + half, low, pos -> {
            out.add(pos.immutable());
            return true;
        });
        out.sort((a, b) -> Integer.compare(a.getY(), b.getY()));
        return out;
    }

    /**
     * 身体脚在 {@code feetY} 时占到的最高一格(碰撞盒的顶在这一格里)。最低一格就是脚所在的格
     * {@link Footing#cellOf}。身体经过哪几格、碰到哪几格,都按这两头数。
     */
    public static int topCell(BodyStats body, double feetY) {
        return Mth.floor(feetY + body.height() - DEFLATE);
    }

    /** 身体站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时占着 {@code cell} 这一格。 */
    public static boolean occupies(BodyStats body, int x, double feetY, int z, BlockPos cell) {
        return cell.getX() == x && cell.getZ() == z && cell.getY() >= Footing.cellOf(feetY)
                && cell.getY() <= topCell(body, feetY);
    }

    /**
     * {@code state} 整块实心:碰撞箱正好是整格,不随世界或身体变。身体哪一部分都进不了这样一格,要进去只能先挖掉它。
     */
    public static boolean solid(BlockState state) {
        return Boxes.whole(state);
    }

    /**
     * 盒子 {@code (minX..maxX, minY..maxY, minZ..maxZ)}(身体的碰撞盒,或它沿路扫过的包络)与方块碰撞箱交叠的格逐个交给 {@code hit};它答 false
     * 就停下。有交叠返回 true。{@code feetY} 是这具身体的脚高,碰撞箱随身体变化的方块按它回答。
     */
    private static boolean visit(BlockGetter level, BodyStats stats, double minX, double minY, double minZ, double maxX, double maxY,
                                 double maxZ, double feetY, Predicate<BlockPos> hit) {
        // 身体的盒向内收一点,贴着的面不算撞上
        double innerMinX = minX + DEFLATE;
        double innerMinY = minY + DEFLATE;
        double innerMinZ = minZ + DEFLATE;
        double innerMaxX = maxX - DEFLATE;
        double innerMaxY = maxY - DEFLATE;
        double innerMaxZ = maxZ - DEFLATE;
        int x0 = Mth.floor(innerMinX);
        int x1 = Mth.floor(innerMaxX);
        int z0 = Mth.floor(innerMinZ);
        int z1 = Mth.floor(innerMaxZ);
        // 下面一格的碰撞箱可能高出它自己(栅栏、墙到 1.5)
        int y0 = Mth.floor(innerMinY) - 1;
        int y1 = Mth.floor(innerMaxY);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean any = false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (AABB box : Boxes.at(level, stats, x, y, z, level.getBlockState(pos.set(x, y, z)), feetY)) {
                        if (intersects(innerMinX, innerMinY, innerMinZ, innerMaxX, innerMaxY, innerMaxZ, box.minX + x, box.minY + y,
                                box.minZ + z, box.maxX + x, box.maxY + y, box.maxZ + z)) {
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

    /** 与 {@code AABB.intersects} 同一个口径:贴边不算。 */
    private static boolean intersects(double aMinX, double aMinY, double aMinZ, double aMaxX, double aMaxY, double aMaxZ,
                                      double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
        return aMinX < bMaxX && aMaxX > bMinX && aMinY < bMaxY && aMaxY > bMinY && aMinZ < bMaxZ && aMaxZ > bMinZ;
    }
}
