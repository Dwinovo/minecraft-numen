package com.dwinovo.numen.pathing.plan;

import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;

/**
 * 跑酷:朝东南西北起跳,越过一到三格的空隙,落在同一个节点高度,或(规格开着时)高一级。
 *
 * <p>前提:规格开着跑酷;起步时站在地上、脚下不泡在液体里;紧挨着的那一列是空隙(托不住脚——托得住就直接走过去);
 * 起跳能升的高度按脚下方块的起跳系数算({@link BodyStats#jumpHeight}),起步那一列头顶与空中经过的每一列在起跳的脚高与
 * 最高点都放得下身体;落点是第一列托得住脚、放得下身体的节点。跳多远看助跑:脚下步速慢(灵魂沙、蜂蜜块)最远落到第 2 列,
 * 不疾跑最远第 3 列,疾跑第 4 列;落到第 4 列、落点比起跳的脚高(跳上高一级,或落在比起跳处高半格的半砖上)都要疾跑——
 * 落点越高,身体越早落回那个高度,留给水平飞的刻数越少。落点再往前一列不能是这条路线排除的格——
 * 落地时的冲劲会把身体带过去。跑酷不改地形。
 */
final class Parkour implements Move {

    /** 跳上高一级最远落到第几列。 */
    private static final int ASCEND_SPAN = 3;

    @Override
    public MoveKind kind() {
        return MoveKind.PARKOUR;
    }

    @Override
    public List<Heading> headings() {
        return Heading.CARDINAL;
    }


    @Override
    public Premise premise(CostModel model, WorldView view, BlockPos from, Stance stance, Heading heading) {
        if (!model.spec().parkour()) {
            return Premise.fail(from, Reason.DISABLED);
        }
        if (!stance.grounded() || Strides.feetInFluid(view, from)) {
            return Premise.fail(from, Reason.WRONG_STANCE);
        }
        BodyStats body = model.body().stats();
        Draft draft = new Draft(model, view);
        int x = from.getX();
        int y = from.getY();
        int z = from.getZ();
        int dx = heading.dx();
        int dz = heading.dz();
        double f0 = stance.feetY();
        double peak = f0 + body.jumpHeight(Semantics.jumpFactor(view, x, f0, z));
        if (!Double.isNaN(Footing.height(view, body, x + dx, y, z + dz))) {
            return Premise.fail(from.offset(dx, 0, dz), Reason.NO_GAP);
        }
        if (!Clearance.fits(view, body, Pose.STANDING, x, peak, z)) {
            return Premise.fail(from.above(2), Reason.NO_CLEARANCE);
        }
        boolean sprintable = model.maySprint();
        int longest = Semantics.speedFactor(view, x, f0, z) < 1 ? 2 : 4;
        BlockPos to = null;
        Stance landing = null;
        int span = 0;
        for (int d = 1; d <= longest && to == null; d++) {
            int cx = x + dx * d;
            int cz = z + dz * d;
            if (d >= 2) {
                double flat = Footing.height(view, body, cx, y, cz);
                double up = Footing.height(view, body, cx, y + 1, cz);
                if (!Double.isNaN(up)) {
                    if (!model.spec().parkourAscend() || d > ASCEND_SPAN || up - f0 > 1 + Footing.EPSILON || up > peak) {
                        return Premise.fail(new BlockPos(cx, y + 1, cz), Reason.TOO_HIGH);
                    }
                    to = new BlockPos(cx, y + 1, cz);
                } else if (!Double.isNaN(flat)) {
                    if (flat > f0 + body.stepHeight() + Footing.EPSILON) {
                        return Premise.fail(new BlockPos(cx, y, cz), Reason.TOO_HIGH);
                    }
                    to = new BlockPos(cx, y, cz);
                }
                if (to != null) {
                    landing = Stance.at(view, body, to);
                    if (landing == null || !landing.grounded()) {
                        return Premise.fail(to, Reason.NO_CLEARANCE);
                    }
                    span = d;
                    break;
                }
            }
            // 空中经过这一列:起跳的脚高与最高点都放得下
            if (!Clearance.fits(view, body, Pose.STANDING, cx, f0, cz)
                    || !Clearance.fits(view, body, Pose.STANDING, cx, peak, cz)) {
                return Premise.fail(new BlockPos(cx, y + 1, cz), Reason.NO_CLEARANCE);
            }
        }
        if (to == null) {
            return Premise.fail(from.offset(dx * longest, 0, dz * longest), Reason.NO_FOOTING);
        }
        boolean ascend = landing.feetY() > f0 + Footing.EPSILON;
        if ((span == 4 || ascend) && !sprintable) {
            return Premise.fail(to, Reason.NO_SPRINT);
        }
        Contact contact = new Contact(body, from, f0);
        for (int d = 1; d < span; d++) {
            contact.column(x + dx * d, z + dz * d, f0, peak);
        }
        contact.column(to.getX(), to.getZ(), landing.feetY(), peak);
        // 落地的冲劲会把身体带进前面那一列:那里的脚、头与脚下都不能是这条路线排除的格
        BlockPos beyond = to.offset(dx, 0, dz);
        for (BlockPos cell : new BlockPos[] {beyond, beyond.above(), beyond.below()}) {
            if (model.excludes(Semantics.mask(view, cell))) {
                return Premise.fail(cell, Reason.EXCLUDED);
            }
        }
        BlockPos support = landing.support(to.getX(), to.getZ());
        if (!contact.admit(draft, model, support, true)) {
            return draft.failure();
        }
        return new Premise.Holds(new Maneuver(MoveKind.PARKOUR, heading, from, stance, to, landing, true, span == 4 || ascend,
                false, Strides.inWater(view, to), Semantics.speedFactor(view, x, f0, z), Math.max(0, f0 - landing.feetY()), 0,
                span, draft.edits(), contact.cells(), contact.exposure(), support));
    }

    @Override
    public double cost(CostModel model, Maneuver m) {
        double pace = m.sprint() ? ActionCosts.SPRINT_ONE_BLOCK : ActionCosts.WALK_ONE_BLOCK;
        return m.span() * pace + model.spec().jumpPenalty() + model.overhead(m);
    }
}
