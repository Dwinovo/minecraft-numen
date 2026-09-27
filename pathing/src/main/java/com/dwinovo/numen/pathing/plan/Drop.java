package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.core.BlockPos;

/**
 * 走出边沿往下落,{@link MoveKind#DESCEND 下一级}与{@link MoveKind#FALL 下落}是同一个动作,只是落点深浅不同,这里是它们
 * 共用的前提与代价:
 * <ul>
 *   <li>起步时站着;</li>
 *   <li>顺着那一列往下找第一个身体待得住的节点:托得住脚的地面、接得住身体的水、抓得住的梯子;托得住脚却放不下身体
 *       就落不下去;</li>
 *   <li>迈出去不用起跳,走完落到的脚高与落点一致(第 0 层 {@link Stepping#walkOff});身体在起步的脚高上走进那一列
 *       途中挡着的格,门开关、其余挖开;</li>
 *   <li>落在硬地上时落差不超过摔落上限({@link CostModel#fallLimit});落进水里、抓住梯子不摔伤,不受这个上限。</li>
 * </ul>
 */
final class Drop implements Move {

    private final MoveKind kind;

    /** @param kind {@link MoveKind#DESCEND} 只收低一个节点的落点,{@link MoveKind#FALL} 只收低两个节点以上的 */
    Drop(MoveKind kind) {
        if (kind != MoveKind.DESCEND && kind != MoveKind.FALL) {
            throw new IllegalArgumentException("走出边沿只有下一级与下落两种:" + kind);
        }
        this.kind = kind;
    }

    @Override
    public MoveKind kind() {
        return kind;
    }

    @Override
    public List<Heading> headings() {
        return Heading.CARDINAL;
    }

    @Override
    public int reach() {
        return 1;
    }

    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!stance.grounded()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        int tx = from.getX() + heading.dx();
        int tz = from.getZ() + heading.dz();
        double f0 = stance.feetY();
        BlockPos to = null;
        Stance landing = null;
        for (int y = from.getY() - 1; y >= draft.getMinBuildHeight(); y--) {
            landing = Stance.at(draft, body, tx, y, tz);
            if (landing != null) {
                to = new BlockPos(tx, y, tz);
                break;
            }
            if (!Double.isNaN(Footing.height(draft, body, tx, y, tz))) {
                return Premise.fail(new BlockPos(tx, y, tz), Reason.NO_CLEARANCE);
            }
        }
        if (to == null) {
            return Premise.fail(new BlockPos(tx, from.getY() - 1, tz), Reason.NO_FOOTING);
        }
        int depth = from.getY() - to.getY();
        if (kind == MoveKind.DESCEND ? depth != 1 : depth < 2) {
            return Premise.fail(to, Reason.WRONG_DROP);
        }
        if (!walksOff(draft, body, from, f0, heading, landing)) {
            // 走过去途中挡着的格腾出来再判
            if (!draft.clear(Strides.across(draft, body, from, heading, f0), true, from.getX(), f0, from.getZ(), true)) {
                return draft.failure();
            }
            if (!walksOff(draft, body, from, f0, heading, landing)) {
                return Premise.fail(to, Reason.NO_CLEARANCE);
            }
        }
        boolean wading = Strides.inWater(draft, to);
        double drop = f0 - landing.feetY();
        if (landing.grounded() && !wading && drop > model.fallLimit() + Footing.EPSILON) {
            return Premise.fail(to, Reason.TOO_FAR_TO_FALL);
        }
        Contact contact = new Contact(body, from, f0).column(tx, tz, landing.feetY(), f0);
        BlockPos support = landing.support(tx, tz);
        if (!contact.admit(draft, model, support, drop > 0.5)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(kind, heading, from, stance, to, landing, false, false, false, wading,
                Semantics.speedFactor(draft, from.getX(), f0, from.getZ()), drop, 1, draft.edits(), contact.cells(), contact.exposure(),
                support));
    }

    /**
     * 不用起跳就迈得出去,走完落到的脚高与落点一致({@link Stepping#walkOff});落进水里、抓住梯子时,脚下没有东西把身体
     * 托在落点之上就行。
     */
    private static boolean walksOff(Draft draft, BodyStats body, BlockPos from, double f0, Heading heading,
                                    Stance landing) {
        double after = Stepping.walkOff(draft, body, from.getX(), f0, from.getZ(), heading.dx(), heading.dz(),
                landing.feetY());
        return landing.grounded()
                ? Math.abs(after - landing.feetY()) <= Footing.EPSILON
                : after <= landing.feetY() + Footing.EPSILON;
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return ActionCosts.WALK_OFF_EDGE / m.speedFactor() + Strides.landing(model, m) + model.overhead(m);
    }
}
