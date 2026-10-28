package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Stepping;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/**
 * 走出边沿往下落,{@link MoveKind#DESCEND 下一级}与{@link MoveKind#FALL 下落}是同一个动作,只是落点深浅不同,这里是它们
 * 共用的前提与代价:
 * <ul>
 *   <li>起步时站着;</li>
 *   <li>顺着那一列往下找第一个身体待得住的节点:托得住脚的地面、接得住身体的水、抓得住的梯子;托得住脚却放不下身体
 *       就落不下去;</li>
 *   <li>迈出去不用起跳,走完落到的脚高与落点一致(第 0 层 {@link Stepping#walkOff});身体在起步的脚高上走进那一列
 *       途中挡着的格,门开关、其余挖开;</li>
 *   <li>站着落地时摔得起({@link CostModel#bearsFall}:摔掉几点血按落差与落点脚下那一格算,朝上的滴水石锥尖加重,干草块、
 *       床这些减轻);落进水里、抓住梯子不摔伤,不受这个上限;摔不起而身上有水桶时,在落点倒一桶水接住(落定后收回,
 *       {@link Edit.Catch})。</li>
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
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!stance.grounded()) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        int tx = from.getX() + heading.dx();
        int tz = from.getZ() + heading.dz();
        double f0 = stance.feetY();
        // 落点是往下第一个待得住的节点;下一级只收低一个节点处,那里待不住,落点就更深或没有,不必再往下找
        int lowest = kind == MoveKind.DESCEND ? from.getY() - 1 : draft.getMinBuildHeight();
        BlockPos to = null;
        Stance landing = null;
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos(tx, from.getY() - 1, tz);
        boolean airHere = draft.getBlockState(cell).isAir();
        for (int y = from.getY() - 1; y >= lowest; y--) {
            if (airHere && draft.airSection(tx, y - 1, tz)) {
                // 下面一格所在的区段整段是空气:一直到区段底,每一格与它下面一格都是空气,整段落过去
                int bottom = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(y - 1));
                if (bottom < lowest) {
                    break;
                }
                y = bottom + 1;
                continue;
            }
            boolean airBelow = draft.getBlockState(cell.setY(y - 1)).isAir();
            if (airHere && airBelow) {
                // 这一格与下面一格都是空气:托不住脚、也挂不住身体,落点只能更深
                airHere = true;
                continue;
            }
            airHere = airBelow;
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
            return Premise.fail(new BlockPos(tx, from.getY() - 1, tz),
                    kind == MoveKind.DESCEND ? Reason.WRONG_DROP : Reason.NO_FOOTING);
        }
        if (landing.kind() == Stance.Kind.SWIMMING && Strides.inWater(draft, to.above())) {
            // 落进水里的身体浮在水面上,够不到水面以下的节点(那要往下潜):落点只能是水面那一格
            return Premise.fail(to, Reason.WRONG_DROP);
        }
        int depth = from.getY() - to.getY();
        if (kind == MoveKind.FALL && depth < 2) {
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
        if (!Strides.roomyLanding(draft, body, to, landing, heading)) {
            return Premise.fail(to, Reason.NO_CLEARANCE);
        }
        boolean wading = Strides.inWater(draft, to);
        double drop = f0 - landing.feetY();
        BlockPos support = landing.support(tx, tz);
        int damage = model.fallDamage(draft, landing, support, drop, wading);
        if (!model.bearsLanding(landing, wading, drop, damage)) {
            // 摔不起:身上有水桶就在落点倒一桶水接住,落进水里
            if (!draft.catchFall(to)) {
                return draft.failure();
            }
            wading = true;
            damage = 0;
        }
        Contact contact = new Contact(body, from, f0).column(tx, tz, landing.feetY(), f0);
        if (!contact.admit(draft, model, support, drop > 0.5)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(kind, heading, from, stance, to, landing, false, false, false, wading,
                Strides.submerged(draft, body, from, stance, to, landing), Semantics.speedFactor(draft, from.getX(), f0, from.getZ()), drop, damage, 1, draft.edits(), contact.cells(), contact.exposure(),
                support, Strides.reversible(draft, body, from, stance, to, landing)));
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

    /**
     * 平地上站着的节点,落点那一列在起步脚高上身体占的格全是空的、脚下那一格也是空的,顺着那一列往下找第一块整块:它上面那一格
     * 就是落点。下一级只收低一个节点处,下落只收低两个节点以上;落点脚下是实的,走出去脚还踩在原来的高度上,那是平走,
     * 不是往下落。起步脚高上身体占的格里有整块,要挖掉每一块(不许挖、挖不动的过不去);落点摔不起,身上有水桶才有倒水接住的可能。
     * 要挖、要接的,与标志字说不清的格,答"可能":落点按标志字找(说不清时取最浅的那个),代价按同一个公式在最省的情形下算。
     */
    @Override
    public int quick(Quick q, Heading heading) {
        if (!q.stance().grounded()) {
            return Quick.REJECT;
        }
        int dx = heading.dx();
        int dz = heading.dz();
        int shallowest = kind == MoveKind.DESCEND ? 1 : 2;
        double floor = kind == MoveKind.DESCEND ? q.descendFloor() : q.fallFloor();
        if (!q.plain()) {
            // 落点那一列脚下一格是整块:往下落不过去(同下面平地上的判断,只是现读)
            return Quick.whole(q.flagsAt(dx, -1, dz)) && q.loadedAround(dx, dz) ? Quick.REJECT
                    : around(q, dx, -shallowest, dz, floor);
        }
        boolean blocked = false;
        for (int dy = 0; dy <= q.top(); dy++) {
            int flags = q.cell(dx, dy, dz);
            if (!Quick.simple(flags)) {
                return around(q, dx, -shallowest, dz, floor);
            }
            blocked |= Quick.whole(flags);
        }
        int under = q.cell(dx, -1, dz);
        if (!Quick.simple(under)) {
            return around(q, dx, -shallowest, dz, floor);
        }
        if (Quick.whole(under)) {
            return Quick.REJECT;
        }
        double digs = 0;
        if (blocked) {
            if (!q.mayDig()) {
                return Quick.REJECT;
            }
            digs = q.digFloor(dx, 0, q.top(), dz);
            if (Double.isInfinite(digs)) {
                return Quick.REJECT;
            }
        }
        int support = q.cell(dx, -2, dz);
        if (!Quick.simple(support)) {
            return around(q, dx, -shallowest, dz, floor + digs);
        }
        int depth = 1;
        if (Quick.whole(support)) {
            if (kind == MoveKind.FALL) {
                return Quick.REJECT;
            }
        } else {
            if (kind == MoveKind.DESCEND) {
                return Quick.REJECT;
            }
            int below = -3;
            while (true) {
                if (q.y() + below < q.floorY()) {
                    return Quick.REJECT;
                }
                if (-below > q.scan()) {
                    return q.likelyNear(dx, below + 1, dz, floor + digs);
                }
                support = q.flagsAt(dx, below, dz);
                if (!Quick.simple(support)) {
                    return q.likelyNear(dx, below + 1, dz, floor + digs);
                }
                if (Quick.whole(support)) {
                    break;
                }
                below--;
            }
            depth = -below - 1;
        }
        CostModel model = q.model();
        double move = movement(q.walkOff(), 1.0, q.landing(depth));
        int damage = model.fallDamage(depth, q.state(dx, -depth - 1, dz));
        if (!model.bearsLanding(q.ground(q.y() - depth), false, depth, damage)) {
            // 倒一桶水接住:落进水里不摔,只有走与落的工夫
            return model.mayCatch(q.view()) ? q.likely(dx, -depth, dz, move + digs) : Quick.REJECT;
        }
        if (blocked || !q.calm() || !q.calmBelow(dx, dz, -depth - 1, -3)) {
            return q.likely(dx, -depth, dz, move + Strides.bruise(damage) + digs);
        }
        q.entering();
        for (int dy = -depth; dy <= q.top(); dy++) {
            q.enter(dx, dy, dz);
        }
        if (q.forbidsPassing() || !q.standsOn(dx, -depth - 1, dz, support)) {
            return Quick.REJECT;
        }
        return q.holds(q.x() + dx, q.y() - depth, q.z() + dz, move + Strides.bruise(damage) + q.overhead(dx, -depth - 1, dz), move);
    }

    /** 标志字定不下落点时答"可能":下一级的落点只有低一个节点那一处,下落的落点是猜的最浅的那个。 */
    private int around(Quick q, int dx, int dy, int dz, double cost) {
        return kind == MoveKind.DESCEND ? q.likely(dx, dy, dz, cost) : q.likelyNear(dx, dy, dz, cost);
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        return movement(model, m) + Strides.bruise(m) + model.overhead(m);
    }

    @Override
    public double ticks(CostModel model, Maneuver m) {
        return movement(model, m) + model.workTicks(m);
    }

    /** 身体下去的刻数:走出边沿,落下去,走回列中心。 */
    private static double movement(CostModel model, Maneuver m) {
        return movement(Kinematics.walkOffEdgeTicks(model.body().stats()), m.speedFactor(), Strides.landing(model, m));
    }

    /** 同上,走出边沿的工夫、脚下的步速系数与落地的工夫已经给出。 */
    static double movement(double walkOff, double speedFactor, double landing) {
        return walkOff / speedFactor + landing;
    }
}
