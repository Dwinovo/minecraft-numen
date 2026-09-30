package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.task.dig.Beyond;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.core.task.move.Destination;
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

    /** dig 一次最多要多少个物品:帮助与快捷工具的 schema 写的范围就是它;读参数不查范围,受理时按它夹。 */
    public static final int MAX_DIG_COUNT = 256;

    /**
     * {@code dig}:挖点名的几处(区域、区域的部分、坐标)的并里要挖的格,{@code count} 可选、不给就挖完工作区里的那些。几处在派发
     * 这一刻解析、分好区里区外、按活世界数区里要挖的格:没有这块区域、区域是空的、整片都在工作区外、区里一格都不用挖,当场拒收,
     * 工具结果直接说明——不先回"已受理"再在后台失败。
     *
     * <p>工作区在受理这一刻定下:以她此刻脚下那一格为中心({@link WorkArea#around})。有几格在区里的照常受理,区外那几格留着
     * 不挖,受理回执与结局都交代,并给出照抄就能开路的写法。
     *
     * @param places 命令行上那一串:一个名字是一块区域,三个数是一格({@link Destination.Stop#each})
     */
    public TaskRecord dig(ServerSource src, List<String> places, Integer count) {
        NumenPlayer her = src.companion();
        NamedAreas named = NamedAreas.of(her);
        List<Destination.Stop> stops = Destination.Stop.each(places);
        Cells cells = Cells.EMPTY;
        List<BlockPos> points = new ArrayList<>();
        List<String> said = new ArrayList<>();
        AreaRef firstArea = null;
        for (Destination.Stop stop : stops) {
            if (stop.area() != null) {
                cells = cells.union(named.resolve(stop.area()).cells());
                firstArea = firstArea == null ? stop.area() : firstArea;
            } else if (stop.cell() == null) {
                throw new IllegalArgumentException("work dig takes cells (x y z, all three) and areas; "
                        + stop.words() + " is " + (stop.x() == null ? "a height" : "a column") + ", not one cell");
            } else {
                points.add(stop.cell());
            }
            said.add(stop.area() != null ? stop.area().toString() : stop.words());
        }
        cells = cells.union(Cells.of(points));
        String what = String.join(" ", said);
        // 只点了一块区域(或它的一部分):开路的去处写它,空的时候照抄的扫描也扫进它
        AreaRef onlyArea = stops.size() == 1 ? firstArea : null;
        String into = onlyArea != null ? onlyArea.name() : "<area>";
        if (cells.isEmpty()) {
            throw new IllegalArgumentException(what + " has no cells yet, so I did not start; `scan blocks <radius> "
                    + "<block ids> --into " + into + "` or `area add " + into + " --box <x1,y1,z1..x2,y2,z2>` fills it");
        }
        WorkArea work = WorkArea.around(her);
        Beyond beyond = new Beyond(cells.minus(work.cells()), onlyArea, what, String.join(" ", places));
        Cells inside = cells.intersect(work.cells());
        if (inside.isEmpty()) {
            throw new IllegalArgumentException(beyond.refusal(work));
        }
        Level level = her.level();
        Set<Block> kinds = new LinkedHashSet<>();
        Set<Block> scannedKinds = new LinkedHashSet<>();
        int[] total = {0};
        inside.forEach((x, y, z, seen) -> {
            BlockState now = level.getBlockState(new BlockPos(x, y, z));
            if (seen != null) {
                scannedKinds.add(seen.state().getBlock());
            }
            if (DigTaskRecord.wants(seen, now)) {
                kinds.add(now.getBlock());
                total[0]++;
            }
        });
        if (kinds.isEmpty()) {
            String rest = beyond.isEmpty() ? "" : "; " + beyond.told(Feet.cell(her));
            if (!scannedKinds.isEmpty()) {
                throw new IllegalArgumentException("the scanned cells of " + what + " in my work area ("
                        + work.describe() + ") were all gone or had changed since the scan, so I did not start"
                        + (firstArea != null ? "; `" + DigTaskRecord.rescan(ids(scannedKinds), firstArea.name())
                                + "` adds what is there now" : "") + rest);
            }
            throw new IllegalArgumentException("the " + inside.size() + " cell(s) of " + what + " in my work area ("
                    + work.describe() + ") hold nothing to dig — air or fluid — so I did not start" + rest);
        }
        int until = count == null ? DigTaskRecord.UNTIL_GONE : Math.clamp(count, 1, MAX_DIG_COUNT);
        long timeout = DigTaskRecord.timeoutTicks(until == DigTaskRecord.UNTIL_GONE ? total[0] : until);
        return new DigTaskRecord(src, level.getGameTime() + timeout, cells, work, beyond, kinds, total[0], until,
                what, labelFor(kinds), firstArea);
    }

    /** 方块 id,空格隔开。 */
    private static String ids(Set<Block> blocks) {
        return String.join(" ", blocks.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList());
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
