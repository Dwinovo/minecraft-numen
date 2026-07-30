package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

public final class CombatTacticPolicyTest {
    @Test
    void boundsCreeperSpacingAndKeepsOtherTargetsIndependent() {
        assertTrue(
            CombatTacticPolicy.MAX_CREEPER_DANGER_TICKS <= 30,
            "danger handling must stop within the normal creeper fuse timescale, not after a long generic timeout"
        );

        var meleeRetreat = CombatTacticPolicy.updateEngagement(
            true,
            false,
            4.4,
            false,
            new CombatTacticPolicy.Engagement(true, false)
        );
        assertTrue(
            meleeRetreat.retreatActive() && !meleeRetreat.closeHitPending(),
            "melee-only combat must keep retreating until it actually reaches the recovery side of the distance band"
        );
        meleeRetreat = CombatTacticPolicy.updateEngagement(
            true,
            false,
            CombatTacticPolicy.CREEPER_MELEE_RESUME_RANGE,
            false,
            meleeRetreat
        );
        assertTrue(
            !meleeRetreat.retreatActive() && meleeRetreat.closeHitPending(),
            "reaching melee recovery distance must re-arm exactly one next close hit"
        );
        var unrelatedTarget = CombatTacticPolicy.updateEngagement(
            false,
            false,
            2.0,
            true,
            new CombatTacticPolicy.Engagement(true, true)
        );
        assertTrue(
            !unrelatedTarget.retreatActive() && !unrelatedTarget.closeHitPending(),
            "creeper engagement state must never leak into combat with another entity type"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                3.0,
                true,
                true,
                true,
                true,
                0,
                1
            )),
            "a nearby primed creeper must stop the approach and retreat even when a ranged weapon is ready"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                3.0,
                true,
                false,
                false,
                true,
                CombatTacticPolicy.MAX_CREEPER_DANGER_TICKS,
                1
            )),
            "a live fuse must always be escaped before any bounded combat stop is considered"
        );

        assertEquals(
            CombatTacticPolicy.Action.ABANDON,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                6.0,
                true,
                false,
                false,
                true,
                0,
                CombatTacticPolicy.MAX_CREEPER_RETREAT_CYCLES
            )),
            "after the fuse has cleared, a bounded melee-only fight may stop instead of fleeing forever"
        );

        assertEquals(
            CombatTacticPolicy.Action.RANGED,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                6.0,
                true,
                true,
                true,
                true,
                0,
                0
            )),
            "an unprimed creeper at a safe firing distance must prefer an available ranged weapon"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                2.5,
                true,
                true,
                true,
                true,
                0,
                0
            )),
            "a close creeper must first create safe spacing instead of drawing a bow in blast range"
        );

        var spacingContext = new CombatTacticPolicy.Context(
            true,
            false,
            6.0,
            true,
            true,
            false,
            true,
            0,
            0
        );
        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(spacingContext, true),
            "once bow spacing starts, the creeper must not make combat draw at the same four-block boundary"
        );
        assertEquals(
            CombatTacticPolicy.Action.RANGED,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                CombatTacticPolicy.CREEPER_RANGED_RESUME_RANGE,
                true,
                true,
                false,
                true,
                0,
                0
            ), true),
            "bow combat may resume only after reaching the wider side of the creeper spacing band"
        );

        assertEquals(
            CombatTacticPolicy.Action.RANGED,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                3.5,
                true,
                true,
                false,
                true,
                0,
                0
            ), false, false, true),
            "an unprimed creeper crossing the spacing boundary must not cancel a bow shot already being drawn"
        );

        assertEquals(
            CombatTacticPolicy.Action.RANGED,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                2.75,
                true,
                true,
                false,
                true,
                0,
                0
            ), false, true, true),
            "a pending close hit must not replace a bow shot that has already started"
        );

        assertEquals(
            CombatTacticPolicy.Action.MELEE,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                2.5,
                true,
                true,
                false,
                true,
                0,
                0
            ), false, true),
            "a creeper already inside real melee reach must receive one confirmed knockback hit before bow spacing"
        );

        assertEquals(
            CombatTacticPolicy.Action.MELEE,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                2.75,
                true,
                false,
                false,
                true,
                1,
                1
            ), false, true, false),
            "a newly lit fuse inside real melee reach must still allow the one pending ready strike before retreat"
        );

        assertEquals(
            CombatTacticPolicy.Action.ABANDON,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                2.75,
                true,
                false,
                false,
                false,
                1,
                1
            ), false, true, false),
            "the opening hit must never be attempted when no retreat is available afterward"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                2.75,
                true,
                false,
                false,
                true,
                CombatTacticPolicy.CREEPER_OPENING_STRIKE_TICKS + 1,
                1
            ), false, true, false),
            "an unconfirmed opening hit must be abandoned as soon as its short safe window closes"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                2.75,
                true,
                true,
                false,
                true,
                1,
                1
            ), false, true, true),
            "a live fuse must interrupt a bow shot instead of switching from the bow to a risky close hit"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                2.5,
                true,
                true,
                false,
                true,
                0,
                0
            ), true, false),
            "after that close hit, ranged combat must create spacing instead of swinging repeatedly"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                3.5,
                true,
                false,
                false,
                true,
                0,
                1
            ), true, false, false),
            "after a confirmed melee-only hit, combat must back out of the fuse trigger area before approaching again"
        );

        assertEquals(
            CombatTacticPolicy.Action.APPROACH,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                2.5,
                true,
                false,
                false,
                true,
                0,
                1
            )),
            "with only an ordinary melee weapon and a cleared fuse, native combat must approach and attack again"
        );

        assertEquals(
            CombatTacticPolicy.Action.APPROACH,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                CombatTacticPolicy.CREEPER_MELEE_RESUME_RANGE,
                true,
                false,
                false,
                true,
                0,
                3
            ), true, true, false),
            "a melee-only creeper fight must allow enough hit-and-retreat cycles to defeat it with an ordinary weak weapon"
        );

        assertEquals(
            CombatTacticPolicy.Action.SPEAR,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                false,
                false,
                3.0,
                true,
                false,
                true,
                true,
                0,
                0
            )),
            "a spear must be selected only inside its effective two-to-four-and-a-half-block band"
        );

        assertEquals(
            CombatTacticPolicy.Action.APPROACH,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                false,
                false,
                3.5,
                true,
                false,
                false,
                true,
                0,
                0
            ), true, true, true),
            "creeper-only retreat, close-hit, and shot state must not alter another entity's original approach"
        );

        assertEquals(
            CombatTacticPolicy.Action.MELEE,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                false,
                false,
                1.75,
                true,
                false,
                true,
                true,
                0,
                0
            )),
            "inside the spear minimum range combat must switch to an ordinary melee weapon"
        );

        assertEquals(
            CombatTacticPolicy.Action.RETREAT,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                true,
                8.0,
                true,
                false,
                true,
                true,
                1,
                1
            )),
            "a primed creeper without a ready ranged shot must never resume approaching from outside the blast radius"
        );

        assertEquals(
            CombatTacticPolicy.Action.MELEE,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                true,
                false,
                1.75,
                true,
                false,
                false,
                true,
                0,
                0
            )),
            "without a ranged weapon or spear, an unprimed creeper must allow one close hit instead of oscillating forever"
        );

        assertEquals(
            CombatTacticPolicy.Action.MELEE,
            CombatTacticPolicy.decide(new CombatTacticPolicy.Context(
                false,
                false,
                CombatTacticPolicy.SPEAR_MIN_RANGE,
                true,
                false,
                true,
                true,
                0,
                0
            )),
            "at the approximate two-block spear floor, combat must choose the non-stalling close-range fallback"
        );
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
