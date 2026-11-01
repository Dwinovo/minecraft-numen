package com.dwinovo.numen.pathing.benchmark;

import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 中立地形格式的类别表(GRAV3D 规则版本 {@value #RULES_VERSION}):一格一个 uint8 类别码,圈外的人只靠这张表就能读懂地形,
 * 不需要 Minecraft。类别码、标志与说明是导出的 {@code classes.json} 的唯一来源;求解器与验证器读那份文件,不读这里。
 *
 * <p>方块状态到类别由 {@link #of} 算出:碰撞形状取原版的(碰撞高度 ≥ 0.5 算实心,否则算空气),挖掘档按原版硬度,
 * 液体、攀爬、门、危险这些语义取 {@link Semantics}。只看方块状态本身,不看邻格:所以"静水"是水源方块,
 * 流动的水归禁入;被水淹的梯子仍是梯子。
 */
final class Neutral {

    static final String RULES_VERSION = "grav3d-1.0";

    /** 挖掘档的硬度分界:原版方块的硬度(破坏速度)小于 1.5 的归软,小于 3 的归石,其余归硬;负数(基岩)挖不动。 */
    private static final float SOFT_BELOW = 1.5F;
    private static final float STONE_BELOW = 3F;
    /** 碰撞高度到这个值算实心;超过一格(栅栏、墙)是高障碍。 */
    private static final double SOLID_FROM = 0.5;

    /** 一个类别:码(声明顺序)、名字、标志(含义见 RULES.md)与一句说明。 */
    enum Cls {
        AIR("air", true, false, false, false, 0, true, "空气,含碰撞不到半格的植物、地毯、雪层等可穿过的东西"),
        SOFT("soft", false, true, false, false, 1, false, "实心,软:土、沙、叶、玻璃、雪块……(硬度 < 1.5)"),
        STONE("stone", false, true, false, false, 2, false, "实心,石:石头、圆石、砖、原木与木板(硬度 1.5 到 3,不含 3)"),
        HARD("hard", false, true, false, false, 3, false, "实心,硬:深板岩、各种矿、黑曜石……(硬度 ≥ 3)"),
        BEDROCK("bedrock", false, true, false, false, 0, false, "不可挖的实心:基岩等硬度为负的方块"),
        WATER("water", true, false, true, false, 0, true, "静水:水源方块,含海草、海带这类自带水源的方块"),
        FORBIDDEN("forbidden", false, false, false, false, 0, false,
                "禁入:岩浆、火、仙人掌、细雪、蜘蛛网、流动的水、气泡柱等;进不去、站不上、挖不了、放不了"),
        CLIMB("climb", true, false, false, true, 0, false, "可攀爬:梯子、藤蔓、脚手架"),
        DOOR("door", true, false, false, false, 0, false, "门:身体能用手打开的门、活板门、栅栏门;可通行,站不上"),
        TALL("tall", false, false, false, false, 1, false, "高障碍:栅栏、墙等碰撞高过一格的方块;挡路、站不上");

        final String key;
        final boolean passable;
        final boolean support;
        final boolean swim;
        final boolean climb;
        /** 挖开它用第几档的挖掘代价;0 是挖不动。 */
        final int dig;
        /** 能往里放一块方块(搭桥、垫柱)。 */
        final boolean place;
        final String about;

        Cls(String key, boolean passable, boolean support, boolean swim, boolean climb, int dig, boolean place, String about) {
            this.key = key;
            this.passable = passable;
            this.support = support;
            this.swim = swim;
            this.climb = climb;
            this.dig = dig;
            this.place = place;
            this.about = about;
        }

        int code() {
            return ordinal();
        }
    }

    private Neutral() {}

    /** 一个方块状态属于哪个类别。 */
    static Cls of(BlockState state) {
        if (state.isAir()) {
            return Cls.AIR;
        }
        int kinds = Semantics.stateMask(state);
        if ((kinds & (Semantics.bit(Semantics.Kind.LAVA) | Semantics.bit(Semantics.Kind.HAZARD))) != 0 || state.is(Blocks.BUBBLE_COLUMN)) {
            return Cls.FORBIDDEN;
        }
        if ((kinds & Semantics.bit(Semantics.Kind.CLIMBABLE)) != 0) {
            return Cls.CLIMB;
        }
        if ((kinds & Semantics.bit(Semantics.Kind.DOOR)) != 0) {
            return Cls.DOOR;
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        double top = shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
        if (top > 1.0) {
            return Cls.TALL;
        }
        if (top >= SOLID_FROM) {
            float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (hardness < 0) {
                return Cls.BEDROCK;
            }
            return hardness < SOFT_BELOW ? Cls.SOFT : hardness < STONE_BELOW ? Cls.STONE : Cls.HARD;
        }
        if (Semantics.wet(state)) {
            return state.getFluidState().isSource() ? Cls.WATER : Cls.FORBIDDEN;
        }
        return Cls.AIR;
    }
}
