package com.dwinovo.numen.core.task.collect;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

import java.util.Set;

/**
 * Typed task descriptor for {@code work collect}: "walk around and
 * pick up dropped items nearby". The goal ({@link CollectItemsCompanionTask}) scans
 * for {@code ItemEntity}s in its area ({@link #area}: a sphere around where she stood when the call
 * was made, cut down to a named area when one was given), walks to each with the pathfinder (the entity
 * auto-absorbs items it gets close to), and repeats until none remain there. An optional {@link #filter} restricts to specific item types; empty
 * means collect everything.
 */
public final class CollectItemsTaskRecord extends TaskRecord {

    private static final long TIMEOUT_TICKS = 60 * 20;

    /** Item types to collect; empty = collect every dropped item. */
    public final Set<Item> filter;
    /**
     * 只捡这块区域里的:受理时她脚下那一格为中心的球(半径是模型给的,不给取默认),点名了区域时再和它求交。在不在里面只问它
     * ({@link Area#contains})。
     */
    public final Area area;
    /** 回执里怎么说这块地方:{@code within 16 blocks of 1,64,2}、{@code in ores, within 48 blocks of 1,64,2}。 */
    public final String where;
    /** Human-readable label for messages (e.g. "all items" or "diamond"). */
    public final String label;

    /** 到手的件数:背包里要捡的那几种比开工时多出来的数目,任务每刻更新。 */
    private int collected = 0;

    public CollectItemsTaskRecord(ServerSource source, Set<Item> filter, Area area, String where, String label) {
        super(source, source.companion().level().getGameTime() + TIMEOUT_TICKS);
        this.filter = Set.copyOf(filter);
        this.area = area;
        this.where = where;
        this.label = label;
    }

    public int getCollected() {
        return collected;
    }

    public void setCollected(int collected) {
        this.collected = collected;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "捡东西 " + label + " x" + collected;
    }
}
