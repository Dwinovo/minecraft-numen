package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NumenScreenStateTest {
    @Test void derivesActiveCompletedAndFailedToolCalls() {
        LlmToolCall completed = new LlmToolCall("done", "move_to", "{}");
        LlmToolCall active = new LlmToolCall("active", "mine", "{}");
        List<ConvoState.Msg> messages = List.of(
                new ConvoState.Msg.Assistant(new AssistantTurn("", List.of(completed, active), null)),
                new ConvoState.Msg.Tool("done", "{\"success\":false,\"message\":\"blocked\"}"));

        assertEquals(List.of(active), NumenScreenState.activeToolCalls(messages));
        assertEquals(java.util.Set.of("done"), NumenScreenState.completedToolIds(messages));
        assertEquals(java.util.Set.of("done"), NumenScreenState.failedToolIds(messages));
        assertEquals(List.of("blocked"), NumenScreenState.recentFailures(messages, 2));
    }

    @Test void keepsMostRecentValidTodoPlan() {
        LlmToolCall valid = new LlmToolCall("1", "todowrite",
                "{\"todos\":[{\"content\":\"first\",\"status\":\"in_progress\"}]}");
        LlmToolCall invalid = new LlmToolCall("2", "todowrite", "not-json");
        List<ConvoState.Msg> messages = List.of(
                new ConvoState.Msg.Assistant(new AssistantTurn("", List.of(valid), null)),
                new ConvoState.Msg.Assistant(new AssistantTurn("", List.of(invalid), null)));

        assertEquals("first", NumenScreenState.latestPlan(messages).get(0)
                .getAsJsonObject().get("content").getAsString());
    }

    @Test void normalizesLabelsAndDurations() {
        assertEquals(List.of("auto", "none", "high"),
                NumenScreenState.joinReasoningValues(List.of("none", "high", "high")));
        assertEquals("a b…", NumenScreenState.oneLine("a   b c", 3));
        assertEquals("1时1分", NumenScreenState.formatEta(3_660));
        assertEquals("fallback", NumenScreenState.blankTo(" ", "fallback"));
        assertEquals("64K", NumenScreenState.formatTokens(64_000));
        assertEquals("1M", NumenScreenState.formatTokens(1_000_000));
    }
}
