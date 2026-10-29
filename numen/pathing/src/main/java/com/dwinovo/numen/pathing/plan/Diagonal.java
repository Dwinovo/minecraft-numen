package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.core.BlockPos;

/**
 * 斜走:进斜对角一列(同一层上,落脚高度差由跳与落带过)。身体从两列的交角擦过去,两侧那两列也要让得开——
 * 切角挡不挡由第 0 层 {@link Stepping} 从四列的碰撞箱推导。斜走不改地形,只开关挡路的门。
 */
final class Diagonal implements Move {

    @Override
    public MoveKind kind() {
        return MoveKind.DIAGONAL;
    }

    @Override
    public List<Heading> headings() {
        return Heading.DIAGONAL;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!stance.grounded()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        BlockPos to = from.offset(heading.dx(), heading.dy(), heading.dz());
        double f0 = stance.feetY();
        double f1 = Footing.height(draft, body, to.getX(), to.getY(), to.getZ());
        if (Double.isNaN(f1)) {
            return Premise.fail(to, Reason.NO_FOOTING);
        }
        int x = from.getX();
        int z = from.getZ();
        double low = Math.min(f0, f1);
        double high = Math.max(f0, f1);
        Stepping.Step step = Stepping.between(draft, body, x, f0, z, heading.dx(), heading.dz(), f1);
        if (step == Stepping.Step.BLOCKED) {
            // 挡着的若是门,开关一下再判;别的不挖
            List<BlockPos> blockers = Strides.union(Strides.at(draft, body, to, f1),
                    Strides.across(draft, body, from, heading, high));
            if (!draft.clear(blockers, false, x, f0, z, true)) {
                return draft.failure();
            }
            step = Stepping.between(draft, body, x, f0, z, heading.dx(), heading.dz(), f1);
            if (step == Stepping.Step.BLOCKED) {
                return Premise.fail(to, Reason.NO_CLEARANCE);
            }
        }
        Stance landing = Stance.at(draft, body, to);
        if (landing == null || !landing.grounded()) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        if (Strides.pit(draft, body, to, landing)) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        boolean jump = step == Stepping.Step.JUMP;
        Contact contact = new Contact(body, from, f0)
                .column(to.getX(), to.getZ(), low, high)
                .column(x + heading.dx(), z, low, high)
                .column(x, z + heading.dz(), low, high)
                .over(x + heading.dx(), z, f0)
                .over(x, z + heading.dz(), f0);
        BlockPos support = landing.support(to.getX(), to.getZ());
        boolean wading = Strides.inWater(draft, to);
        double drop = Math.max(0, f0 - f1);
        int damage = model.fallDamage(draft, landing, support, drop, wading);
        if (!model.bearsLanding(landing, wading, damage)) {
            return Premise.fail(support, Reason.TOO_FAR_TO_FALL);
        }
        if (!contact.admit(draft, model, support, f0 - f1 > 0.5)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.DIAGONAL, heading, from, stance, to, landing, jump, false, false, wading,
                Strides.submerged(draft, body, from, stance, to, landing), Strides.speedFactor(draft, from, f0, to, f1), drop, damage, 1, draft.edits(),
                contact.cells(), contact.exposure(), support,
                Strides.reversible(draft, body, from, stance, to, landing)));
    }

    /**
     * 平地上站着的节点,落点那一列与两侧两列身体占的格全是空的、落点脚下是整块,就是斜着走过去;落点那一列有整块挡着,过不去
     * (斜走不改地形);两侧有整块,可能擦过去、可能要起跳。标志字说不清的答"可能",代价按最快的步速斜走一格。
     */
    @Override
    public int quick(Quick q, Heading heading) {
        if (!q.stance().grounded()) {
            return Quick.REJECT;
        }
        int dx = heading.dx();
        int dz = heading.dz();
        double floor = movement(q.fastest(), false, 0, 0.0, 0);
        if (!q.plain()) {
            return likely(q, dx, dz, floor);
        }
        boolean blocked = false;
        boolean step = false;
        boolean wall = false;
        for (int dy = 0; dy <= q.top(); dy++) {
            int flags = q.cell(dx, dy, dz);
            int side = q.cell(dx, dy, 0);
            int other = q.cell(0, dy, dz);
            if (!Quick.simple(flags) || !Quick.simple(side) || !Quick.simple(other)) {
                return likely(q, dx, dz, floor);
            }
            blocked |= Quick.whole(flags);
            if (Quick.whole(side) || Quick.whole(other)) {
                // 脚那一层的整块是一格高的坎,可以起跳越过去;再往上还有整块就越不过
                step |= dy == 0;
                wall |= dy > 0;
                if (q.excluded(side) || q.excluded(other)) {
                    // 越过去身体经过它的上方,路线排除这样的格,这一步就不成立
                    return Quick.REJECT;
                }
            }
        }
        int under = q.cell(dx, -1, dz);
        if (!Quick.simple(under)) {
            return likely(q, dx, dz, floor);
        }
        // 擦过的两个拐角列,身体压在它们脚下那一格的上面:路线排除的种类,这一步就不成立
        int cornerSide = q.cell(dx, -1, 0);
        int cornerOther = q.cell(0, -1, dz);
        if (!Quick.simple(cornerSide) || !Quick.simple(cornerOther)) {
            return likely(q, dx, dz, floor);
        }
        if (q.excluded(cornerSide) || q.excluded(cornerOther)) {
            return Quick.REJECT;
        }
        if (Quick.pass(under) || blocked || wall) {
            return Quick.REJECT;
        }
        if (step) {
            // 越过去的那一刻脚比起跳时高一格多,头上多占一格:四列和起步那一列头顶这一格都要空着
            int top = q.top() + 1;
            int onto = q.cell(dx, top, dz);
            int side = q.cell(dx, top, 0);
            int other = q.cell(0, top, dz);
            int head = q.cell(0, top, 0);
            if (!Quick.simple(onto) || !Quick.simple(side) || !Quick.simple(other) || !Quick.simple(head)) {
                return likely(q, dx, dz, floor);
            }
            if (Quick.whole(onto) || Quick.whole(side) || Quick.whole(other) || Quick.whole(head)) {
                return Quick.REJECT;
            }
        }
        if (!q.calm()) {
            return likely(q, dx, dz, floor);
        }
        int damage = q.model().fallDamage(0.0, q.state(dx, -1, dz));
        if (!q.model().bearsLanding(q.ground(q.y()), false, damage)) {
            return Quick.REJECT;
        }
        q.entering();
        for (int dy = 0; dy <= q.top(); dy++) {
            q.enter(dx, dy, dz);
        }
        for (int dy = 0; dy <= q.top(); dy++) {
            q.enter(dx, dy, 0);
        }
        for (int dy = 0; dy <= q.top(); dy++) {
            q.enter(0, dy, dz);
        }
        if (q.forbidsPassing() || !q.standsOn(dx, -1, dz, under)) {
            return Quick.REJECT;
        }
        double move = movement(q.pace(), step, step ? q.levelJump() : 0, 0.0, 0);
        double cost = step ? move + q.jumpPenalty() : move;
        return q.holds(q.x() + dx, q.y(), q.z() + dz, cost + q.overhead(dx, -1, dz), move);
    }

    /** 标志字说不清时答"可能";落点那一格托不住脚就过不去(精确判断头一条就是它;斜走不垫),当场不成立。 */
    private static int likely(Quick q, int dx, int dz, double floor) {
        return q.loadedColumn(dx, dz) && Double.isNaN(q.footing(dx, 0, dz)) ? Quick.REJECT : q.likely(dx, 0, dz, floor);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return movement(model, m) + (m.jump() ? model.spec().jumpPenalty() : 0)
                + (m.drop() > 0.5 ? Strides.bruise(m) : 0) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return movement(model, m) + model.workTicks(m);
    }

    /** 身体斜着过去的刻数:斜走一格,要跳的至少是起跳上一格的工夫,落差过半格的加上落地。 */
    private static double movement(CostModel model, Maneuver m) {
        return movement(Strides.pace(model, m), m.jump(), m.jump() ? Strides.jump(model, Strides.rise(m)) : 0, m.drop(),
                m.drop() > 0.5 ? Strides.landing(model, m) : 0);
    }

    /** 同上,步速、要不要跳、起跳落到那一高度的工夫({@code jump} 时才用)、落差、落地的工夫({@code drop} 过半格时才用)已经给出。 */
    static double movement(double pace, boolean jump, double jumpTicks, double drop, double landing) {
        double move = pace * Math.sqrt(2);
        if (jump) {
            move = Math.max(move, jumpTicks);
        }
        return drop > 0.5 ? move + landing : move;
    }
}
