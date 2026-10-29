package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Stepping;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/** 几种走法共用的小事:身体要腾出的格、泡没泡在水里、脚下的步速、每格走多久。 */
final class Strides {

    private Strides() {}

    /** 身体脚在 {@code feetY} 站在 {@code to} 那一列时挡着它的格,自下而上。 */
    static List<BlockPos> at(BlockGetter level, BodyStats body, BlockPos to, double feetY) {
        return Clearance.blockers(level, body, to.getX(), feetY, to.getZ());
    }

    /** 身体脚在 {@code feetY},从 {@code from} 那一列朝 {@code heading} 走进相邻一列,途中挡着它的格,自下而上。 */
    static List<BlockPos> across(BlockGetter level, BodyStats body, BlockPos from, Heading heading, double feetY) {
        return Clearance.blockers(level, body, from.getX(), feetY, from.getZ(), heading.dx(), heading.dz());
    }

    /** 几份挡路的格合起来,去重后自下而上。 */
    @SafeVarargs
    static List<BlockPos> union(List<BlockPos>... lists) {
        List<BlockPos> out = new ArrayList<>();
        for (List<BlockPos> list : lists) {
            for (BlockPos cell : list) {
                if (!out.contains(cell)) {
                    out.add(cell);
                }
            }
        }
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }

    /** 脚所在的这一格有液体(水、岩浆、含水的方块):站在里面跳不起来。 */
    static boolean feetInFluid(BlockGetter level, BlockPos feet) {
        return !level.getFluidState(feet).isEmpty();
    }

    /** 水:静的与流动的。 */
    private static final java.util.EnumSet<Kind> WATERS = java.util.EnumSet.of(Kind.WATER, Kind.FLOWING_WATER);

    /** 脚所在的这一格泡在水里。 */
    static boolean inWater(BlockGetter level, BlockPos feet) {
        return Semantics.isAny(level, feet, WATERS);
    }

    /**
     * 泡在水里的身体从 {@code from} 这个节点出水,脚最高能上到多高:从 {@code from} 往上数到这一列水面(最上面一格水的液面),再加迈步高度。
     * 依据原版 {@code LivingEntity.travel}:水里顶着岸沿、头上有空处时每刻往上蹿 0.3,蹿出水面后靠冲劲再升一小截,实测(顶着岸沿按住跳往前)
     * 脚升到水面之上 0.7 格左右;迈步高度(0.6)是留了余量的取值。岸沿(落点脚高)更高的,游不上去。
     */
    static double exitCeiling(BlockGetter level, BodyStats body, BlockPos from) {
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos(from.getX(), from.getY(), from.getZ());
        while (inWater(level, cell.setY(cell.getY() + 1))) {
            // 往上数到最上面一格水
        }
        cell.setY(cell.getY() - 1);
        return cell.getY() + level.getFluidState(cell).getHeight(level, cell) + body.stepHeight();
    }

    /**
     * 从高处落下去的落点站得下:不是站着的(水、梯子)不看;站着的,落点那一列里沿前进方向的滑行余量({@link Stepping#slideRoom})不比
     * {@link Stepping#MIN_LANDING_ROOM} 小——炼药锅、堆肥桶、漏斗里只比身体宽一点点,身子从上面落进去,冲劲收不住,弹出来。
     * 平着走进去的不看这个:步态已经为窄落点收了脚({@code Gait})。
     */
    static boolean roomyLanding(BlockGetter level, BodyStats body, BlockPos to, Stance landing, Heading heading) {
        return !landing.grounded() || Stepping.slideRoom(level, body, to.getX(), landing.feetY(), to.getZ(), heading.dx(), heading.dz())
                >= Stepping.MIN_LANDING_ROOM;
    }

    /**
     * 落点是个坑:某个水平轴向上,两头滑出去都不到 {@link Stepping#MIN_LANDING_ROOM} 就撞上(炼药锅、漏斗、堆肥桶)。直着走进去步态为它收脚
     * ({@code Gait}),斜着进去两个轴向上都带着冲劲,停不稳。
     */
    static boolean pit(BlockGetter level, BodyStats body, BlockPos to, Stance landing) {
        if (!landing.grounded()) {
            return false;
        }
        for (int[] axis : new int[][] {{1, 0}, {0, 1}}) {
            if (Stepping.slideRoom(level, body, to.getX(), landing.feetY(), to.getZ(), axis[0], axis[1]) < Stepping.MIN_LANDING_ROOM
                    && Stepping.slideRoom(level, body, to.getX(), landing.feetY(), to.getZ(), -axis[0], -axis[1]) < Stepping.MIN_LANDING_ROOM) {
                return true;
            }
        }
        return false;
    }

    /** 起步与落点两处脚下步速系数的平均:各管半程。 */
    static double speedFactor(BlockGetter level, BlockPos from, double fromFeet, BlockPos to, double toFeet) {
        return (Semantics.speedFactor(level, from.getX(), fromFeet, from.getZ())
                + Semantics.speedFactor(level, to.getX(), toFeet, to.getZ())) / 2;
    }

    /**
     * 这一步每走一格要几刻:在水里按水里的步速;潜行按潜行;身体跑得动、这一步物理上跑得起来({@link Maneuver#runnable})就按疾跑——
     * 每一步按它物理上能达到的最快步态定价,步态({@code Gait})只要能跑就跑,两者只在起步、收脚处有偏差;其余平走;再按脚下的步速系数放慢。
     */
    static double pace(CostModel model, Maneuver m) {
        return pace(model, m.wading(), m.sneak(), m.runnable(), m.speedFactor());
    }

