package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.CompanionState;
import com.dwinovo.numen.api.mind.Feed;
import com.dwinovo.numen.api.mind.Slot;
import com.dwinovo.numen.api.library.EntryId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 大脑发生的事照序放进视图;主人做的事交给大脑({@code LoopHostTest}),不在这里办。 */
class CompanionViewTest {

    private static final UUID HER = UUID.randomUUID();
    private static final UUID ME = UUID.randomUUID();
    private static final UUID FRIEND = UUID.randomUUID();

    private final List<Feed> heard = new ArrayList<>();
    private final CompanionView her = new CompanionView(HER, null, heard::add);

    private static CompanionState state(Phase phase, String compactProblem, List<String> outstanding) {
        return new CompanionState(phase, null, null, false, null, 0.0,
                List.of(new EventQueue.Preview("p1", "query", "<query>去挖矿</query>")), compactProblem, null, null,
                1500, new Usage(1, 2, 3, 4), new Usage(5, 6, 7, 8), 900, 2, 33, 64000,
                Map.of(Slot.PERSONA, new CompanionState.Binding(new Bound(ME, EntryId.mine("小焰")), "我"),
                        Slot.PROVIDER, new CompanionState.Binding(new Bound(FRIEND, EntryId.mine("prov1")), "小柚")),
                null, "G", outstanding, 12);
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
        assertEquals("my:小焰", her.personaName(), "人设的名字就是它的 id");
        assertEquals("my:prov1", her.binding(Slot.PROVIDER).id());
        assertEquals(FRIEND, her.binding(Slot.PROVIDER).bound().by(), "档案是另一位主人绑的");
        assertEquals("小柚", her.binding(Slot.PROVIDER).byName());
        assertNull(her.binding(Slot.VOICE), "没绑声线");
        assertEquals("G", her.conversation());
        assertTrue(her.isToolCallOutstanding("call_1"));
        assertTrue(!her.isToolCallOutstanding("call_9"), "不在服务端手里的调用是被切断的");
    }

    @Test
    void theBoundRowIsTheOneThisOwnerBoundNotAnotherOwnersOfTheSameName() {
        her.apply(List.of(new Feed.Status(state(Phase.MODEL, null, List.of()))));

        assertTrue(her.binds(Slot.PERSONA, ME, "my:小焰"), "我绑的、id 对得上:就是我库里的这一条");
        assertTrue(!her.binds(Slot.PERSONA, ME, "my:小冰"), "id 不同");
        assertTrue(!her.binds(Slot.PROVIDER, ME, "my:prov1"), "朋友绑的同名条目在他的库里,不是我库里的这一条");
        assertTrue(her.binds(Slot.PROVIDER, FRIEND, "my:prov1"), "在朋友的客户端上它才是他的那一条");
        assertTrue(!her.binds(Slot.VOICE, ME, "my:派蒙"), "没绑");
        assertEquals("小柚", her.binding(Slot.PROVIDER).by().shown());
    }

    @Test
    void everyEventAlsoGoesToThePaintersInOrder() {
        List<Feed> batch = List.of(new Feed.TurnStart(false), new Feed.Delta("你", ""), new Feed.Halted());

        her.apply(batch);

        assertEquals(batch, heard);
    }
}
