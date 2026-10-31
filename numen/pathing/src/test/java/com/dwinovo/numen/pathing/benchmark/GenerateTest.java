package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** 收题的判据:题只收各适用规格下的答案都有定论(最优或区域内无解)的,拒绝按理由的先后次序说最靠前的一个。 */
class GenerateTest {

    private static Reference.Solved solved(Answers.Status status) {
        return new Reference.Solved(status, Double.NaN, 0, 0);
    }

    @Test
    void aCandidateIsAcceptedOnlyWhenEverySpecHasAVerdict() {
        assertEquals(Optional.empty(), Generate.verdict(List.of(solved(Answers.Status.OPTIMAL), solved(Answers.Status.UNSOLVABLE))));
        assertEquals(Optional.of(Generate.Reject.TOO_HARD), Generate.verdict(List.of(solved(Answers.Status.OPTIMAL), solved(Answers.Status.TOO_HARD))));
        assertEquals(Optional.of(Generate.Reject.UPPER_BOUND_ONLY), Generate.verdict(List.of(solved(Answers.Status.BEST_KNOWN))));
        assertEquals(Optional.of(Generate.Reject.BEYOND_REGION),
                Generate.verdict(List.of(solved(Answers.Status.TOO_HARD), solved(Answers.Status.BEST_KNOWN), solved(Answers.Status.BEYOND_REGION))),
                "几个理由同时在,先说靠前的");
    }
}
