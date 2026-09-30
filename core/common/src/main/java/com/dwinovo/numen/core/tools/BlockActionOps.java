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

    /** mine 一次最多要多少个物品:帮助与快捷工具的 schema 写的范围就是它;读参数不查范围,受理时按它夹。 */
    public static final int MAX_MINE_COUNT = 256;

    /**
     * {@code mine}:挖点名的区域(或它的几部分的并)里扫描过的格,{@code count} 可选、不给就挖完工作区里的那些。区域在派发这一刻
     * 解析:没有这块、没有扫描过的格、整块都在工作区外,当场拒收,工具结果直接说明——不先回"已受理"再在后台失败。
     * {@code spec} 是已经叠在 mine 自己默认规格上的那份。
     *
     * <p>工作区在受理这一刻定下:以她此刻脚下那一格为中心({@link WorkArea#around})。区域有几格在区里的照常受理,区外那几格
     * 留着不挖,受理回执与结局都交代。
     */
    public TaskRecord autoMine(ServerSource src, List<AreaRef> areas, Integer count, RouteSpec spec) {
        NumenPlayer her = src.companion();
        WorkArea work = WorkArea.around(her);
        String name = String.join(" ", areas.stream().map(AreaRef::toString).toList());
        String into = areas.get(0).name();
        Area area = AreaOps.resolveAll(her, areas);
        Cells scanned = MineBlockTaskRecord.scanned(area);
        if (scanned.isEmpty()) {
            throw new IllegalArgumentException(name + " has no scanned cells, so I did not start: work_mine digs the "
                    + "cells a scan added, and framed cells carry no block. "
                    + MineBlockTaskRecord.rescan("<block ids>", into) + " adds what is there, then work_mine it");
        }
        Cells inside = scanned.intersect(work.cells());
        if (inside.isEmpty()) {
            throw new IllegalArgumentException(Beyond.areaOutside(name, scanned.size(), work,
                    scanned.nearest(work.center())));
        }
        Set<Block> kinds = new LinkedHashSet<>();
        scanned.forEach((x, y, z, seen) -> kinds.add(seen.state().getBlock()));
        int until = count == null ? MineBlockTaskRecord.UNTIL_GONE : Math.clamp(count, 1, MAX_MINE_COUNT);
        long timeout = MineBlockTaskRecord.timeoutTicks(until == MineBlockTaskRecord.UNTIL_GONE
                ? (int) inside.size() : until);
        return new MineBlockTaskRecord(src, her.level().getGameTime() + timeout, kinds, area, name, into, until,
                labelFor(kinds), spec, work);
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    /**
     * {@code use block}({@code aim} 是那一格)与 {@code use ahead}({@code aim} 为 null,朝她此刻面对的方向)。
     * {@code sneak}:按住潜行再点。
     */
    public TaskRecord interactAt(ServerSource source, String button, BlockPos aim, Integer hold_ticks,
                                 String item_id, boolean sneak) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(source, buttonVal, aim, holdTicks, item, sneak);
    }

    public TaskRecord interactEntity(ServerSource source, String button, int entity_id, Integer hold_ticks,
                                     String item_id, boolean sneak) {
        MouseButton buttonVal = ToolParse.parseButton(button);
        int holdTicks = hold_ticks == null ? 0 : hold_ticks;
        return new InteractEntityTaskRecord(source, buttonVal, entity_id, holdTicks,
                item_id == null ? null : ToolArgs.parseItem(item_id), sneak);
    }
}

