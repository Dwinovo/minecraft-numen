package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Recall;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;

/**
 * 身体在一个节点上是怎么待着的:站着、攀着,还是浮着。节点 {@code (x, y, z)} 是脚所在的那一格(第 0 层 {@link Footing#cellOf}),
 * 只有身体待得住的节点才进搜索;待不待得住全部问第 0 层。
 *
 * <ul>
 *   <li>{@link Kind#GROUND 站着}:这一格里有东西托住脚({@link Footing#height}),身体在那个脚高上放得下({@link Clearance});</li>
 *   <li>{@link Kind#CLIMBING 攀着}:没东西托,但脚所在的格能攀爬(原版 {@code onClimbable} 看的就是这一格),身体放得下;</li>
 *   <li>{@link Kind#SWIMMING 浮着}:没东西托,但脚所在的格是水,身体放得下。</li>
 * </ul>
 * 站着优先:梯子底下有地、浅水底下有地时,身体是站着的,它同时也可以攀、可以游。
 *
 * @param feetY    脚的绝对高度;攀着和浮着时取这一格的底
 * @param supportY 站着时托住脚的那一格的 y(第 0 层 {@link Footing#supportY});攀着和浮着时为 {@link Integer#MIN_VALUE}
 */
public record Stance(Kind kind, double feetY, int supportY) {

    public enum Kind {
        GROUND, CLIMBING, SWIMMING
    }

    /** 站姿,一次搜索里按格记住({@link Recall})。 */
    private static final Recall.Fact<Stance> STANCE = new Recall.Fact<>(Stance::measure);

    /** 身体在 {@code (x, y, z)} 这个节点上怎么待着;待不住为 null。 */
    public static Stance at(BlockGetter level, BodyStats body, int x, int y, int z) {
        return STANCE.at(level, body, x, y, z);
    }

    private static Stance measure(BlockGetter level, BodyStats body, int x, int y, int z) {
        double feet = Footing.height(level, body, x, y, z);
        if (!Double.isNaN(feet) && Clearance.fits(level, body, Pose.STANDING, x, feet, z)) {
            return new Stance(Kind.GROUND, feet, Footing.supportY(level, body, x, y, z));
        }
        BlockPos feetCell = new BlockPos(x, y, z);
        if (hangs(level, feetCell) && Clearance.fits(level, body, Pose.STANDING, x, y, z)) {
            boolean climbable = Semantics.is(level, feetCell, Semantics.Kind.CLIMBABLE);
            return new Stance(climbable ? Kind.CLIMBING : Kind.SWIMMING, y, Integer.MIN_VALUE);
        }
        return null;
    }

    /** 脚所在的这一格没东西托也待得住身体:能攀爬,或是水。 */
    static boolean hangs(BlockGetter level, BlockPos feet) {
        return Semantics.is(level, feet, Semantics.Kind.CLIMBABLE) || Strides.inWater(level, feet);
    }

    public static Stance at(BlockGetter level, BodyStats body, BlockPos node) {
        return at(level, body, node.getX(), node.getY(), node.getZ());
    }

    /**
     * {@code pos} 所在那一列里身体待得住的节点:这一格待得住就是它;在半空(放得下身体却没东西托着)就往下找,埋在方块里
     * (放不下身体)就往上找,取第一个待得住的;那一列都待不住,就还是 {@code pos}。
     */
    public static BlockPos settle(BlockGetter level, BodyStats body, BlockPos pos) {
        if (at(level, body, pos) != null) {
            return pos;
        }
        int step = Clearance.fits(level, body, Pose.STANDING, pos.getX(), pos.getY(), pos.getZ()) ? -1 : 1;
        for (int y = pos.getY() + step; y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight(); y += step) {
            BlockPos node = new BlockPos(pos.getX(), y, pos.getZ());
            if (at(level, body, node) != null) {
                return node;
            }
        }
        return pos;
    }

    public boolean grounded() {
        return kind == Kind.GROUND;
    }

    /** 托住脚的那一格;不是站着时为 null。 */
    public BlockPos support(int x, int z) {
        return grounded() ? new BlockPos(x, supportY, z) : null;
    }
}
