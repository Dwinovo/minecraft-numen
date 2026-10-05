package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;

/**
 * 这一刻疾不疾跑,全模块只在这里定。能不能跑是规划定的({@link Maneuver#sprint()}:规格许、身体跑得动、这一步不挖不放
 * 不涉水不起跳),执行层只在上面加一条:要停下的地方不跑——跑着停不住。所以一步能跑,还得下一步接着走、下一步也能跑
 * (或是要疾跑助跑的跑酷);跑酷本身要跑时就跑。身体此刻饿不饿、撞没撞墙,由按键照原版客户端的规矩再管一道
 * ({@link com.dwinovo.numen.pathing.body.Controls})。
 */
final class SprintPolicy {

    private SprintPolicy() {}

    /**
     * @param flows 走完这一步不停下,接着走下一步
     */
    static boolean sprint(Maneuver m, Maneuver next, boolean flows) {
        if (!m.sprint()) {
            return false;
        }
        if (m.kind() == MoveKind.PARKOUR) {
            return true;
        }
        return flows && next.sprint();
    }
}
