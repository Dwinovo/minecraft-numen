package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.combat.CombatDeathLedger;
import java.util.UUID;

public final class CombatDeathLedgerTest {
    @Test
    void verifiedRuntimeBehavior() {
        CombatDeathLedger ledger = new CombatDeathLedger(1_200L);
        UUID target = UUID.randomUUID();

        ledger.recordDeath("minecraft:overworld", 42, target, 150L);

        assertTrue(
            ledger.diedSince("minecraft:overworld", 42, target, 100L, 151L),
            "a requested target's real death after task start must count as cleared"
        );
        assertTrue(
            !ledger.diedSince("minecraft:overworld", 43, target, 100L, 151L),
            "an entity that merely disappeared without a death record must remain unresolved"
        );
        assertTrue(
            !ledger.diedSince("minecraft:the_nether", 42, target, 100L, 151L),
            "a death in another dimension must not resolve this target"
        );
        assertTrue(
            !ledger.diedSince("minecraft:overworld", 42, UUID.randomUUID(), 100L, 151L),
            "a recycled runtime id must not inherit another entity's death"
        );
        assertTrue(
            !ledger.diedSince("minecraft:overworld", 42, target, 151L, 151L),
            "a death before this task started must not resolve a new task"
        );
        assertTrue(
            !ledger.diedSince("minecraft:overworld", 42, target, 10L, 50L),
            "a record from a previous world clock must not survive a game-time reset"
        );
        assertTrue(
            !ledger.diedSince("minecraft:overworld", 42, target, 100L, 1_351L),
            "expired deaths must not leak into later combat tasks"
        );
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
