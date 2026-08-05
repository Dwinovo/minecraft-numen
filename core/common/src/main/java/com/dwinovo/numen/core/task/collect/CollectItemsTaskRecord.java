package com.dwinovo.numen.core.task.collect;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

import java.util.Set;

/**
 * Typed task descriptor for the {@code collect_items} tool: "walk around and
 * pick up dropped items nearby". The goal ({@link CollectItemsCompanionTask}) scans
 * for {@code ItemEntity}s within the radius, walks to each with the pathfinder
 * (the entity auto-absorbs items it gets close to), and repeats until none
 * remain. An optional {@link #filter} restricts to specific item types; empty
 * means collect everything.
 */
public final class CollectItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "collect_items";

    /** Item types to collect; empty = collect every dropped item. */
    public final Set<Item> filter;
    /** Search radius in blocks. */
    public final int radius;
    /** Human-readable label for messages (e.g. "all items" or "diamond"). */
    public final String label;

    /** Live progress, updated by the goal as items are absorbed. */
    private int collected = 0;

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.filter = Set.copyOf(filter);
        this.radius = radius;
        this.label = label;
    }

    public int getCollected() {
        return collected;
    }

    public void incrementCollected() {
        this.collected++;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "捣东西 " + label + " x" + collected;
    }
}
