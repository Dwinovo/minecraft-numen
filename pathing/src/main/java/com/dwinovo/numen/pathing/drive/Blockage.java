package com.dwinovo.numen.pathing.drive;

import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.plan.Reason;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一步走不下去:卡在哪一格、那一格此刻是什么方块、哪种走法、为什么。原因二选一——前提在活世界上复核不成立,是
 * {@code reason}({@link Reason},与规划报的是同一套);前提成立而身体做不到,是 {@code hitch}。
 *
 * @param reason 复核不成立的那一条前提;不是复核的事为 null
 * @param hitch  执行里出的事;是复核的事为 null
 */
public record Blockage(BlockPos cell, BlockState block, MoveKind move, Reason reason, Hitch hitch) {

    /** 前提成立、身体却做不到的几种情况。 */
    public enum Hitch {
        /** 这一步超过了期限({@link Watchdog})。 */
        STUCK,
        /** 要挖、要开的那一格看不见(准星碰不上它)。 */
        OCCLUDED,
        /** 要放的那一格没有一个点得中的面。 */
        NO_FACE,
        /** 手上拿不到要放的料。 */
        NO_MATERIALS,
        /** 复核时这一步落到的已经不是规划的那个节点(比如脚下的落点变了)。 */
        DIVERTED
    }
}
