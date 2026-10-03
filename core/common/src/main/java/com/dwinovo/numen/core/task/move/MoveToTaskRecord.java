package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for {@code numen.move.go(route)}, which the library function {@code numen.move.goto_} calls: walk a
 * route by name. The route itself — waypoints, flags, the plan she saw — lives in the owner's route store, not here; a
 * {@code numen.move.goto_} first writes her own anonymous route and then walks it like any other. The deadline is handled by
 * the base class.
 */
public final class MoveToTaskRecord extends TaskRecord {

    /** 基础期限:30 秒,出发后按路程再往后推(见 {@code MoveToCompanionTask})。 */
    private static final long BUDGET_TICKS = 30 * 20;

    /** 走哪条路线。 */
    public final String route;
    /** 给主人看的那一句。 */
    private final String label;
    /** 受理时交代给模型的那一句;没有为 null。 */
    private final String note;

    /**
     * @param label 头顶气泡、面板上给主人看的一句
     * @param note  受理回执里要交代的事实(比如 {@code numen.move.goto_} 把这一趟记成了哪条路线);没有为 null
     */
    public MoveToTaskRecord(ServerSource source, String route, String label, String note) {
        super(source, source.companion().level().getGameTime() + BUDGET_TICKS);
        this.route = route;
        this.label = label;
        this.note = note;
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return label;
    }

    @Override
    public String acceptNote() {
        return note;
    }
}
