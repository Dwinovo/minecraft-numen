package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Recall;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * 身体在一个节点上是怎么待着的:站着、攀着,还是浮着。节点 {@code (x, y, z)} 是脚所在的那一格(第 0 层 {@link Footing#cellOf}),
 * 只有身体待得住的节点才进搜索;待不待得住全部问第 0 层。
 *
 * <ul>
 *   <li>{@link Kind#GROUND 站着}:这一格里有东西托住脚({@link Footing#height}),身体在那个脚高上放得下({@link Clearance});</li>
 *   <li>{@link Kind#CLIMBING 攀着}:没东西托,但脚所在的格能攀爬(原版 {@code onClimbable} 看的就是这一格),身体放得下;</li>
 *   <li>{@link Kind#SWIMMING 浮着}:没东西托,但脚所在的格是水,身体放得下;</li>
 *   <li>{@link Kind#FLYING 飞着}:创造飞行,浮在空中。不是世界单独就量得出的站姿——只有这一格没东西托、不是水与梯子、身体在脚正好是这一格底时放得下
 *       ({@link #afloat}),才待得住;只有飞着的身体与起飞、飞行、降落三种走法交出它。</li>
 * </ul>
 * 站着优先:梯子底下有地、浅水底下有地时,身体是站着的,它同时也可以攀、可以游。一格要么待得住站着、攀着、浮着,要么只有飞着才待得住,
 * 同一格的节点只有一种待法,所以"在飞"不用另占节点的键。
 *
 * @param feetY    脚的绝对高度;攀着、浮着和飞着时取这一格的底
 * @param supportY 站着时托住脚的那一格的 y(第 0 层 {@link Footing#supportY});攀着、浮着和飞着时为 {@link Integer#MIN_VALUE}
 */
public record Stance(Kind kind, double feetY, int supportY) {

    public enum Kind {
        GROUND, CLIMBING, SWIMMING, FLYING
    }

    /**
     * 站姿,一次搜索里按格记住({@link Recall})。读的格:这一列从脚下一格({@code y - 1},托脚的碰撞箱可能在那里)到身体占的最高一格
     * ({@code y + 2},脚高不到 {@code y + 1}、身高 1.8),穿冰霜行者时水面上那一格再往上一格({@code y + 3})。
     */
    private static final Recall.Fact<Stance> STANCE = new Recall.Fact<>(Stance::measure, new Recall.Reach(1, 3));

    /** 飞着停在一格里,脚离这一格底的高度可以在 {@code [0, HOVER_SPAN)}。 */
    public static final double HOVER_SPAN = 0.4;

    /** 身体在 {@code (x, y, z)} 这个节点上怎么待着;待不住为 null。 */
    public static Stance at(BlockGetter level, BodyStats body, int x, int y, int z) {
        return STANCE.at(level, body, x, y, z);
    }

    /**
     * 飞着的身体在这个节点上怎么待着:世界里待得住站着、攀着、浮着的就是那样,否则脚正好在这一格底、身体放得下就是飞着({@link #afloat});都不是为 null。
     * 不在飞的身体问 {@link #at(BlockGetter, BodyStats, int, int, int)}。
     */
    public static Stance at(BlockGetter level, BodyStats body, BlockPos node, boolean flying) {
        Stance stance = at(level, body, node);
        return stance != null || !flying ? stance : afloat(level, body, node.getX(), node.getY(), node.getZ());
    }

    /**
     * 浮在空中:这一格世界里待不住(没东西托、不是水也不是梯子),而身体在脚在这一格底、与脚高出底 {@link #HOVER_SPAN} 两处都放得下。飞着升降的最小一步
     * (按一刻跳或潜行)是 0.375 格,脚停不到正好某个高度:脚落在这一格里 {@code [y, y + HOVER_SPAN)} 的哪里都放得下,飞着停在这一格才有余地。
     */
    public static Stance afloat(BlockGetter level, BodyStats body, int x, int y, int z) {
        if (at(level, body, x, y, z) != null || !Clearance.fits(level, body, x, y, z)
                || !Clearance.fits(level, body, x, y + HOVER_SPAN, z)) {
            return null;
        }
        return new Stance(Kind.FLYING, y, Integer.MIN_VALUE);
    }

    private static Stance measure(BlockGetter level, BodyStats body, int x, int y, int z) {
        double feet = Footing.height(level, body, x, y, z);
        if (!Double.isNaN(feet) && Clearance.fits(level, body, x, feet, z)) {
            return new Stance(Kind.GROUND, feet, Footing.supportY(level, body, x, y, z));
        }
        BlockPos feetCell = new BlockPos(x, y, z);
        if (hangs(level, feetCell) && Clearance.fits(level, body, x, y, z)) {
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
        int step = Clearance.fits(level, body, pos.getX(), pos.getY(), pos.getZ()) ? -1 : 1;
        for (int y = pos.getY() + step; y >= level.getMinY() && y <= level.getMaxY(); y += step) {
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
