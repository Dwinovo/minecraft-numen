package com.dwinovo.numen.client.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {

    private final CommandRegistry registry = new CommandRegistry();

    @Test
    void slashPrefixReturnsGoalAndNumenFamilies() {
        List<CommandCandidate> all = registry.candidates("/");

        assertTrue(all.stream().anyMatch(c -> c.command().equals("/goal")));
        assertTrue(all.stream().anyMatch(c -> c.command().equals("/numen")));
    }

    @Test
    void goalSubcommandsFilterByPrefix() {
        List<CommandCandidate> add = registry.candidates("/goal a");
        assertTrue(add.stream().anyMatch(c -> c.command().equals("/goal add")));
        assertTrue(add.stream().filter(c -> c.command().equals("/goal add"))
                .allMatch(CommandCandidate::requiresArgument));
        assertFalse(add.stream().anyMatch(c -> c.command().equals("/goal settings")));

        List<CommandCandidate> recent = registry.candidates("/goal rec");
        assertTrue(recent.stream().anyMatch(c -> c.command().equals("/goal recent")));
        assertTrue(recent.stream().noneMatch(CommandCandidate::requiresArgument));
    }

    @Test
    void aliasesResolveToCanonicalCommands() {
        assertTrue(registry.candidates("/g").stream().anyMatch(c -> c.command().equals("/goal")));
        assertTrue(registry.candidates("/n").stream().anyMatch(c -> c.command().equals("/numen")));
    }

    @Test
    void externalCommandsAreRegistered() {
        registry.register(new CommandCandidate("/custom tool", "custom test", "test"));

        assertTrue(registry.candidates("/custom").stream()
                .anyMatch(c -> c.command().equals("/custom tool")));
    }

    @Test
    void nonSlashInputHasNoCandidates() {
        assertTrue(registry.candidates("goal").isEmpty());
        assertEquals(List.of(), registry.candidates(null));
    }
}
