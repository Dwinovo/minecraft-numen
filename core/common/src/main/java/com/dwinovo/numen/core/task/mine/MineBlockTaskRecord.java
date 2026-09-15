package com.dwinovo.numen.core.task.mine;

import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.Set;

/**
 * Typed task descriptor for the intent-level {@code mine} tool, in one of two forms:
 * <ul>
 *   <li><b>block_ids</b> — "gather {@code count} of these block types, find them yourself": the task
 *       searches the loaded area around the body, walks to the nearest with the terrain-modifying
 *       pathfinder, mines into the inventory, repeats until the count is met or nothing reachable
 *       remains;</li>
 *   <li><b>groups</b> — "dig exactly these groups from the latest {@code scan_blocks}": the targets are
 *       only those cells, each still holding the block the scan recorded; nothing beyond them. The ids
 *       are resolved against the body's group book when the call is dispatched, so a stale id is refused
 *       in the tool result itself and the record carries the cells.</li>
 * </ul>
 * Drops/tool-tier follow from whatever the entity holds, as in vanilla.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "mine";

    /**
     * mine 的默认规格:可以改地形,需要主人同意的格也算进去、按价排在后面(挖不挖由动手前的权限裁决定)。
     * 模型给的 {@code spec} 叠在它上面,没给的字段保持这里的值。
     */
    public static final RouteSpec DEFAULT_SPEC = RouteSpec.defaults().withAlter(RouteSpec.Alter.ANY);

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

    /** Live progress = matching ITEMS gathered since the task started (counted in the inventory,
     *  not blocks broken — multi-drop ores like redstone yield several items per block), or cells
     *  dug for {@link #UNTIL_GONE}. Set each tick by the task; drives the stop condition + the debug
     *  overlay text. */
    private int mined = 0;

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime, Set<Block> targets,
                               Map<BlockPos, Block> named, int count, String label, RouteSpec spec) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.named = Map.copyOf(named);
        this.count = count;
        this.label = label;
        this.spec = spec;
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

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return count == UNTIL_GONE
                ? "挖 " + label + " " + mined + "/" + cells() + " 格"
                : "挖 " + label + " " + mined + "/" + count;
    }
}
