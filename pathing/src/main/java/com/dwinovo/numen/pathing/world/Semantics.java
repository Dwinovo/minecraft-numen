package com.dwinovo.numen.pathing.world;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

/**
 * 碰撞箱表达不了的语义:一格是什么,而不是它占多大地方。能不能站、能不能过、要不要跳一律由碰撞箱推导
 * ({@link Footing}、{@link Clearance}、{@link Stepping}),不进这里;这里只回答几何回答不了的事——身体泡进去会怎样、
 * 碰了会怎样、能不能被身体改变。
 *
 * <p>一格可以同时是几种({@link Kind}):被水淹的梯子既是水又可攀爬,岩浆既是岩浆又是危险。多数种类只由方块状态决定,
 * 按状态缓存;流水与"开着的活板门接在梯子上"要看邻格,按坐标现算。
 *
 * <p>碰撞箱随世界或身体变化的方块也在这里点明({@link #dynamicCollision}),几何那几个类据此对它们不走缓存。
 */
public final class Semantics {

    /** 一格的语义种类。路线规格按种类排除格子,用的就是这张表。 */
    public enum Kind {
        /** 不流动的水:源头与静止的水体,身体泡在里面不被推走。 */
        WATER,
        /**
         * 流动的水:身体会被推着走——原版算出的水流不为零的(流淌的水,也包括池边那一圈源头),以及把身体往上托、往下拽的气泡柱。
         */
        FLOWING_WATER,
        /** 岩浆,流不流都是。 */
        LAVA,
        /** 身体在里面能攀爬:{@code #minecraft:climbable},以及原版认作梯子的"开着、接在同向梯子上的活板门"。 */
        CLIMBABLE,
        /** 身体能用手打开或关上的门、栅栏门、活板门。铁门、铁活板门不在其列,它们开没开只看真实状态。 */
        DOOR,
        /**
         * 碰了掉血、被困住或被传走:火、营火、岩浆、岩浆块、仙人掌、甜浆果丛、凋灵玫瑰、细雪、蜘蛛网、末地传送门与折跃门。
         * 朝上的滴水石锥不在其列:原版它没有碰了就伤身的机制,站上去、走过去都不疼,只在落上去时加重摔伤
         * ({@code PointedDripstoneBlock.fallOn}),那归摔伤算({@code BodySnapshot.fallDamage})。
         */
        HAZARD,
        /** 下面空了会掉下来的方块:沙子、沙砾、混凝土粉末、铁砧等。 */
        FALLING,
        /** 身体一碰就触发的机关:压力板、绊线。 */
        TRIGGER,
        /** 站上去会被踩坏:耕地、海龟蛋。 */
        FRAGILE
    }

    private static final Kind[] KINDS = Kind.values();
    /** 只由方块状态决定的那几种,按状态缓存成位掩码。流水与活板门梯子要看邻格,不在其中。 */
    private static final ConcurrentHashMap<BlockState, Integer> STATE_KINDS = new ConcurrentHashMap<>();

    private Semantics() {}

    /** 这一格是不是某一种。 */
    public static boolean is(BlockGetter level, BlockPos pos, Kind kind) {
        return (mask(level, pos) & bit(kind)) != 0;
    }

    /** 这一格是 {@code kinds} 里的任何一种。 */
    public static boolean isAny(BlockGetter level, BlockPos pos, Set<Kind> kinds) {
        return (mask(level, pos) & mask(kinds)) != 0;
    }

    /** 这一格的全部种类;交出的是一份新的集合。 */
    public static Set<Kind> kinds(BlockGetter level, BlockPos pos) {
        int bits = mask(level, pos);
        EnumSet<Kind> out = EnumSet.noneOf(Kind.class);
        for (Kind kind : KINDS) {
            if ((bits & bit(kind)) != 0) {
                out.add(kind);
            }
        }
        return out;
    }

