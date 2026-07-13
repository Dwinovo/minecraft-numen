package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgentContextPolicyTest {
    @Test void configuredLimitAndWindowHeadroomControlCompaction() {
        assertEquals(64_000, AgentContextPolicy.compactThreshold(1_000_000, 64_000));
        assertEquals(51_000, AgentContextPolicy.compactThreshold(64_000, 96_000));
        assertEquals(19_000, AgentContextPolicy.compactThreshold(32_000, 64_000));
        assertEquals(64_000, AgentContextPolicy.compactThreshold(1_000_000, 0));
        assertTrue(AgentContextPolicy.shouldCompact(1_000_000, 64_000, 64_000, 8, false, 100, 0));
        assertFalse(AgentContextPolicy.shouldCompact(1_000_000, 64_000, 63_999, 8, false, 100, 0));
        assertFalse(AgentContextPolicy.shouldCompact(1_000_000, 64_000, 64_000, 7, false, 100, 0));
        assertFalse(AgentContextPolicy.shouldCompact(1_000_000, 64_000, 64_000, 8, true, 100, 0));
        assertFalse(AgentContextPolicy.shouldCompact(1_000_000, 64_000, 64_000, 8, false, 100, 101));
    }

    @Test void recentDirectivesUseNewestUserMessagesOnly() {
        List<ConvoState.Msg> messages = List.of(
                new ConvoState.Msg.User("old"),
                new ConvoState.Msg.Assistant(new AssistantTurn("reply", List.of(), null)),
                new ConvoState.Msg.User("middle"),
                new ConvoState.Msg.User("new"));
        assertEquals("new middle", AgentContextPolicy.recentUserDirectives(messages, 2));
    }

    @Test void oldOrOutOfRangeProfileValuesNormalizeSafely() {
        assertEquals(64_000, CompanionAiConfigStore.normalizeAutoCompactTokens(0));
        assertEquals(16_000, CompanionAiConfigStore.normalizeAutoCompactTokens(1));
        assertEquals(1_000_000, CompanionAiConfigStore.normalizeAutoCompactTokens(Integer.MAX_VALUE));
        assertEquals(96_000, CompanionAiConfigStore.normalizeAutoCompactTokens(96_000));
    }

    @Test void compactionFailuresUseIncreasingCooldowns() {
        assertEquals(0L, AgentContextPolicy.failureCooldownMillis(0));
        assertEquals(120_000L, AgentContextPolicy.failureCooldownMillis(1));
        assertEquals(300_000L, AgentContextPolicy.failureCooldownMillis(2));
        assertEquals(900_000L, AgentContextPolicy.failureCooldownMillis(3));
        assertEquals(900_000L, AgentContextPolicy.failureCooldownMillis(99));
        assertEquals(Duration.ofSeconds(45), AgentContextPolicy.COMPACTION_TIMEOUT);
    }
}
