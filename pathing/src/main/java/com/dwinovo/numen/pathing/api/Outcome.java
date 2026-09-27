package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.drive.Blockage;
import com.dwinovo.numen.pathing.spec.RouteSpec;

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
     * 规格许改的不够:放宽到 {@code level} 才有路,那条路要改 {@code alterations} 格。不许改地形时是 {@code NATURAL}
     * (许改自然地形就够)或 {@code ANY}(还要动主人得同意的格);只许改自然地形时是 {@code ANY}。
     */
    record NeedsAlter(RouteSpec.Alter level, int alterations) implements Outcome {}

    /** 要垫方块才有路,身上没有能垫的料。 */
    record NoMaterials() implements Outcome {}

    /** 规格的改动预算不够:最便宜的那条路要改 {@code needed} 格。 */
    record OverAlterBudget(int needed) implements Outcome {}

    /** 许可拒绝了 {@code cell};{@code reason} 是许可给的理由,原样交还。 */
    record Denied(BlockPos cell, Object reason) implements Outcome {}

    /** 执行受阻:哪一格、什么方块、哪种走法、为什么。 */
    record Blocked(Blockage blockage) implements Outcome {}

    /** 到了,但要看的 {@code target} 这一格看不见。 */
    record NoLineOfSight(BlockPos target) implements Outcome {}

    Outcome ARRIVED = new Arrived();
}
