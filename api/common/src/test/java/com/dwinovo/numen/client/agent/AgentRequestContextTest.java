package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRequestContextTest {

    @Test
    void afterAToolResultTheStateComesAsItsOwnUserMessage() {
        // 工具结果原样交回,一个字不加:挂上去的话,"这一轮的 user 消息"就有了例外,
        // 而且读日志的人会以为工具自己吐了个 <runtime_state>。
        List<ConvoState.Msg> source = List.of(
                new ConvoState.Msg.User("go"),
                new ConvoState.Msg.Assistant(new AssistantTurn("",
                        List.of(new LlmToolCall("call-1", "goto", "{}")), null)),
                new ConvoState.Msg.Tool("call-1", "{\"success\":true}"));

        List<ConvoState.Msg> request = AgentRequestContext.attach(source,
                "<runtime_state><current_task id=\"t1\"/></runtime_state>");

        assertEquals(source.size() + 1, request.size());
        assertEquals("{\"success\":true}", ((ConvoState.Msg.Tool) request.get(2)).content());
        assertTrue(((ConvoState.Msg.User) request.get(3)).content().contains("current_task"));
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

    /** 这一轮所有 role=user 的消息拼起来——"发出去的 user 消息"就是这些,没有别处。 */
    private static String userMessages(List<ConvoState.Msg> request) {
        StringBuilder sb = new StringBuilder();
        for (ConvoState.Msg m : request) {
            if (m instanceof ConvoState.Msg.User u) sb.append(u.content()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void runtimeStateIsAlwaysCarriedByAUserMessage() {
        // 不管尾巴是什么形状,运行期状态都在 user 里。"这一轮的 user 消息"因此是一个
        // 完整的答案,不需要谁再去别处补看一眼。
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
            assertTrue(userMessages(AgentRequestContext.attach(tail, rt)).contains(rt),
                    "这种尾巴下运行期状态没落在 user 消息里:" + tail);
        }
    }

    @Test
    void toolResultsAreHandedBackWordForWord() {
        // 工具结果是服务端交回的原文。往里塞东西 = 读日志的人会以为工具自己吐了它。
        ConvoState.Msg.Tool result = new ConvoState.Msg.Tool("c1", "{\"success\":true}");
        List<ConvoState.Msg> request = AgentRequestContext.attach(
                List.of(new ConvoState.Msg.User("go"),
                        new ConvoState.Msg.Assistant(new AssistantTurn("",
                                List.of(new LlmToolCall("c1", "goto", "{}")), null)),
                        result),
                "<runtime_state/>");

        for (ConvoState.Msg m : request) {
            if (m instanceof ConvoState.Msg.Tool t) {
                assertEquals(result.content(), t.content());
            }
        }
    }

    @Test
    void aTurnStuckMidToolCallCarriesNoRuntimeStateOnPurpose() {
        // assistant 的 tool_calls 与它的结果之间插不进任何消息(上游会 400),
        // 所以这一刻宁可不带,不找地方硬塞。
        List<ConvoState.Msg> source = List.of(
                new ConvoState.Msg.User("go"),
                new ConvoState.Msg.Assistant(new AssistantTurn("",
                        List.of(new LlmToolCall("c1", "goto", "{}")), null)));

        List<ConvoState.Msg> request = AgentRequestContext.attach(source, "<runtime_state/>");

        assertEquals(source, request);
    }
}
