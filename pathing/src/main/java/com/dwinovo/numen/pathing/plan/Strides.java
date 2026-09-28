package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;

/** 几种走法共用的小事:身体要腾出的格、泡没泡在水里、脚下的步速、每格走多久。 */
final class Strides {

    private Strides() {}

    /** 身体脚在 {@code feetY} 站在 {@code to} 那一列时挡着它的格,自下而上。 */
    static List<BlockPos> at(BlockGetter level, BodyStats body, BlockPos to, double feetY) {
        return Clearance.blockers(level, body, Pose.STANDING, to.getX(), feetY, to.getZ());
    }

    /** 身体脚在 {@code feetY},从 {@code from} 那一列朝 {@code heading} 走进相邻一列,途中挡着它的格,自下而上。 */
    static List<BlockPos> across(BlockGetter level, BodyStats body, BlockPos from, Heading heading, double feetY) {
        return Clearance.blockers(level, body, Pose.STANDING, from.getX(), feetY, from.getZ(), heading.dx(), heading.dz());
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

    /** 这一步每走一格要几刻:在水里按水里的步速;否则潜行、疾跑或平走,再按脚下的步速系数放慢。 */
    static double pace(CostModel model, Maneuver m) {
        if (m.wading()) {
            return model.waterStep();
        }
        double pace = m.sneak() ? ActionCosts.SNEAK_ONE_BLOCK
                : m.sprint() ? ActionCosts.SPRINT_ONE_BLOCK : ActionCosts.WALK_ONE_BLOCK;
        return pace / m.speedFactor();
    }

    /** 起跳的价钱:起跳升一格的耗时,加规格的起跳罚分。 */
    static double jump(CostModel model) {
        return ActionCosts.JUMP_ONE_BLOCK + model.spec().jumpPenalty();
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

    /** 从 {@code drop} 高处落到这个落点:下落耗时(至少要走回列中心那一截),摔疼的按掉的血折价。 */
    static double landing(CostModel model, Maneuver m) {
        return Math.max(ActionCosts.fall(m.drop()), ActionCosts.CENTER_AFTER_FALL)
                + m.fallDamage() * ActionCosts.FALL_DAMAGE_PER_POINT;
    }
}
