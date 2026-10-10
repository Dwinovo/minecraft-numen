package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;

/**
 * 步态:一步怎么走——疾不疾跑、走完这一步带不带着冲劲进下一步还是收脚。疾跑与收脚全模块只在这里定,控制器照做;
 * 价钱那一头只问"这一步物理上跑不跑得起来"({@link Maneuver#runnable}),也是这里读的同一个事实。
 * 判据只看路线本身的事实(这一步与下一步),不看世界:
 * <ul>
 *   <li><b>疾跑</b>:身体跑得动(饱食度够),这一步物理上跑得起来({@link Maneuver#runnable}),就跑——起跳不打断疾跑
 *       (原版疾跑跳),上一格、下一级也跑。走出边沿的下落(下一级、下落)压着速度走出去,空中飘得远,不在跑动里;
 *       跑酷要助跑({@link Maneuver#runUp})就跑。路径一侧是空着的落坑({@link Maneuver#flanked})的不跑——疾跑时歪一点、冲出去一点就踩空。身体此刻饿不饿、撞没撞墙,由按键照原版客户端的规矩再管一道
 *       ({@link com.dwinovo.numen.api.entity.Controls});</li>
 *   <li><b>带着冲劲进下一步</b>({@link Stride#flows}),否则这一步在落点上收脚:下一步要先原地改地形、下一步贴着落坑、起步或落点不在地上
 *       (水里、攀着没有地面可借力)、自由下落(飘得太远)、下一步不是平地上的走法,都收脚;下一级与跑酷离地之后冲劲带着身子往前飘,
 *       只有下一步朝同一个方向才接得住(冲过了头也还在路上);转弯时下一步用不上的那一份速度(转角不到直角是侧向的那一份,过了直角是
 *       全部)要在落点格里停得住——{@link Kinematics#stopDistance} 不超过身体在格里能多滑出去而不碰邻格的余量(格宽减身宽的一半)。</li>
 * </ul>
 */
final class Gait {

    /** 一步的步态。 */
    record Stride(boolean sprint, boolean flows) {}

    private Gait() {}

    /**
     * @param body      身体(运动学读它的速度)
     * @param maySprint 身体跑得动
     * @param m         这一步
     * @param next      路线上的下一步;这是最后一步为 null
     */
    static Stride stride(BodyStats body, boolean maySprint, Maneuver m, Maneuver next) {
        return new Stride(sprints(maySprint, m), flows(body, m, next));
    }

    private static boolean sprints(boolean maySprint, Maneuver m) {
        if (!maySprint) {
            return false;
        }
        return switch (m.kind()) {
            case PARKOUR -> true;
            case WALK, DIAGONAL, ASCEND -> m.runnable() && m.flanked();
            case DESCEND, FALL, PILLAR, DOWNWARD, CLIMB, SWIM -> false;
        };
    }

    private static boolean flows(BodyStats body, Maneuver m, Maneuver next) {
        if (next == null || !next.edits().isEmpty() || !m.landing().grounded() || m.wading()
                || !next.start().grounded() || m.kind() == MoveKind.FALL) {
            return false;
        }
        boolean flat = switch (next.kind()) {
            case WALK, DIAGONAL, ASCEND -> next.flanked();
            case DESCEND, FALL, PARKOUR -> true;
            case PILLAR, DOWNWARD, CLIMB, SWIM -> false;
        };
        if (!flat) {
            return false;
        }
        boolean sameWay = next.heading().dx() == m.heading().dx() && next.heading().dz() == m.heading().dz();
        boolean airborne = m.kind() == MoveKind.DESCEND || m.kind() == MoveKind.PARKOUR;
        return airborne ? sameWay : turnAbsorbed(body, m, next);
    }

    /** 转向之后下一步用不上的那一份速度,在落点格里停得住。 */
    private static boolean turnAbsorbed(BodyStats body, Maneuver m, Maneuver next) {
        double ax = m.heading().dx();
        double az = m.heading().dz();
        double bx = next.heading().dx();
        double bz = next.heading().dz();
        double cos = (ax * bx + az * bz) / (Math.hypot(ax, az) * Math.hypot(bx, bz));
        double wasted = cos > 0 ? Math.sqrt(1 - cos * cos) : 1;
        double slack = (1 - body.width()) / 2;
        return Kinematics.stopDistance(body, Kinematics.sprintSpeed(body) * wasted) <= slack;
    }
}
