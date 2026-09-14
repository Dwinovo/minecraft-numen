package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.spec.CellClass;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 移动原语与搜索共用的挖掘/放置判定库(BlockGetter 域):禁挖、破坏成本、可替换、
 * 放置贴面、霜行者。格子本身是什么、能不能穿能不能站,归 {@link CellClass}。
 */
public final class MovementHelper {

    private MovementHelper() {}

    // ==================== 霜行者 ====================

    /** 霜行者能否把该格冻成冰面(静水源且有附魔)。 */
    public static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state.getBlock() == Blocks.WATER
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * 若要站上/走过该格,它是否必须是实心的(霜行者判定用):
     * 梯子/藤蔓不算;流体上盖着上半台阶/顶部楼梯/关着的顶部活板门/
     * 脚手架/树叶等仍算有实心顶面;上方还是流体则不必实心(游泳位)。
     */
    public static boolean mustBeSolidToWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.LADDER || block == Blocks.VINE) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            if (block instanceof SlabBlock) {
                if (state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM) {
                    return true;
                }
            } else if (block instanceof StairBlock) {
                if (state.getValue(StairBlock.HALF) == Half.TOP) {
                    return true;
                }
                StairsShape shape = state.getValue(StairBlock.SHAPE);
                if (shape == StairsShape.INNER_LEFT || shape == StairsShape.INNER_RIGHT) {
                    return true;
                }
            } else if (block instanceof TrapDoorBlock) {
                if (!state.getValue(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
                    return true;
                }
            } else if (block == Blocks.SCAFFOLDING) {
                return true;
            } else if (block instanceof LeavesBlock) {
                return true;
            }
            if (context.getBlock(x, y + 1, z) instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    // ==================== 禁挖判定 ====================

    /**
     * 挖 (x,y,z) 是否被<b>物理上</b>禁止:世界边界外拒绝(内缩一格,边界外的方块
     * 没法贴放/挖到);冰(挖了变水搅乱路径)、被虫蚀方块,以及上方/四个
     * 水平邻格的液体与悬空落沙规则。这一格许不许动不在这里——那是权限层的裁决,
     * 由 {@link CalculationContext#breakCostMultiplierAt} 折成代价。
     */
    public static boolean avoidBreaking(CalculationContext context, int x, int y, int z, BlockState state) {
        if (context.worldBorder != null
                && !(x > context.worldBorder.getMinX()
                        && x + 1 < context.worldBorder.getMaxX()
                        && z > context.worldBorder.getMinZ()
                        && z + 1 < context.worldBorder.getMaxZ())) {
            return true;
        }
        Block b = state.getBlock();
        return b == Blocks.ICE
                || b instanceof InfestedBlock
                || neighbourForbidsBreaking(context, x, y + 1, z, true)
                || neighbourForbidsBreaking(context, x + 1, y, z, false)
                || neighbourForbidsBreaking(context, x - 1, y, z, false)
                || neighbourForbidsBreaking(context, x, y, z + 1, false)
                || neighbourForbidsBreaking(context, x, y, z - 1, false);
    }

    /**
     * 邻格 (x,y,z) 是否让"挖它旁边那格"变得危险。只查上方与四个水平向,不查下方。
     * 上方是落沙类不禁(整根沙柱的连锁挖掘成本已计入);上方是液体必禁。水平向:
     * 悬空的落沙类会被更新塌下来 → 禁;液体源爱水平漫延 → 禁;流动液体只要下方
     * 不是液体(会向水平流)→ 禁;规格要求从严时任何相邻液体都禁。
     */
    private static boolean neighbourForbidsBreaking(CalculationContext context, int x, int y, int z,
                                                    boolean directlyAbove) {
        BlockState state = context.get(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove
                && block instanceof FallingBlock
                && context.avoidUpdatingFallingBlocks
                && FallingBlock.isFree(context.get(x, y - 1, z))) {
            return true;
        }
        // 只按纯液体方块判(含水方块可能有封闭底面,不算)
        if (block instanceof LiquidBlock) {
            if (directlyAbove || context.spec.strictLiquidCheck()) {
                return true;
            }
            int level = state.getValue(LiquidBlock.LEVEL);
            if (level == 0) {
                return true; // 源方块爱水平漫延
            }
            return !(context.getBlock(x, y - 1, z) instanceof LiquidBlock);
        }
        return !state.getFluidState().isEmpty();
    }

    // ==================== 破坏成本 ====================

    public static double getMiningDurationTicks(CalculationContext context, int x, int y, int z, boolean includeFalling) {
        return getMiningDurationTicks(context, x, y, z, context.get(x, y, z), includeFalling);
    }

    /**
     * 挖穿该格的成本(tick)。本就可穿行 → 0;流体 → INF;
     * 禁挖 → INF;否则 1/速度 + 附加罚金,再乘上下文乘数(规格与权限层的定价)。
     * {@code includeFalling} 时向上递归叠加整根落沙柱的成本。
     */
    public static double getMiningDurationTicks(CalculationContext context, int x, int y, int z,
                                                BlockState state, boolean includeFalling) {
        return miningDuration(context, x, y, z, state, includeFalling, true);
    }

    /**
     * 挖穿该格要多久,不问权限层:同 {@link #getMiningDurationTicks},乘数只取规格与总开关。
     * 回答"这一格挖不挖得动",选挖什么的剪枝用它——许不许挖是执行开始时权限层的事。
     */
    public static double getUnpricedMiningDurationTicks(CalculationContext context, int x, int y, int z,
                                                        BlockState state, boolean includeFalling) {
        return miningDuration(context, x, y, z, state, includeFalling, false);
    }

    private static double miningDuration(CalculationContext context, int x, int y, int z,
                                         BlockState state, boolean includeFalling, boolean priced) {
        if (!context.canWalkThrough(x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            double mult = priced
                    ? context.breakCostMultiplierAt(x, y, z, state)
                    : context.terrainBreakMultiplierAt(x, y, z, state);
            if (mult >= COST_INF) {
                return COST_INF;
            }
            if (avoidBreaking(context, x, y, z, state)) {
                return COST_INF;
            }
            double strVsBlock = context.toolSet.getStrVsBlock(state);
            if (strVsBlock <= 0) {
                return COST_INF;
            }
            double result = 1 / strVsBlock;
            result += context.spec.breakPenalty();
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += miningDuration(context, x, y + 1, z, above, true, priced);
                }
            }
            return result;
        }
        return 0; // 无需真挖,也就不必查上方落沙
    }

    // ==================== 放置相关 ====================

    /**
     * 该格是否可被放置动作替换掉:空气、单层雪(未加载 chunk 放行)、
     * 高草/大型蕨,及其余原版可替换方块。
     */
    public static boolean isReplaceable(int x, int y, int z, BlockState state, ChunkLoadedTest loaded) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return true;
        }
        if (block instanceof SnowLayerBlock) {
            if (!loaded.isLoaded(x, z)) {
                return true;
            }
            return state.getValue(SnowLayerBlock.LAYERS) == 1;
        }
        if (block == Blocks.LARGE_FERN || block == Blocks.TALL_GRASS) {
            return true;
        }
        return state.canBeReplaced();
    }

    public static boolean canPlaceAgainst(CalculationContext context, int x, int y, int z) {
        return canPlaceAgainst(context, x, y, z, context.get(x, y, z));
    }

    public static boolean canPlaceAgainst(CalculationContext context, int x, int y, int z, BlockState state) {
        if (!placeableWithinBorder(context.worldBorder, x, z)) {
            return false;
        }
        return canPlaceAgainst(state);
    }

    public static boolean canPlaceAgainst(BlockGetter level, BlockPos pos) {
        if (level instanceof net.minecraft.world.level.Level live
                && !placeableWithinBorder(live.getWorldBorder(), pos.getX(), pos.getZ())) {
            return false;
        }
        return canPlaceAgainst(level.getBlockState(pos));
    }

    /**
     * 贴面格是否离世界边界足够远:各向内缩一格——贴着边界的方块无法
     * 被右键选面。边界未知(null)按不限制。
     */
    public static boolean placeableWithinBorder(net.minecraft.world.level.border.WorldBorder border,
                                                int x, int z) {
        if (border == null) {
            return true;
        }
        return x > border.getMinX() && x + 1 < border.getMaxX()
                && z > border.getMinZ() && z + 1 < border.getMaxZ();
    }

    /**
     * 能否瞄准该方块侧面中心作为放置贴面:完整实心方块或玻璃。
     * 技术上能贴、实际瞄不准的薄片方块(地毯之类)不算。
     */
    public static boolean canPlaceAgainst(BlockState state) {
        return CellClass.isFullCube(state)
                || state.getBlock() == Blocks.GLASS
                || state.getBlock() instanceof StainedGlassBlock;
    }
}
