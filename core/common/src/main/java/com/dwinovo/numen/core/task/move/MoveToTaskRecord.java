package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for {@code move goto} (shortcut {@code move_goto}): either a {@link Destination} — coordinates
 * plus how arrival counts, its goal already built when the call was accepted — or a {@code route} id, a route the
 * planner already priced (a {@code move goto} refusal or a {@code move route} reply listed it by id), whose destination
 * and spec are the route's own. The deadline is handled by the base class.
 *
 * <p>{@link #spec} is the parsed route spec the walk searches and executes under ({@link RouteSpec#defaults()} = never
 * changes a block). It is {@code null} only for the route form, whose spec travels with the route.
 */
public final class MoveToTaskRecord extends TaskRecord {

    /** 基础期限:30 秒,出发后按路程再往后推(见 {@code MoveToCompanionTask})。 */
    private static final long BUDGET_TICKS = 30 * 20;

    /** 去处;走路线簿里的一条时为 null。 */
    public final Destination destination;
    /** 路线簿里的编号;按坐标走时为 null。 */
    public final String route;
    /** 按坐标走的规格;走路线时为 null。 */
    public final RouteSpec spec;

    public MoveToTaskRecord(ServerSource source, Destination destination, RouteSpec spec, String route) {
        super(source, source.companion().level().getGameTime() + BUDGET_TICKS);
        if ((destination == null) == (route == null)) {
            throw new IllegalArgumentException("goto 要么按坐标走,要么走一条路线");
        }
        this.destination = destination;
        this.route = route;
        this.spec = spec;
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        String where = destination == null ? "走路线 " + route : destination.describe();
        return spec != null && spec.alter().mayAlter() ? where + "(可开路)" : where;
    }
}
