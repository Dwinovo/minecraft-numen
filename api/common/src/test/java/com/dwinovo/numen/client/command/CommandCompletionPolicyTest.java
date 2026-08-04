package com.dwinovo.numen.client.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompletionPolicyTest {

    private static final List<CommandCandidate> CANDIDATES = List.of(
            new CommandCandidate("/goal", "status", "goal"),
            new CommandCandidate("/goal status", "status", "goal"),
            new CommandCandidate("/goal add", "add", "goal", true));

    @Test
    void enterSendsWhenThereAreNoCandidates() {
        CommandCompletionPolicy.EnterDecision decision =
                CommandCompletionPolicy.enter("hello", List.of(), 0);

        assertTrue(decision.send());
        assertEquals("hello", decision.text());
    }

    @Test
    void enterCompletesPartialInput() {
        CommandCompletionPolicy.EnterDecision decision =
                CommandCompletionPolicy.enter("/goal a", CANDIDATES, 2);

        assertFalse(decision.send());
        assertEquals("/goal add ", decision.text());
    }

    @Test
    void enterOnExactArgumentCommandKeepsEditingInsteadOfSending() {
        CommandCompletionPolicy.EnterDecision decision =
                CommandCompletionPolicy.enter("/goal add", CANDIDATES, 2);

        assertFalse(decision.send());
        assertEquals("/goal add ", decision.text());
    }

    @Test
    void enterSendsAnExactTypedCommandEvenWhenAnotherRowIsSelected() {
        CommandCompletionPolicy.EnterDecision decision =
                CommandCompletionPolicy.enter("/goal status", CANDIDATES, 0);

        assertTrue(decision.send());
        assertEquals("/goal status", decision.text());
    }

    @Test
    void tabFillsTheSelectedCandidate() {
        CommandCompletionPolicy.TabDecision decision =
                CommandCompletionPolicy.tab("/goal a", CANDIDATES, 2, 1);

        assertEquals("/goal add ", decision.text());
        assertEquals(2, decision.selectedIndex());
    }

    @Test
    void tabOnExactArgumentCommandAppendsSpaceWithoutCycling() {
        CommandCompletionPolicy.TabDecision decision =
                CommandCompletionPolicy.tab("/goal add", CANDIDATES, 2, 1);

        assertEquals("/goal add ", decision.text());
        assertEquals(2, decision.selectedIndex());
    }

    @Test
    void tabCyclesFromAnExactCommand() {
        CommandCompletionPolicy.TabDecision decision =
                CommandCompletionPolicy.tab("/goal", CANDIDATES, 0, 1);

        assertEquals("/goal status", decision.text());
        assertEquals(1, decision.selectedIndex());
    }

    @Test
    void shiftTabCyclesBackwards() {
        CommandCompletionPolicy.TabDecision decision =
                CommandCompletionPolicy.tab("/goal status", CANDIDATES, 1, -1);

        assertEquals("/goal", decision.text());
        assertEquals(0, decision.selectedIndex());
    }
}
