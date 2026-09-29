package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.plan.Permit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 实际账:这次导航真的改了世界的哪几格。只收 {@link Effector} 真实交回的结果——挖碎的是哪一格、原来是什么;右键之后哪几格
 * 变成了什么——不记意图:放方块落进了高草那一格,记的就是那一格。调用方从这里取,不另记一本。
 *
 * <p>一格换了一种方块是放下(或倒下的水),同一种方块换了状态是开关(门、栅栏门、活板门)。每一笔带着规划给那一格的许可
 * 答复,要主人同意的格因此看得出来。
 */
public final class EditLedger {

    /** 一笔。 */
    public sealed interface Entry {
        BlockPos pos();

        /** 许可对这一格的答复(规划时交给这一步的);不是规划里的那一格为 null。 */
        Permit permit();
    }

    /** 挖碎了 {@code pos},原来是 {@code before}。 */
    public record Dug(BlockPos pos, BlockState before, Permit permit) implements Entry {}

    /** {@code pos} 从 {@code before} 换成了另一种方块 {@code after}。 */
    public record Placed(BlockPos pos, BlockState before, BlockState after, Permit permit) implements Entry {}

    /** {@code pos} 还是同一种方块,状态从 {@code before} 变成了 {@code after}(开关了门)。 */
    public record Toggled(BlockPos pos, BlockState before, BlockState after, Permit permit) implements Entry {}

    private final List<Entry> entries = new ArrayList<>();
    private int steps;

    void dug(BlockPos pos, BlockState before, Permit permit) {
        entries.add(new Dug(pos.immutable(), before, permit));
    }

    /** 右键之后变了的几格;{@code planned} 是规划里这一下要动的那一格与它的许可。 */
    void used(List<Effector.Change> changes, BlockPos planned, Permit permit) {
        for (Effector.Change c : changes) {
            Permit p = c.pos().equals(planned) ? permit : null;
            if (c.before().getBlock() == c.after().getBlock()) {
                entries.add(new Toggled(c.pos().immutable(), c.before(), c.after(), p));
            } else {
                entries.add(new Placed(c.pos().immutable(), c.before(), c.after(), p));
            }
        }
    }

    /** 走完了一步。 */
    void stepped() {
        steps++;
    }

    /** 全部笔,按先后。 */
    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** 走完的步数。 */
    public int steps() {
        return steps;
    }

    /** 改地形的笔(挖与放,开关门不算)。 */
    public int alterations() {
        int n = 0;
        for (Entry e : entries) {
            if (!(e instanceof Toggled)) {
                n++;
            }
        }
        return n;
    }
}
