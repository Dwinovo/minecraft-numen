package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.CellFlags;

import net.minecraft.core.BlockPos;

/**
 * 向下挖:挖掉脚下托着身体的那一格,落下一个节点,站在再下面那一格上。
 *
 * <p>前提:起步时站着,托着脚的正是下面那一格(站在半砖、地毯这类矮方块上时托着脚的是脚所在那一格,
 * 不从这里挖);那一格挖得了(规格、物理、许可,见 {@link CostModel#admitDig});挖掉后落下一个节点就站得住——再往下
 * 空着就不是这一步;落上去摔掉的血身体受得起(朝上的滴水石锥尖落一格就疼,{@link BodySnapshot#fallDamage})。
 * 挂在梯子上往下是 {@link Climb}。
 */
final class Downward implements Move {

    private static final List<Heading> HEADINGS = List.of(Heading.DOWN);

    @Override
    public MoveKind kind() {
        return MoveKind.DOWNWARD;
    }

    @Override
    public List<Heading> headings() {
        return HEADINGS;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        BlockPos below = from.below();
        if (!stance.grounded() || stance.supportY() != below.getY()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        double f0 = stance.feetY();
        if (!draft.dig(below, from.getX(), f0, from.getZ(), true)) {
            return draft.failure();
        }
        Stance landing = Stance.at(draft, body, below);
        if (landing == null || !landing.grounded()) {
            return Premise.fail(below, Reason.NO_FOOTING);
        }
        Contact contact = new Contact(body, from, f0).column(from.getX(), from.getZ(), landing.feetY(), f0);
        BlockPos support = landing.support(below.getX(), below.getZ());
        double drop = f0 - landing.feetY();
        boolean wading = Strides.inWater(draft, below);
        int damage = model.fallDamage(draft, landing, support, drop, wading);
        if (!model.bearsLanding(landing, wading, drop, damage)) {
            return Premise.fail(support, Reason.TOO_FAR_TO_FALL);
        }
        if (!contact.admit(draft, model, support, drop > 0.5)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.DOWNWARD, heading, from, stance, below, landing, false, false, false,
                wading, Strides.submerged(draft, body, from, stance, below, landing), 1, drop, damage, 0, draft.edits(), contact.cells(), contact.exposure(), support,
                Strides.reversible(draft, body, from, stance, below, landing)));
    }

    /**
     * 向下挖要站着、脚踩的正是下面那一格,还要许挖。不许挖的,精确判断在拒绝之前要先瞄(射线读四周的格),所以只在四周读全了
     * 的节点上断定;其余答"可能",代价至少是落下去的工夫加挖掉脚下那一格的最低价(挖不动的那一格,四周读全了时当场不成立)。
     */
    @Override
    public int quick(Quick q, Heading heading) {
        if (!q.stance().grounded() || q.stance().supportY() != q.y() - 1) {
            return Quick.REJECT;
        }
        if (!q.mayDig() && q.plain()) {
            return Quick.REJECT;
        }
        if ((q.own() & CellFlags.FLUID) != 0 && q.loadedAround(0, 0)) {
            // 脚所在的格有液体:挖掉脚下那格,液体往下灌({@code DigRules} 的漏液)
            return Quick.REJECT;
        }
        double dig = q.model().digFloor(q.state(0, -1, 0));
        if (Double.isInfinite(dig)) {
            return q.plain() ? Quick.REJECT : q.likely(0, -1, 0, q.descendFloor() - q.walkOff());
        }
        // 平地上挖掉脚下那块、再下面是整块:正好落一格
        double landing = q.plain() && Quick.whole(q.cell(0, -2, 0)) ? q.landing(1) : q.descendFloor() - q.walkOff();
        return q.likely(0, -1, 0, landing + dig);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return Strides.landing(model, m) + Strides.bruise(m) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return Strides.landing(model, m) + model.workTicks(m);
    }
}
