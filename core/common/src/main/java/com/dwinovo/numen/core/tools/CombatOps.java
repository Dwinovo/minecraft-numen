package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.task.TaskRecord;

import java.util.List;

/** 造 {@code fight.attack} 的任务账本。 */
public final class CombatOps {

    /** 打一只给多久。 */
    private static final long TICKS = 120L * 20L;

    /** 打点名的那一只。 */
    public TaskRecord attack(ServerSource src, int entityId) {
        return new AttackTaskRecord(src.taskName(), src.toolCallId(),
                src.companion().level().getGameTime() + TICKS, List.of(entityId), false);
    }
}
