package com.dwinovo.numen.pathing.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

/** 题与结果行的 JSON 往返,题的分档、分边与"按构造到不了"的判据。 */
class RowTest {

    private static Row row(String id, Spec spec, boolean arrived, double subopt, Answers.Status ref) {
        return new Row(id, "hills", Problem.Bucket.MEDIUM, spec, Problem.GoalType.NEAR, Problem.EndClass.CAVE, Problem.Split.DEV,
                List.of(Problem.Tag.WATER, Problem.Tag.UNDERGROUND), false, ref, arrived, arrived ? "arrived" : "searched:budget",
                12.3, 45.6, 7.8, 900.0, 3, 1234, 1, 2, 321.0, subopt, 4, 5, 60, 8.5);
    }

    @Test
    void aRowSurvivesAJsonRoundTrip() {
        Row r = row("hills-medium-001", Spec.B, true, 1.0234, Answers.Status.OPTIMAL);
        assertEquals(r, Row.fromJson(r.toJson()));
        Row noAnswers = row("hills-medium-002", Spec.A, true, Double.NaN, null);
        Row back = Row.fromJson(noAnswers.toJson());
        assertEquals(noAnswers, back);
        assertTrue(Double.isNaN(back.subopt()));
    }

    @Test
    void aProblemSurvivesAJsonRoundTripAndTheSplitIsStable() {
        Problem p = new Problem("hills-short-007", "hills", Problem.Bucket.SHORT, 23.4, new BlockPos(1, 2, 3), Problem.GoalType.DIG, new BlockPos(4, 5, 6), 0,
                Problem.EndClass.BURIED, Problem.split("hills-short-007"), List.of(Problem.Tag.DIG, Problem.Tag.UNDERGROUND));
        Problem back = Problem.fromJson(p.toJson());
        assertEquals(p.id(), back.id());
        assertEquals(p.to(), back.to());
        assertEquals(p.tags(), back.tags());
        assertEquals(Problem.split("hills-short-007"), Problem.split("hills-short-007"));
        long dev = java.util.stream.IntStream.range(0, 400).filter(i -> Problem.split("x-" + i) == Problem.Split.DEV).count();
        assertTrue(dev > 140 && dev < 260, "该五五开:" + dev);
    }

    @Test
    void bucketsAreByHorizontalDistance() {
        assertEquals(Problem.Bucket.SHORT, Problem.Bucket.of(32));
        assertEquals(Problem.Bucket.MEDIUM, Problem.Bucket.of(32.1));
        assertEquals(Problem.Bucket.LONG, Problem.Bucket.of(400));
    }

    /** 规格 A 不挖不垫:走到封死的空腔或封闭的一格按构造到不了;规格 B 与"挖到"、"走到附近"都不是。 */
    @Test
    void aSealedCellCannotBeWalkedToWithoutDigging() {
        BlockPos at = new BlockPos(0, 0, 0);
        Problem walkToBuried = new Problem("a", "r", Problem.Bucket.SHORT, 10, at, Problem.GoalType.AT, at, 0, Problem.EndClass.BURIED, Problem.Split.DEV, List.of());
        assertTrue(walkToBuried.unsolvable(Spec.A));
        assertFalse(walkToBuried.unsolvable(Spec.B));
        Problem digBuried = new Problem("b", "r", Problem.Bucket.SHORT, 10, at, Problem.GoalType.DIG, at, 0, Problem.EndClass.BURIED, Problem.Split.DEV, List.of());
        assertFalse(digBuried.unsolvable(Spec.A));
        Problem surface = new Problem("c", "r", Problem.Bucket.SHORT, 10, at, Problem.GoalType.AT, at, 0, Problem.EndClass.SURFACE, Problem.Split.DEV, List.of());
        assertFalse(surface.unsolvable(Spec.A));
    }
}
