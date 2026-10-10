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
        if (m.wading()) {
            return model.waterStep();
        }
        BodyStats body = model.body().stats();
        double pace = m.sneak() ? Kinematics.sneakTicksPerBlock(body)
                : model.maySprint() && m.runnable() ? Kinematics.sprintTicksPerBlock(body) : Kinematics.walkTicksPerBlock(body);
        return pace / m.speedFactor();
    }

    /**
     * 不改地形,身体能不能从落点 {@code to} 回到起点 {@code from}(交出半程路线的一截只能停在回得了头的节点上)。判据全是运动学与第 0 层的几何:
     * <ul>
     *   <li>起点不是站着的(攀着、浮着):回去是爬上去、游上去,都回得去;</li>
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

    /**
     * 身体从 {@code drop} 高处落到 {@code support}(落定后脚踩的那一格)上摔掉几点血:站着落地、没落进水里,才照身体快照按落差与
     * 那一格算({@link BodySnapshot#fallDamage});落进水里、攀着、浮着不摔。
     *
     * <p>脚踩的那一格是托住脚的碰撞箱所在的格(第 0 层 {@code Footing.supportY})。原版结算摔伤看的是脚底往下 0.2 格处那一格
     * ({@code Entity.getOnPosLegacy}),两者只在托脚的是不到 0.2 格厚的薄片(地毯、一层雪、中继器)时不同:那时原版看的是薄片底下那一块。
     */
    static int fallDamage(CostModel model, BlockGetter level, Stance landing, BlockPos support, double drop, boolean wading) {
        if (!landing.grounded() || wading || support == null) {
            return 0;
        }
        return model.body().fallDamage(drop, level.getBlockState(support));
    }

    /**
     * 这个落点摔得起摔不起:站着落地、没落进水里的,身体受得起这一摔({@link BodySnapshot#bears})、落差也在规格的无水落差以内
     * ({@link CostModel#bearsFall});攀着、浮着、落进水里的不摔。所有会落下去的走法(落下一级、斜走、向下挖)问的都是这一处。
     */
    static boolean bearsLanding(CostModel model, Stance landing, boolean wading, double drop, int damage) {
        return !landing.grounded() || wading || model.bearsFall(drop, damage);
    }

    /** 从 {@code drop} 高处落到这个落点的耗时:下落,至少要走回列中心那一截。 */
    static double landing(CostModel model, Maneuver m) {
        BodyStats body = model.body().stats();
        return Math.max(Kinematics.fallTicks(body.gravity(), m.drop()), Kinematics.centerAfterFallTicks(body));
    }

    /** 落地摔疼的折价:掉的血按 {@link ActionCosts#FALL_DAMAGE_PER_POINT} 折成刻。 */
    static double bruise(Maneuver m) {
        return m.fallDamage() * ActionCosts.FALL_DAMAGE_PER_POINT;
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
