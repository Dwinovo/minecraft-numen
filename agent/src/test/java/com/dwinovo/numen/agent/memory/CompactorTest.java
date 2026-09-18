package com.dwinovo.numen.agent.memory;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.MemoryPort;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 整理记忆:什么时候该自己动手、摘要怎么落地、连着失败就停手、清空不删记录。 */
class CompactorTest {

    private static final int WINDOW = 64_000;

    private static Compactor compactor(ConvoState convo, Path dir) {
        return new Compactor("test", convo, ConvoLog.atFile(dir.resolve("chat.jsonl")), () -> WINDOW);
    }

    private static ConvoState historyOf(int messages) {
        ConvoState convo = new ConvoState();
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
        Compactor c = compactor(historyOf(10), dir);
        c.on(turnUsed(40_000));
        assertFalse(c.compactionDue(), "离窗口还远");

        c.on(turnUsed(WINDOW - 5_000));
        assertTrue(c.compactionDue(), "逼近窗口,留不出下一轮的余量了");
    }

    @Test
    void aShortHistoryIsNotWorthCompactingOnItsOwn(@TempDir Path dir) {
        Compactor c = compactor(historyOf(4), dir);
        c.on(turnUsed(WINDOW));
        assertFalse(c.compactionDue(), "短于下限不自己动手——手动整理不看这个");
    }

    @Test
    void onlyConversationCallsCountTowardTheGate(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10), dir);
        c.on(new LoopEvent.ModelUsed(new Usage(WINDOW, 10, 0, 0), LoopEvent.Purpose.GOAL));
        c.on(new LoopEvent.ModelUsed(new Usage(WINDOW, 10, 0, 0), LoopEvent.Purpose.COMPACT));
        assertEquals(0, c.contextPercent(), "目标评估与整理本身的体量不是对话上下文的体量");
    }

    @Test
    void aSummaryReplacesTheHistoryAndLeavesADividerInTheRecord(@TempDir Path dir) {
        ConvoState convo = historyOf(10);
        Compactor c = compactor(convo, dir);
        ConvoLog log = ConvoLog.atFile(dir.resolve("chat.jsonl"));

        MemoryPort.Compaction cut = c.compaction(false);
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

    @Test
    void anEmptySummaryLeavesTheHistoryAlone(@TempDir Path dir) {
        ConvoState convo = historyOf(10);
        List<ConvoState.Msg> before = convo.snapshot();
        MemoryPort.Compaction cut = compactor(convo, dir).compaction(true);

        assertFalse(cut.apply(new AssistantTurn("<summary>  </summary>", List.of(), null), Usage.ZERO));
        assertEquals(before, convo.snapshot());
    }

    @Test
    void threeFailuresInARowStopTheAutoPathUntilTheHistoryChanges(@TempDir Path dir) {
        Compactor c = compactor(historyOf(10), dir);
        c.on(turnUsed(WINDOW));
        for (int i = 0; i < 3; i++) {
            c.compaction(true).failed("超时");
        }
        assertFalse(c.compactionDue(), "熔断:连着失败就别再自己动手烧 token");

        c.on(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.CLEAR));
        c.on(turnUsed(WINDOW));
        assertTrue(c.compactionDue(), "历史换过之后熔断从头计");
    }

    @Test
    void clearingEmptiesTheContextButKeepsTheRecord(@TempDir Path dir) {
        ConvoState convo = new ConvoState(ConvoLog.atFile(dir.resolve("chat.jsonl"))::append);
        convo.addUser("你好");
        Compactor c = compactor(convo, dir);
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
