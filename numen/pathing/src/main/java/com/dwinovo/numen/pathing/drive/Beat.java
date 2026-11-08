package com.dwinovo.numen.pathing.drive;

import net.minecraft.core.BlockPos;

/** 一步在这一刻的结果:还在做,或做不下去了。一步做完不由它报,由段状态机看身体落在了哪个节点上。 */
sealed interface Beat {

    /** 还在做;{@code worked} 是这一刻真的干了活(挖掘有进度、门开了、块放下了)。 */
    record Going(boolean worked) implements Beat {}

    /** 做不下去了。 */
    record Blocked(Blockage blockage) implements Beat {}

    /** 动手被拒:{@code reason} 是拒绝方自己的理由。 */
    record Denied(BlockPos cell, Object reason) implements Beat {}

    Beat IDLE = new Going(false);
    Beat WORKED = new Going(true);
}
