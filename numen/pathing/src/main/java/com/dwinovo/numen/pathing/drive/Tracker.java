package com.dwinovo.numen.pathing.drive;

import java.util.function.Predicate;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Stance;

import net.minecraft.core.BlockPos;

/**
 * 认步:身体此刻在路线上的哪一步。身体落在了哪个节点,照搜索定起点的同一条规则({@link com.dwinovo.numen.pathing.search.Origin})
 * 定;它稳稳落在(站着、攀着、浮着,与那一步的落点一致)后面某一步的落点上,就认到那一步——冲劲把她带过了一两步也不回头;落在前面
 * 某一步的起点上(被推回去了),就退回那一步;落在路线之外一阵子,就认作丢了路线。只看路线和身体落在的节点,不碰身体。
 */
final class Tracker {

    /** 认步时往前往后看几步。 */
    static final int WINDOW = 4;
    /** 落在路线之外这么多刻就算丢了路线。 */
    static final int OFF_ROUTE_TICKS = 15;

    /** 认出来的结果。 */
    sealed interface Fix {
        /** 前 {@code to} 步走完了:身体稳稳落在第 {@code to} 步(下标 {@code to - 1})的落点上。 */
        record Advanced(int to) implements Fix {}

        /** 落回了前面一步 {@code earlier} 的起点:正在走的那一步没走成。 */
        record FellBack(Maneuver earlier) implements Fix {}

        /** 落在路线之外够久了。 */
        record Lost() implements Fix {}

        /** 还在这一步上,或者说不上来(落在路线之外但还没够久)。 */
        record Here() implements Fix {}
    }

    private int stray;

    /**
     * @param node    身体此刻落在的节点;悬在半空、卡在方块里为 null(说不上来)
     * @param settled 身体此刻稳稳地以这种方式待着
     */
    Fix locate(Course course, BlockPos node, Predicate<Stance.Kind> settled) {
        if (node == null) {
            return new Fix.Here();
        }
        int last = Math.min(course.cur() + WINDOW, course.size());
        for (int j = last; j > course.cur(); j--) {
            Maneuver done = course.at(j - 1);
            if (done.to().equals(node) && settled.test(done.landing().kind())) {
                stray = 0;
                return new Fix.Advanced(j);
            }
        }
        Maneuver current = course.current();
        if (node.equals(current.from()) || node.equals(current.to())) {
            stray = 0;
            return new Fix.Here();
        }
        for (int j = Math.max(0, course.cur() - WINDOW); j < course.cur(); j++) {
            Maneuver earlier = course.at(j);
            if (earlier.from().equals(node) && settled.test(earlier.start().kind())) {
                stray = 0;
                return new Fix.FellBack(earlier);
            }
        }
        if (settled.test(Stance.Kind.GROUND) || settled.test(Stance.Kind.CLIMBING) || settled.test(Stance.Kind.SWIMMING)) {
            return strays() ? new Fix.Lost() : new Fix.Here();
        }
        return new Fix.Here();
    }

    /** 又一刻落在路线之外:够久了为 true(并重新计数)。到达判定里"没停在终点"也数这一份。 */
    boolean strays() {
        if (++stray > OFF_ROUTE_TICKS) {
            stray = 0;
            return true;
        }
        return false;
    }

    /** 回到路线上,或换了新路线:重新计数。 */
    void reset() {
        stray = 0;
    }
}
