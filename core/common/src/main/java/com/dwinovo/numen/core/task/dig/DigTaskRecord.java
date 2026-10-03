package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code work.dig} 这件活的记录:点名的几处(区域、区域的部分、坐标)的并里要挖的格,她站在原地手够得着的那些挖掉。要挖的格由区域的
 * 数据定,不另设开关({@link #wants}):扫描来的格只挖还是当时那种方块的,框出来的格与点里面是什么挖什么(空气、流体跳过)。
 *
 * <p>点名的几处在派发这一刻解析成格子,记录里带着格子;扫描来的格附带当时的方块,框出来的格不带。
 */
public final class DigTaskRecord extends TaskRecord {

    /**
     * 挡在前面的格清不清得掉按这份规格问:天然地形可以挖开,要主人同意的、规则不许的不挖(不问,如实说是哪一格、哪条规则)。
     * 建造清场的走动也从它起({@code ClearSiteRecord})。
     */
    public static final RouteSpec SPEC = RouteSpec.defaults().edit().alter(RouteSpec.Alter.NATURAL).build();

    /**
     * 点名的目标按这份规格定价、判挖不挖得成:改地形一档放到 {@link RouteSpec.Alter#ANY}——目标是她点名要挖的,要问主人的照价乘倍、
     * 动手前问,规则不许的、物理上挖不成的价钱无穷。{@code work.dig} 挑目标与 {@code arrive = "dig"} 挑能去挖的格都按它。
     */
    public static final RouteSpec TARGET_SPEC = SPEC.edit().alter(RouteSpec.Alter.ANY).build();

    /** {@link #count} 取这个值:没给 {@code --count},手够得着的都挖。 */
    public static final int ALL = 0;

    /** 一格的期限给得宽:换工具、清遮挡、等主人点头都在里面。 */
    private static final long TICKS_PER_BLOCK = 30 * 20;
    private static final long MIN_TIMEOUT_TICKS = 60 * 20;

    /** 要挖的格(点名的区域与坐标的并);扫描来的附带当时的方块。 */
    public final Cells cells;
    /** 她点名的几处,按写下的顺序:回执里能照抄的下一步照它写。 */
    public final List<Place> named;
    /** 此刻要挖的那几种方块:工具收不收得到掉落按它们判。 */
    public final Set<Block> targets;
    /** 至多挖几格,或 {@link #ALL}。 */
    public final int count;
    /** 回执里怎么称呼要挖的东西({@code ores/g3}、{@code 10 64 5})。 */
    public final String what;
    /** 这几种方块的简称({@code iron_ore}、{@code oak_log+1}),主人也读它。 */
    public final String label;

    /** 进度:挖掉的格数。任务每刻写。 */
    private int dug = 0;

    public DigTaskRecord(ServerSource source, long now, Cells cells, List<Place> named, Set<Block> targets,
                         int count, String label) {
        super(source, now + timeoutTicks(count == ALL ? (int) Math.min(cells.size(), 64) : count));
        this.cells = cells;
        this.named = List.copyOf(named);
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.what = named.stream().map(Place::written).collect(Collectors.joining(" "));
        this.label = label;
    }

    /**
     * 这一格此刻要不要挖:扫描来的格({@code seen} 不为 null)还是当时那种方块({@link Cells.Seen#holds})才挖;框出来的格与点
     * 里面有方块就挖,空气与流体不挖。判据只此一处:派发时数格、干活时收候选、建造清场、{@code area has} 都问它。
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

    /**
     * 够不着的那些格的下一步,能照抄:{@code move.goto_(去处, {arrive = "dig"})},再 {@code work.dig(同样的几处)}。只点了一块区域
     * (或它的一部分)时去处写它,她从离得最近的那一侧够过去;否则写 {@code nearest} 这一格。
     *
     * @param named 她点名的几处,按写下的顺序
     */
    public static String reachThem(List<Place> named, BlockPos nearest) {
        AreaRef only = named.size() == 1 ? named.get(0).area() : null;
        Place to = only != null ? Place.area(only) : Place.cell(nearest);
        return NavText.gotoCall(to, "arrive = \"dig\"") + ", then `work.dig("
                + named.stream().map(NavText::lua).collect(Collectors.joining(", ")) + ")`";
    }

    public int getDug() {
        return dug;
    }

    /** 进度,任务每刻写。 */
    public void setDug(int dug) {
        this.dug = dug;
    }

    /** 一行人话,给主人看的:头顶气泡、面板、task status 印的都是它。 */
    @Override
    public String describe() {
        return count == ALL ? "挖 " + label + ",已挖 " + dug + " 格" : "挖 " + label + " " + dug + "/" + count + " 格";
    }
}
