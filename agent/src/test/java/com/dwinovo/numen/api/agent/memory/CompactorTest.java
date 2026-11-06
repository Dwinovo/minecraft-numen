package com.dwinovo.numen.api.agent.memory;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.llm.CompactSplit;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.MemoryPort;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Compats;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.ModelSpec;
import com.dwinovo.numen.api.agent.provider.RequestOptions;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 整理记忆:什么时候该自己动手、摘要怎么落地、连着失败就停手、清空不删记录。 */
class CompactorTest {

    private static final int WINDOW = 64_000;

    private static final LlmEndpoint PLAIN = endpoint("", "");
    private static final LlmEndpoint DEEPSEEK = endpoint("deepseek", "https://api.deepseek.com");

    private static LlmEndpoint endpoint(String providerId, String baseUrl) {
        return endpoint(providerId, baseUrl, WINDOW);
    }

    private static LlmEndpoint endpoint(String providerId, String baseUrl, int window) {
        return endpoint(providerId, baseUrl, window, 16_384, 20_000);
    }

    private static LlmEndpoint endpoint(String providerId, String baseUrl, int window, int reserve, int keep) {
        return new LlmEndpoint(providerId, baseUrl, Map.of(), "k", "",
                new ModelSpec("m", ThinkingTable.AUTO_ONLY, true), window,
                Compats.resolve(Api.CHAT, providerId, baseUrl, "m", ThinkingTable.AUTO_ONLY, null),
                RequestOptions.NONE, reserve, keep);
    }

    private static List<ModelOutcome.Answered> answered(String... texts) {
        return java.util.Arrays.stream(texts)
                .map(t -> new ModelOutcome.Answered(new AssistantTurn(t, List.of(), null), new Usage(9_000, 300, 0, 0)))
                .toList();
    }

    private static Compactor compactor(ConvoState convo, ConvoLog log) {
        return compactor(convo, log, PLAIN);
    }

    private static Compactor compactor(ConvoState convo, ConvoLog log, LlmEndpoint endpoint) {
        return new Compactor("test", convo, log, () -> endpoint);
    }

    private static ConvoLog logIn(Path dir) {
        return ConvoLog.atFile(dir.resolve("chat.jsonl"));
    }

    /** 和游戏里一样接线:历史里的每条消息同时写进日志(整理的"保留"指向的就是日志里这些记录)。 */
    private static ConvoState historyOf(int messages, ConvoLog log) {
        ConvoState convo = new ConvoState(m -> log.append(m, null));
        for (int i = 0; i < messages; i++) {
            if (i % 2 == 0) {
                convo.addUser("第" + i + "句");
            } else {
                convo.addAssistant(new AssistantTurn("回" + i, List.of(), null));
            }
        }
        return convo;
    }

    private static LoopEvent.ModelUsed turnUsed(int promptTokens) {
        return new LoopEvent.ModelUsed(new Usage(promptTokens, 10, 0, 0), LoopEvent.Purpose.TURN);
    }

