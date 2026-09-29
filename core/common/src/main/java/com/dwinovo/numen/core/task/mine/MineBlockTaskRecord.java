package com.dwinovo.numen.core.task.mine;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/**
 * Typed task descriptor for {@code work mine} (shortcut {@code work_mine}). She digs the cells of an area: the
 * cells a scan added, each still holding the block the scan saw, inside her work area ({@link #work}); what lies
 * beyond the work area is reported, not visited. The area comes one of two ways:
 * <ul>
 *   <li><b>--area</b> — an area (or parts of it) the model named, resolved when the call is dispatched, so a
 *       missing area, one with no scanned cells, or one lying wholly beyond the work area is refused in the
 *       tool result itself and the record carries the cells;</li>
 *   <li><b>--block_ids + count</b> — shorthand for "scan these block types into an anonymous area, then mine it":
 *       the task scans at the start ({@link #SCAN_RADIUS} around where she stands) and digs that area the same way,
 *       until {@code count} items are gained or none remain in the work area.</li>
 * </ul>
 * Drops/tool-tier follow from whatever the entity holds, as in vanilla.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    /**
     * mine 的默认规格:可以改地形,需要主人同意的格也算进去、按价排在后面(挖不挖由动手前的权限裁决定)。
     * 模型给的 {@code spec} 叠在它上面,没给的字段保持这里的值。
     */
    public static final RouteSpec DEFAULT_SPEC = RouteSpec.defaults().edit().alter(RouteSpec.Alter.ANY).build();

    /** {@link #count} 取这个值:点名区域的用法没给 count,挖完区域里的格为止。 */
    public static final int UNTIL_GONE = 0;

    /**
     * 简写先看多远:和 {@code scan blocks} 能看的一样远。区里的进候选,区外的只报告——回执说区外还有几个、最近一个在哪。
     */
    public static final int SCAN_RADIUS = BlockScan.MAX_RADIUS;

    /** Per-block budget is generous; total scales with the work so big jobs don't time out. */
    private static final long TICKS_PER_BLOCK = 30 * 20;   // 30s each
    private static final long MIN_TIMEOUT_TICKS = 60 * 20; // 1 min floor

    /** Block types to gather: the block_ids given, or the kinds the area's scanned cells held. */
    public final Set<Block> targets;
    /**
     * 点名的区域(整块或几部分的并)里扫描时附带了方块的格——能挖的只有它们,框出来的格不知道当时是什么。简写为 null:
     * 开工时现看一块匿名的。
     */
    public final Cells scanned;
    /** 回执里怎么称呼点名的区域({@code ores/g3});简写为 null。 */
    public final String areaName;
    /** How many ITEMS to gather before reporting success, or {@link #UNTIL_GONE}. */
    public final int count;
    /** Human-readable target label (block names, e.g. "iron_ore", "oak_log+1") — the owner reads it too. */
    public final String label;
    /** How the body may move and dig: {@link #DEFAULT_SPEC} with the model's fields laid over it. */
    public final RouteSpec spec;
    /** 工作区:受理时她脚下那一格为中心。目标只取区里的,区外的只报告。 */
    public final WorkArea work;

    /** Live progress = matching ITEMS gathered since the task started (counted in the inventory,
     *  not blocks broken — multi-drop ores like redstone yield several items per block), or cells
     *  dug for {@link #UNTIL_GONE}. Set each tick by the task; drives the stop condition + the debug
     *  overlay text. */
    private int mined = 0;

    public MineBlockTaskRecord(ServerSource source, long deadlineGameTime, Set<Block> targets, Area area,
                               String areaName, int count, String label, RouteSpec spec, WorkArea work) {
        super(source, deadlineGameTime);
        this.targets = Set.copyOf(targets);
        this.scanned = area == null ? null : scanned(area);
        this.areaName = areaName;
        this.count = count;
        this.label = label;
        this.spec = spec;
        this.work = work;
    }

    /** 挖 {@code blocks} 格(或收 {@code blocks} 个物品)的期限预算。 */
    public static long timeoutTicks(int blocks) {
        return Math.max(MIN_TIMEOUT_TICKS, (long) blocks * TICKS_PER_BLOCK);
    }

    /** 区域里扫描时附带了方块的格:能挖的只有它们(框出来的格不知道当时是什么)。 */
    public static Cells scanned(Area area) {
        return area.cells().filter(state -> true);
    }

    public int getMined() {
        return mined;
    }

    /** Set the running tally (the task recomputes it each tick). */
    public void setMined(int gathered) {
        this.mined = gathered;
    }

    /** 点名的区域里扫描过的格数;简写为 0。 */
    public int cells() {
        return scanned == null ? 0 : (int) scanned.size();
    }

    /** 受理时交代工作区在哪;点名的区域有格落在区外的,说有几格、它们留着不挖。 */
    @Override
    public String acceptNote() {
        String where = "My work area is " + work.describe() + ", where I stand now";
        if (scanned == null) {
            return where + ": I mine only there, and the end reports what lies beyond it.";
        }
        long inside = scanned.intersect(work.cells()).size();
        long outside = scanned.size() - inside;
        return outside == 0
                ? where + "; all " + scanned.size() + " scanned cells of " + areaName + " lie in it."
                : where + ": " + inside + " of the " + scanned.size() + " scanned cells of " + areaName + " lie in it; "
                        + "the other " + outside + " lie beyond it and will be left.";
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
