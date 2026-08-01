package com.dwinovo.numen.core.task;

import java.util.List;

/** Typed descriptor for attacking an explicit, perception-authorized entity-id set. */
public final class MeleeAttackTaskRecord extends CombatTaskRecord {
    public static final String TOOL_NAME = "melee_attack";

    public MeleeAttackTaskRecord(String toolCallId, long deadlineGameTime, List<Integer> entityIds) {
        super(TOOL_NAME, toolCallId, deadlineGameTime, entityIds);
    }

    @Override public String completedWord() { return "defeated"; }
    @Override public String completingWord() { return "defeating"; }
    @Override public String strikeWord() { return "hits"; }
}
