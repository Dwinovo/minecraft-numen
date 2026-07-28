package com.dwinovo.numen.core.agent;

import java.util.UUID;
import org.junit.jupiter.api.Test;

public final class AgentTurnActivityTest {
    @Test
    void activeTurnExpiresWithoutAHeartbeatAndStopsImmediatelyOnFinalState() {
        UUID companion = UUID.fromString("1702c992-821a-4f5a-89f6-2667347a2f85");
        AgentTurnActivity.clear(companion);

        AgentTurnActivity.observe(companion, true, 100L);
        require(AgentTurnActivity.isActive(companion, 100L), "a live agent turn must be active");
        require(
            AgentTurnActivity.isActive(companion, 200L),
            "a healthy heartbeat interval must keep the turn active"
        );
        require(
            !AgentTurnActivity.isActive(companion, 201L),
            "a missing client heartbeat must expire instead of blocking cleanup forever"
        );

        AgentTurnActivity.observe(companion, true, 300L);
        AgentTurnActivity.observe(companion, false, 301L);
        require(
            !AgentTurnActivity.isActive(companion, 301L),
            "an explicit final response must release cleanup immediately"
        );
        AgentTurnActivity.clear(companion);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
