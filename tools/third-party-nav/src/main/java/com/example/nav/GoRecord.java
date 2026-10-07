package com.example.nav;

import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.TaskRecord;

import net.minecraft.core.BlockPos;

/** {@code mynav.nav.go} 派下来的那件活:走去哪一格。 */
final class GoRecord extends TaskRecord {

    /** 一分钟走不到就当超时。 */
    private static final int DEADLINE_TICKS = 60 * 20;

    final BlockPos target;

    GoRecord(ServerCall source, BlockPos target) {
        super(source, source.her().level().getGameTime() + DEADLINE_TICKS);
        this.target = target.immutable();
    }

    @Override
    public String describe() {
        return getToolName() + " " + target.toShortString();
    }
}
