package com.dwinovo.numen.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class MergeTest {

    private static Merge.Result<Say> merge(Tier tier, boolean listed, Say... answers) {
        return Merge.merge(tier, listed, List.of(answers), s -> s);
    }

    @Test
    void firstStepADenyBeatsEverythingEvenTheList() {
        var r = merge(Tier.ALLOW, true, Say.ALLOW, Say.ASK, Say.DENY);
        assertEquals(Tier.DENY, r.outcome());
        assertEquals(Merge.Step.ANSWERER_DENIED, r.step());
        assertEquals(Say.DENY, r.by());
    }

    @Test
    void secondStepTheListBeatsAnAsk() {
        var r = merge(Tier.ASK, true, Say.ASK, Say.ALLOW);
        assertEquals(Tier.ALLOW, r.outcome());
        assertEquals(Merge.Step.LISTED, r.step());
        assertNull(r.by());
    }

    @Test
    void thirdStepAnAskBeatsAnAllow() {
        var r = merge(Tier.ALLOW, false, Say.ALLOW, Say.ASK);
        assertEquals(Tier.ASK, r.outcome());
        assertEquals(Merge.Step.ANSWERER_ASKED, r.step());
    }

    @Test
    void fourthStepAnAllowBeatsTheTier() {
        var r = merge(Tier.ASK, false, Say.ABSTAIN, Say.ALLOW);
        assertEquals(Tier.ALLOW, r.outcome());
        assertEquals(Merge.Step.ANSWERER_ALLOWED, r.step());
    }

    @Test
    void aDenyTierIsNotUnlockedByTheListOrByAnAllow() {
        var r = merge(Tier.DENY, true, Say.ALLOW);
        assertEquals(Tier.DENY, r.outcome());
        assertEquals(Merge.Step.TIER, r.step());
        assertNull(r.by());
    }

    @Test
    void fifthStepNobodySpeaksSoTheTierDecides() {
        for (Tier tier : Tier.values()) {
            var r = merge(tier, false, Say.ABSTAIN, Say.ABSTAIN);
            assertEquals(tier, r.outcome());
            assertEquals(Merge.Step.TIER, r.step());
            assertNull(r.by());
        }
        assertEquals(Tier.ASK, merge(Tier.ASK, false).outcome());
    }

    @Test
    void theOrderOfAnswerersDoesNotMatter() {
        assertEquals(Tier.DENY, merge(Tier.ALLOW, false, Say.DENY, Say.ASK).outcome());
        assertEquals(Tier.DENY, merge(Tier.ALLOW, false, Say.ASK, Say.DENY).outcome());
    }
}
