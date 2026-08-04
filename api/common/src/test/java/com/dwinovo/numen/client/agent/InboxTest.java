package com.dwinovo.numen.client.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxTest {

    @Test
    void ambientEventsStayQueuedButDoNotCountAsOwnerBacklog(@TempDir Path dir) {
        UUID entityUuid = UUID.randomUUID();
        Inbox inbox = new Inbox(dir, entityUuid);
        String event = "<persona-change>switched</persona-change>";
        String prompt = "<query>hello</query>";

        inbox.pushEvent(event);

        Inbox restored = new Inbox(dir, entityUuid);

        assertFalse(restored.isEmpty());
        assertEquals(0, restored.promptCount());
        assertEquals(1, restored.eventCount());
        assertTrue(restored.promptSnapshot().isEmpty());
        assertEquals(List.of(event), restored.snapshot());

        restored.pushPrompt(prompt);

        assertEquals(1, restored.promptCount());
        assertEquals(List.of(prompt), restored.promptSnapshot());
        assertEquals(List.of(event, prompt), restored.drain());
        assertTrue(restored.isEmpty());
        assertEquals(0, restored.promptCount());
        assertEquals(0, restored.eventCount());
    }
}
