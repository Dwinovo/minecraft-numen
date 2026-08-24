package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩切分的回归钉:切点只能落在 User(首选)或 Assistant(劈轮),工具结果永远
 * 跟着它的调用;预算装得下整段时不切;一条都装不下时保留段为空(退化全量总结)。
 */
class CompactSplitTest {

    private static ConvoState.Msg user(int approxTokens) {
        return new ConvoState.Msg.User("字".repeat(Math.max(0, approxTokens - 8)));
    }

    private static ConvoState.Msg assistant(int approxTokens) {
        return new ConvoState.Msg.Assistant(new AssistantTurn(
                "字".repeat(Math.max(0, approxTokens - 8)), List.of(), null));
    }

    private static ConvoState.Msg assistantWithCall(String id) {
        return new ConvoState.Msg.Assistant(new AssistantTurn(
                "", List.of(new LlmToolCall(id, "goto", "{}")), null));
    }

    private static ConvoState.Msg tool(String id, int approxTokens) {
        return new ConvoState.Msg.Tool(id, "字".repeat(Math.max(0, approxTokens - 8)));
    }

    @Test
    void cutsAtTheEarliestUserBoundaryWithinBudget() {
        List<ConvoState.Msg> h = List.of(
                user(100), assistant(100),          // 旧轮:应被总结
                user(50), assistant(50),            // 新轮:预算内,原文保留
                user(50), assistant(50));
        var split = CompactSplit.byRecentBudget(h, 220);
        assertEquals(2, split.toSummarize().size());
        assertEquals(4, split.kept().size());
        assertTrue(split.kept().get(0) instanceof ConvoState.Msg.User);
    }

    @Test
    void toolResultNeverLeadsTheKeptSpan() {
        // 单轮超预算:User 边界装不下,劈轮落在 Assistant 上;工具结果跟着它的调用
        List<ConvoState.Msg> h = List.of(
                user(500),
                assistantWithCall("a"), tool("a", 40),
                assistantWithCall("b"), tool("b", 40),
                assistant(40));
        var split = CompactSplit.byRecentBudget(h, 150);
        assertFalse(split.kept().isEmpty());
        assertTrue(split.kept().get(0) instanceof ConvoState.Msg.Assistant,
                "劈轮点必须是 Assistant,不能把 Tool 拆成保留段的第一条");
        // 保留段里的每个 Tool,它的调用都在保留段里
        for (int i = 0; i < split.kept().size(); i++) {
            if (split.kept().get(i) instanceof ConvoState.Msg.Tool t) {
                boolean callKept = split.kept().stream()
                        .filter(m -> m instanceof ConvoState.Msg.Assistant)
                        .map(m -> ((ConvoState.Msg.Assistant) m).turn())
                        .anyMatch(turn -> turn.toolCalls().stream()
                                .anyMatch(c -> c.id().equals(t.toolCallId())));
                assertTrue(callKept, "orphan tool result in kept span: " + t.toolCallId());
            }
        }
    }

    @Test
    void everythingFitsMeansNothingToSummarize() {
        List<ConvoState.Msg> h = List.of(user(50), assistant(50));
        var split = CompactSplit.byRecentBudget(h, 10_000);
        assertTrue(split.toSummarize().isEmpty());
        assertEquals(2, split.kept().size());
    }

    @Test
    void nothingFitsMeansSummarizeEverything() {
        List<ConvoState.Msg> h = List.of(user(500), assistant(500), user(500));
        var split = CompactSplit.byRecentBudget(h, 100);
        assertTrue(split.kept().isEmpty());
        assertEquals(3, split.toSummarize().size());
    }

    @Test
    void estimatorCountsCjkHeavierThanAscii() {
        var cjk = new ConvoState.Msg.User("字".repeat(400));
        var ascii = new ConvoState.Msg.User("a".repeat(400));
        assertTrue(CompactSplit.estimateTokens(cjk) > CompactSplit.estimateTokens(ascii) * 3,
                "CJK 每字约 1 token,ASCII 约 4 字符/token");
        // 列表求和 = 逐条之和
        var list = new ArrayList<ConvoState.Msg>(List.of(cjk, ascii));
        assertEquals(CompactSplit.estimateTokens(cjk) + CompactSplit.estimateTokens(ascii),
                CompactSplit.estimateTokens(list));
    }
}
