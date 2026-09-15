package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;

/**
 * Block-action tool implementations — the business half of {@code AutoMineTool},
 * {@code InteractAtTool} and {@code InteractEntityTool}. Each method validates its
 * args and builds a {@link TaskRecord}; the {@link ToolContext} carries the call
 * id and deadline basis.
 */
public final class BlockActionOps {

    // mine bounds.
    private static final int MAX_COUNT = 256;

    // interact_at: covers walking to the aim.
    private static final long INTERACT_AT_TIMEOUT_TICKS = 30 * 20;
    // interact_entity: covers chasing a moving target.
    private static final long INTERACT_ENTITY_TIMEOUT_TICKS = 60 * 20;

    /**
     * {@code mine} 的两种用法二选一:{@code block_ids}(她自己挑最近的,{@code count} 必给)或 {@code groups}
     * (最新一次 scan_blocks 的团编号,{@code count} 可选、不给就挖完)。团编号在任务开工时对着身体上的团簿
     * 取,那时才知道它还在不在最新一次扫描里。{@code spec} 叠在 mine 自己的默认规格上。
     */
    public TaskRecord autoMine(List<String> block_ids, List<String> groups, Integer count, JsonObject spec,
                               ToolContext ctx) {
        boolean byIds = block_ids != null && !block_ids.isEmpty();
        boolean byGroups = groups != null && !groups.isEmpty();
        if (byIds == byGroups) {
            throw new IllegalArgumentException(byIds
                    ? "give block_ids or groups, not both — block_ids lets her pick the nearest blocks of those"
                            + " types, groups digs exactly the groups a scan_blocks listed"
                    : "give block_ids (block types; she finds the nearest herself) or groups (ids from your"
                            + " latest scan_blocks)");
        }
        RouteSpec parsed = RouteSpecJson.parse(spec, MineBlockTaskRecord.DEFAULT_SPEC);
        if (byGroups) {
            List<String> ids = groups.stream().map(String::strip).distinct().toList();
            int until = count == null ? MineBlockTaskRecord.UNTIL_GONE : Math.clamp(count, 1, MAX_COUNT);
            // 格数要到开工取团时才知道;那时任务按格数续上期限
            long timeout = MineBlockTaskRecord.timeoutTicks(until);
            return new MineBlockTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), Set.of(), ids, until,
                    String.join(",", ids), parsed);
        }
        Set<Block> targets = ToolParse.parseBlocks(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        if (count == null) {
            throw new IllegalArgumentException("count is required with block_ids: how many ITEMS to gather");
        }
        int clampedCount = Math.clamp(count, 1, MAX_COUNT);
        long deadline = ctx.deadline(MineBlockTaskRecord.timeoutTicks(clampedCount));
        return new MineBlockTaskRecord(ctx.toolCallId(), deadline, targets, List.of(), clampedCount,
                labelFor(targets), parsed);
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    public TaskRecord interactAt(
String button,
Integer x,
Integer y,
Integer z,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;

        BlockPos aim = null;
        if (x != null || y != null || z != null) {
            if (x == null || y == null || z == null) {
                throw new IllegalArgumentException(
                        "an aim point needs all of x, y, z (or leave all null to use the held item straight ahead).");
            }
            aim = new BlockPos(x, y, z);
        }
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_AT_TIMEOUT_TICKS), buttonVal, aim, holdTicks, item);
    }

    public TaskRecord interactEntity(
String button,
int entity_id,
Integer hold_ticks,
String item_id,
            ToolContext ctx) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        return new InteractEntityTaskRecord(ctx.toolCallId(), ctx.deadline(INTERACT_ENTITY_TIMEOUT_TICKS), buttonVal, entity_id, holdTicks,
                item_id == null ? null : ToolArgs.parseItem(item_id));
    }
}

