package com.dwinovo.numen.core.task.combat;

import java.util.List;

/** Typed descriptor for attacking explicit runtime entity ids at range. */
public final class RangedAttackTaskRecord extends CombatTaskRecord {
    public static final String TOOL_NAME = "ranged_attack";

    public RangedAttackTaskRecord(String toolCallId, long deadlineGameTime, List<Integer> entityIds) {
        super(TOOL_NAME, toolCallId, deadlineGameTime, entityIds);
    }

    @Override public String completedWord() { return "destroyed"; }
    @Override public String completingWord() { return "destroying"; }
    @Override public String strikeWord() { return "shots"; }
}
