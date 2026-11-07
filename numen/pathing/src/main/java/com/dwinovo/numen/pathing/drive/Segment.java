package com.dwinovo.numen.pathing.drive;

import java.util.List;

import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;

/**
 * 一趟行程里的一段:走路的一段({@link Driver})或滑翔的一段({@link Sortie})。每段一种移动方式,共用一套家伙({@link Rig}:同一本账、
 * 同一份行程日志)。行程({@link Voyage})把几段串起来,段与段之间只通过"这一段到了没有"相连,不互相知道细节。
 */
public interface Segment {

    /** 这一段的状态。 */
    Driver.State state();

    /** 推一刻。 */
    Driver.State tick();

    /** 这一段收场的原因;没收场或不是因为出事为 null。 */
    Halt halt();

    /** 在推进。 */
    boolean progressing();

    /** 身体此刻站着等搜索的结论。 */
    boolean waiting();

    /** 还没走完的步,按先后;没有路线的段为空。 */
    List<Route.Leg> remaining();

    /** 身体此刻是计划内的坠落或滑翔:宿主的摔落反射只接管计划外的。 */
    boolean plannedFall();

    /** 身体此刻在计划内的一段水下。 */
    boolean plannedDive();

    /** 换目标。 */
    void retarget(Goal goal);

    /** 叫停:松开所有键。 */
    void stop();

    /** 最近一次"离目标最近处被拒"的那一步;没有为 null。 */
    Blockage nearestRefusal();
}
