package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 移动原语与搜索共用的静态方块判定库(BlockGetter 域):
 * 可穿行 / 可跳穿 / 可站立 / 禁挖 / 流体流动 / 破坏成本等。
 * 位置无关的判定拆成三态预筛(YES/NO/MAYBE),MAYBE 再做位置精判,
 * 让绝大多数格子只看 BlockState 就能出结论。
 */
public final class MovementHelper {

    private MovementHelper() {}

    /** 三态判定结果:仅看 BlockState 能否定论,MAYBE 需要位置精判。 */
    public enum Ternary {
        YES, MAYBE, NO
    }

    // ==================== 可穿行(身体能否占据该格) ====================

    public static boolean canWalkThrough(CalculationContext context, int x, int y, int z) {
        return canWalkThrough(context.view, context.loadedTest, x, y, z, context.get(x, y, z));
    }

    public static boolean canWalkThrough(CalculationContext context, int x, int y, int z, BlockState state) {
        return canWalkThrough(context.view, context.loadedTest, x, y, z, state);
    }

    /** 实时世界重载(chunk 视为全部已加载)。 */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos) {
        return canWalkThrough(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
    }

    public static boolean canWalkThrough(BlockGetter view, ChunkLoadedTest loaded,
                                         int x, int y, int z, BlockState state) {
        Ternary result = canWalkThroughBlockState(state);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkThroughPosition(view, loaded, x, y, z, state);
    }

    /** 三态预筛:只看 BlockState。 */
    public static Ternary canWalkThroughBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof BaseFireBlock || block == Blocks.TRIPWIRE || block == Blocks.COBWEB
                || block == Blocks.END_PORTAL || block == Blocks.COCOA
                || block instanceof AbstractSkullBlock || block == Blocks.BUBBLE_COLUMN
                || block instanceof ShulkerBoxBlock || block instanceof SlabBlock
                || block instanceof TrapDoorBlock || block == Blocks.HONEY_BLOCK
                || block == Blocks.END_ROD || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.POINTED_DRIPSTONE || block instanceof AmethystClusterBlock
                || block instanceof AzaleaBlock || block == Blocks.BIG_DRIPLEAF
                || block == Blocks.POWDER_SNOW) {
            return Ternary.NO;
        }
        if (NavSettings.get().blocksToAvoid().contains(block)) {
            return Ternary.NO;
        }
        if (block instanceof DoorBlock || block instanceof FenceGateBlock) {
            // 木门/栅栏门假定可开(执行层右键);铁门无红石打不开,按实体墙处理
            if (block == Blocks.IRON_DOOR) {
                return Ternary.NO;
            }
            return Ternary.YES;
        }
        if (block instanceof CarpetBlock) {
            return Ternary.MAYBE;
        }
        if (block instanceof SnowLayerBlock) {
            // 缓存 chunk 顶层的雪可能拿不到层数,留到位置精判
            return Ternary.MAYBE;
        }
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (fluidState.getAmount() != 8) {
                return Ternary.NO; // 非满格流体(流动中)不可走
            }
            return Ternary.MAYBE;
        }
        if (block instanceof CauldronBlock) {
            return Ternary.NO;
        }
        return state.isPathfindable(PathComputationType.LAND) ? Ternary.YES : Ternary.NO;
    }

    /** MAYBE 的位置精判:地毯 / 雪层 / 满格流体。 */
    public static boolean canWalkThroughPosition(BlockGetter view, ChunkLoadedTest loaded,
                                                 int x, int y, int z, BlockState state) {
        Block block = state.getBlock();

        if (block instanceof CarpetBlock) {
            // 地毯是薄层,可走过的前提是地毯下面能站
            return canWalkOn(view, loaded, x, y - 1, z);
        }

        if (block instanceof SnowLayerBlock) {
            // 未加载 chunk 拿不到层数,放行(否则雪原长途寻路直接瘫掉)
            if (!loaded.isLoaded(x, z)) {
                return true;
            }
            // 原版可通行判定是 <5 层;但 2 格高净空里 ≥3 层就挤不过去了
            if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                return false;
            }
            return canWalkOn(view, loaded, x, y - 1, z);
        }

        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            if (isFlowing(view, x, y, z, state)) {
                return false; // 水流会把人冲离路径
            }
            if (NavSettings.get().assumeWalkOnWater) {
                return false; // 水面行走语义下水柱不可穿
            }
            BlockState up = view.getBlockState(new BlockPos(x, y + 1, z));
            if (!up.getFluidState().isEmpty() || up.getBlock() instanceof WaterlilyBlock) {
                return false; // 上方还有流体/睡莲,穿过去等于潜水
            }
            return fluidState.getType() instanceof WaterFluid; // 只有水柱可游走
        }

        return state.isPathfindable(PathComputationType.LAND);
    }

    // ==================== 完全无阻碍(可跳跃穿过) ====================

    /**
     * 比可穿行更严:不含需要右键的门/栅栏门、不含减速的
     * 藤蔓/梯子/蛛网、不含任何流体。用于跑酷与头顶净空检查。
     */
    public static Ternary fullyPassableBlockState(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof AirBlock) {
            return Ternary.YES;
        }
        if (block instanceof BaseFireBlock
                || block == Blocks.TRIPWIRE
                || block == Blocks.COBWEB
                || block == Blocks.VINE
                || block == Blocks.LADDER
                || block == Blocks.COCOA
                || block instanceof AzaleaBlock
                || block instanceof DoorBlock
                || block instanceof FenceGateBlock
                || block instanceof SnowLayerBlock
                || !state.getFluidState().isEmpty()
                || block instanceof TrapDoorBlock
                || block instanceof EndPortalBlock
                || block instanceof SkullBlock
                || block instanceof ShulkerBoxBlock) {
            return Ternary.NO;
        }
        return state.isPathfindable(PathComputationType.LAND) ? Ternary.YES : Ternary.NO;
    }

    public static boolean fullyPassable(CalculationContext context, int x, int y, int z) {
        return fullyPassable(context.get(x, y, z));
    }

    public static boolean fullyPassable(CalculationContext context, int x, int y, int z, BlockState state) {
        return fullyPassable(state);
    }

    public static boolean fullyPassable(BlockGetter level, BlockPos pos) {
        return fullyPassable(level.getBlockState(pos));
    }

    public static boolean fullyPassable(BlockState state) {
        return fullyPassableBlockState(state) == Ternary.YES;
    }

    // ==================== 可站立(能否作为脚下地面) ====================

    public static boolean canWalkOn(CalculationContext context, int x, int y, int z) {
        return canWalkOn(context.view, context.loadedTest, x, y, z, context.get(x, y, z));
    }

    public static boolean canWalkOn(CalculationContext context, int x, int y, int z, BlockState state) {
        return canWalkOn(context.view, context.loadedTest, x, y, z, state);
    }

    /** 实时世界重载。 */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos) {
        return canWalkOn(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos));
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded, int x, int y, int z) {
        return canWalkOn(view, loaded, x, y, z, view.getBlockState(new BlockPos(x, y, z)));
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded,
                                    int x, int y, int z, BlockState state) {
        Ternary result = canWalkOnBlockState(state);
        if (result == Ternary.YES) {
            return true;
        }
        if (result == Ternary.NO) {
            return false;
        }
        return canWalkOnPosition(view, loaded, x, y, z, state);
    }

    /** 三态预筛:只看 BlockState。 */
    public static Ternary canWalkOnBlockState(BlockState state) {
        Block block = state.getBlock();
        if (isBlockNormalCube(state) && block != Blocks.MAGMA_BLOCK
                && block != Blocks.BUBBLE_COLUMN && block != Blocks.HONEY_BLOCK) {
            return Ternary.YES;
        }
        if (block instanceof AzaleaBlock) {
            return Ternary.YES;
        }
        if (block == Blocks.LADDER || (block == Blocks.VINE && NavSettings.get().allowVines)) {
            return Ternary.YES;
        }
        if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.SOUL_SAND) {
            return Ternary.YES;
        }
        if (block == Blocks.ENDER_CHEST || block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) {
            return Ternary.YES;
        }
        if (block == Blocks.GLASS || block instanceof StainedGlassBlock) {
            return Ternary.YES;
        }
        if (block instanceof StairBlock) {
            return Ternary.YES;
        }
        if (isWater(state)) {
            return Ternary.MAYBE;
        }
        if (isLava(state) && NavSettings.get().assumeWalkOnLava) {
            return Ternary.MAYBE;
        }
        if (block instanceof SlabBlock) {
            if (!NavSettings.get().allowWalkOnBottomSlab) {
                // 只许站非下半台阶
                return state.getValue(SlabBlock.TYPE) != SlabType.BOTTOM ? Ternary.YES : Ternary.NO;
            }
            return Ternary.YES;
        }
        return Ternary.NO;
    }

    /**
     * MAYBE 的位置精判(水/岩浆)。水的"游泳位"语义:默认只能站在
     * 上方还有水的水格里(浮在水柱中);开水面行走则只能站在上方
     * 无水的水面上——两者按 XOR 互斥。
     */
    public static boolean canWalkOnPosition(BlockGetter view, ChunkLoadedTest loaded,
                                            int x, int y, int z, BlockState state) {
        if (isWater(state)) {
            BlockState upState = view.getBlockState(new BlockPos(x, y + 1, z));
            Block up = upState.getBlock();
            if (up == Blocks.LILY_PAD || up instanceof CarpetBlock) {
                return true;
            }
            if (isFlowing(view, x, y, z, state) || upState.getFluidState().getType() == Fluids.FLOWING_WATER) {
                // 流水上唯一能站的情形:压在静水下面且未开水面行走
                return isWater(upState) && !NavSettings.get().assumeWalkOnWater;
            }
            return isWater(upState) ^ NavSettings.get().assumeWalkOnWater;
        }

        if (isLava(state) && !isFlowing(view, x, y, z, state) && NavSettings.get().assumeWalkOnLava) {
            return true;
        }

        return false; // 未识别的一律不站,宁可绕
    }

    /** 霜行者能否把该格冻成冰面(静水源且有附魔)。 */
    public static boolean canUseFrostWalker(CalculationContext context, BlockState state) {
        return context.frostWalker != 0
                && state.getBlock() == Blocks.WATER
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /**
     * 若要站上/走过该格,它是否必须是实心的(霜行者判定用):
     * 梯子/藤蔓不算;流体上盖着上半台阶/顶部楼梯/关着的顶部活板门/
     * 脚手架/树叶等仍算有实心顶面。
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
            if (context.assumeWalkOnWater) {
                return false;
            }
            if (context.getBlock(x, y + 1, z) instanceof LiquidBlock) {
                return false;
            }
        }
        return true;
    }

    // ==================== 禁挖判定 ====================

    /**
     * 挖 (x,y,z) 是否被禁止:世界边界外拒绝(内缩一格,边界外的方块
     * 没法贴放/挖到);硬禁令清单(blocksToDisallowBreaking,
     * 也不解除)、冰(挖了变水搅乱路径)、被虫蚀方块,以及上方/四个
     * 水平邻格的液体与悬空落沙规则。
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
        if (NavSettings.get().blocksToDisallowBreaking().contains(b)) {
            return true;
        }
        return b == Blocks.ICE
                || b instanceof InfestedBlock
                || avoidAdjacentBreaking(context, x, y + 1, z, true)
                || avoidAdjacentBreaking(context, x + 1, y, z, false)
                || avoidAdjacentBreaking(context, x - 1, y, z, false)
                || avoidAdjacentBreaking(context, x, y, z + 1, false)
                || avoidAdjacentBreaking(context, x, y, z - 1, false);
    }

    /**
     * 邻格 (x,y,z) 是否让"挖它旁边那格"变得危险。只查上方与四个
     * 水平向,不查下方。上方是落沙类不禁(整根沙柱的连锁挖掘成本
     * 已计入);上方是液体必禁。水平向:悬空的落沙类会被更新塌下
     * 来 → 禁;液体源爱水平漫延 → 禁;流动液体只要下方不是液体
     * (会向水平流)→ 禁。
     */
    public static boolean avoidAdjacentBreaking(CalculationContext context, int x, int y, int z, boolean directlyAbove) {
        BlockState state = context.get(x, y, z);
        Block block = state.getBlock();
        if (!directlyAbove
                && block instanceof FallingBlock
                && NavSettings.get().avoidUpdatingFallingBlocks
                && FallingBlock.isFree(context.get(x, y - 1, z))) {
            return true;
        }
        // 只按纯液体方块判(含水方块可能有封闭底面,不算)
        if (block instanceof LiquidBlock) {
            if (directlyAbove || NavSettings.get().strictLiquidCheck) {
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
     * 禁挖 → INF;否则 1/速度 + 附加罚金,再乘上下文乘数。
     * {@code includeFalling} 时向上递归叠加整根落沙柱的成本。
     */
    public static double getMiningDurationTicks(CalculationContext context, int x, int y, int z,
                                                BlockState state, boolean includeFalling) {
        if (!canWalkThrough(context, x, y, z, state)) {
            if (!state.getFluidState().isEmpty()) {
                return COST_INF;
            }
            double mult = context.breakCostMultiplierAt(x, y, z, state);
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
            result += context.breakBlockAdditionalCost;
            result *= mult;
            if (includeFalling) {
                BlockState above = context.get(x, y + 1, z);
                if (above.getBlock() instanceof FallingBlock) {
                    result += getMiningDurationTicks(context, x, y + 1, z, above, true);
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
        return isBlockNormalCube(state)
                || state.getBlock() == Blocks.GLASS
                || state.getBlock() instanceof StainedGlassBlock;
    }

    // ==================== 门 / 栅栏门通行 ====================

    /** 木门当下能否直接走过(玩家在门格里 → 不行)。 */
    public static boolean isDoorPassable(BlockGetter level, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) {
            return false;
        }
        BlockState state = level.getBlockState(doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }
        return isHorizontalBlockPassable(doorPos, state, playerPos, DoorBlock.OPEN);
    }

    /** 栅栏门当下能否走过(只看 OPEN)。 */
    public static boolean isGatePassable(BlockGetter level, BlockPos gatePos, BlockPos playerPos) {
        if (playerPos.equals(gatePos)) {
            return false;
        }
        BlockState state = level.getBlockState(gatePos);
        if (!(state.getBlock() instanceof FenceGateBlock)) {
            return true;
        }
        return state.getValue(FenceGateBlock.OPEN);
    }

    /**
     * 带朝向的门板通行判定:接近轴与门板朝向轴同向时,开着才能过;
     * 垂直时反而是关着才不挡路(门板收在格边)。
     */
    public static boolean isHorizontalBlockPassable(BlockPos blockPos, BlockState blockState,
                                                    BlockPos playerPos, BooleanProperty propertyOpen) {
        if (playerPos.equals(blockPos)) {
            return false;
        }
        var facing = blockState.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = blockState.getValue(propertyOpen);

        net.minecraft.core.Direction.Axis playerFacing;
        if (playerPos.north().equals(blockPos) || playerPos.south().equals(blockPos)) {
            playerFacing = net.minecraft.core.Direction.Axis.Z;
        } else if (playerPos.east().equals(blockPos) || playerPos.west().equals(blockPos)) {
            playerFacing = net.minecraft.core.Direction.Axis.X;
        } else {
            return true;
        }
        return (facing == playerFacing) == open;
    }

    // ==================== 危险格 ====================

    /** 绝不能走进去的格:任何流体、岩浆块、仙人掌、浆果丛、火等。 */
    public static boolean avoidWalkingInto(BlockState state) {
        Block block = state.getBlock();
        return !state.getFluidState().isEmpty()
                || block == Blocks.MAGMA_BLOCK
                || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH
                || block instanceof BaseFireBlock
                || block == Blocks.END_PORTAL
                || block == Blocks.COBWEB
                || block == Blocks.BUBBLE_COLUMN;
    }

    // ==================== 台阶 / 流体基础判定 ====================

    /** 占据下半格的台阶。 */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /** 是否为水(含流动态)。 */
    public static boolean isWater(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.WATER || f == Fluids.FLOWING_WATER;
    }

    /** 是否为岩浆(含流动态)。 */
    public static boolean isLava(BlockState state) {
        Fluid f = state.getFluidState().getType();
        return f == Fluids.LAVA || f == Fluids.FLOWING_LAVA;
    }

    /** 是否为任意液体。 */
    public static boolean isLiquid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /** 可能在流动:流体类且非满格。 */
    public static boolean possiblyFlowing(BlockState state) {
        FluidState fluidState = state.getFluidState();
        return fluidState.getType() instanceof FlowingFluid
                && fluidState.getAmount() != 8;
    }

    /**
     * 该格流体是否在流动:非满格即流动;满格源方块若四个水平邻格
     * 任一可能在流动(池边),也按流动处理。
     */
    public static boolean isFlowing(BlockGetter view, int x, int y, int z, BlockState state) {
        FluidState fluidState = state.getFluidState();
        if (!(fluidState.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluidState.getAmount() != 8) {
            return true;
        }
        return possiblyFlowing(view.getBlockState(new BlockPos(x + 1, y, z)))
                || possiblyFlowing(view.getBlockState(new BlockPos(x - 1, y, z)))
                || possiblyFlowing(view.getBlockState(new BlockPos(x, y, z + 1)))
                || possiblyFlowing(view.getBlockState(new BlockPos(x, y, z - 1)));
    }

    /**
     * 完整实心立方体判定:碰撞形状为满格,并排除一批形状异常/
     * 会动的方块(竹、活塞移动方块、脚手架、潜影盒、滴水石锥、
     * 紫水晶簇)。取形状抛异常时按 false。
     */
    public static boolean isBlockNormalCube(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof BambooStalkBlock
                || block instanceof MovingPistonBlock
                || block instanceof ScaffoldingBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof PointedDripstoneBlock
                || block instanceof AmethystClusterBlock) {
            return false;
        }
        try {
            return Block.isShapeFullBlock(state.getCollisionShape(null, null));
        } catch (Exception ignored) {
            // 拿不到碰撞形状的异类按非实心处理
        }
        return false;
    }

    // ==================== 行走朝向几何 ====================

    /** 方块中心点。 */
    public static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点;全部被遮挡返回 null。
     * 判定次序:
     * <ol>
     *   <li>沿当前实际视角的射线已命中该格 → 保持视线,直接返回命中点
     *       (已注视时不再回中,避免无谓转头);</li>
     *   <li>碰撞形状中心与六面心逐一试射:每个候选点先算理想转角,再按
     *       视角步进量化出"本 tick 实际能转到的转角",沿该转角射线——
     *       命中该格才算可达(没转到位的 tick 不误判可视)。</li>
     * </ol>
     * 触及距离取 {@link NavSettings#blockReachDistance}。
     */
    public static Vec3 reachableAimPoint(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        var level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = blockReachDistance(player);
        var state = level.getBlockState(pos);
        boolean fire = state.getBlock() instanceof BaseFireBlock;
        // 已注视捷径:沿当前视角的射线恰好命中该格才保持(严格等格)
        BlockHitResult looking = clipAlongRotation(player, player.getYRot(), player.getXRot(), reach);
        if (looking.getType() == HitResult.Type.BLOCK && looking.getBlockPos().equals(pos)) {
            return looking.getLocation();
        }
        // 首选取心:碰撞形状中点(无碰撞体退整格心);火取底面高度
        // (灭火看火的根部)。六面心按轮廓形状取(射线判定也是轮廓)。
        Vec3 center = collisionCenter(level, pos, state);
        VoxelShape outline = state.getShape(level, pos);
        if (outline.isEmpty()) {
            outline = Shapes.block();
        }
        Vec3[] aims = {
                center,
                shapePoint(pos, outline, 0.5, 0.0, 0.5),
                shapePoint(pos, outline, 0.5, 1.0, 0.5),
                shapePoint(pos, outline, 0.5, 0.5, 0.0),
                shapePoint(pos, outline, 0.5, 0.5, 1.0),
                shapePoint(pos, outline, 0.0, 0.5, 0.5),
                shapePoint(pos, outline, 1.0, 0.5, 0.5),
        };
        var aim = new com.dwinovo.numen.core.pathing.execute.AimProcessor();
        for (Vec3 aimPoint : aims) {
            Vec3 dir = aimPoint.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) {
                continue;
            }
            // 本 tick 实际能转到的视角,沿它试射;可达则返回候选点本身
            // (调用方以候选点为视角目标,后续 tick 向它收敛)
            var stepped = aim.step(player.getYRot(), player.getXRot(),
                    yawTo(eye, aimPoint), pitchTo(eye, aimPoint));
            BlockHitResult res = clipAlongRotation(player, stepped.yaw(), stepped.pitch(), reach);
            if (hitsTarget(res, pos, fire)) {
                return aimPoint;
            }
        }
        return null;
    }

    /** 命中判定:命中该格;目标是火时命中其下方支撑格也算(火焰轮廓极薄)。 */
    private static boolean hitsTarget(BlockHitResult res, BlockPos pos, boolean fire) {
        if (res.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return res.getBlockPos().equals(pos) || (fire && res.getBlockPos().equals(pos.below()));
    }

    /** 碰撞形状中点;无碰撞体取整格心;火把 y 压到格底(看火的根部)。 */
    public static Vec3 collisionCenter(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        double x = (shape.min(net.minecraft.core.Direction.Axis.X) + shape.max(net.minecraft.core.Direction.Axis.X)) / 2;
        double y = (shape.min(net.minecraft.core.Direction.Axis.Y) + shape.max(net.minecraft.core.Direction.Axis.Y)) / 2;
        double z = (shape.min(net.minecraft.core.Direction.Axis.Z) + shape.max(net.minecraft.core.Direction.Axis.Z)) / 2;
        if (state.getBlock() instanceof BaseFireBlock) {
            y = 0;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** 方块触及距离:创造 5.0,生存按设置(默认 4.5)。 */
    public static double blockReachDistance(net.minecraft.server.level.ServerPlayer player) {
        return player.isCreative() ? 5.0 : NavSettings.get().blockReachDistance;
    }

    /** 从眼位沿给定 yaw/pitch 的轮廓射线(不穿流体);方向向量按原版 float 三角。 */
    private static BlockHitResult clipAlongRotation(net.minecraft.server.level.ServerPlayer player,
                                                    float yaw, float pitch, double reach) {
        Vec3 eye = player.getEyePosition();
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = net.minecraft.util.Mth.cos(g);
        float i = net.minecraft.util.Mth.sin(g);
        float j = net.minecraft.util.Mth.cos(f);
        float k = net.minecraft.util.Mth.sin(f);
        Vec3 dir = new Vec3(i * j, -k, h * j);
        Vec3 end = eye.add(dir.scale(reach));
        return player.level().clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
    }

    /** 方块碰撞形状上按比例取点(m 为各轴的 min↔max 插值系数)。 */
    private static Vec3 shapePoint(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(net.minecraft.core.Direction.Axis.X) * mx
                + shape.max(net.minecraft.core.Direction.Axis.X) * (1 - mx);
        double y = shape.min(net.minecraft.core.Direction.Axis.Y) * my
                + shape.max(net.minecraft.core.Direction.Axis.Y) * (1 - my);
        double z = shape.min(net.minecraft.core.Direction.Axis.Z) * mz
                + shape.max(net.minecraft.core.Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** 从 from 看向 to 的 yaw(度,MC 朝向约定;用 Mth.atan2 多项式近似)。 */
    public static float yawTo(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return (float) Math.toDegrees(net.minecraft.util.Mth.atan2(dx, -dz));
    }

    /** 从 from 看向 to 的 pitch(度,向下为正;用 Mth.atan2 多项式近似)。 */
    public static float pitchTo(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dy = from.y - to.y;
        double dz = from.z - to.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.toDegrees(net.minecraft.util.Mth.atan2(dy, horizontal));
    }

    /**
     * 朝目标方块走:yaw 对准方块中心、pitch 保持现状(不强制转头),
     * 并按住前进。
     */
    public static void moveTowards(Player player, MovementState state, BlockPos pos) {
        float yaw = yawTo(player.getEyePosition(), blockCenter(pos));
        state.setTarget(new MovementState.MovementTarget(yaw, player.getXRot(), false));
        state.setInput(Input.MOVE_FORWARD, true);
    }
}