    /** 同上,这一步的事实已经拆开给出,用不着 {@link Maneuver}。 */
    static double pace(CostModel model, boolean wading, boolean sneak, boolean runnable, double speedFactor) {
        if (wading) {
            return model.waterStep();
        }
        BodyStats body = model.body().stats();
        double pace = sneak ? Kinematics.sneakTicksPerBlock(body)
                : model.maySprint() && runnable ? Kinematics.sprintTicksPerBlock(body) : Kinematics.walkTicksPerBlock(body);
        return pace / speedFactor;
    }

    /**
     * 不改地形,身体能不能从落点 {@code to} 回到起点 {@code from}(没搜到这一段的终点时,往边界推进的一截只能停在回得了头的节点上)。判据全是运动学与第 0 层的几何:
     * <ul>
     *   <li>起点不是站着的(攀着、浮着):回去是爬上去、游上去,都回得去;</li>
     *   <li>起点站着,可这一步自己改过的格已经让身体在起点那一列站不回去了(垫柱放在脚刚离开的那一格里):回去要经过被改的格,
     *       不改地形就回不去,不论落差多小;</li>
     *   <li>站着落在站着的地方,落差(起点的脚高减落点的)不超过迈步高度,回去只是走上去,回得去;更高就看回去那一步
     *       ({@link Stepping#between},落差在起跳能到的高度以内才回得去);同一列(往下挖穿)没有那一步,落差要在起跳能到的高度以内;</li>
     *   <li>落在攀着、浮着的地方:顺着梯子、藤蔓爬上去,爬到头了剩下的落差要在起跳能到的高度以内;在水里游到水面,岸沿高出水面不超过
     *       迈步高度(原版出水靠撞着岸沿时顶上一点,{@code LivingEntity.travel} 里的 0.6 格);再从起点那一列挪回去
     *       ({@link Stepping#fromHold})。</li>
     * </ul>
     */
    static boolean reversible(BlockGetter level, BodyStats body, BlockPos from, Stance start, BlockPos to, Stance landing) {
        if (!start.grounded()) {
            return true;
        }
        if (!Clearance.fits(level, body, from.getX(), start.feetY(), from.getZ())) {
            return false;
        }
        double rise = start.feetY() - landing.feetY();
        int dx = from.getX() - to.getX();
        int dz = from.getZ() - to.getZ();
        boolean sameColumn = dx == 0 && dz == 0;
        double jump = Kinematics.jumpHeight(body, Semantics.jumpFactor(level, to.getX(), landing.feetY(), to.getZ()));
        if (landing.grounded()) {
            if (rise <= body.stepHeight() + Footing.EPSILON) {
                return true;
            }
            return sameColumn ? rise <= jump + Footing.EPSILON
                    : Stepping.between(level, body, to.getX(), landing.feetY(), to.getZ(), dx, dz, start.feetY()) != Stepping.Step.BLOCKED;
        }
        double reach = landing.feetY();
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
        boolean water = inWater(level, to);
        double ledge;
        if (water) {
            int y = to.getY();
            while (inWater(level, cell.set(to.getX(), y + 1, to.getZ()))) {
                y++;
            }
            reach = y + 1;
            ledge = body.stepHeight();
        } else {
            int y = to.getY();
            while (Semantics.is(level, cell.set(to.getX(), y + 1, to.getZ()), Kind.CLIMBABLE)) {
                y++;
            }
            reach = y + 1;
            ledge = jump;
        }
        if (start.feetY() - reach > ledge + Footing.EPSILON) {
            return false;
        }
        return sameColumn || Stepping.fromHold(level, body, to.getX(), landing.feetY(), to.getZ(), dx, dz, start.feetY()) != Stepping.Step.BLOCKED;
    }

    /** 起跳落在比起跳时高 {@code rise} 格的平面上、能接着再跳要几刻({@link Kinematics#jumpCycleTicks})。 */
    static double jump(CostModel model, double rise) {
        return Kinematics.jumpCycleTicks(model.body().stats(), 1.0, rise);
    }

    /** 这一步起跳,落点比起步高几格。 */
    static double rise(Maneuver m) {
        return m.landing().feetY() - m.start().feetY();
    }

    /** 从 {@code drop} 高处落到这个落点的耗时:下落,至少要走回列中心那一截。 */
    static double landing(CostModel model, Maneuver m) {
        return landing(model, m.drop());
    }

    /** 同上,落差已经给出。 */
    static double landing(CostModel model, double drop) {
        return Kinematics.fallLandingTicks(model.body().stats(), drop);
    }

    /** 落地摔疼的折价:掉的血按 {@link ActionCosts#FALL_DAMAGE_PER_POINT} 折成刻。 */
    static double bruise(Maneuver m) {
        return bruise(m.fallDamage());
    }

    /** 同上,摔掉的血已经给出。 */
    static double bruise(int damage) {
        return damage * ActionCosts.FALL_DAMAGE_PER_POINT;
    }

    /**
     * 身体以 {@code start} 待在 {@code from}、走到 {@code to} 以 {@code landing} 待着,这一步里眼睛换不换得了气:起步或落定时
     * 眼睛(列中心、脚高加站立眼高)换不了气({@link Semantics#breathless}),整步都按憋着气算——半截在水里的那一步也算进憋气,
     * 宁可多算。
     */
    static boolean submerged(BlockGetter level, BodyStats body, BlockPos from, Stance start, BlockPos to, Stance landing) {
        double eye = body.eyeHeight();
        return Semantics.breathless(level, from.getX() + 0.5, start.feetY() + eye, from.getZ() + 0.5)
                || Semantics.breathless(level, to.getX() + 0.5, landing.feetY() + eye, to.getZ() + 0.5);
    }
}
