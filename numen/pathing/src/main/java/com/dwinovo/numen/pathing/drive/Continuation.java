package com.dwinovo.numen.pathing.drive;

import java.util.function.ToDoubleFunction;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.search.Route;

/**
 * 接续:一条没到目标的路线(半程路线)走到快完了,从它的终点提前搜下一段,接在后面,段与段之间不停顿。管两件事:
 * <ul>
 *   <li><b>何时搜</b>:路线剩下的步子几何上最短还要 {@link #LOOKAHEAD_TICKS} 刻以内({@link Course#remainingTicks},运动学算的)就搜。
 *       按路线上的位置定,不按墙钟;</li>
 *   <li><b>何时能接着走</b>:身体只在"后面接什么"已经定下来之后,才开始走路线末尾那几步——接续段到了(或者确认接不上、没有后面了),
 *       末尾那几步才开始。接续段先到还是后到,只改变身体要不要在末尾前几步的起点上等一等,不改变每一步怎么走:后面有没有路由路线数据
 *       定,不由工作线程快慢定。末尾那几步的范围是认步看得到的范围({@link Tracker#WINDOW})再加这一步。</li>
 * </ul>
 */
final class Continuation {

    /** 路线剩下的步子最短还要这么多刻就提前搜下一段。 */
    static final double LOOKAHEAD_TICKS = 100;
    /** 末尾这么多步要等"后面接什么"定下来才开始走:认步往前看的 {@link Tracker#WINDOW} 步,再加走这一步时要看的下一步。 */
    static final int TAIL = Tracker.WINDOW + 1;

    /** 从这条路线的终点接着搜过,没搜出能接上的一段。 */
    private boolean failed;

    /** 该提前搜下一段了。 */
    boolean due(Course course) {
        return !course.complete() && !failed && !course.isEmpty() && course.remainingTicks() < LOOKAHEAD_TICKS;
    }

    /** 能开始走第 {@code course.cur()} 步:路线已到目标,或后面不会再有路了,或这一步离末尾够远、后面的步子都已在手。 */
    boolean settled(Course course) {
        return course.complete() || failed || course.cur() + TAIL < course.size();
    }

    /**
     * 接续段 {@code found} 搜出来了(没搜出路线为 null):起点接得上就拼在路线后面,接不上就认作没有后面了。
     *
     * @return 拼上了
     */
    boolean splice(Course course, Route found, boolean arrived, ToDoubleFunction<Maneuver> ticks) {
        if (found != null && found.start().equals(course.end())) {
            course.extend(found, arrived, ticks);
            return true;
        }
        failed = true;
        return false;
    }

    /** 接续没搜出来:不再提前搜了,走到终点再从脚下搜。 */
    boolean failed() {
        return failed;
    }

    /** 换了新路线,或扔掉了旧的:重新开始。 */
    void reset() {
        failed = false;
    }
}
