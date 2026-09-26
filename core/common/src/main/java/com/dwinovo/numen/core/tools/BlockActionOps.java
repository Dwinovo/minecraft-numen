package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.core.scan.GroupBook;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block-action implementations — the business half of {@code AutoMineTool} and of
 * {@code use block} / {@code use ahead} / {@code use entity} ({@code UseCommands}). Each method validates its
 * args and builds a {@link TaskRecord}; a command's records take their name, call id and deadline basis from its
 * {@link ServerSource}, a tool's from its {@link ToolContext}.
 */
public final class BlockActionOps {

    // mine bounds.
    private static final int MAX_COUNT = 256;

    /**
     * {@code mine} 的两种用法二选一:{@code block_ids}(她自己挑最近的,{@code count} 必给)或 {@code groups}
     * (最新一次 scan_blocks 的团编号,{@code count} 可选、不给就挖完)。团编号在派发这一刻对着身体上的团簿取:
     * 不在最新一次扫描里就当场拒收,工具结果直接说明——不先回"已受理"再在后台失败,模型也就不会拿着受理回执
     * 告诉主人"去了"。{@code spec} 叠在 mine 自己的默认规格上。
     */
    public TaskRecord autoMine(NumenPlayer companion, List<String> block_ids, List<String> groups, Integer count,
                               JsonObject spec, ToolContext ctx) {
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
            GroupBook book = GroupBook.of(companion);
            String stale = book.staleMessage(ids);
            if (stale != null) {
                throw new IllegalArgumentException(stale);
            }
            Map<BlockPos, Block> cells = book.cells(ids);
            Set<Block> kinds = Set.copyOf(cells.values());
            int until = count == null ? MineBlockTaskRecord.UNTIL_GONE : Math.clamp(count, 1, MAX_COUNT);
            long timeout = MineBlockTaskRecord.timeoutTicks(until == MineBlockTaskRecord.UNTIL_GONE
                    ? cells.size() : until);
            return new MineBlockTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), kinds, cells, until,
                    labelFor(kinds), parsed);
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
        return new MineBlockTaskRecord(ctx.toolCallId(), deadline, targets, Map.of(), clampedCount,
                labelFor(targets), parsed);
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    /**
     * {@code use block}({@code aim} 是那一格)与 {@code use ahead}({@code aim} 为 null,朝她此刻面对的方向)。
     */
    public TaskRecord interactAt(ServerSource source, String button, BlockPos aim, Integer hold_ticks,
                                 String item_id) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(source, buttonVal, aim, holdTicks, item);
    }

    public TaskRecord interactEntity(ServerSource source, String button, int entity_id, Integer hold_ticks,
                                     String item_id) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        return new InteractEntityTaskRecord(source, buttonVal, entity_id, holdTicks,
                item_id == null ? null : ToolArgs.parseItem(item_id));
    }
}

