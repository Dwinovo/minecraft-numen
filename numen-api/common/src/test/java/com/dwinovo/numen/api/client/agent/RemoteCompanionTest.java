package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.brain.CompanionState;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.Slot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务端推来的东西照序放进视图;主人做的事只变成发给服务端的话,不在这里办。 */
class RemoteCompanionTest {

    private static final UUID HER = UUID.randomUUID();

    private final List<OwnerInput> sent = new ArrayList<>();
    private final List<Feed> heard = new ArrayList<>();
    private final List<ConvoLog.Line> lines = new ArrayList<>();
    private final RemoteCompanion her = new RemoteCompanion(HER, sent::add, heard::add, lines::add);

    private static CompanionState state(Phase phase, String compactProblem, List<String> outstanding) {
        return new CompanionState(phase, null, null, false, null, 0.0,
                List.of(new EventQueue.Preview("p1", "query", "<query>去挖矿</query>")), compactProblem, null, null,
                1500, new Usage(1, 2, 3, 4), new Usage(5, 6, 7, 8), 900, 2, 33, 64000, "小焰", "prov1", null, null, "G",
                outstanding, 12);
    }

    @Test
    void theTypewriterGrowsWithDeltasAndEmptiesWhenTheReplyLands() {
        her.apply(List.of(new Feed.TurnStart(false), new Feed.Delta("你", "想"), new Feed.Delta("好", "")));

        assertEquals("你好", her.livePartial());
        assertEquals("想", her.liveReasoning());

        her.apply(List.of(new Feed.Replied("你好", false)));
        assertEquals("", her.livePartial(), "落库的那条行接管显示,半截不再画");
        assertEquals("", her.liveReasoning());
    }

    @Test
    void aCutVoidsTheHalfWrittenReply() {
        her.apply(List.of(new Feed.TurnStart(true), new Feed.Delta("半截", "")));
        her.apply(List.of(new Feed.TurnCut()));

        assertEquals("", her.livePartial());
    }

    @Test
    void whatTheServerSaysAboutHerIsWhatTheViewReads() {
        her.apply(List.of(new Feed.Status(state(Phase.MODEL, "numen.loop.compacting", List.of("call_1")))));

        assertEquals(Phase.MODEL, her.status().phase());
        assertEquals("<query>去挖矿</query>", her.status().queuedPreview().get(0).text());
        assertEquals(1500, her.totalTokensUsed());
        assertEquals(new Usage(1, 2, 3, 4), her.usageTotals());
        assertEquals(new Usage(5, 6, 7, 8), her.lastUsage());
        assertEquals(900, her.wastedTokens());
        assertEquals(33, her.contextPercent());
        assertEquals(64000, her.modelWindow());
        assertEquals("小焰", her.personaName(), "人设的名字就是它的 id");
        assertEquals("prov1", her.providerEntryId());
        assertEquals("G", her.conversation());
        assertTrue(her.isToolCallOutstanding("call_1"));
        assertTrue(!her.isToolCallOutstanding("call_9"), "不在服务端手里的调用是被切断的");
    }

    @Test
    void logLinesGoToTheViewAndTheTranscripts() {
        ConvoLog.Line line = new ConvoLog.Line("a1", 1000, null, new ConvoState.Msg.User("<query>好</query>"));

        her.apply(List.of(new Feed.Line(line)));

        assertEquals(List.of(line), her.display());
        assertEquals(List.of(line), lines);
    }

    @Test
    void everyEventAlsoGoesToThePaintersInOrder() {
        List<Feed> batch = List.of(new Feed.TurnStart(false), new Feed.Delta("你", ""), new Feed.Halted());

        her.apply(batch);

        assertEquals(batch, heard);
    }

    @Test
    void theOwnersActionsBecomeWordsToTheServer() {
        her.apply(List.of(new Feed.Status(state(Phase.MODEL, null, List.of()))));

        assertTrue(her.submitPrompt("你好"));
        her.submitSkill("/build 木屋", "build", "木屋");
        assertNull(her.requestCompact());
        assertNull(her.requestClearContext());
        her.abort();
        her.setGoal(GoalState.of("挖铁", 0), "/goal 挖铁");
        her.clearGoal(null);
        her.bind(Slot.PERSONA, "my:小焰");
        her.bind(Slot.PROVIDER, "my:prov2");
        her.bind(Slot.VOICE, null);

        assertEquals(List.of(new OwnerInput.Say(HER.toString(), "你好"), new OwnerInput.Command(HER, "/build 木屋"),
                new OwnerInput.Compact(HER), new OwnerInput.Clear(HER), new OwnerInput.Stop(HER.toString()),
                new OwnerInput.SetGoal(HER, "挖铁", "/goal 挖铁"), new OwnerInput.ClearGoal(HER),
                new OwnerInput.Bind(HER, Slot.PERSONA, "my:小焰"), new OwnerInput.Bind(HER, Slot.PROVIDER, "my:prov2"),
                new OwnerInput.Bind(HER, Slot.VOICE, null)), sent);
    }

    @Test
    void aCompactTheServerSaysIsNotPossibleIsNotSent() {
        her.apply(List.of(new Feed.Status(state(Phase.COMPACT, "numen.loop.compacting", List.of()))));

        assertEquals("numen.loop.compacting", her.requestCompact(), "理由是服务端算的,随状态推来");
        assertTrue(sent.isEmpty());
    }
}
