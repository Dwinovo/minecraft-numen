package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.sdk.Target;
import com.dwinovo.numen.core.task.MouseButton;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractAtTaskRecord;
import com.dwinovo.numen.core.task.interact.InteractEntityTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按键与挖的活:{@code numen.work.dig} 与 {@code numen.use.block/item/entity/hit} 各自认参数、造一件活,活的名字、调用 id 与期限的起点
 * 取自那次调用({@link ServerCall})。
 */
public final class BlockActionOps {

    /** dig 一次至多挖几格:帮助与快捷工具的 schema 写的范围就是它;读参数不查范围,受理时按它夹。 */
    public static final int MAX_DIG_COUNT = 256;

    /**
     * {@code dig}:挖点名的几格里要挖的,她站在原地手够得着的那些;{@code count} 可选,至多挖几格。点名的 Block 带着当时那里的方块,
     * 那一格换了别的就不挖;一格要挖的都没有(都是空气流体,或点名的方块都变了)当场拒收,工具结果直接说明——不先回"已受理"再在后台
     * 失败。手够不够得着在任务受理之前的准备里判({@link DigCompanionTask})。
     *
     * @param targets 点名的几格
     */
    public static DigTaskRecord dig(ServerCall src, List<Target> targets, Integer count) {
        NumenPlayer her = src.her();
        Level level = her.level();
        List<BlockPos> plain = new ArrayList<>();
        Map<BlockPos, BlockState> named = new LinkedHashMap<>();
        for (Target target : targets) {
            if (target.block() == null) {
                plain.add(target.cell());
            } else {
                named.put(target.cell(), target.block().defaultBlockState());
            }
        }
        Cells cells = Cells.of(plain).union(Cells.seen(named, level.getGameTime()));
        String what = targets.size() == 1 ? com.dwinovo.numen.sdk.LuaCodecs.literal(targets.get(0))
                : "the " + targets.size() + " given";
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
        // 点名的格里已经没有要挖的了:不是写错,是那些东西不在了
        if (kinds.isEmpty()) {
            if (!scannedKinds.isEmpty()) {
                throw new ApiError(ErrorKind.NOT_FOUND, "the blocks given are all gone or have changed since they were "
                        + "seen, so I did not start", Call.of("numen.scan.blocks", ids(scannedKinds).toArray()));
            }
            throw new ApiError(ErrorKind.NOT_FOUND, "the " + cells.size() + " cell(s) given hold nothing to dig — air "
                    + "or fluid — so I did not start", null);
        }
        int until = count == null ? DigTaskRecord.ALL : Math.clamp(count, 1, MAX_DIG_COUNT);
        return new DigTaskRecord(src, level.getGameTime(), cells, targets, kinds, until, labelFor(kinds), what);
    }

    /** 方块 id。 */
    private static List<String> ids(Set<Block> blocks) {
        return blocks.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList();
    }

    /** Short label for messages: the first target's path (e.g. "iron_ore"), "+N" if more. */
    private static String labelFor(Set<Block> targets) {
        Block first = targets.iterator().next();
        String path = BuiltInRegistries.BLOCK.getKey(first).getPath();
        return targets.size() == 1 ? path : path + "+" + (targets.size() - 1);
    }

    /**
     * {@code use block}({@code aim} 是那一格)与 {@code use item}({@code aim} 为 null,朝她此刻面对的方向)。
     *
     * @param holdTicks 按住几刻;0 是按一下
     * @param item      先拿到手上的物品;用手上的为 null
     * @param sneak     按住潜行再点
     */
    public static InteractAtTaskRecord interactAt(ServerCall source, MouseButton button, BlockPos aim, int holdTicks,
                                                  Item item, boolean sneak) {
        String bodyBound = InteractAtTaskRecord.bodyBoundReason(item);
        if (bodyBound != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, bodyBound, null);
        }
        return new InteractAtTaskRecord(source, button, aim, holdTicks, item, sneak);
    }

    /** {@code use entity}:参数同 {@link #interactAt},按的是那一只实体。 */
    public static InteractEntityTaskRecord interactEntity(ServerCall source, MouseButton button, int entityId,
                                                          int holdTicks, Item item, boolean sneak) {
        return new InteractEntityTaskRecord(source, button, entityId, holdTicks, item, sneak);
    }
}
