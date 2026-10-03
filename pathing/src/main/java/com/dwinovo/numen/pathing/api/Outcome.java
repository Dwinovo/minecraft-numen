package com.dwinovo.numen.pathing.api;

import java.util.List;

import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Permit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一次导航(或一次只搜不走的规划)的结局。都是事实,不是给人看的话——怎么对模型或玩家说由宿主渲染。
 */
public sealed interface Outcome {

    /** 到了:身体停在目标里,目标要求看得见的那一格也看得见。 */
    record Arrived() implements Outcome {}

    /** 按这次的规格走得到的地方都搜过了,没有路。 */
    record NoRoute() implements Outcome {}

    /** 展开节点的预算用完了还没搜完:不能证明没路。 */
    record OutOfBudget() implements Outcome {}

    /** 路伸进了没加载的区块,那边是什么不知道。 */
    record Unloaded() implements Outcome {}

    /** 身体待不住:卡在 {@code cell}(此刻是 {@code block})里,或悬在半空,无从出发。 */
    record Stranded(BlockPos cell, BlockState block) implements Outcome {}

    /**
     * 规格许的改动不够:放开之后才有路,那条路要做 {@code changes} 这几件改地形的事(挖哪几格、放哪几格,连同许可的答复)。
     * 缺的是哪几样从改动本身读:有挖({@link #digs})要许挖,有放({@link #places})要许放,有许可答"要问"的格({@link #asks})
     * 要把要问的格算能走。
     */
    record NeedsChanges(List<Edit> changes) implements Outcome {

        public NeedsChanges {
            changes = List.copyOf(changes);
        }

        /** 那条路要改几格。 */
        public int alterations() {
            return changes.size();
        }

        /** 那条路要挖。 */
        public boolean digs() {
            return changes.stream().anyMatch(e -> e instanceof Edit.Dig);
        }

        /** 那条路要放方块(倒水接坠落也算)。 */
        public boolean places() {
            return changes.stream().anyMatch(e -> e instanceof Edit.Place || e instanceof Edit.Catch);
        }

        /** 那条路上有许可答"要问"的格。 */
        public boolean asks() {
            return changes.stream().anyMatch(e -> switch (e) {
                case Edit.Dig d -> d.permit() instanceof Permit.Ask;
                case Edit.Place p -> p.permit() instanceof Permit.Ask;
                case Edit.Catch c -> c.permit() instanceof Permit.Ask;
                case Edit.Door door -> false;
            });
        }
    }

    /** 要垫方块才有路,身上没有能垫的料。 */
    record NoMaterials() implements Outcome {}

    /** 规格的改动预算不够:最便宜的那条路要改 {@code needed} 格。 */
    record OverAlterBudget(int needed) implements Outcome {}

    /**
     * 憋不住气:照这次的规格有路,可路上有一段水下从 {@code from} 下去、到 {@code to} 才换得了气,要一口气憋 {@code held} 刻,
     * 身体到那里时只能安全地憋 {@code spare} 刻({@link com.dwinovo.numen.pathing.plan.Breath#spare})。
     */
    record Breathless(BlockPos from, BlockPos to, int held, int spare) implements Outcome {}

    /** 许可拒绝了 {@code cell};{@code reason} 是许可给的理由,原样交还。 */
    record Denied(BlockPos cell, Object reason) implements Outcome {}

    /** 执行受阻:哪一格、什么方块、哪种走法、为什么。 */
    record Blocked(Blockage blockage) implements Outcome {}

    /** 到了,但要看的 {@code target} 这一格看不见。 */
    record NoLineOfSight(BlockPos target) implements Outcome {}

    Outcome ARRIVED = new Arrived();
}
