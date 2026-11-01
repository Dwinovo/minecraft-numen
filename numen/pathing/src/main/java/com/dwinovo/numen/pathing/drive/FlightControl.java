package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.api.entity.Controls.Key;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 起飞、飞、降落的控制器:只按键、只看方向({@link Aloft}),不碰速度与位置。
 *
 * <ul>
 *   <li><b>起飞</b>:身体站在地上,连按两下跳——按、松、按({@link Kinematics#TAKEOFF_TAPS} 刻),第二下切成飞行并起跳;切成了就是飞,
 *       接着往落点那一格上空飞;</li>
 *   <li><b>飞</b>:朝落点的中心飞,脚朝落点格里的高度({@link Aloft#HOVER});</li>
 *   <li><b>降落</b>:朝落点的中心飞,脚朝着落点脚下去,落地就停飞(落地的那一刻由键盘管,{@link com.dwinovo.numen.api.entity.Controls})。</li>
 * </ul>
 * 这一段飞行收在哪里({@code end},{@link Course#flightEnd})一路都在:往那里停,中途的航点不停。
 */
final class FlightControl extends Control {

    /** 降落时脚朝落点脚下这么深的地方去:落地之前一直按着潜行。 */
    private static final double DIG_IN = 0.5;
    /** 水平离落点中心这么近,身子才整个在落点那一格里,可以往下落了。 */
    private static final double OVER = 0.3;

    private final BlockPos end;
    /** 起飞已经按过几刻键。 */
    private int taps;

    FlightControl(Rig rig, Maneuver m, Gait.Stride stride, BlockPos end) {
        super(rig, m, stride);
        this.end = end;
    }

    @Override
    Beat tick() {
        if (m.kind() == MoveKind.TAKEOFF && !rig.entity.getAbilities().flying) {
            return takeoff();
        }
        BlockPos to = m.to();
        double feet = to.getY() + Aloft.HOVER;
        if (m.kind() == MoveKind.LAND) {
            // 先在落点那一格的正上方,再往下落:还在峡谷上空就下去,脚就沉到地面之下了
            boolean above = Math.hypot(to.getX() + 0.5 - rig.entity.getX(), to.getZ() + 0.5 - rig.entity.getZ()) < OVER;
            feet = above ? m.landing().feetY() - DIG_IN : m.from().getY() + Aloft.HOVER;
        }
        Vec3 aim = new Vec3(to.getX() + 0.5, feet, to.getZ() + 0.5);
        Vec3 rest = end == null ? null : new Vec3(end.getX() + 0.5, 0, end.getZ() + 0.5);
        Aloft.steer(rig, aim, rest, feet, true);
        return Beat.IDLE;
    }

    /** 连按两下跳:按、松、按。三刻之后还没飞起来,按键就停在松开上,等看护超时、重搜。 */
    private Beat takeoff() {
        keys().release(Key.SPRINT);
        keys().release(Key.FORWARD);
        keys().release(Key.BACK);
        keys().release(Key.SNEAK);
        if (taps < Kinematics.TAKEOFF_TAPS) {
            keys().set(Key.JUMP, taps != 1);
            taps++;
        } else {
            keys().release(Key.JUMP);
        }
        return Beat.IDLE;
    }
}
