package com.dwinovo.numen.pathing.plan;

import net.minecraft.core.BlockPos;

/**
 * 一步的前提成不成立。成立时交出这一步的全部事实({@link Maneuver});不成立时交出是哪一格、哪一条前提不成立——
 * 执行复核停下时报的就是它。
 */
public sealed interface Premise {

    /** 前提成立。 */
    record Holds(Maneuver maneuver) implements Premise {}

    /**
     * 前提不成立。
     *
     * @param cell   卡在哪一格
     * @param reason 哪一条前提
     * @param detail 许可拒绝时它给的理由,原样交还;其余为 null
     */
    record Fails(BlockPos cell, Reason reason, Object detail) implements Premise {}

    static Premise.Fails fail(BlockPos cell, Reason reason) {
        return new Fails(cell.immutable(), reason, null);
    }
}
