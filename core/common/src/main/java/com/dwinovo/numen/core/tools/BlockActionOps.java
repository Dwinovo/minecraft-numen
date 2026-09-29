package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.core.task.mine.Beyond;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Block-action implementations — the business half of {@code work mine} and of
 * {@code use block} / {@code use ahead} / {@code use entity} ({@code UseCommands}). Each method validates its
 * args and builds a {@link TaskRecord}, which takes its name, call id and deadline basis from the call's
 * {@link ServerSource}.
 */
public final class BlockActionOps {

    // mine bounds.
    private static final int MAX_COUNT = 256;

    /**
     * {@code mine} 的两种写法二选一,挖的都是一块区域里扫描过的格:{@code area}(点名的区域或它的几部分,{@code count} 可选、
     * 不给就挖完)或 {@code block_ids}(简写:她开工时先把这几种方块扫进一块匿名区域,{@code count} 必给)。点名的区域在派发这一刻
     * 解析:没有这块、没有扫描过的格、整块都在工作区外,当场拒收,工具结果直接说明——不先回"已受理"再在后台失败。
     * {@code spec} 是已经叠在 mine 自己默认规格上的那份。
     *
     * <p>工作区在受理这一刻定下:以她此刻脚下那一格为中心({@link WorkArea#around})。区域有几格在区里的照常受理,区外那几格
     * 留着不挖,受理回执与结局都交代。
     */
    public TaskRecord autoMine(ServerSource src, List<String> block_ids, List<AreaRef> areas, Integer count,
                               RouteSpec spec) {
        NumenPlayer her = src.companion();
        long now = her.level().getGameTime();
        WorkArea work = WorkArea.around(her);
        boolean byIds = block_ids != null && !block_ids.isEmpty();
        boolean byArea = areas != null && !areas.isEmpty();
        if (byIds == byArea) {
            throw new IllegalArgumentException(byIds
                    ? "give block_ids or area, not both — block_ids scans for those types around her and mines them,"
                            + " area digs the scanned cells of an area you name"
                    : "give block_ids (block types; she scans for them herself) or area (an area or parts of it, as"
                            + " scan blocks --into keeps them)");
        }
        if (byArea) {
            String name = String.join(" ", areas.stream().map(AreaRef::toString).toList());
            Area area = AreaOps.resolveAll(her, areas);
            Cells scanned = MineBlockTaskRecord.scanned(area);
            if (scanned.isEmpty()) {
                throw new IllegalArgumentException(name + " has no scanned cells: work_mine digs cells a scan added,"
                        + " each still holding the block it saw, and framed cells carry none; scan blocks with into"
                        + " adds them");
            }
            Cells inside = scanned.intersect(work.cells());
            if (inside.isEmpty()) {
                throw new IllegalArgumentException(Beyond.areaOutside(name, work, scanned.nearest(work.center())));
            }
            Set<Block> kinds = new LinkedHashSet<>();
            scanned.forEach((x, y, z, seen) -> kinds.add(seen.state().getBlock()));
            int until = count == null ? MineBlockTaskRecord.UNTIL_GONE : Math.clamp(count, 1, MAX_COUNT);
            long timeout = MineBlockTaskRecord.timeoutTicks(until == MineBlockTaskRecord.UNTIL_GONE
                    ? (int) inside.size() : until);
            return new MineBlockTaskRecord(src, now + timeout, kinds, area, name, until, labelFor(kinds), spec, work);
        }
        Set<Block> targets = ToolParse.parseBlocks(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("block_ids contained no valid block ids");
        }
        if (count == null) {
            throw new IllegalArgumentException("count is required with block_ids: how many ITEMS to gather");
        }
        int clampedCount = Math.clamp(count, 1, MAX_COUNT);
        long deadline = now + MineBlockTaskRecord.timeoutTicks(clampedCount);
        return new MineBlockTaskRecord(src, deadline, targets, null, null, clampedCount, labelFor(targets), spec, work);
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

