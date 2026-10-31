package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 收题的判据:题只收各适用规格下的答案都有定论(最优或区域内无解)的,拒绝按理由的先后次序说最靠前的一个;一档收完的收获写下来、读回来一样(续跑靠它)。 */
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

    @Test
    void aFinishedBucketReadsBackExactlyAsWritten() {
        Problem p = new Problem("hills-short-001", "hills", Problem.Bucket.SHORT, 20.5, new BlockPos(1, 2, 3), Problem.GoalType.AT, new BlockPos(4, 5, 6), 0,
                Problem.EndClass.SURFACE, Problem.split("hills-short-001"), List.of(Problem.Tag.ROUGH));
        Answers.Answer a = new Answers.Answer("hills-short-001", Spec.A, Answers.Status.OPTIMAL, 123.456, 789, 100.5);
        Map<Generate.Reject, Integer> rejected = new EnumMap<>(Generate.Reject.class);
        rejected.put(Generate.Reject.PLAIN_WALK, 7);
        rejected.put(Generate.Reject.TOO_HARD, 3);
        Generate.Unit unit = new Generate.Unit(List.of(p), List.of(a), 31, rejected, new long[] {0, 5, 2}, new long[] {0, 1500, 9000});
        Generate.Unit back = Generate.Unit.fromJson(unit.toJson());
        assertEquals(unit.problems().get(0).id(), back.problems().get(0).id());
        assertEquals(unit.problems().get(0).to(), back.problems().get(0).to());
        assertEquals(a.key(), back.answers().get(0).key());
        assertEquals(a.cost(), back.answers().get(0).cost(), 1e-9);
        assertEquals(31, back.candidates());
        assertEquals(7, back.rejected().get(Generate.Reject.PLAIN_WALK));
        assertEquals(0, back.rejected().get(Generate.Reject.BEYOND_REGION), "没写的理由读回是 0");
        assertEquals(2, back.tierJobs()[2]);
        assertEquals(9000, back.tierMillis()[2]);
    }
}
