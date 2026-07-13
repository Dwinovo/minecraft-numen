package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConvoStateTest {

    @Test
    void toolCallIdIsCompletedOnlyOnce() {
        List<ConvoState.Msg> persisted = new ArrayList<>();
        ConvoState state = new ConvoState(persisted::add);

        state.addToolResult("call-1", "first");
        state.addToolResult("call-1", "duplicate");

        assertEquals(1, state.snapshot().size());
        assertEquals(1, persisted.size());
        assertEquals(new ConvoState.Msg.Tool("call-1", "first"), state.snapshot().get(0));
    }

    @Test
    void preloadDoesNotRepersistAndStillParticipatesInDeduplication() {
        List<ConvoState.Msg> persisted = new ArrayList<>();
        ConvoState state = new ConvoState(persisted::add);
        state.preload(List.of(new ConvoState.Msg.Tool("reconnected-call", "already done")));

        state.addToolResult("reconnected-call", "must not be appended again");

        assertEquals(1, state.snapshot().size());
        assertEquals(List.of(), persisted);
    }
}
