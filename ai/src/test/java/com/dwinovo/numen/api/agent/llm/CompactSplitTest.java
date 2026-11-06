package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Compats;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.ModelSpec;
import com.dwinovo.numen.api.agent.provider.RequestOptions;
import com.google.gson.JsonObject;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩切分的回归钉:切点只能落在 User(首选)或 Assistant(劈轮),工具结果永远
 * 跟着它的调用;预算装得下整段时不切;一条都装不下时保留段为空(退化全量总结)。
 */
class CompactSplitTest {

    /** 不回传思考的端点。 */
    private static final LlmEndpoint PLAIN = LlmEndpoint.unresolved("");
    /** 要求带工具调用的助手消息回传 reasoning_content 的端点(DeepSeek)。 */
    private static final LlmEndpoint DEEPSEEK = new LlmEndpoint("deepseek", "https://api.deepseek.com", Map.of(), "k", "",
            new ModelSpec("deepseek-v4", ThinkingTable.AUTO_ONLY, true), 64_000,
            Compats.resolve(Api.CHAT, "deepseek", "https://api.deepseek.com", "deepseek-v4", ThinkingTable.AUTO_ONLY, null),
            RequestOptions.NONE, 16_384, 20_000);

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
        var split = CompactSplit.byRecentBudget(h, 220, PLAIN);
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
        var split = CompactSplit.byRecentBudget(h, 150, PLAIN);
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

    /** 劈在一轮中间:那一轮切点之前的半截是前缀,单独成段,不混进更早的历史。 */
    @Test
    void aTurnSplitInTheMiddleHasItsFirstHalfAsThePrefix() {
        List<ConvoState.Msg> h = List.of(
                user(100), assistant(100),          // 更早的一轮
                user(500),                          // 被劈的这一轮的开头
                assistantWithCall("a"), tool("a", 40),
                assistantWithCall("b"), tool("b", 40),
                assistant(40));
        var split = CompactSplit.byRecentBudget(h, 150, PLAIN);

        assertEquals(2, split.toSummarize().size(), "更早的一轮");
        assertEquals(h.get(2), split.turnPrefix().get(0), "前缀从被劈那一轮的开头(User)起");
        assertEquals(h.subList(2, 2 + split.turnPrefix().size()), split.turnPrefix(), "到切点为止,连续");
        assertTrue(split.kept().get(0) instanceof ConvoState.Msg.Assistant);
        assertEquals(h.size(), split.toSummarize().size() + split.turnPrefix().size() + split.kept().size());
    }

    @Test
    void cuttingAtATurnBoundaryHasNoPrefix() {
        List<ConvoState.Msg> h = List.of(user(100), assistant(100), user(50), assistant(50));
        assertTrue(CompactSplit.byRecentBudget(h, 120, PLAIN).turnPrefix().isEmpty());
    }

    @Test
    void everythingFitsMeansNothingToSummarize() {
        List<ConvoState.Msg> h = List.of(user(50), assistant(50));
        var split = CompactSplit.byRecentBudget(h, 10_000, PLAIN);
        assertTrue(split.toSummarize().isEmpty());
        assertEquals(2, split.kept().size());
    }

    @Test
    void nothingFitsMeansSummarizeEverything() {
        List<ConvoState.Msg> h = List.of(user(500), assistant(500), user(500));
        var split = CompactSplit.byRecentBudget(h, 100, PLAIN);
        assertTrue(split.kept().isEmpty());
        assertEquals(3, split.toSummarize().size());
    }

    @Test
    void estimatorCountsCjkHeavierThanAscii() {
        var cjk = new ConvoState.Msg.User("字".repeat(400));
        var ascii = new ConvoState.Msg.User("a".repeat(400));
        assertTrue(CompactSplit.estimateTokens(cjk, PLAIN) > CompactSplit.estimateTokens(ascii, PLAIN) * 3,
                "CJK 每字约 1 token,ASCII 约 4 字符/token");
        // 列表求和 = 逐条之和
        var list = new ArrayList<ConvoState.Msg>(List.of(cjk, ascii));
        assertEquals(CompactSplit.estimateTokens(cjk, PLAIN) + CompactSplit.estimateTokens(ascii, PLAIN),
                CompactSplit.estimateTokens(list, PLAIN));
    }

    /** 一轮 DeepSeek 式的工具调用:用户一句 + 带思考的调用回复 + 结果。 */
    private static List<ConvoState.Msg> thinkingRound(int n, String origin) {
        JsonObject extras = new JsonObject();
        extras.addProperty("reasoning_content", "思".repeat(1500));
        AssistantTurn turn = new AssistantTurn("", List.of(new LlmToolCall("c" + n, "goto", "{}")), extras,
                "思".repeat(1500), origin);
        return List.of(new ConvoState.Msg.User("第" + n + "句"),
                new ConvoState.Msg.Assistant(turn),
                new ConvoState.Msg.Tool("c" + n, "到了"));
    }

    private static List<ConvoState.Msg> thinkingHistory(int rounds, String origin) {
        List<ConvoState.Msg> h = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            h.addAll(thinkingRound(i, origin));
        }
        return h;
    }

    @Test
    void replayedReasoningCountsTowardWhatIsKept() {
        List<ConvoState.Msg> h = thinkingHistory(30, DEEPSEEK.origin());
        int budget = 6_000;
        var split = CompactSplit.byRecentBudget(h, budget, DEEPSEEK);

        assertTrue(CompactSplit.estimateTokens(split.kept(), DEEPSEEK) <= budget,
                "保留段按真正发出去的量(含回传的思考)装进预算");
        // 每轮约 1500 token 的思考:6000 的预算只装得下三轮左右,而不是不数思考时的几十轮
        assertTrue(split.kept().size() <= 4 * 3, "kept=" + split.kept().size());
    }

    @Test
    void reasoningThatWontBeSentIsNotCounted() {
        // 出处对不上(换绑过模型):发送层会脱掉思考,估算也不数
        List<ConvoState.Msg> h = thinkingHistory(30, "chat/other/some-model");
        var split = CompactSplit.byRecentBudget(h, 6_000, DEEPSEEK);

        assertTrue(split.kept().size() > 4 * 3, "kept=" + split.kept().size());
    }
}
