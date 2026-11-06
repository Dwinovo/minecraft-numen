package com.dwinovo.numen.task.move;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.nav.Walked;
import com.dwinovo.numen.pathing.api.Travel;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

/**
 * {@code numen.move.walk}、{@code numen.move.fly}、{@code numen.move.glide} 与 {@code numen.move.away} 这件活的记录:走、创造飞行或滑翔({@link Travel})到哪儿({@link Arrival})。
 * 动手的活够不着时替自己走过去也用它({@link #errand}),那时只走路、不驾船。基础期限是 30 秒,出发后按路程再往后推(见 {@link MoveToCompanionTask})。
 */
public final class MoveToTaskRecord extends TaskRecord<Walked> {

    /** 基础期限:30 秒,出发后按路程再往后推(见 {@code MoveToCompanionTask})。 */
    private static final long BUDGET_TICKS = 30 * 20;

    /** 怎样算到了。 */
    public final Arrival arrival;
    /** 怎么去:{@code move.walk}、{@code move.away}、替自己走过去是走,{@code move.fly} 是创造飞行,{@code move.glide} 是鞘翅滑翔。 */
    public final Travel travel;
    /** 坐在船上可以驾船过去;不可以(动手的活替自己走路)就只走路,坐着任何载具都要先下来。 */
    public final boolean sailing;

    public MoveToTaskRecord(ServerCall source, Arrival arrival, Travel travel) {
        super(source, source.her().level().getGameTime() + BUDGET_TICKS);
        this.arrival = arrival;
        this.travel = travel;
        this.sailing = true;
    }

    private MoveToTaskRecord(TaskRecord<?> parent, long now, Arrival arrival) {
        super(parent.getToolName(), parent.getToolCallId(), now + BUDGET_TICKS);
        this.arrival = arrival;
        this.travel = Travel.WALK;
        this.sailing = false;
    }

    /** {@code parent} 这件活够不着时替自己走过去的一趟:挂在它名下,只走路。 */
    public static MoveToTaskRecord errand(TaskRecord<?> parent, long now, Arrival arrival) {
        return new MoveToTaskRecord(parent, now, arrival);
    }

    /**
     * 一行人话 —— 这是<b>给模型看的</b>:{@code <current_task>} 与 task status 印的是它,主人看的是 {@link #activity()}。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return arrival.describe();
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return arrival.activity();
    }
}
