package com.dwinovo.numen.api.agent.memory;

import com.dwinovo.numen.api.agent.catalog.Api;
import com.dwinovo.numen.api.agent.catalog.ThinkingTable;
import com.dwinovo.numen.api.agent.llm.CompactSplit;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.MemoryPort;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 整理记忆:什么时候该自己动手、摘要怎么落地、连着失败就停手、清空不删记录。 */
class CompactorTest {

    private static final int WINDOW = 64_000;

    private static final LlmEndpoint PLAIN = endpoint("", "");
    private static final LlmEndpoint DEEPSEEK = endpoint("deepseek", "https://api.deepseek.com");

    private static LlmEndpoint endpoint(String providerId, String baseUrl) {
        return new LlmEndpoint(providerId, baseUrl, Map.of(), "k", "",
                new ModelSpec("m", ThinkingTable.AUTO_ONLY, true), WINDOW,
                Compats.resolve(Api.CHAT, providerId, baseUrl, "m", ThinkingTable.AUTO_ONLY, null),
                RequestOptions.NONE);
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

        MemoryPort.Compaction cut = c.compaction(false).orElseThrow();
        assertTrue(cut.request().tools().isEmpty(), "整理不带工具");
        boolean applied = cut.apply(new AssistantTurn(
                "<analysis>草稿</analysis><summary>主人要铁;基地在 0,64,0</summary>", List.of(), null),
                new Usage(9_000, 300, 0, 0));

        assertTrue(applied);
        ConvoState.Msg first = convo.snapshot().get(0);
        assertTrue(((ConvoState.Msg.User) first).content().endsWith("主人要铁;基地在 0,64,0"), "只留摘要,不留草稿");
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
        c.compaction(false).orElseThrow().apply(new AssistantTurn(
                "<summary>主人要铁;基地在 0,64,0</summary>", List.of(), null),
                new Usage(9_000, 300, 0, 0));
        for (int i = 0; i < 10; i++) {
            convo.addUser("后来第" + i + "句");
        }

        List<ConvoState.Msg> request = c.compaction(false).orElseThrow().request().messages();
        String all = request.stream()
                .map(m -> m instanceof ConvoState.Msg.User u ? u.content() : "")
                .reduce("", (a, b) -> a + System.lineSeparator() + b);
        assertTrue(all.contains("<previous_summary>"), "上一份摘要要单独给:" + all);
        assertTrue(all.contains("基地在 0,64,0"), "旧摘要的内容必须还在");
        assertTrue(request.stream().noneMatch(m -> m instanceof ConvoState.Msg.User u
                        && u.content() != null && u.content().startsWith("[对话历史已压缩]")),
                "它不该再作为一条普通消息混在待整理的历史里");
        assertTrue(all.contains("在那份摘要之上做更新"), "提示词要换成更新那一份");
    }

    @Test
    void anEmptySummaryLeavesTheHistoryAlone(@TempDir Path dir) {
        ConvoLog log = logIn(dir);
        ConvoState convo = historyOf(10, log);
        List<ConvoState.Msg> before = convo.snapshot();
        MemoryPort.Compaction cut = compactor(convo, log).compaction(true).orElseThrow();

        assertFalse(cut.apply(new AssistantTurn("<summary>  </summary>", List.of(), null), Usage.ZERO));
        assertEquals(before, convo.snapshot());
    }

    @Test
    void threeFailuresInARowStopTheAutoPathUntilTheHistoryChanges(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10, logIn(dir)), logIn(dir));
        c.on(turnUsed(WINDOW));
        for (int i = 0; i < 3; i++) {
            c.compaction(true).orElseThrow().failed("超时");
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

        assertTrue(c.compaction(true).orElseThrow().apply(
                new AssistantTurn("<summary>摘要</summary>", List.of(), null), new Usage(WINDOW - 12_000, 300, 0, 0)));

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
        assertTrue(c.compaction(true).isEmpty(), "不压、不发摘要请求");
        assertTrue(c.compaction(false).isEmpty(), "手动整理同一判据");
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

    @Test
    void theSummaryIsReadFromItsTagsAndToleratesSloppyOnes() {
        assertEquals("要点", Compactor.extractSummary("<analysis>想想</analysis><summary>要点</summary>"));
        assertEquals("没收尾也读到底", Compactor.extractSummary("<summary>没收尾也读到底"));
        assertEquals("没有标签就去掉草稿", Compactor.extractSummary("<analysis>草稿</analysis>没有标签就去掉草稿"));
        assertNull(Compactor.extractSummary("<analysis>想了很多</analysis><summary></summary>"),
                "摘要标签空着就是没压成——不能把空标签本身当摘要,换掉整段历史");
    }
}
