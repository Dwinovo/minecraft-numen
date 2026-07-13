package com.dwinovo.numen.core.task;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStateStoreTest {

    @Test
    void activeTaskRestoresPendingAtQueueHeadWithRelativeTimeoutBudget() {
        MoveToTaskRecord original = new MoveToTaskRecord("call-active", 1_500L,
                1.0, 2.0, 3.0, 0.8);

        JsonObject json = TaskStateStore.encodeRecord(original, 1_000L, true);

        assertFalse(json.has("deadline_game_time"));
        assertEquals(500L, json.get("remaining_timeout_ticks").getAsLong());
        TaskRecord decoded = TaskStateStore.decodeRecord(json, 20_000L);
        MoveToTaskRecord restored = assertInstanceOf(MoveToTaskRecord.class, decoded);
        assertEquals(TaskState.PENDING, restored.getState());
        assertEquals(20_500L, restored.getDeadlineGameTime());
        assertTrue(restored.wasRestored());
        assertTrue(restored.wasActiveBeforeRestart());
    }

    @Test
    void individuallyPausedTaskStaysPausedAndKeepsItsBudget() {
        MoveToTaskRecord original = new MoveToTaskRecord("call-paused", 1_500L,
                1.0, 2.0, 3.0, 1.0);
        original.pauseAt(900L);

        JsonObject json = TaskStateStore.encodeRecord(original, 1_200L, false);
        TaskRecord restored = TaskStateStore.decodeRecord(json, 50_000L);

        assertEquals(600L, json.get("remaining_timeout_ticks").getAsLong());
        assertEquals(TaskState.PAUSED, restored.getState());
        assertEquals(50_600L, restored.getDeadlineGameTime());
    }

    @Test
    void unknownOrUnsafeTaskBecomesExplicitlyInterrupted() {
        JsonObject json = new JsonObject();
        json.addProperty("tool_call_id", "unknown-call");
        json.addProperty("tool_name", "future_tool");
        json.addProperty("arguments_json", "{}");
        json.addProperty("remaining_timeout_ticks", 20L);
        json.addProperty("recoverable", true);
        json.add("parameters", new JsonObject());
        json.add("progress", new JsonObject());

        TaskRecord restored = TaskStateStore.decodeRecord(json, 100L);

        assertInstanceOf(InterruptedTaskRecord.class, restored);
        assertEquals(TaskState.PENDING, restored.getState());
        assertEquals(120L, restored.getDeadlineGameTime());
    }
}
