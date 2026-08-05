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

    /**
     * 透视面板画得出运行期状态——不管它挂在哪一种消息上。
     *
     * <p>{@code ChatView} 在 {@code showsModelRequest} 下画 User / Assistant 正文 / Tool
     * 三种;挂载点却随尾巴形状变(见 {@link AgentRequestContext#attach})。两边各改各的话,
     * 挂到一种面板不画的消息上,现象就是"开了 debug 还是看不见 current_task"——而那
     * 会被读成"这东西没发出去"。所以这里按面板的口径去找。
     */
    private static String whatThePanelWouldDraw(List<ConvoState.Msg> request) {
        StringBuilder sb = new StringBuilder();
        for (ConvoState.Msg m : request) {
            switch (m) {
                case ConvoState.Msg.User u -> sb.append(u.content()).append('\n');
                case ConvoState.Msg.Tool t -> sb.append(t.content()).append('\n');
                case ConvoState.Msg.Assistant a -> sb.append(a.turn().content()).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void runtimeStateAlwaysLandsSomewhereThePanelDraws() {
        String rt = "<runtime_state><current_task id=\"t1\" tool=\"follow\"/></runtime_state>";

        List<List<ConvoState.Msg>> tails = List.of(
                List.of(new ConvoState.Msg.User("<query>跟着我</query>")),
                List.of(new ConvoState.Msg.User("go"),
                        new ConvoState.Msg.Assistant(new AssistantTurn("",
                                List.of(new LlmToolCall("c1", "goto", "{}")), null)),
                        new ConvoState.Msg.Tool("c1", "{\"success\":true}")),
                List.of(new ConvoState.Msg.User("hi"),
                        new ConvoState.Msg.Assistant(new AssistantTurn("好的", List.of(), null))));

        for (List<ConvoState.Msg> tail : tails) {
            assertTrue(whatThePanelWouldDraw(AgentRequestContext.attach(tail, rt)).contains(rt),
                    "这种尾巴下面板画不出运行期状态:" + tail);
        }
    }

    @Test
    void aTurnStuckMidToolCallCarriesNoRuntimeStateOnPurpose() {
        // assistant 的 tool_calls 与它的结果之间插不进 user 消息(上游会 400),
        // 所以这一刻宁可不带。面板跟着一起没有,两边看到的是同一件事。
        List<ConvoState.Msg> source = List.of(
                new ConvoState.Msg.User("go"),
                new ConvoState.Msg.Assistant(new AssistantTurn("",
                        List.of(new LlmToolCall("c1", "goto", "{}")), null)));

        List<ConvoState.Msg> request = AgentRequestContext.attach(source, "<runtime_state/>");

        assertEquals(source.size(), request.size());
        assertTrue(!whatThePanelWouldDraw(request).contains("runtime_state"));
    }
}
