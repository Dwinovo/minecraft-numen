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
 *   <li>周围八列也站得住、高差不超过一格({@link #RIM}):不在边沿上;周围的区块已加载;</li>
 *   <li>落点上方的空柱({@link #SHAFT}):半径内没有比落点高的地面,控制器在柱里绕着沉下来。</li>
 * </ul>
 */
final class Landings {

    /**
     * 落点上方的空柱:以落点为中心、这么多格半径的圆柱里,地面都不比落点高,所以柱里从落点往上是空的(树冠、山坡、台阶都不在柱里)。
     * 控制器在柱里一边绕一边沉,转弯半径量得十格上下(水平速度每刻 1.5 格、转角每刻不到 6 度),取身体盒半宽(0.3)加八格留出圈上身体落在线外的一截;
     * 柱底是落点那一格的地面,身体掉到它的高度以下还没落地,就是出了柱子({@link Sortie}),这个落点拉黑。
     */
    private static final int SHAFT = 4;
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
        for (int dx = -SHAFT; dx <= SHAFT; dx++) {
            for (int dz = -SHAFT; dz <= SHAFT; dz++) {
                if (dx * dx + dz * dz <= SHAFT * SHAFT && craft.world().top(x + dx, z + dz) > cell.getY()) {
                    return null;
                }
            }
        }
        return cell;
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
