package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * 创造飞行的三种走法({@link Takeoff}、{@link Fly}、{@link Land})共用的小事:往哪几个方向飞、飞一步要几刻、沿途挡着身体的格、身体新进入的格。
 * 飞的耗时全来自 {@link Kinematics} 里飞行的稳态速度:水平疾飞每刻 {@link Kinematics#flySpeed}、竖直每刻 {@link Kinematics#flyClimb},
 * 水平与竖直是两套键(前进键与跳、潜行键),同时按,一步的耗时取两者里长的。
 */
final class Flight {

    /** 空中一步的方向:东南西北与四个斜方向平飞,上、下,以及朝东南西北边飞边升或降一格。 */
    static final List<Heading> CRUISE;

    static {
        List<Heading> all = new ArrayList<>(Heading.CARDINAL);
        all.addAll(Heading.DIAGONAL);
        all.add(Heading.UP);
        all.add(Heading.DOWN);
        for (Heading side : Heading.CARDINAL) {
            all.add(new Heading(side.dx(), 1, side.dz()));
            all.add(new Heading(side.dx(), -1, side.dz()));
        }
        CRUISE = List.copyOf(all);
    }

    /** 降落的方向:东南西北各平着、下一格、上一格,四个斜方向平着,以及正下方。 */
    static final List<Heading> ALIGHT;

    static {
        List<Heading> all = new ArrayList<>();
        for (Heading side : Heading.CARDINAL) {
            all.add(side);
            all.add(new Heading(side.dx(), -1, side.dz()));
            all.add(new Heading(side.dx(), 1, side.dz()));
        }
        all.addAll(Heading.DIAGONAL);
        all.add(Heading.DOWN);
        ALIGHT = List.copyOf(all);
    }

    private Flight() {}

    /** 这具身体的飞行速度(能力里的)。 */
    static float speed(CostModel model) {
        return model.body().mobility().flyingSpeed();
    }

    /** 水平挪的格数:斜着一步是 √2。 */
    private static double horizontal(Heading heading) {
        return heading.dx() != 0 && heading.dz() != 0 ? Math.sqrt(2) : heading.horizontal() ? 1 : 0;
    }

    /** 飞一步、竖直方向要挪 {@code vertical} 格,要几刻:水平与竖直同时在走,取长的。 */
    static double ticks(CostModel model, Heading heading, double vertical) {
        float speed = speed(model);
        return Math.max(horizontal(heading) * Kinematics.flyTicksPerBlock(speed),
                Math.abs(vertical) * Kinematics.flyClimbTicksPerBlock(speed));
    }

    /** 飞一步按方向的整格数算要几刻;落点的脚高不一定正好是整格({@link Land})的另算。 */
    static double ticks(CostModel model, Heading heading) {
        return ticks(model, heading, heading.dy());
    }

    /** 从巡航速度停下比一路巡航滑完这段距离多花的刻数:降落停在落点要付的。 */
    static double stopLoss(CostModel model) {
        return Kinematics.flyStopLoss(speed(model));
    }

    /** 沿途挡着身体的格:脚底中心从 {@code (from 那一列, fromFeet)} 直飞到 {@code (to 那一列, toFeet)}。 */
    static List<BlockPos> blockers(BlockGetter level, BodyStats body, BlockPos from, double fromFeet, BlockPos to, double toFeet) {
        return Clearance.blockersAlong(level, body, from.getX() + 0.5, fromFeet, from.getZ() + 0.5, to.getX() + 0.5, toFeet,
                to.getZ() + 0.5);
    }

    /**
     * 身体这一步新进入的格:起步与落点两列(斜着飞还有擦过的另两列),脚高在起步与落点之间。起步时身体已经占着的格不算新进入的({@link Contact})。
     */
    static Contact contact(BodyStats body, BlockPos from, double fromFeet, BlockPos to, double toFeet) {
        Contact contact = new Contact(body, from, fromFeet);
        for (int x : new int[] {from.getX(), to.getX()}) {
            for (int z : new int[] {from.getZ(), to.getZ()}) {
                contact.column(x, z, fromFeet, toFeet);
            }
        }
        return contact;
    }

    /** 空中的一步:不改地形、不起跳、不泡水,落点之外没有别的事实。 */
    static Maneuver maneuver(MoveKind kind, Heading heading, BlockPos from, Stance start, BlockPos to, Stance landing, BodyStats body,
                             Draft draft, Contact contact, BlockPos support) {
        return new Maneuver(kind, heading, from, start, to, landing, false, false, false, false,
                Strides.submerged(draft, body, from, start, to, landing), 1, 0, 0, heading.horizontal() ? 1 : 0, draft.edits(),
                contact.cells(), contact.exposure(), support, true);
    }
}
