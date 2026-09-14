package com.dwinovo.numen.core.pathing.spec;

import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.CauldronBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * 格子分类:成本模型看一格先归类,再按 {@link RouteSpec} 查这一类的代价与能力。类型表是
 * 引擎固定词汇,规格只改代价。{@link #of} 是全仓唯一的分类函数;可穿行 / 可站立 / 完全无
 * 阻碍 / 危险 / 流体 / 门这些判定全部从它派生,别处不得另写一份。
 *
 * <p>{@link #of} 只看 {@link BlockState},不分配对象(搜索热路径)。要看邻格的判定——地毯
 * 下面能不能站、水柱是不是在流——在 {@link #canWalkThrough}/{@link #canWalkOn} 的位置精判
 * 里做。含水方块按方块本身归类:被水淹的楼梯还是楼梯,水只在可穿行的格里才当水看。
 */
public enum CellClass {
    /** 身体可占据、不能站:空气、草、花、火把、告示牌等无碰撞方块。 */
    OPEN,
    /** 可站的实心面:整块、玻璃、箱子、耕地、土径、灵魂沙、杜鹃、上半与双层台阶、八层雪。 */
    GROUND,
    /** 楼梯:可站;碰撞不满格,原版寻路视为可通行。 */
    STAIRS,
    /** 下半台阶:可站,不可穿。 */
    BOTTOM_SLAB,
    /** 地毯:薄层,下面能站才可走过。 */
    CARPET,
    /** 雪层(不足八层):三层以下且下面能站才可走过。 */
    SNOW_LAYER,
    /** 木门与栅栏门:可穿(执行层右键开),不可站。铁门无红石打不开,归障碍。 */
    DOOR,
    /** 梯子:可穿可站。 */
    LADDER,
    /** 藤蔓:可穿;可站与否看 {@link RouteSpec#climbVines()}。 */
    VINE,
    /** 静水柱(源或竖直下落,含被水淹的可穿行方块):只能在水面游过、浮在有水的柱子里。 */
    WATER,
    /** 流动的水(非满格):不可穿,会被冲离路径;上方有水时仍可浮着。 */
    FLOWING,
    /** 岩浆(源或流动):不可穿;可站与否看代价表,出厂排除。 */
    LAVA,
    /** 绝不走进:火、蛛网、末地传送门、甜浆果丛、仙人掌、岩浆块、气泡柱。 */
    HAZARD,
    /** 其余不可穿不可站的东西(铁门、栅栏、活板门、潜影盒、坩埚等),挖得动才过。 */
    OBSTACLE;

    /** 唯一的分类函数。 */
    public static CellClass of(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof AirBlock) {
            return OPEN;
        }
        if (isHazard(b)) {
            return HAZARD;
        }
        if (b instanceof DoorBlock || b instanceof FenceGateBlock) {
            return b == Blocks.IRON_DOOR ? OBSTACLE : DOOR;
        }
        if (b instanceof CarpetBlock) {
            return CARPET;
        }
        if (b instanceof SnowLayerBlock) {
            return state.getValue(SnowLayerBlock.LAYERS) == 8 ? GROUND : SNOW_LAYER;
        }
        if (b == Blocks.LADDER) {
            return LADDER;
        }
        if (b == Blocks.VINE) {
            return VINE;
        }
        if (b instanceof StairBlock) {
            return STAIRS;
        }
        if (b instanceof SlabBlock) {
            return state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? BOTTOM_SLAB : GROUND;
        }
        if (b instanceof LiquidBlock) {
            return ofFluid(state.getFluidState());
        }
        if (isFullCube(state) && b != Blocks.HONEY_BLOCK) {
            return GROUND;
        }
        if (b instanceof AzaleaBlock
                || b == Blocks.FARMLAND || b == Blocks.DIRT_PATH || b == Blocks.SOUL_SAND
                || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.ENDER_CHEST
                || b == Blocks.GLASS || b instanceof StainedGlassBlock) {
            return GROUND;
        }
        // 碰撞形状空缺或残缺、身体却进不去的方块:原版形状判定会误放行,点名拦下
        if (b == Blocks.TRIPWIRE || b == Blocks.COCOA || b instanceof AbstractSkullBlock
                || b instanceof ShulkerBoxBlock || b == Blocks.HONEY_BLOCK || b == Blocks.END_ROD
                || b == Blocks.POINTED_DRIPSTONE || b instanceof AmethystClusterBlock
                || b == Blocks.BIG_DRIPLEAF || b == Blocks.POWDER_SNOW
                || b instanceof TrapDoorBlock || b instanceof CauldronBlock) {
            return OBSTACLE;
        }
        if (!state.isPathfindable(PathComputationType.LAND)) {
            return OBSTACLE;
        }
        FluidState fluid = state.getFluidState();
        return fluid.isEmpty() ? OPEN : ofFluid(fluid);
    }

    private static CellClass ofFluid(FluidState fluid) {
        if (fluid.getType() instanceof LavaFluid) {
            return LAVA;
        }
        if (!(fluid.getType() instanceof WaterFluid)) {
            return OBSTACLE;
        }
        return fluid.getAmount() == 8 ? WATER : FLOWING;
    }

    private static boolean isHazard(Block b) {
        return b instanceof BaseFireBlock || b == Blocks.COBWEB || b == Blocks.END_PORTAL
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK
                || b == Blocks.BUBBLE_COLUMN;
    }

    // ==================== 可穿行(身体能否占据该格) ====================

    /** 实时世界(chunk 视为全部已加载)。 */
    public static boolean canWalkThrough(BlockGetter level, BlockPos pos, RouteSpec spec) {
        return canWalkThrough(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos), spec);
    }

    public static boolean canWalkThrough(BlockGetter view, ChunkLoadedTest loaded,
                                         int x, int y, int z, BlockState state, RouteSpec spec) {
        CellClass cls = of(state);
        if (spec.cellCost(cls) >= RouteSpec.FORBID) {
            return false;
        }
        PositionCosts positions = spec.positions();
        if (!positions.isEmpty() && positions.pass(BlockPos.asLong(x, y, z)) >= RouteSpec.FORBID) {
            return false;
        }
        switch (cls) {
            case OPEN, DOOR, LADDER, VINE, STAIRS:
                return true;
            case CARPET:
                return canWalkOn(view, loaded, x, y - 1, z, spec);
            case SNOW_LAYER:
                // 原版可通行判定是 <5 层;但 2 格高净空里 ≥3 层就挤不过去了
                if (state.getValue(SnowLayerBlock.LAYERS) >= 3) {
                    return false;
                }
                return canWalkOn(view, loaded, x, y - 1, z, spec);
            case WATER: {
                if (isFlowing(view, x, y, z, state)) {
                    return false; // 池边的源方块也在流,水流会把人冲离路径
                }
                BlockState up = view.getBlockState(new BlockPos(x, y + 1, z));
                // 上方还有流体/睡莲,穿过去等于潜水
                return up.getFluidState().isEmpty() && !(up.getBlock() instanceof WaterlilyBlock);
            }
            default:
                return false;
        }
    }

    // ==================== 完全无阻碍(可跳跃穿过) ====================

    /**
     * 比可穿行更严:不含需要右键的门、不含减速的藤/梯/蛛网、不含任何流体、不含雪层。
     * 用于跑酷与头顶净空检查。地毯薄到不碍事。
     */
    public static boolean fullyPassable(BlockState state) {
        CellClass cls = of(state);
        return cls == OPEN || cls == CARPET;
    }

    public static boolean fullyPassable(BlockGetter level, BlockPos pos) {
        return fullyPassable(level.getBlockState(pos));
    }

    // ==================== 可站立(能否作为脚下地面) ====================

    /** 实时世界(chunk 视为全部已加载)。 */
    public static boolean canWalkOn(BlockGetter level, BlockPos pos, RouteSpec spec) {
        return canWalkOn(level, ChunkLoadedTest.ALWAYS,
                pos.getX(), pos.getY(), pos.getZ(), level.getBlockState(pos), spec);
    }

    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded,
                                    int x, int y, int z, RouteSpec spec) {
        return canWalkOn(view, loaded, x, y, z, view.getBlockState(new BlockPos(x, y, z)), spec);
    }

    /**
     * 水的"游泳位"语义:只能站在上方还有水的水格里(浮在水柱中),盖着睡莲或地毯的
     * 水面也算;流动的水同理。岩浆只在规格给了有限代价、且不在流动时才当地面。
     */
    public static boolean canWalkOn(BlockGetter view, ChunkLoadedTest loaded,
                                    int x, int y, int z, BlockState state, RouteSpec spec) {
        CellClass cls = of(state);
        if (spec.cellCost(cls) >= RouteSpec.FORBID) {
            return false;
        }
        PositionCosts positions = spec.positions();
        if (!positions.isEmpty() && positions.stand(BlockPos.asLong(x, y, z)) >= RouteSpec.FORBID) {
            return false;
        }
        if (spec.bans().standingOn().contains(state.getBlock())) {
            return false;
        }
        switch (cls) {
            case GROUND, STAIRS, LADDER, BOTTOM_SLAB:
                return true;
            case VINE:
                return spec.climbVines();
            case WATER, FLOWING: {
                BlockState up = view.getBlockState(new BlockPos(x, y + 1, z));
                return up.getBlock() == Blocks.LILY_PAD || up.getBlock() instanceof CarpetBlock
                        || isWater(up);
            }
            case LAVA:
                return !isFlowing(view, x, y, z, state);
            default:
                return false;
        }
    }

    // ==================== 危险格 ====================

    /** 绝不能走进去的格:危险类,以及任何含流体的格(水流会把人冲走)。 */
    public static boolean avoidWalkingInto(BlockState state) {
        return isHazard(state.getBlock()) || !state.getFluidState().isEmpty();
    }

    // ==================== 门 / 栅栏门通行 ====================

    /**
     * 木门当下能否直接走过:玩家在门格里不行;接近轴与门板朝向轴同向时开着才能过,
     * 垂直时反而是关着才不挡路(门板收在格边)。
     */
    public static boolean isDoorPassable(BlockGetter level, BlockPos doorPos, BlockPos playerPos) {
        if (playerPos.equals(doorPos)) {
            return false;
        }
        BlockState state = level.getBlockState(doorPos);
        if (!(state.getBlock() instanceof DoorBlock)) {
            return true;
        }
        Direction.Axis facing = state.getValue(HorizontalDirectionalBlock.FACING).getAxis();
        boolean open = state.getValue(DoorBlock.OPEN);
        Direction.Axis approach;
        if (playerPos.north().equals(doorPos) || playerPos.south().equals(doorPos)) {
            approach = Direction.Axis.Z;
        } else if (playerPos.east().equals(doorPos) || playerPos.west().equals(doorPos)) {
            approach = Direction.Axis.X;
        } else {
            return true;
        }
        return (facing == approach) == open;
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

    // ==================== 台阶 / 流体 / 实心基础判定 ====================

    /** 占据下半格的台阶。 */
    public static boolean isBottomSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock
                && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
    }

    /** 是否为水(含流动态、含被水淹的方块)。 */
    public static boolean isWater(BlockState state) {
        return state.getFluidState().getType() instanceof WaterFluid;
    }

    /** 是否为岩浆(含流动态)。 */
    public static boolean isLava(BlockState state) {
        return state.getFluidState().getType() instanceof LavaFluid;
    }

    /** 是否含任意流体。 */
    public static boolean isLiquid(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    /**
     * 该格流体是否在流动:非满格即流动;满格源方块若四个水平邻格任一非满格(池边),
     * 也按流动处理。
     */
    public static boolean isFlowing(BlockGetter view, int x, int y, int z, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (!(fluid.getType() instanceof FlowingFluid)) {
            return false;
        }
        if (fluid.getAmount() != 8) {
            return true;
        }
        return isPartial(view.getBlockState(new BlockPos(x + 1, y, z)))
                || isPartial(view.getBlockState(new BlockPos(x - 1, y, z)))
                || isPartial(view.getBlockState(new BlockPos(x, y, z + 1)))
                || isPartial(view.getBlockState(new BlockPos(x, y, z - 1)));
    }

    private static boolean isPartial(BlockState state) {
        FluidState fluid = state.getFluidState();
        return fluid.getType() instanceof FlowingFluid && fluid.getAmount() != 8;
    }

    /**
     * 完整实心立方体:碰撞形状为满格,并排除一批形状异常/会动的方块(竹、活塞移动方块、
     * 脚手架、潜影盒、滴水石锥、紫水晶簇)。取形状抛异常时按非实心。
     */
    public static boolean isFullCube(BlockState state) {
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
            // 形状依赖世界与坐标的异类拿不到碰撞形状,按非实心处理
        }
        return false;
    }
}
