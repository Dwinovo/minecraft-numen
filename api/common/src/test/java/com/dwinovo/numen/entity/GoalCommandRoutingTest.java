package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalCommandRoutingTest {

    @Test
    void exactOwnedCompanionUsesStatusInsteadOfBroadcastCreation() {
        assertEquals("/goal status", GoalCommands.directCommand("worker1", true));
    }

    @Test
    void unknownSingleArgumentKeepsDirectCreationSemantics() {
        assertEquals("/goal build a house", GoalCommands.directCommand("build a house", false));
    }

    @Test
    void blankOrNullSingleArgumentNormalizesToRootCommand() {
        assertEquals("/goal", GoalCommands.directCommand("  ", false));
        assertEquals("/goal", GoalCommands.directCommand(null, false));
    }
}
