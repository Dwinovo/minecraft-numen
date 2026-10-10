package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.pathing.plan.MoveKind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 实际账:这次导航真的改了世界的哪几格。只收 {@link Mouse} 真实交回的结果——挖碎的是哪一格、原来是什么;右键之后哪几格
 * 变成了什么——不记意图:放方块落进了高草那一格,记的就是那一格。调用方从这里取,不另记一本。
 *
 * <p>一格换了一种方块是放下(或倒下的水),同一种方块换了状态是开关(门、栅栏门、活板门)。
 */
public final class EditLedger {

    /** 一笔。 */
    public sealed interface Entry {
        BlockPos pos();
    }

    /** 挖碎了 {@code pos},原来是 {@code before}。 */
    public record Dug(BlockPos pos, BlockState before) implements Entry {}

    /** {@code pos} 从 {@code before} 换成了另一种方块 {@code after}。 */
    public record Placed(BlockPos pos, BlockState before, BlockState after) implements Entry {}

    /** {@code pos} 还是同一种方块,状态从 {@code before} 变成了 {@code after}(开关了门)。 */
    public record Toggled(BlockPos pos, BlockState before, BlockState after) implements Entry {}

    private final List<Entry> entries = new ArrayList<>();
    private final Map<MoveKind, Integer> walked = new EnumMap<>(MoveKind.class);

    void dug(BlockPos pos, BlockState before) {
        entries.add(new Dug(pos.immutable(), before));
    }

    /** 右键之后变了的几格。 */
    void used(List<Mouse.Change> changes) {
        for (Mouse.Change c : changes) {
            if (c.before().getBlock() == c.after().getBlock()) {
                entries.add(new Toggled(c.pos().immutable(), c.before(), c.after()));
            } else {
                entries.add(new Placed(c.pos().immutable(), c.before(), c.after()));
            }
        }
    }

    /** 走完了一步 {@code kind}。 */
    void stepped(MoveKind kind) {
        walked.merge(kind, 1, Integer::sum);
    }

    /** 全部笔,按先后。 */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** 走完的步数。 */
    public int steps() {
        return walked.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 实际走完的步,按走法分;没走过的走法不在里面。 */
    public Map<MoveKind, Integer> walked() {
        return Collections.unmodifiableMap(walked);
    }
}