    /**
     * 这一格的全部种类,一种一位(第 {@code kind.ordinal()} 位)。规划里一步要问几十格,按位与一个种类掩码({@link #mask(Set)})
     * 就答完,不建集合。
     */
    public static int mask(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        int bits = STATE_KINDS.computeIfAbsent(state, Semantics::stateKinds);
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) {
            // 身体被不被推走,以原版对这一格算出的水流为准:池边的源头也在流;气泡柱的水是源头,却竖着推人
            boolean flowing = state.is(Blocks.BUBBLE_COLUMN) || fluid.getFlow(level, pos).lengthSqr() > 0;
            bits |= bit(flowing ? Kind.FLOWING_WATER : Kind.WATER);
        }
        if (state.getBlock() instanceof TrapDoorBlock && ladderTrapdoor(level, pos, state)) {
            bits |= bit(Kind.CLIMBABLE);
        }
        return bits;
    }

    private static int stateKinds(BlockState state) {
        Block block = state.getBlock();
        int bits = 0;
        if (state.getFluidState().is(FluidTags.LAVA)) {
            bits |= bit(Kind.LAVA);
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            bits |= bit(Kind.CLIMBABLE);
        }
        if (openableByHand(state)) {
            bits |= bit(Kind.DOOR);
        }
        // 着火的方块沿用原版寻路的同一个判据(火、营火、岩浆、岩浆块、岩浆炼药锅)
        if (NodeEvaluator.isBurningBlock(state) || block == Blocks.CACTUS || block == Blocks.SWEET_BERRY_BUSH
                || block == Blocks.WITHER_ROSE || block == Blocks.POWDER_SNOW || block == Blocks.COBWEB
                || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) {
            bits |= bit(Kind.HAZARD);
        }
        if (block instanceof FallingBlock) {
            bits |= bit(Kind.FALLING);
        }
        if (block instanceof BasePressurePlateBlock || block instanceof TripWireBlock) {
            bits |= bit(Kind.TRIGGER);
        }
        if (block instanceof FarmBlock || block instanceof TurtleEggBlock) {
            bits |= bit(Kind.FRAGILE);
        }
        return bits;
    }

    /** 一种一位的掩码,与 {@link #mask(BlockGetter, BlockPos)} 同一种编法。 */
    public static int bit(Kind kind) {
        return 1 << kind.ordinal();
    }

    /** 这些种类合成的掩码。 */
    public static int mask(Set<Kind> kinds) {
        int mask = 0;
        for (Kind kind : kinds) {
            mask |= bit(kind);
        }
        return mask;
    }

    /** 原版 {@code LivingEntity.trapdoorUsableAsLadder}:开着的活板门,正下方是朝向相同的梯子。 */
    private static boolean ladderTrapdoor(BlockGetter level, BlockPos pos, BlockState trapdoor) {
        if (!trapdoor.getValue(TrapDoorBlock.OPEN)) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        return below.is(Blocks.LADDER)
                && below.getValue(LadderBlock.FACING) == trapdoor.getValue(TrapDoorBlock.FACING);
    }

    // ==================== 门 ====================

    /**
     * 身体能不能用手开关它:木门与木活板门(原版按方块组的 {@code canOpenByHand})、所有栅栏门。铁门、铁活板门不能,
     * 它们开没开只看真实状态,几何照真实状态算。
     */
    public static boolean openableByHand(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        if (block instanceof TrapDoorBlock) {
            return trapdoorOpenableByHand(state);
        }
        return block instanceof FenceGateBlock;
    }

    /** 原版 {@code TrapDoorBlock} 的方块组是受保护的,按同一个判据:铁活板门之外的活板门都能用手开。 */
    private static boolean trapdoorOpenableByHand(BlockState state) {
        return !state.is(Blocks.IRON_TRAPDOOR);
    }

    /**
     * 身体开关一下之后的状态。门的上下两半由原版一起翻转,每一半都用这个求自己的新状态;几何按新状态从碰撞箱推导。
     *
     * @throws IllegalArgumentException 不是身体能用手开关的方块
     */
    public static BlockState toggled(BlockState state) {
        if (!openableByHand(state)) {
            throw new IllegalArgumentException("身体开关不了 " + state);
        }
        return state.cycle(BlockStateProperties.OPEN);
    }

    // ==================== 碰撞箱随世界或身体变化 ====================

    /**
     * 碰撞箱随世界或身体变化的方块:原版把它们标成 dynamic shape,不给缓存碰撞箱。1.21.1 里是这六种——
     * 脚手架(身体在它上面且没下蹲才托得住)、细雪(只托得住穿皮靴的实体与下落中的方块)、竹子与滴水石锥
     * (碰撞箱按坐标偏移)、潜影盒(开盖时变高)、移动中的活塞(形状在方块实体里)。模组方块碰撞箱带偏移的,原版要求
     * 同样标上。几何那几个类对这些不走缓存,按坐标与身体的脚高向原版现问。
     */
    public static boolean dynamicCollision(BlockState state) {
        return state.getBlock().hasDynamicShape();
    }

    // ==================== 起跳与步速 ====================

    /**
     * 脚在 {@code feetY} 时脚下方块的起跳系数,照原版 {@code Entity.getBlockJumpFactor}:先看脚所在那一格的方块,
     * 它不改起跳才看脚下半格处的方块。蜂蜜块是 0.5,其余原版方块是 1。
     */
    public static double jumpFactor(BlockGetter level, int x, double feetY, int z) {
        float here = level.getBlockState(new BlockPos(x, Mth.floor(feetY), z)).getBlock().getJumpFactor();
        if (here != 1.0F) {
            return here;
        }
        return level.getBlockState(new BlockPos(x, Mth.floor(feetY - 0.500001), z)).getBlock().getJumpFactor();
    }

    /**
     * 脚在 {@code feetY} 时脚下方块的步速系数,照原版 {@code Entity.getBlockSpeedFactor}:脚所在那一格是水或气泡柱时就用它,
     * 否则它不改步速才看脚下半格处的方块。灵魂沙、蜂蜜块是 0.4,其余原版方块是 1。
     */
    public static double speedFactor(BlockGetter level, int x, double feetY, int z) {
        BlockState here = level.getBlockState(new BlockPos(x, Mth.floor(feetY), z));
        float factor = here.getBlock().getSpeedFactor();
        if (here.is(Blocks.WATER) || here.is(Blocks.BUBBLE_COLUMN) || factor != 1.0F) {
            return factor;
        }
        return level.getBlockState(new BlockPos(x, Mth.floor(feetY - 0.500001), z)).getBlock().getSpeedFactor();
    }

    // ==================== 眼睛 ====================

    /**
     * 眼睛在 {@code (x, eyeY, z)} 时是不是泡在水里,照原版 {@code Entity.updateFluidOnEyes}:眼睛所在那一格的水面高过眼睛。
     * 水下挖掘变慢看的就是它。
     */
    public static boolean eyeInWater(BlockGetter level, double x, double eyeY, double z) {
        BlockPos pos = BlockPos.containing(x, eyeY, z);
        FluidState fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && pos.getY() + fluid.getHeight(level, pos) > eyeY;
    }

    /**
     * 眼睛在 {@code (x, eyeY, z)} 时换不了气,照原版 {@code LivingEntity.baseTick}:眼睛泡在水里({@link #eyeInWater}),
     * 而眼睛所在那一格不是气泡柱——气泡柱里原版不扣氧气,照常回气。憋气按它算。
     */
    public static boolean breathless(BlockGetter level, double x, double eyeY, double z) {
        return eyeInWater(level, x, eyeY, z)
                && !level.getBlockState(BlockPos.containing(x, eyeY, z)).is(Blocks.BUBBLE_COLUMN);
    }
}
