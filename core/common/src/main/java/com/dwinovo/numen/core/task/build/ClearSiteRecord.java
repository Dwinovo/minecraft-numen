package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * 建造清场这件子活的记录({@code build at} 是工作流,见 {@link BuildCompanionTask}):工地里要挖掉的格、工地这块工作区、走动的规格。
 * 名字与调用编号随派它的那件建造,期限按要挖的格数从此刻算;挖完为止。
 */
public final class ClearSiteRecord extends TaskRecord {

    /** 一格的期限给得宽;总数随活的大小走,大活不至于超时。 */
    private static final long TICKS_PER_BLOCK = 30 * 20;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;

    /** 走动的规格(还没关进工作区):在 {@link DigTaskRecord#SPEC} 上护着图纸里不清的格。 */
    public final RouteSpec spec;
    /** 要挖的格。 */
    public final Cells cells;
    /** 工作区:工地外扩一圈,目标只取区里的,走动关在区里。 */
    public final WorkArea work;
    /** 要挖的那几种方块:捡掉落物按它们掉的东西认。 */
    public final Set<Block> targets;
    /** 派发时要挖的格数。 */
    public final int total;
    /** 回执里怎么称呼要挖的东西。 */
    public final String what;
    /** 这几种方块的简称({@code dirt}、{@code oak_log+1}),主人也读它。 */
    public final String label;

    /** 进度:挖掉的格数。任务每刻写。 */
    private int mined = 0;

    public ClearSiteRecord(TaskRecord parent, long now, RouteSpec spec, Cells cells, WorkArea work, Set<Block> targets,
                           String what, String label) {
        super(parent.getToolName(), parent.getToolCallId(), now + timeoutTicks((int) cells.size()));
        this.spec = spec;
        this.cells = cells;
        this.work = work;
        this.targets = Set.copyOf(targets);
        this.total = (int) cells.size();
        this.what = what;
        this.label = label;
    }

    /** 这一格此刻要不要挖(按本件活的格子,判据见 {@link DigTaskRecord#wants})。 */
    public boolean wantsAt(BlockPos pos, net.minecraft.world.level.block.state.BlockState now) {
        return DigTaskRecord.wants(cells.seenAt(pos), now);
    }

    /** 挖 {@code blocks} 格的期限预算。 */
    private static long timeoutTicks(int blocks) {
        return Math.max(MIN_TIMEOUT_TICKS, (long) blocks * TICKS_PER_BLOCK);
    }

    public int getMined() {
        return mined;
    }

    /** Set the running tally (the task recomputes it each tick). */
    public void setMined(int dug) {
        this.mined = dug;
    }

    /** 一行人话,给主人看的:头顶气泡、面板、task status 印的都是它。 */
    @Override
    public String describe() {
        return "挖 " + label + " " + mined + "/" + total + " 格";
    }
}
