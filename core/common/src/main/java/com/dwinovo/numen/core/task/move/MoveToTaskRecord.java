package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for {@code numen.move.go(plan)}, which the library function {@code numen.move.to} calls: walk a plan that
 * {@code numen.route.plan} made in the same program. The plan itself — stops, description, the way she saw — rides along
 * in memory; it is not saved, so a restart has nothing to walk and the task ends saying so. The deadline is handled by
 * the base class.
 */
public final class MoveToTaskRecord extends TaskRecord {

    /** 基础期限:30 秒,出发后按路程再往后推(见 {@code MoveToCompanionTask})。 */
    private static final long BUDGET_TICKS = 30 * 20;

    /** 走哪一份计划。 */
    public final Plan plan;

    public MoveToTaskRecord(ServerCall source, Plan plan) {
        super(source, source.her().level().getGameTime() + BUDGET_TICKS);
        this.plan = plan;
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        var stops = plan.description().stops();
        return (plan.description().mode() == com.dwinovo.numen.core.route.Description.Mode.BOAT ? "驾船," : "")
                + stops.get(stops.size() - 1).describe() + (stops.size() > 1 ? "(途经 " + (stops.size() - 1) + " 处)"
                : "");
    }
}
