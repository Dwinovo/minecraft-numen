package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityAgentLoopMemoryCountTest {

    @Test
    void personaMarkersDoNotIncreaseLogicalMemoryCount() {
        List<ConvoState.Msg> messages = List.of(
                new ConvoState.Msg.User("<query>hello</query>"),
                new ConvoState.Msg.User(ConvoLog.PERSONA_DIVIDER),
                new ConvoState.Msg.User("<persona-change>new persona</persona-change>"),
                new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER),
                new ConvoState.Msg.User("<query>continue</query>"));

        assertEquals(2, EntityAgentLoop.countMemoryMessages(messages));
    }
}
