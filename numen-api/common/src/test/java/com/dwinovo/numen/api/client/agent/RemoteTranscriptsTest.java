package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.conversation.Audience;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 面板看的会话记录:翻页取回的老行加上实时推来的新行,一行不重不漏;群里主人的一句话副本只画一次。 */
class RemoteTranscriptsTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final String G = "group-1";

    private final RemoteTranscripts transcripts = new RemoteTranscripts();

    private static Transcript.Entry entry(UUID who, String id, long ts, ConvoState.Msg msg) {
        return new Transcript.Entry(ts, who, msg, Map.of(who, id));
    }

    private static ConvoLog.Line line(String id, long ts, String conv, ConvoState.Msg msg) {
        return new ConvoLog.Line(id, ts, conv, msg);
    }

    private static ConvoState.Msg ownerSays(String said, int turn) {
        return new ConvoState.Msg.User(EventQueue.query(said) + "\n" + Audience.line(turn, "阿岚"));
    }

    @Test
    void theFirstPageIsAskedForOnceAndOlderOnesOnlyWhileThereAreMore() {
        assertTrue(transcripts.wantsFirstPage(G));
        transcripts.asked(G);
        assertFalse(transcripts.wantsFirstPage(G), "已经在要了");

        transcripts.page(G, List.of(entry(A, "a2", 2000, new ConvoState.Msg.User("<query>二</query>"))), "c1", true);
        assertTrue(transcripts.wantsOlder(G));
        transcripts.asked(G);
        assertFalse(transcripts.wantsOlder(G), "更早的一页也不重复要");

        transcripts.page(G, List.of(entry(A, "a1", 1000, new ConvoState.Msg.User("<query>一</query>"))), "c0", false);
        assertFalse(transcripts.wantsOlder(G), "没有更早的了");
        assertEquals(List.of("a1", "a2"), transcripts.entries(G).stream().map(e -> e.ids().get(A)).toList(),
                "老的接在前面");
    }

    @Test
    void aLineThatArrivesLiveIsAppendedOnlyOnceTheConversationIsLoaded() {
        transcripts.live(G, A, line("a3", 3000, G, new ConvoState.Msg.User("<query>三</query>")));
        assertTrue(transcripts.entries(G).isEmpty(), "还没取过最新一页:那一页回来会带上它");

        transcripts.page(G, List.of(entry(A, "a2", 2000, new ConvoState.Msg.User("<query>二</query>"))), "c", false);
        transcripts.live(G, A, line("a3", 3000, G, new ConvoState.Msg.User("<query>三</query>")));
        transcripts.live(G, A, line("a3", 3000, G, new ConvoState.Msg.User("<query>三</query>")));

        assertEquals(2, transcripts.entries(G).size(), "同一行编号只收一次");
    }

    @Test
    void theOwnersWordsCopiedIntoEveryMemberAreDrawnOnce() {
        transcripts.page(G, List.of(), "", false);

        transcripts.live(G, A, line("a1", 1000, G, ownerSays("都在吗", 4)));
        transcripts.live(G, B, line("b1", 1006, G, ownerSays("都在吗", 4)));
        transcripts.live(G, B, line("b2", 1100, G, new ConvoState.Msg.Assistant(
                new com.dwinovo.numen.api.agent.provider.AssistantTurn("在", List.of(), null))));

        assertEquals(2, transcripts.entries(G).size(), "副本并进了第一份");
        assertEquals(B, transcripts.entries(G).get(1).companion(), "回复各标各的说话人");
    }

    @Test
    void aPageDoesNotRepeatWhatAlreadyCameLive() {
        transcripts.page(G, List.of(entry(A, "a2", 2000, new ConvoState.Msg.User("<query>二</query>"))), "c", true);
        transcripts.live(G, A, line("a3", 3000, G, new ConvoState.Msg.User("<query>三</query>")));

        transcripts.page(G, List.of(entry(A, "a1", 1000, new ConvoState.Msg.User("<query>一</query>")),
                entry(A, "a2", 2000, new ConvoState.Msg.User("<query>二</query>"))), "c0", false);

        assertEquals(List.of("a1", "a2", "a3"), transcripts.entries(G).stream().map(e -> e.ids().get(A)).toList());
    }
}
