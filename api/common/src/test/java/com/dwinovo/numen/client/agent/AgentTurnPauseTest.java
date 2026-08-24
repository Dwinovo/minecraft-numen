package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTurnPauseTest {

    @Test
    void wakeWorthyEventResumesARecoverableFailure() {
        AgentTurnPause resumed = AgentTurnPause.RECOVERABLE_FAILURE.afterWakeEvent(true);

        assertEquals(AgentTurnPause.NONE, resumed);
        assertFalse(resumed.isPaused());
    }

    @Test
    void ambientEventDoesNotResumeARecoverableFailure() {
        assertEquals(AgentTurnPause.RECOVERABLE_FAILURE,
                AgentTurnPause.RECOVERABLE_FAILURE.afterWakeEvent(false));
    }

    @Test
    void eventNeverOverridesOwnerStopOrConfigurationBlock() {
        assertEquals(AgentTurnPause.OWNER_INTERRUPT,
                AgentTurnPause.OWNER_INTERRUPT.afterWakeEvent(true));
        assertEquals(AgentTurnPause.BLOCKED,
                AgentTurnPause.BLOCKED.afterWakeEvent(true));
        assertTrue(AgentTurnPause.OWNER_INTERRUPT.isPaused());
        assertTrue(AgentTurnPause.BLOCKED.isPaused());
    }
}
