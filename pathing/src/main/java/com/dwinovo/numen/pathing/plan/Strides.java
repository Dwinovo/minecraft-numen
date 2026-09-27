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

    /** 从 {@code drop} 高处落到这个落点:下落耗时(至少要走回列中心那一截),落在硬地上摔疼的按掉血折价。 */
    static double landing(CostModel model, Maneuver m) {
        double cost = Math.max(ActionCosts.fall(m.drop()), ActionCosts.CENTER_AFTER_FALL);
        if (m.landing().grounded() && !m.wading()) {
            cost += model.body().fallDamage(m.drop()) * ActionCosts.FALL_DAMAGE_PER_POINT;
        }
        return cost;
    }
}
