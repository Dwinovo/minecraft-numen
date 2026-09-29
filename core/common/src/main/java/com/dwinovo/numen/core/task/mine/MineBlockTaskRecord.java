package com.dwinovo.numen.core.task.mine;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Set;

/**
 * Typed task descriptor for {@code work mine} (shortcut {@code mine}), in one of two forms:
 * <ul>
 *   <li><b>block_ids</b> — "gather {@code count} of these block types, find them yourself": the task
 *       searches its work area ({@link #area}), walks to the cheapest with the terrain-modifying
 *       pathfinder, mines into the inventory, repeats until the count is met or nothing reachable
 *       remains there; what lies beyond the area is reported, not visited;</li>
 *   <li><b>groups</b> — "dig exactly these groups from the latest {@code scan_blocks}": the targets are
 *       only those cells, each still holding the block the scan recorded; nothing beyond them. The ids
 *       are resolved against the body's group book when the call is dispatched, so a stale id, or a group
 *       lying wholly beyond the work area, is refused in the tool result itself and the record carries the
 *       cells.</li>
 * </ul>
 * Drops/tool-tier follow from whatever the entity holds, as in vanilla.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    /**
     * mine 的默认规格:可以改地形,需要主人同意的格也算进去、按价排在后面(挖不挖由动手前的权限裁决定)。
     * 模型给的 {@code spec} 叠在它上面,没给的字段保持这里的值。
     */
    public static final RouteSpec DEFAULT_SPEC = RouteSpec.defaults().edit().alter(RouteSpec.Alter.ANY).build();

    /** {@link #count} 取这个值:groups 用法没给 count,挖完这些团为止。 */
    public static final int UNTIL_GONE = 0;

    /** Per-block budget is generous; total scales with the work so big jobs don't time out. */
    private static final long TICKS_PER_BLOCK = 30 * 20;   // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    /** Block types to gather: the block_ids given, or the kinds the named cells held when scanned. */
    public final Set<Block> targets;
    /** groups 用法点名的格子和扫描时记下的方块(派发时从团簿取好);block_ids 用法为空。 */
    public final Map<BlockPos, Block> named;
    /** How many ITEMS to gather before reporting success, or {@link #UNTIL_GONE}. */
    public final int count;
    /** Human-readable target label (block names, e.g. "iron_ore", "oak_log+1") — the owner reads it too. */
    public final String label;
    /** How the body may move and dig: {@link #DEFAULT_SPEC} with the model's fields laid over it. */
    public final RouteSpec spec;
    /** 工作区:受理时她脚下那一格为中心。目标只取区里的,区外的只报告。 */
    public final WorkArea area;

    /** Live progress = matching ITEMS gathered since the task started (counted in the inventory,
     *  not blocks broken — multi-drop ores like redstone yield several items per block), or cells
     *  dug for {@link #UNTIL_GONE}. Set each tick by the task; drives the stop condition + the debug
     *  overlay text. */
    private int mined = 0;

    public MineBlockTaskRecord(ServerSource source, long deadlineGameTime, Set<Block> targets,
                               Map<BlockPos, Block> named, int count, String label, RouteSpec spec,
                               WorkArea area) {
        super(source, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.named = Map.copyOf(named);
        this.count = count;
        this.label = label;
        this.spec = spec;
        this.area = area;
    }

    /** 挖 {@code blocks} 格(或收 {@code blocks} 个物品)的期限预算。 */
    public static long timeoutTicks(int blocks) {
        return Math.max(MIN_TIMEOUT_TICKS, (long) blocks * TICKS_PER_BLOCK);
    }

    public int getMined() {
        return mined;
    }

    /** Set the running tally (the task recomputes it each tick). */
    public void setMined(int gathered) {
        this.mined = gathered;
    }

    /** groups 用法点名的格数。 */
    public int cells() {
        return named.size();
    }

    /** 受理时交代工作区在哪;点名的格有落在区外的,说有几格、它们留着不挖。 */
    @Override
    public String acceptNote() {
        String where = "My work area is " + area.describe() + ", where I stand now";
        if (named.isEmpty()) {
            return where + ": I mine only there, and the end reports what lies beyond it.";
        }
        long outside = named.keySet().stream().filter(p -> !area.contains(p)).count();
        return outside == 0
                ? where + "; all " + named.size() + " named cells lie in it."
                : where + ": " + (named.size() - outside) + " of the " + named.size() + " named cells lie in it; the"
                        + " other " + outside + " lie beyond it and will be left.";
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return count == UNTIL_GONE
                ? "挖 " + label + " " + mined + "/" + cells() + " 格"
                : "挖 " + label + " " + mined + "/" + count;
    }
}
