package com.dwinovo.numen.client.agent.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalCommandsTest {

    @Test
    void recognizesOnlySlashGoalCommands() {
        assertTrue(GoalCommands.isGoalCommand("/goal"));
        assertTrue(GoalCommands.isGoalCommand("/goal status"));
        assertTrue(GoalCommands.isGoalCommand("  /goal add build a house  "));
        assertFalse(GoalCommands.isGoalCommand("goal status"));
        assertFalse(GoalCommands.isGoalCommand("/numen reset"));
        assertFalse(GoalCommands.isGoalCommand(null));
    }

    @Test
    void addCreatesActiveGoalAndRecordsHistory() {
        GoalState state = GoalState.none("entity-1");
        GoalCommands.Result result =
                GoalCommands.execute(state, "/goal add build a house", 1000);

        assertTrue(result.success());
        assertEquals(GoalCommand.ADD, result.command());
        assertEquals(GoalStatus.ACTIVE, state.status());
        assertEquals("build a house", state.title());
        assertEquals(1, state.history().size());
    }

    @Test
    void pauseResumeCompleteWorkThroughCommands() {
        GoalState state = GoalState.none("entity-1");
        GoalCommands.execute(state, "/goal add reach the village", 1000);

        assertTrue(GoalCommands.execute(state, "/goal pause", 3000).success());
        assertEquals(GoalStatus.PAUSED, state.status());
        assertEquals(2000, state.elapsedMs());

        assertTrue(GoalCommands.execute(state, "/goal resume", 4000).success());
        assertEquals(GoalStatus.ACTIVE, state.status());

        assertTrue(GoalCommands.execute(state, "/goal complete", 6000).success());
        assertEquals(GoalStatus.COMPLETED, state.status());
        assertEquals(4000, state.elapsedMs());
    }

    @Test
    void directTextCreatesGoalWithoutAddVerb() {
        GoalState state = GoalState.none("entity-1");
        GoalCommands.Result result = GoalCommands.execute(state, "/goal nope", 1000);

        assertTrue(result.success());
        assertEquals(GoalCommand.ADD, result.command());
        assertEquals(GoalStatus.ACTIVE, state.status());
        assertEquals("nope", state.title());
        assertEquals(1, state.history().size());
    }

    @Test
    void likelyCommandTyposAreRejectedWithoutCreatingGoal() {
        GoalState state = GoalState.none("entity-1");

        GoalCommands.Result missingLetter = GoalCommands.execute(state, "/goal comlete", 1000);
        assertFalse(missingLetter.success());
        assertTrue(missingLetter.text().contains("/goal complete"));
        assertFalse(state.hasGoal());

        GoalCommands.Result transposed = GoalCommands.execute(state, "/goal udpate new title", 2000);
        assertFalse(transposed.success());
        assertTrue(transposed.text().contains("/goal update"));
        assertTrue(transposed.text().contains("/goal add udpate new title"));
        assertFalse(state.hasGoal());
    }

    @Test
    void naturalLanguageStillCreatesGoalsDirectly() {
        GoalState chinese = GoalState.none("entity-1");
        GoalCommands.Result chineseResult = GoalCommands.execute(chinese, "/goal 去挖一组原石", 1000);
        assertTrue(chineseResult.success());
        assertEquals("去挖一组原石", chinese.title());

        GoalState english = GoalState.none("entity-2");
        GoalCommands.Result englishResult = GoalCommands.execute(english, "/goal build a house", 1000);
        assertTrue(englishResult.success());
        assertEquals("build a house", english.title());

        GoalState nearCommand = GoalState.none("entity-3");
        GoalCommands.Result nearCommandResult = GoalCommands.execute(nearCommand, "/goal house", 1000);
        assertTrue(nearCommandResult.success());
        assertEquals("house", nearCommand.title());
    }

    @Test
    void everyKnownVerbParsesExactlyAndIsNotTreatedAsTypo() {
        for (GoalCommand command : GoalCommand.values()) {
            assertEquals(command, GoalCommand.parse(command.text()));
            assertEquals(null, GoalCommand.typoSuggestion(command.text()));
        }
    }

    @Test
    void helpAndRecentProvideReadableFeedback() {
        GoalState state = GoalState.none("entity-1");
        GoalCommands.execute(state, "/goal add mine iron", 1000);
        GoalCommands.execute(state, "/goal pause", 3000);

        assertTrue(GoalCommands.execute(state, "/goal help", 4000).text().contains("/goal add"));
        assertTrue(GoalCommands.execute(state, "/goal recent", 4000).text().contains("/goal add"));
        assertTrue(GoalCommands.execute(state, "/goal recent", 4000).text().contains("/goal pause"));
    }

    @Test
    void compactMarksStateAndPersistsHistory() {
        GoalState state = GoalState.none("entity-1");
        GoalCommands.execute(state, "/goal add build a farm", 1000);
        GoalCommands.Result result = GoalCommands.execute(state, "/goal compact", 2000);

        assertTrue(result.success());
        assertTrue(state.compactRequested());
        assertTrue(state.history().get(state.history().size() - 1).command().equals("/goal compact"));
    }
}
