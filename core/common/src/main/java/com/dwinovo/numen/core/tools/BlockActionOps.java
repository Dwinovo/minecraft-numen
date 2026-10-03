package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Block-action implementations — the business half of {@code work dig} and of
 * {@code use block} / {@code use ahead} / {@code use entity} ({@code UseCommands}). Each method validates its
 * args and builds a {@link TaskRecord}, which takes its name, call id and deadline basis from the call's
 * {@link ServerSource}.
 */
public final class BlockActionOps {

    /** dig 一次至多挖几格:帮助与快捷工具的 schema 写的范围就是它;读参数不查范围,受理时按它夹。 */
    public static final int MAX_DIG_COUNT = 256;

    /**
     * {@code dig}:挖点名的几处(区域、区域的部分、一格坐标)的并里要挖的格,她站在原地手够得着的那些;{@code count} 可选,至多挖几格。
     * 几处在派发这一刻解析:没有这块区域、点的是一列或一个高度、区域是空的、一格要挖的都没有(都是空气流体,或扫描来的都变了),
     * 当场拒收,工具结果直接说明——不先回"已受理"再在后台失败。手够不够得着在任务受理之前的准备里判({@link DigCompanionTask})。
     *
     * @param places 命令行上那一串:区域或一格坐标
     */
    public TaskRecord dig(ServerSource src, List<Place> places, Integer count) {
        NumenPlayer her = src.companion();
        NamedAreas named = NamedAreas.of(her);
        Cells cells = Cells.EMPTY;
        List<BlockPos> points = new ArrayList<>();
        AreaRef firstArea = null;
        for (Place place : places) {
            if (place.area() != null) {
                cells = cells.union(named.resolve(place.area()).cells());
                firstArea = firstArea == null ? place.area() : firstArea;
            } else if (place.cell() == null) {
                throw new IllegalArgumentException("work.dig takes cells ({x, y, z}, all three) and areas; " + place
                        + " is " + (place.x() == null ? "a height" : "a column") + ", not one cell");
            } else {
                points.add(place.cell());
            }
        }
        cells = cells.union(Cells.of(points));
        String what = String.join(" ", places.stream().map(Place::written).toList());
        String into = firstArea != null ? firstArea.name() : "<area>";
        if (cells.isEmpty()) {
            throw new IllegalArgumentException(what + " has no cells yet, so I did not start; `scan.blocks(<block ids>, "
                    + "{into = \"" + into + "\"})` or `area.add(\"" + into + "\", {box = {x1, y1, z1, x2, y2, z2}})` "
                    + "fills it");
        }
        Level level = her.level();
        Set<Block> kinds = new LinkedHashSet<>();
        Set<Block> scannedKinds = new LinkedHashSet<>();
        cells.forEach((x, y, z, seen) -> {
            BlockState now = level.getBlockState(new BlockPos(x, y, z));
            if (seen != null) {
                scannedKinds.add(seen.state().getBlock());
            }
            if (DigTaskRecord.wants(seen, now)) {
                kinds.add(now.getBlock());
            }
        });
        if (kinds.isEmpty()) {
            if (!scannedKinds.isEmpty()) {
                throw new IllegalArgumentException("the scanned cells of " + what + " are all gone or have changed since"
                        + " the scan, so I did not start" + (firstArea != null ? "; `scan.blocks(" + ids(scannedKinds)
                        + ", {into = \"" + firstArea.name() + "\"})` adds what is there now" : ""));
            }
            throw new IllegalArgumentException("the " + cells.size() + " cell(s) of " + what + " hold nothing to dig — "
                    + "air or fluid — so I did not start");
        }
        int until = count == null ? DigTaskRecord.ALL : Math.clamp(count, 1, MAX_DIG_COUNT);
        return new DigTaskRecord(src, level.getGameTime(), cells, places, kinds, until, labelFor(kinds));
    }

    /** 方块 id,空格隔开。 */
    private static String ids(Set<Block> blocks) {
        return String.join(", ", blocks.stream().map(b -> "\"" + BuiltInRegistries.BLOCK.getKey(b) + "\"").toList());
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    /**
     * {@code use block}({@code aim} 是那一格)与 {@code use ahead}({@code aim} 为 null,朝她此刻面对的方向)。
     *
     * @param holdTicks 按住几刻;0 是按一下
     * @param itemId    先拿到手上的物品;用手上的为 null
     * @param sneak     按住潜行再点
     */
    public TaskRecord interactAt(ServerSource source, MouseButton button, BlockPos aim, int holdTicks,
                                 String itemId, boolean sneak) {
        Item item = itemId == null ? null : ToolArgs.parseItem(itemId);
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new IllegalArgumentException(bodyBound);
        }
        return new InteractAtTaskRecord(source, button, aim, holdTicks, item, sneak);
    }

    /** {@code use entity}:参数同 {@link #interactAt},按的是那一只实体。 */
    public TaskRecord interactEntity(ServerSource source, MouseButton button, int entityId, int holdTicks,
                                     String itemId, boolean sneak) {
        return new InteractEntityTaskRecord(source, button, entityId, holdTicks,
                itemId == null ? null : ToolArgs.parseItem(itemId), sneak);
    }
}
