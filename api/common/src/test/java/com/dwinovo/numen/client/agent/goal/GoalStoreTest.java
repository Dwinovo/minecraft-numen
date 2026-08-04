package com.dwinovo.numen.client.agent.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileLoadsAsNoGoal() {
        UUID entityUuid = UUID.randomUUID();
        GoalStore store = GoalStore.forEntity(tempDir.resolve("goals"), entityUuid);

        GoalState state = store.load();

        assertEquals(entityUuid.toString(), state.id());
        assertEquals(GoalStatus.NONE, state.status());
        assertFalse(state.hasGoal());
    }

    @Test
    void saveAndReloadPreservesState() {
        UUID entityUuid = UUID.randomUUID();
        GoalStore store = GoalStore.forEntity(tempDir.resolve("goals"), entityUuid);
        GoalState goal = GoalState.create(entityUuid.toString(), "mine diamonds", 1000);
        goal.pause(3000);
        goal.recordCommand("/goal add mine diamonds", "created", 1000);

        assertTrue(store.save(goal));

        GoalState restored = new GoalStore(store.file()).load();
        assertEquals(entityUuid.toString(), restored.id());
        assertEquals("mine diamonds", restored.title());
        assertEquals(GoalStatus.PAUSED, restored.status());
        assertEquals(2000, restored.elapsedMs());
        assertEquals(1, restored.history().size());
    }

    @Test
    void corruptFileLoadsAsNoGoalAndDoesNotDeleteTheFile() throws Exception {
        UUID entityUuid = UUID.randomUUID();
        Path file = tempDir.resolve("goals").resolve(entityUuid + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);

        GoalStore store = new GoalStore(file);
        GoalState state = store.load();

        assertEquals(GoalStatus.NONE, state.status());
        assertTrue(Files.isRegularFile(file));
    }
}