    @Test
    void theGateFallsWhenTheLastRequestNearsTheWindow(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10, logIn(dir)), logIn(dir));
        c.on(turnUsed(40_000));
        assertFalse(c.compactionDue(), "离窗口还远");

        c.on(turnUsed(WINDOW - 5_000));
        assertTrue(c.compactionDue(), "逼近窗口,留不出下一轮的余量了");
    }

    @Test
    void aShortHistoryIsNotWorthCompactingOnItsOwn(@TempDir Path dir) {
        Compactor c = compactor(historyOf(4, logIn(dir)), logIn(dir));
        c.on(turnUsed(WINDOW));
        assertFalse(c.compactionDue(), "短于下限不自己动手——手动整理不看这个");
    }

    @Test
    void onlyConversationCallsCountTowardTheGate(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10, logIn(dir)), logIn(dir));
        c.on(new LoopEvent.ModelUsed(new Usage(WINDOW, 10, 0, 0), LoopEvent.Purpose.GOAL));
        c.on(new LoopEvent.ModelUsed(new Usage(WINDOW, 10, 0, 0), LoopEvent.Purpose.COMPACT));
        assertEquals(0, c.contextPercent(), "目标评估与整理本身的体量不是对话上下文的体量");
    }

    @Test
    void aSummaryReplacesTheHistoryAndLeavesADividerInTheRecord(@TempDir Path dir) {
        ConvoLog written = logIn(dir);
        ConvoState convo = historyOf(10, written);
        Compactor c = compactor(convo, written);
        ConvoLog log = logIn(dir);

        MemoryPort.Compaction cut = c.compaction(false, "").orElseThrow();
        assertTrue(cut.requests().get(0).tools().isEmpty(), "整理不带工具");
        boolean applied = cut.apply(answered("## 目标\n主人要铁;基地在 0,64,0"));

        assertTrue(applied);
        ConvoState.Msg first = convo.snapshot().get(0);
        assertTrue(((ConvoState.Msg.User) first).content().endsWith("## 目标\n主人要铁;基地在 0,64,0"), "回复整段就是摘要");
        assertEquals(convo.snapshot(), log.load(100), "重进游戏读回的就是整理后的样子");
        assertTrue(log.loadDisplay(100).contains(new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER)),
                "面板上的记录只多一条分隔,之前的话一句不少");
    }

    /**
     * 第二次整理不许把上一份摘要再嚼一遍:它被单独摘出来当 <previous_summary> 给出去,
     * 请求里不再有那条 user 消息,提示词也换成"在它之上更新"。
     *
     * <p>不这么做的话,上一份摘要会随"较早的部分"被重新总结,每整理一轮,早先记下的坐标
     * 和教训就少一点,而且没人会发现。
     */
    @Test
    void theSecondPassUpdatesTheLastSummaryInsteadOfChewingItAgain(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = historyOf(10, log);
        Compactor c = compactor(convo, log);
        c.compaction(false, "").orElseThrow().apply(answered("主人要铁;基地在 0,64,0"));
        for (int i = 0; i < 10; i++) {
            convo.addUser("后来第" + i + "句");
        }

        String all = lastUser(c.compaction(false, "").orElseThrow());
        assertTrue(all.contains("<previous-summary>\n主人要铁;基地在 0,64,0\n</previous-summary>"),
                "上一份摘要要单独给:" + all);
        assertFalse(all.substring(0, all.indexOf("<previous-summary>")).contains("[对话历史已压缩]"),
                "它不该再作为一条普通消息混在待整理的历史里");
        assertTrue(all.endsWith(Compactor.UPDATE_SUMMARIZATION_PROMPT), "提示词要换成更新那一份");
    }

    @Test
    void anEmptySummaryLeavesTheHistoryAlone(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = historyOf(10, log);
        List<ConvoState.Msg> before = convo.snapshot();
        MemoryPort.Compaction cut = compactor(convo, log).compaction(true, "").orElseThrow();

        assertFalse(cut.apply(answered("  ")));
        assertEquals(before, convo.snapshot());
    }

    @Test
    void threeFailuresInARowStopTheAutoPathUntilTheHistoryChanges(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10, logIn(dir)), logIn(dir));
        c.on(turnUsed(WINDOW));
        for (int i = 0; i < 3; i++) {
            c.compaction(true, "").orElseThrow().failed("超时");
        }
        assertFalse(c.compactionDue(), "熔断:连着失败就别再自己动手烧 token");

        c.on(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.CLEAR));
        c.on(turnUsed(WINDOW));
        assertTrue(c.compactionDue(), "历史换过之后熔断从头计");
    }

    /** 仿 DeepSeek 的长历史:每轮 用户一句 + 带思考回传的工具调用 + 工具结果,按线格式远超窗口。 */
    private static ConvoState deepseekHistory(int rounds, ConvoLog log) {
        ConvoState convo = new ConvoState(m -> log.append(m, null));
        for (int i = 0; i < rounds; i++) {
            JsonObject extras = new JsonObject();
            extras.addProperty("reasoning_content", "思".repeat(700));
            convo.addUser("第" + i + "句" + "话".repeat(200));
            convo.addAssistant(new AssistantTurn("", List.of(new LlmToolCall("c" + i, "goto", "{}")), extras,
                    "思".repeat(700), DEEPSEEK.origin()));
            convo.addToolResult("c" + i, "路".repeat(300));
        }
        return convo;
    }

    @Test
    void compactingBringsTheContextBelowTheTriggerEvenWhenReasoningIsReplayed(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = deepseekHistory(70, log);
        Compactor c = compactor(convo, log, DEEPSEEK);
        c.on(turnUsed(WINDOW - 12_000));
        assertTrue(c.compactionDue());

        assertTrue(c.compaction(true, "").orElseThrow().apply(answered("摘要")));

        // 保留段按真正发出去的量(含回传的思考)装进 2 万的预算:压完离触发线(窗口减预留)还很远
        int after = CompactSplit.estimateTokens(convo.snapshot(), DEEPSEEK);
        assertTrue(after < 22_000, "压完约 " + after + " token");
    }

    @Test
    void whenOnlyTheLastSummaryIsOlderThanTheKeptSpanThereIsNothingToCompact(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = new ConvoState(m -> log.append(m, null));
        convo.addUser("[对话历史已压缩] 以下是此前全部对话的摘要，请将其作为既成事实继续工作：\n\n" + "旧".repeat(8_000));
        for (int i = 0; i < 4; i++) {
            convo.addUser("话".repeat(2_400));
            convo.addAssistant(new AssistantTurn("答".repeat(2_400), List.of(), null));
        }
        List<ConvoState.Msg> before = convo.snapshot();
        Compactor c = compactor(convo, log);
        c.on(turnUsed(WINDOW));

        assertFalse(c.compactionDue(), "切完只剩旧摘要:闸门不放行,不空转");
        assertTrue(c.compaction(true, "").isEmpty(), "不压、不发摘要请求");
        assertTrue(c.compaction(false, "").isEmpty(), "手动整理同一判据");
        assertEquals(before, convo.snapshot());
    }

    @Test
    void clearingEmptiesTheContextButKeepsTheRecord(@TempDir Path dir) {
        ConvoLog sink = ConvoLog.atFile(dir.resolve("chat.jsonl"));
        ConvoState convo = new ConvoState(m -> sink.append(m, null));
        convo.addUser("你好");
        Compactor c = compactor(convo, sink);
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));

        c.clear();

        assertTrue(convo.snapshot().isEmpty());
        assertTrue(log.load(100).isEmpty(), "模型那一侧从白纸开始");
        assertEquals(List.of(new ConvoState.Msg.User("你好"), new ConvoState.Msg.User(ConvoLog.CLEAR_DIVIDER)),
                log.loadDisplay(100), "记录一个字不删");
    }

    // ---- 触发 ----

    /** 每条回复只问一次:放行之后同一条回复不会再触发,压缩之后压缩前那条回复的旧用量也不触发。 */
    @Test
    void aResponseTriggersAtMostOnceAndAStaleUsageNeverTriggersAfterACompaction(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10, logIn(dir)), logIn(dir));
        c.on(turnUsed(WINDOW - 5_000));
        assertTrue(c.compactionDue());
        assertFalse(c.compactionDue(), "同一条回复只问一次");

        c.on(turnUsed(WINDOW - 5_000));
        c.on(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.COMPACT));
        assertFalse(c.compactionDue(), "压缩之前那条回复量的是换掉之前的上下文,不再触发");

        c.on(turnUsed(WINDOW - 5_000));
        assertTrue(c.compactionDue(), "压缩之后的新回复照常判");
    }

    /** 线是 窗口 − 预留:档案里填的预留顶替缺省的 16384。 */
    @Test
    void theReserveFromTheProfileMovesTheTriggerLine(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        Compactor byDefault = compactor(historyOf(10, log), log, endpoint("", "", WINDOW, 16_384, 20_000));
        Compactor bigReserve = compactor(historyOf(10, log), log, endpoint("", "", WINDOW, 30_000, 20_000));

        byDefault.on(turnUsed(40_000));
        bigReserve.on(turnUsed(40_000));

        assertFalse(byDefault.compactionDue(), "40000 < 64000 - 16384");
        assertTrue(bigReserve.compactionDue(), "40000 > 64000 - 30000");
    }

    /** 近段原文的预算也读档案:填得小,保留的就少。 */
    @Test
    void theKeepBudgetFromTheProfileDecidesHowMuchStaysVerbatim(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState small = historyOf(40, log);
        ConvoState large = historyOf(40, log);

        compactor(small, log, endpoint("", "", WINDOW, 16_384, 100)).compaction(false, "").orElseThrow()
                .apply(answered("摘要"));
        compactor(large, log, endpoint("", "", WINDOW, 16_384, 20_000)).compaction(false, "").orElseThrow()
                .apply(answered("摘要"));

        assertTrue(small.snapshot().size() < 20, "预算 100:只留末尾几条 " + small.snapshot().size());
        assertTrue(large.snapshot().size() <= 2, "整段都在预算内:退化为全量总结 " + large.snapshot().size());
    }

    // ---- 提示词 ----

    private static String lastUser(MemoryPort.Compaction cut) {
        List<ConvoState.Msg> messages = cut.requests().get(0).messages();
        return ((ConvoState.Msg.User) messages.get(messages.size() - 1)).content();
    }

    /**
     * 摘要请求只有一条 user 消息:历史写成文字包在 &lt;conversation&gt; 里,后面才是指令;不带工具;系统提示只说"写摘要"。
     * 思考不进文字,长的工具结果截断。
     */
    @Test
    void theSummaryRequestHoldsTheHistoryAsTextInsideConversationTagsBeforeTheInstructions(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = new ConvoState(m -> log.append(m, null));
        JsonObject extras = new JsonObject();
        extras.addProperty("reasoning_content", "秘密思考");
        for (int i = 0; i < 5; i++) {
            convo.addUser("主人说第" + i + "句");
            convo.addAssistant(new AssistantTurn("好的", List.of(new LlmToolCall("c" + i, "lua", "{\"code\":\"goto()\"}")),
                    extras, "秘密思考", PLAIN.origin()));
            convo.addToolResult("c" + i, "路".repeat(3_000));
        }
        Compactor c = compactor(convo, log);

        ModelRequest request = c.compaction(false, "").orElseThrow().requests().get(0);

        assertEquals(1, request.messages().size(), "整段历史不作为一串消息发出去,免得模型接着往下说");
        assertTrue(request.tools().isEmpty());
        assertEquals(Compactor.SUMMARIZATION_SYSTEM_PROMPT, request.systemPrompt());
        String prompt = ((ConvoState.Msg.User) request.messages().get(0)).content();
        assertTrue(prompt.startsWith("<conversation>\n[User]: 主人说第0句"), prompt.substring(0, 40));
        assertTrue(prompt.contains("[Assistant]: 好的\n\n[Assistant tool calls]: lua(code=\"goto()\")"));
        assertTrue(prompt.contains("more characters truncated]"), "工具结果截断");
        assertFalse(prompt.contains("秘密思考"), "思考不进摘要请求");
        assertTrue(prompt.indexOf("</conversation>") < prompt.indexOf(Compactor.SUMMARIZATION_PROMPT),
                "先是材料,后是指令");
        assertTrue(prompt.endsWith(Compactor.SUMMARIZATION_PROMPT), "没有关注点:提示词原样");
    }

    /** 关注点拼在每份提示词末尾:较早历史的一份和被切开那一轮的前缀一份。 */
    @Test
    void aFocusIsAppendedToBothTheHistoryAndTheTurnPrefixPrompts(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = new ConvoState(m -> log.append(m, null));
        for (int i = 0; i < 3; i++) {
            convo.addUser("更早的话" + i);
            convo.addAssistant(new AssistantTurn("答" + i, List.of(), null));
        }
        // 最后一轮自己就超出近段预算:切点劈在这一轮中间
        convo.addUser("长".repeat(400));
        for (int i = 0; i < 6; i++) {
            convo.addAssistant(new AssistantTurn("", List.of(new LlmToolCall("t" + i, "lua", "{}")), null));
            convo.addToolResult("t" + i, "到了");
        }
        Compactor c = compactor(convo, log, endpoint("", "", WINDOW, 16_384, 150));

        MemoryPort.Compaction cut = c.compaction(false, " 坐标别丢 ").orElseThrow();

        assertEquals(2, cut.requests().size(), "较早的历史一份,被切开那一轮的前缀一份");
        String history = ((ConvoState.Msg.User) cut.requests().get(0).messages().get(0)).content();
        String prefix = ((ConvoState.Msg.User) cut.requests().get(1).messages().get(0)).content();
        assertTrue(history.startsWith("<conversation>") && history.endsWith(Compactor.SUMMARIZATION_PROMPT
                + "\n\n额外关注点:坐标别丢"));
        assertTrue(prefix.startsWith("# Conversation\n[User]: 长") && prefix.endsWith(
                "# Instructions\n" + Compactor.TURN_PREFIX_SUMMARIZATION_PROMPT + "\n\n额外关注点:坐标别丢"), prefix);

        assertTrue(cut.apply(answered("历史摘要", "前缀摘要")));
        String first = ((ConvoState.Msg.User) convo.snapshot().get(0)).content();
        assertTrue(first.endsWith("历史摘要\n\n---\n\n**本轮上下文(被切开的一轮):**\n\n前缀摘要"), first);
        assertFalse(cut.apply(answered("历史摘要", " ")), "任何一份是空的就算没压成");
    }

    /** 只有被切开的一轮、更早没有历史:前缀摘要接在"没有历史"后面;有上一份摘要时接在它后面。 */
    @Test
    void aSplitTurnWithoutOlderHistoryUsesThePreviousSummaryOrSaysThereWasNone(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = new ConvoState(m -> log.append(m, null));
        convo.addUser("长".repeat(400));
        for (int i = 0; i < 6; i++) {
            convo.addAssistant(new AssistantTurn("", List.of(new LlmToolCall("t" + i, "lua", "{}")), null));
            convo.addToolResult("t" + i, "到了");
        }
        Compactor c = compactor(convo, log, endpoint("", "", WINDOW, 16_384, 150));

        MemoryPort.Compaction cut = c.compaction(false, "").orElseThrow();

        assertEquals(1, cut.requests().size(), "没有更早的历史就只有前缀这一份");
        assertTrue(cut.apply(answered("前缀摘要")));
        assertTrue(((ConvoState.Msg.User) convo.snapshot().get(0)).content()
                .contains("(此前没有历史。)\n\n---\n\n**本轮上下文(被切开的一轮):**\n\n前缀摘要"));
    }
}
