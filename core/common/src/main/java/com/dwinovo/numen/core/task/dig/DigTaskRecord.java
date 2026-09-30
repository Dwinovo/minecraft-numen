package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * 挖掘这件活的记录:{@code work dig}(快捷工具 {@code work_dig})与建造清场派下的都是它。要挖的格由区域的数据定,不另设开关
 * ({@link #wants}):扫描来的格只挖还是当时那种方块的,框出来的格与点里面是什么挖什么(空气、流体跳过)。只在工作区
 * ({@link #work})里干,区外的只报告({@link #beyond})。
 *
 * <p>区域在派发这一刻解析好、分好区里区外,记录里带着格子;扫描来的格附带当时的方块,框出来的格不带。
 */
public final class DigTaskRecord extends TaskRecord {

    /**
     * 挖的时候怎么走:可以挖开、垫高天然地形,要主人同意的格不进路线——跟前要问的格在动手那一刻逐格问,不先整条规划。
     * 走动关在工作区里({@link WorkArea#confine})。
     */
    public static final RouteSpec SPEC = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();

    /** 走动的规格(还没关进工作区):{@code work dig} 是 {@link #SPEC},建造清场在它上面护着图纸里不清的格。 */
    public final RouteSpec spec;

    /** {@link #count} 取这个值:没给 count,挖完区里要挖的格为止。 */
    public static final int UNTIL_GONE = 0;

    /** 一格的期限给得宽;总数随活的大小走,大活不至于超时。 */
    private static final long TICKS_PER_BLOCK = 30 * 20;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;

    /** 要挖的格(点名的区域与坐标的并);扫描来的附带当时的方块。 */
    public final Cells cells;
    /** 工作区:目标只取区里的,走动关在区里。 */
    public final WorkArea work;
    /** 区外的格:只报告。 */
    public final Beyond beyond;
    /** 区里此刻要挖的那几种方块:数件数按它们掉的东西。 */
    public final Set<Block> targets;
    /** 派发时区里要挖的格数。 */
    public final int total;
    /** How many ITEMS to gather before reporting success, or {@link #UNTIL_GONE}. */
    public final int count;
    /** 回执里怎么称呼要挖的东西({@code ores/g3}、{@code 10,64,5})。 */
    public final String what;
    /** 这几种方块的简称({@code iron_ore}、{@code oak_log+1}),主人也读它。 */
    public final String label;
    /** 再扫一遍时扫进哪块区域:点名的第一块区域;只点了坐标为 null。 */
    public final AreaRef into;

    /** 进度:没给 count 时是挖掉的格数,给了是新到手的物品数。任务每刻写。 */
    private int mined = 0;

    public DigTaskRecord(ServerSource source, long deadlineGameTime, Cells cells, WorkArea work, Beyond beyond,
                         Set<Block> targets, int total, int count, String what, String label, AreaRef into) {
        super(source, deadlineGameTime);
        this.spec = SPEC;
        this.cells = cells;
        this.work = work;
        this.beyond = beyond;
        this.targets = Set.copyOf(targets);
        this.total = total;
        this.count = count;
        this.what = what;
        this.label = label;
        this.into = into;
    }

    /**
     * 另一件活派下的挖掘(建造清场):名字与调用编号随派它的那件活,期限按要挖的格数从此刻算;挖完为止,工作区与走动的规格由
     * 派它的一方给。
     */
    public DigTaskRecord(TaskRecord parent, long now, RouteSpec spec, Cells cells, WorkArea work, Set<Block> targets,
                         String what, String label) {
        super(parent.getToolName(), parent.getToolCallId(), now + timeoutTicks((int) cells.size()));
        this.spec = spec;
        this.cells = cells;
        this.work = work;
        this.beyond = new Beyond(Cells.EMPTY, null, what, null);
        this.targets = Set.copyOf(targets);
        this.total = (int) cells.size();
        this.count = UNTIL_GONE;
        this.what = what;
        this.label = label;
        this.into = null;
    }

    /**
     * 这一格此刻要不要挖:扫描来的格({@code seen} 不为 null)还是当时那种方块({@link Cells.Seen#holds})才挖;框出来的格与点
     * 里面有方块就挖,空气与流体不挖。判据只此一处:派发时数格、干活时收候选都问它。
     */
    public static boolean wants(Cells.Seen seen, BlockState now) {
        if (seen != null) {
            return seen.holds(now);
        }
        return !now.isAir() && !(now.getBlock() instanceof LiquidBlock);
    }

    /** 这一格此刻要不要挖(按本件活的格子)。 */
    public boolean wantsAt(BlockPos pos, BlockState now) {
        return wants(cells.seenAt(pos), now);
    }

    /** 挖 {@code blocks} 格(或收 {@code blocks} 个物品)的期限预算。 */
    public static long timeoutTicks(int blocks) {
        return Math.max(MIN_TIMEOUT_TICKS, (long) blocks * TICKS_PER_BLOCK);
    }

    /**
     * 照抄就能把 {@code blocks} 在她工作区那么大的范围里扫进 {@code into} 的那一行:回执让模型"先扫、再挖"时写的就是它。
     *
     * @param blocks 要扫的方块,空格隔开
     */
    public static String rescan(String blocks, String into) {
        return "scan blocks " + WorkArea.RADIUS + " " + blocks + " --into " + into;
    }

    public int getMined() {
        return mined;
    }

    /** Set the running tally (the task recomputes it each tick). */
    public void setMined(int gathered) {
        this.mined = gathered;
    }

    /** 受理时交代工作区在哪;有格落在区外的,说有几格、它们留着不挖,以及怎么开路过去。 */
    @Override
    public String acceptNote() {
        String where = "My work area is " + work.describe() + ", where I stand now: " + total + " cell(s) of " + what
                + " to dig lie in it";
        return beyond.isEmpty() ? where + "." : where + "; " + beyond.told(work.center());
    }

    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    @Override
    public String describe() {
        return count == UNTIL_GONE
                ? "挖 " + label + " " + mined + "/" + total + " 格"
                : "挖 " + label + " " + mined + "/" + count;
    }
}
