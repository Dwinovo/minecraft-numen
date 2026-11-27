package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 一条 user 消息只放一个人的话:几个人的话各自一条,世界的事并进第一条,说话人是条目上记的,不从文字里读。 */
class SpeakerRowsTest extends LoopHarness {

    private static EventQueue.Entry said(String who, String words, long ts) {
        return EventQueue.said(words, 0L, who, null, null, ts);
    }

    private List<ConvoState.Msg.User> users() {
        return transcript.snapshot().stream().filter(m -> m instanceof ConvoState.Msg.User)
                .map(m -> (ConvoState.Msg.User) m).toList();
    }

    @Test
    void oneSpeakerIsOneMessageMarkedWithTheirIdentity() {
        loop.push(List.of(said("player@000001 (Steve)", "挖铁", 1)));

        assertEquals(1, users().size());
        assertEquals("player@000001", users().get(0).from());
        assertTrue(users().get(0).content().contains("from=\"player@000001 (Steve)\""));
    }

    @Test
    void twoSpeakersInOneBatchAreTwoConsecutiveMessagesAndTheWorldRidesWithTheFirst() {
        host.external = true;   // 让它们一起排着,再一次取走
        loop.push(List.of(said("player@000001 (Steve)", "挖铁", 1), said("player@000002 (Alex)", "别挖", 2),
                new EventQueue.Entry(EventTypes.TASK_FINISHED, "<event kind=\"task_finished\">done</event>", 3, false)));
        host.external = false;
        loop.tick();

        List<ConvoState.Msg.User> users = users();
        assertEquals(2, users.size());
        assertEquals("player@000001", users.get(0).from());
        assertTrue(users.get(0).content().contains("挖铁") && users.get(0).content().contains("task_finished"));
        assertEquals("player@000002", users.get(1).from());
        assertTrue(users.get(1).content().contains("别挖") && !users.get(1).content().contains("挖铁"));
    }

    @Test
    void worldEventsAloneAreNoOnesWords() {
        loop.push(List.of(new EventQueue.Entry(EventTypes.DEATH, "<event kind=\"death\">died</event>", 1, true)));

        assertEquals(1, users().size());
        assertEquals(null, users().get(0).from());
    }
}
