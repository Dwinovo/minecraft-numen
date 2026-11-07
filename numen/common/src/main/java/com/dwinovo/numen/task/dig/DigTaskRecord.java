package com.dwinovo.numen.task.dig;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.area.Cells;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.Target;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.api.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * {@code numen.work.dig} 这件活的记录:点名的几格里要挖的,她站在原地手够得着的那些挖掉。要挖的格由点名的方式定,不另设开关
 * ({@link #wants}):点名的 Block 只挖还是那种方块的,点名的 Pos 里面是什么挖什么(空气、流体跳过)。
 *
 * <p>记录里带着格子;点名 Block 的格附带当时的方块,点名 Pos 的不带。
 */
public final class DigTaskRecord extends TaskRecord<DigCompanionTask.Dug> {

    /**
     * 挡在前面的格清不清得掉按这份规格问:天然地形可以挖开,要主人同意的、规则不许的不挖(不问,如实说是哪一格、哪条规则)。
     * 建造清场的走动也从它起({@code ClearSiteRecord})。
     */
    public static final RouteSpec SPEC = RouteSpec.defaults().edit().changes(true).consent(false).build();

    /**
     * 点名的目标按这份规格定价、判挖不挖得成:要问主人的格也算挖得成({@link RouteSpec#consent})——目标是她点名要挖的,要问主人的照价乘倍、
     * 动手前问,规则不许的、物理上挖不成的价钱无穷。{@code numen.work.dig} 挑目标与走去挖时挑能去挖的格({@code Arrival.dig})都按它。
     */
    public static final RouteSpec TARGET_SPEC = SPEC.edit().consent(true).build();

    /** {@link #count} 取这个值:没给 {@code --count},手够得着的都挖。 */
    public static final int ALL = 0;

    /** 一格的期限给得宽:换工具、清遮挡、等主人点头都在里面。 */
    private static final long TICKS_PER_BLOCK = 30 * 20;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;

    /** 要挖的格;点名 Block 的附带当时的方块。 */
    public final Cells cells;
    /** 此刻要挖的那几种方块:工具收不收得到掉落按它们判。 */
    public final Set<Block> targets;
    /** 至多挖几格,或 {@link #ALL}。 */
    public final int count;
    /** 回执里怎么称呼要挖的东西({@code the 5 given})。 */
    public final String what;
    /** 这几种方块的简称({@code iron_ore}、{@code oak_log+1}),主人也读它。 */
    public final String label;

    /** 进度:挖掉的格数。任务每刻写。 */
    private int dug = 0;

    public DigTaskRecord(ServerCall source, long now, Cells cells, Set<Block> targets, int count, String label,
                         String what) {
        super(source, now + timeoutTicks(count == ALL ? (int) Math.min(cells.size(), 64) : count));
        this.cells = cells;
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.what = what;
        this.label = label;
    }

    /**
     * 这一格此刻要不要挖:点名 Block 的格({@code seen} 不为 null)还是当时那种方块({@link Cells.Seen#holds})才挖;点名 Pos
     * 的格里面有方块就挖,空气与流体不挖。判据只此一处:派发时数格、干活时收候选都问它。
     */
    public static boolean wants(Cells.Seen seen, BlockState now) {
        if (seen != null) {
            return seen.holds(now);
        }
        return !now.isAir() && !(now.getBlock() instanceof LiquidBlock);
    }

    /** 这一格此刻要不要挖:是本件活点名的格,而且 {@link #wants}。 */
    public boolean wantsAt(BlockPos pos, BlockState now) {
        return cells.contains(pos) && wants(cells.seenAt(pos), now);
    }

    /** 身上有没有能让它掉东西的工具(整个背包,不只快捷栏:她能从包里拿工具挖)。不要求工具的方块总是有。 */
    public static boolean harvestable(Container inv, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /** 挖 {@code blocks} 格的期限预算。 */
    private static long timeoutTicks(int blocks) {
        return Math.max(MIN_TIMEOUT_TICKS, (long) blocks * TICKS_PER_BLOCK);
    }

    public int getDug() {
        return dug;
    }

    /** 进度,任务每刻写。 */
    public void setDug(int dug) {
        this.dug = dug;
    }

    /** 一行给模型看的:{@code <current_task>} 与 task status 印的是它,主人看的是 {@link #activity()}。 */
    @Override
    public String describe() {
        return count == ALL ? "挖 " + label + ",已挖 " + dug + " 格" : "挖 " + label + " " + dug + "/" + count + " 格";
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return count == ALL ? Activity.of("numen.activity.dig_all", label, dug)
                : Activity.of("numen.activity.dig", label, dug, count);
    }
}
