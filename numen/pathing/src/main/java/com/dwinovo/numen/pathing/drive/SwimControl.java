package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Footing;

import net.minecraft.world.phys.Vec3;

/**
 * 游:往上按住跳(原版在水里按住跳就往上浮),往下按住潜行(原版客户端潜行时往下沉),平挪时脚沉到这一格的下半截就按跳托住;
 * 水平方向朝落点那一列游。水里不疾跑:疾跑会变成游泳姿势,身子只剩 0.6 高。
 *
 * <p>落点是水底的浅水格(规划算它是站着,脚踩在水底上,{@link com.dwinovo.numen.pathing.plan.Stance.Kind#GROUND}):
 * 原版水里不按键每刻只沉 0.005 格,身子浮在水底之上老远,永远落不到水底;所以平挪到了落点那一列的中心,就按住潜行沉下去,
 * 沉到踩上水底为止——不再按跳托住,段状态机才认得"站在了落点上"。
 */
final class SwimControl extends Control {

    SwimControl(Rig rig, Maneuver m, Gait.Stride stride) {
        super(rig, m, stride);
    }

    /** 按住跳托起来之后松开,身子靠冲劲还要再往上蹿的高度。 */
    private static final double BOB = 0.3;

    /**
     * 平挪时脚沉到这个高度以下就按跳托住:落点那一格的下半截({@link Control#FLOAT});起步到落点的途中头顶压着低顶板、或交界处立着门板(只留不到半格净空)时,
     * 托到 FLOAT 那么高头就撞上顶板边、挪不过去,线降到身子放得下的最高脚高再留一次冲劲的余量(最低是落点那一格的底:再沉就掉进下一个节点了)。
     */
    private double floatLine() {
        double line = m.to().getY() + FLOAT;
        if (!m.heading().horizontal()) {
            return line;
        }
        return Math.max(m.to().getY(), Math.min(line, m.to().getY() + headroom() - BOB));
    }

    /**
     * 身子在起步那一列、脚升高 {@code h}(0 到 1)时,朝落点那一列挪过去的途中(两列的盒与交界处的门板、薄板)是否都放得下;
     * 放得下的最高的 {@code h}。
     */
    private double headroom() {
        var stats = rig.snapshot().stats();
        for (double h = 1.0; h > 0; h -= 0.05) {
            if (Clearance.blockers(rig.world(), stats, m.from().getX(), m.from().getY() + h,
                    m.from().getZ(), m.heading().dx(), m.heading().dz()).isEmpty()) {
                return h;
            }
        }
        return 0;
    }

    @Override
    Beat tick() {
        Beat edits = editsInPlace(allEdits());
        if (edits != null) {
            return edits;
        }
        keys().release(Key.SPRINT);
        int dy = m.to().getY() - m.from().getY();
        double feet = rig.entity.getY();
        Vec3 c = center(m.to());
        if (m.landing().grounded()) {
            boolean centered = horizontalDistance(c) < CENTERED;
            boolean sinking = dy < 0 || dy == 0 && centered && !rig.entity.onGround();
            // 落点踩在比起步高的东西上(水面那一格里的半砖):是顶着它出水,按住跳往上蹿,到了它上面的列中心才沉下去站稳
            boolean climbing = dy == 0 && m.landing().feetY() > m.start().feetY() + Footing.EPSILON && !centered;
            // 落点踩在这一格的水底、起步也在这一格里:没到落点那一列的中心之前不沉——沉到这一格的底以下,就撞上落点那一列水底的侧面了
            boolean afloat = dy == 0 && !centered && feet < floatLine();
            keys().set(Key.JUMP, dy > 0 || climbing || afloat);
            keys().set(Key.SNEAK, sinking && !climbing && !afloat);
        } else {
            keys().set(Key.JUMP, dy > 0 || dy == 0 && feet < floatLine());
            keys().set(Key.SNEAK, dy < 0);
        }
        if (stride.flows()) {
            Steering.pass(rig, c.x, c.z);
        } else {
            Steering.stop(rig, c.x, c.z, m.landing().feetY());
        }
        return Beat.IDLE;
    }
}
