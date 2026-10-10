package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.world.level.BlockGetter;

/**
 * 步态:一步怎么走——疾不疾跑、走完这一步带不带着冲劲进下一步还是收脚。疾跑与收脚全模块只在这里定,控制器照做;
 * 价钱那一头只问"这一步物理上跑不跑得起来"({@link Maneuver#runnable}),也是这里读的同一个事实。
 * 判据来自路线上相邻两步的事实与脚下的世界,冲劲接不接得住按运动学算({@link Kinematics#stopDistance}):
 * <ul>
 *   <li><b>疾跑</b>:身体跑得动(饱食度够),这一步物理上跑得起来({@link Maneuver#runnable}),路径两侧站得稳
 *       ({@link Stepping#flanked}:一侧是空着的落坑,疾跑时歪一点就踩空),落点格里的余量({@link Stepping#slideRoom})够停下来
 *       (炼药锅里这样的窄落点只比身体宽一点点,冲进去就撞出来),就跑——起跳不打断疾跑(原版疾跑跳),上一格、下一级也跑。
 *       走出边沿的下落(下一级、下落)压着速度走出去,空中飘得远,不在跑动里;跑酷要助跑({@link Maneuver#runUp})就跑。
 *       身体此刻饿不饿、撞没撞墙,由按键照原版客户端的规矩再管一道({@link com.dwinovo.numen.api.entity.Controls});</li>
 *   <li><b>带着冲劲进下一步</b>({@link Stride#flows}),否则这一步在落点上收脚:这一步没有水平方向(垫柱、向下挖),没有冲劲可带;下一步要先原地改地形、下一步贴着落坑、起步或
 *       落点不在地上(水里、攀着没有地面可借力)、自由下落(飘得太远)、下一步不是平地上的走法,都收脚;下一级与跑酷离地之后冲劲
 *       带着身子往前飘,只有下一步朝同一个方向才接得住(冲过了头也还在路上);转弯时下一步用不上的那一份速度(转角不到直角是侧向的
 *       那一份,过了直角是全部)要在落点格里停得住——停下来要滑的距离不超过落点格里的余量。</li>
 * </ul>
 */
final class Gait {

    /** 一步的步态。 */
    record Stride(boolean sprint, boolean flows) {}

    private Gait() {}

    /**
     * 这一步的步态。从窗口末尾往回一步一步定:窗口最后一步当它要在落点上收脚,前一步带着冲劲进它,就要它的落点接得住;
     * 它自己带着冲劲往下走(连着的上坡、台阶)就不用停,落点前贴着墙也无妨。每一步先试疾跑:身体跑得动、路径两侧站得稳、物理上跑得起来,
     * 冲劲能带进下一步就连着跑,带不进去就看落点格里的余量够不够在这一步收脚时停下来;跑不了再退到走,走着能带进下一步就带。
     *
     * @param level     脚下的世界
     * @param body      身体(运动学读它的速度)
     * @param maySprint 身体跑得动
     * @param steps     这一步,和路线上紧接着它的几步(够不够都行,最后一步没有下一步)
     */
    static Stride stride(BlockGetter level, BodyStats body, boolean maySprint, List<Maneuver> steps) {
        Stride after = null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            after = stride(level, body, maySprint, steps.get(i), i + 1 < steps.size() ? steps.get(i + 1) : null, after);
        }
        return after;
    }

    private static Stride stride(BlockGetter level, BodyStats body, boolean maySprint, Maneuver m, Maneuver next, Stride onward) {
        double run = Kinematics.sprintSpeed(body);
        if (maySprint && canRun(level, body, m)) {
            if (flows(level, body, run, m, next, onward)) {
                return new Stride(true, true);
            }
            if (m.kind() == MoveKind.PARKOUR || Kinematics.stopDistance(body, run) <= room(level, body, m)) {
                return new Stride(true, false);
            }
        }
        return new Stride(false, flows(level, body, Kinematics.walkSpeed(body), m, next, onward));
    }

    /** 这一步能不能在跑动里走:跑酷要助跑就跑;平走、斜走、上一级要物理上跑得起来、路径两侧站得稳;走出边沿的下落、攀、游、垫、挖不在跑动里。 */
    private static boolean canRun(BlockGetter level, BodyStats body, Maneuver m) {
        return switch (m.kind()) {
            case PARKOUR -> true;
            case WALK, DIAGONAL, ASCEND -> m.runnable() && flanked(level, body, m);
            case DESCEND, FALL, PILLAR, DOWNWARD, CLIMB, SWIM -> false;
        };
    }

    /**
     * 带着 {@code speed}(格每刻)的冲劲走完 {@code m} 进下一步,接不接得住。{@code onward} 是下一步自己的步态:它也带着冲劲往下走
     * 就不用在它的落点上停;没有(窗口到头,或路线到头)就当它要收脚。
     */
    private static boolean flows(BlockGetter level, BodyStats body, double speed, Maneuver m, Maneuver next, Stride onward) {
        if (next == null || !m.heading().horizontal() || !next.edits().isEmpty() || !m.landing().grounded() || m.wading()
                || !next.start().grounded() || m.kind() == MoveKind.FALL) {
            return false;
        }
        boolean flat = switch (next.kind()) {
            // 带进来的速度,下一步要收脚时它的落点格里要停得住;下一步贴着落坑就不带
            case WALK, DIAGONAL, ASCEND -> flanked(level, body, next)
                    && (onward != null && onward.flows() || Kinematics.stopDistance(body, speed) <= room(level, body, next));
            case DESCEND, FALL, PARKOUR -> true;
            case PILLAR, DOWNWARD, CLIMB, SWIM -> false;
        };
        if (!flat) {
            return false;
        }
        boolean sameWay = next.heading().dx() == m.heading().dx() && next.heading().dz() == m.heading().dz();
        boolean airborne = m.kind() == MoveKind.DESCEND || m.kind() == MoveKind.PARKOUR;
        return airborne ? sameWay : turnAbsorbed(level, body, speed, m, next);
    }

    /** 转向之后下一步用不上的那一份速度,在落点格里停得住。 */
    private static boolean turnAbsorbed(BlockGetter level, BodyStats body, double speed, Maneuver m, Maneuver next) {
        double ax = m.heading().dx();
        double az = m.heading().dz();
        double bx = next.heading().dx();
        double bz = next.heading().dz();
        double cos = (ax * bx + az * bz) / (Math.hypot(ax, az) * Math.hypot(bx, bz));
        double wasted = cos > 0 ? Math.sqrt(1 - cos * cos) : 1;
        return Kinematics.stopDistance(body, speed * wasted) <= room(level, body, m);
    }

    private static boolean flanked(BlockGetter level, BodyStats body, Maneuver m) {
        return Stepping.flanked(level, body, m.from(), m.start().feetY(), m.to(), m.landing().feetY(), m.heading().dx(), m.heading().dz());
    }

    /** 落点格里顺着这一步的方向,身体中心还能滑出去多远。 */
    private static double room(BlockGetter level, BodyStats body, Maneuver m) {
        return Stepping.slideRoom(level, body, m.to().getX(), m.landing().feetY(), m.to().getZ(), m.heading().dx(), m.heading().dz());
    }
}
