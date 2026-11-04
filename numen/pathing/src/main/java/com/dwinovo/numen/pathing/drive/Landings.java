package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 滑翔的落点:只求安全,不求精确。什么样的地方算安全的落点,全模块只在这里判;降落与迫降选点同一个判据({@link Glide#descent})。
 *
 * <p>一个落点是一列的<b>最高碰撞面</b>({@link Terrain#top} 读高度图,第一个挡动作或有流体的方块之上),而且:
 * <ul>
 *   <li>脚下是天然实心方块({@link #natural}:土、石、沙、陶瓦、砾石、黏土、雪块、末地石;树叶、玻璃、半砖、人造方块都不算);</li>
 *   <li>最高碰撞面本身就意味着头顶是空柱:这一列上面再没有任何碰撞方块,树冠下、屋顶下都不是落点;</li>
 *   <li>这一格站得住({@link Stance}),脚、头、脚下一格都不是液体,不是会伤身的方块({@link #harmless});</li>
 *   <li>周围八列也站得住、高差不超过一格({@link #RIM}):不在边沿上;周围的区块已加载。</li>
 * </ul>
 */
final class Landings {

    /** 直线进近冲过落点之后要有这么多格地面。 */
    private static final int RUNWAY = 8;
    /** 进近最后一段落点前要有这么多格同高的地面。 */
    private static final int RUN_IN = 5;
    /** 周围八列与这一列的高差最多几格。 */
    private static final int RIM = 1;
    /** 找落点往四周找多少圈(算力预算:一次选点最多查 (2 × 半径 + 1)² 列,有结果就停)。 */
    static final int RADIUS = 64;
    /**
     * 交给 {@code trial} 的落点之间至少隔这么远(方框距离):挨着的落点周围的地形一样,线多半同样过不去(同一片树冠、同一道坡),
     * 一个个试只会把试线的预算花在同一处;隔开了,预算才够到别处。
     */
    private static final int SPACING = 4;

    private Landings() {}

    /**
     * 天然实心方块:土、石、沙、陶瓦、砾石、黏土、雪块、末地石。判据只在这里,不认树叶、玻璃、半砖之类。
     */
    static boolean natural(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
                || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.END_STONE);
    }

    /**
     * 这一列的落点格(脚所在的格);不是安全的落点为 null。{@code x}、{@code z} 周围一圈的区块没加载也是 null:没加载的读出来是空的,不能当地面。
     */
    static BlockPos safe(Craft craft, BodyStats stats, int x, int z) {
        BlockPos cell = standing(craft, stats, x, z);
        if (cell == null) {
            return null;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos beside = held(craft, stats, x + dx, z + dz);
                if (beside == null || Math.abs(beside.getY() - cell.getY()) > RIM) {
                    return null;
                }
            }
        }
        return cell;
    }

    /**
     * 一路飞过来冲过落点之后还有一段地面:从落点起朝 {@code (ux, uz)} 方向 {@link #RUNWAY} 格,每一列都站得住、高差不超过一格(不是水、不是悬崖)。
     * 俯冲加拉平落地不会正好停在落点上,会往前冲一段,那一段上是水或断崖就落水、摔下去。
     */
    static boolean runway(Craft craft, BodyStats stats, BlockPos spot, double ux, double uz) {
        for (int k = 1; k <= RUNWAY; k++) {
            BlockPos beyond = held(craft, stats, (int) Math.floor(spot.getX() + 0.5 + ux * k), (int) Math.floor(spot.getZ() + 0.5 + uz * k));
            if (beyond == null || Math.abs(beyond.getY() - spot.getY()) > RIM) {
                return false;
            }
        }
        return true;
    }

    /**
     * 进近的最后一段从 {@code (-ux, -uz)} 方向飞来,落点前 {@link #RUN_IN} 格每一列的落脚处都和落点同高:线收在落点的地面上,控制器追线总落在线下面一截(量到过零点几格),
     * 落点前一两格有一级台阶(落点是台地的边,从低的一侧飞来),身体擦着低处的地面飞过去,就一头撞在台阶的立面上,水平速度还是每刻一格多,掉近十点血。
     */
    static boolean runIn(Craft craft, BodyStats stats, BlockPos spot, double ux, double uz) {
        for (int k = 1; k <= RUN_IN; k++) {
            BlockPos before = held(craft, stats, (int) Math.floor(spot.getX() + 0.5 - ux * k), (int) Math.floor(spot.getZ() + 0.5 - uz * k));
            if (before == null || before.getY() != spot.getY()) {
                return false;
            }
        }
        return true;
    }

    /** 这一列最高碰撞面上站得住、脚下是天然实心方块、不伤身的那一格。 */
    private static BlockPos standing(Craft craft, BodyStats stats, int x, int z) {
        // 先看脚下是不是天然方块(读一个方块),再做站不站得住的判断(贵得多):树林里绝大多数列在第一步就筛掉了
        if (!craft.world().isLoaded(x, z) || !natural(craft.world().getBlockState(new BlockPos(x, craft.world().top(x, z) - 1, z)))) {
            return null;
        }
        return held(craft, stats, x, z);
    }

    /** 这一列最高碰撞面上站得住、不是液体、不伤身的那一格(周围一圈只要求站得住,不要求天然)。 */
    private static BlockPos held(Craft craft, BodyStats stats, int x, int z) {
        if (!craft.world().isLoaded(x, z)) {
            return null;
        }
        BlockPos cell = new BlockPos(x, craft.world().top(x, z), z);
        Stance stance = Stance.at(craft.world(), stats, cell);
        if (stance == null || !stance.grounded() || !harmless(craft, cell)) {
            return null;
        }
        return cell;
    }

    /** 脚、头、脚下一格都不是液体,脚与头的格子不会伤身。 */
    private static boolean harmless(Craft craft, BlockPos cell) {
        for (BlockPos at : new BlockPos[] {cell, cell.above(), cell.below()}) {
            if (!craft.world().getFluidState(at).isEmpty()) {
                return false;
            }
        }
        for (BlockPos at : new BlockPos[] {cell, cell.above()}) {
            Block block = craft.world().getBlockState(at).getBlock();
            if (block == Blocks.FIRE || block == Blocks.SOUL_FIRE || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                    || block == Blocks.CACTUS || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.WITHER_ROSE
                    || block == Blocks.POWDER_SNOW || block == Blocks.COBWEB || block == Blocks.MAGMA_BLOCK) {
                return false;
            }
        }
        return craft.world().getBlockState(cell.below()).getBlock() != Blocks.MAGMA_BLOCK;
    }

    /**
     * 从 {@code (cx, cz)} 这一列起由近到远(先正下方,再一圈一圈往外,每一圈绕一周)找安全的落点,把每个交给 {@code trial},取第一个有结果的。
     * 算力预算:最多查 {@link #RADIUS} 圈,最多交给 {@code trial} {@code limit} 个落点(它们要做的事比判落点贵:扫线),交出去的落点之间隔 {@link #SPACING} 格以上。没有为 null。
     */
    static <T> T nearest(Craft craft, BodyStats stats, int cx, int cz, int limit, Function<BlockPos, T> trial) {
        List<BlockPos> tried = new ArrayList<>();
        for (int r = 0; r <= RADIUS; r++) {
            for (int step = 0; step < Math.max(1, 8 * r); step++) {
                int[] at = ring(r, step);
                BlockPos spot = safe(craft, stats, cx + at[0], cz + at[1]);
                if (spot == null || tried.stream().anyMatch(t -> Math.max(Math.abs(t.getX() - spot.getX()), Math.abs(t.getZ() - spot.getZ())) < SPACING)) {
                    continue;
                }
                T result = trial.apply(spot);
                if (result != null) {
                    return result;
                }
                tried.add(spot);
                if (tried.size() >= limit) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 第 {@code r} 圈(以 {@code r} 为半径的方框)上的第 {@code step} 个偏移,绕一圈。 */
    private static int[] ring(int r, int step) {
        if (r == 0) {
            return new int[] {0, 0};
        }
        int side = 2 * r;
        int along = step % side;
        return switch (step / side) {
            case 0 -> new int[] {-r + along, -r};
            case 1 -> new int[] {r, -r + along};
            case 2 -> new int[] {r - along, r};
            default -> new int[] {-r, r - along};
        };
    }
}
