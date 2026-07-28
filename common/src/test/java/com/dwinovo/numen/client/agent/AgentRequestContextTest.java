package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRequestContextTest {

    @Test
    void appendsRuntimeStateToToolResultWithoutCreatingAnotherUserTurn() {
        List<ConvoState.Msg> source = List.of(
                new ConvoState.Msg.User("go"),
                new ConvoState.Msg.Assistant(new AssistantTurn("",
                        List.of(new LlmToolCall("call-1", "goto", "{}")), null)),
                new ConvoState.Msg.Tool("call-1", "{\"success\":true}"));

        List<ConvoState.Msg> request = AgentRequestContext.attach(source,
                "<runtime_state><current_task id=\"t1\"/></runtime_state>");

        assertEquals(source.size(), request.size());
        assertNotSame(source.get(2), request.get(2));
        assertEquals("{\"success\":true}", ((ConvoState.Msg.Tool) source.get(2)).content());
        assertTrue(((ConvoState.Msg.Tool) request.get(2)).content().contains("current_task"));
    }

    @Test
    void finalAssistantGetsRequestOnlyContextTurn() {
        List<ConvoState.Msg> source = List.of(
                new ConvoState.Msg.User("hello"),
                new ConvoState.Msg.Assistant(new AssistantTurn("done", List.of(), null)));

        List<ConvoState.Msg> request = AgentRequestContext.attach(source, "<runtime_state/>");

        assertEquals(3, request.size());
        assertEquals("<runtime_state/>", ((ConvoState.Msg.User) request.get(2)).content());
    }

    @Test
    void stripsLegacyPersistedTaskStateButKeepsOwnerText() {
        List<ConvoState.Msg> source = List.of(new ConvoState.Msg.User(
                "<current_task>t1 goto 后台进行中</current_task>\n<query>繼續任務</query>"));

        List<ConvoState.Msg> request = AgentRequestContext.attach(source, "");

        assertEquals("<query>繼續任務</query>", ((ConvoState.Msg.User) request.get(0)).content());
        assertTrue(((ConvoState.Msg.User) source.get(0)).content().contains("current_task"));
    }

    @Test
    void neverStripsOwnerSuppliedTagInsideQuery() {
        String owner = "<query>explain <current_task>literal</current_task></query>";
        List<ConvoState.Msg> request = AgentRequestContext.attach(
                List.of(new ConvoState.Msg.User(owner)), "");
        assertEquals(owner, ((ConvoState.Msg.User) request.get(0)).content());
    }

    @Test
    void blankStateReturnsEquivalentSnapshot() {
        List<ConvoState.Msg> source = List.of(new ConvoState.Msg.User("hello"));
        assertEquals(source, AgentRequestContext.attach(source, " "));
    }
}
