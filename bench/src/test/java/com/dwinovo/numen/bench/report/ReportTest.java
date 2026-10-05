package com.dwinovo.numen.bench.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 记录的读写、汇总表与对比的格式。 */
class ReportTest {

    private static Run run(String scenario, Variant variant, int attempt, boolean passed, EndReason end,
                           Double cost, String tag) {
        List<Run.Check> checks = List.of(
                new Run.Check("raw_iron>=10", "success", passed, passed ? "" : "only 4 raw_iron"),
                new Run.Check("alive", "guard", true, ""));
        List<Run.Check> subgoals = List.of(new Run.Check("any", "subgoal", true, ""),
                new Run.Check("ten", "subgoal", passed, ""));
        String model = variant == Variant.LIVE ? "deepseek/deepseek-v4-flash" : variant.id();
        return new Run("vanilla", scenario, variant.id(), attempt, "abc1234", "0123456789ab", model, passed, checks,
                subgoals, end.name(), 6, 4, passed ? 0 : 2, passed ? 0 : 1, 0, 12000, 30000, 800,
                cost, cost == null ? null : "CNY", 42000, 840, !passed && end == EndReason.DONE, tag,
                "挖好了|给你", "transcripts/" + scenario + "-" + variant.id() + "-" + attempt + ".jsonl", null,
                Map.of(), variant == Variant.LIVE ? List.of(new FunctionUse("numen.work.dig", 2,
                        passed ? Map.of() : Map.of("bad_argument", 1), 1, 0)) : List.of());
    }

    /** 每个函数的账随记录读写;更早的记录没有这一项,读进来是空表。 */
    @Test
    void functionUseSurvivesTheRoundTripAndOlderLinesHaveNone() {
        Run live = run("mine_iron", Variant.LIVE, 2, false, EndReason.DONE, 0.02, null);
        Run back = Runs.fromJson(Runs.toJson(live));
        assertEquals(live.functions(), back.functions());
        assertEquals(1, back.functions().get(0).failed("bad_argument"));
        String older = Runs.toJson(live).replaceFirst(",\"functions\":\\[.*]}$", "}");
        assertFalse(older.contains("functions"), older);
        assertEquals(List.of(), Runs.fromJson(older).functions());
    }

    /** 汇总按函数加总真实模型的几次:调用、失败率、参数错、调用前查帮助;对比把前后两份按函数并排。 */
    @Test
    void summaryAndCompareListEveryFunction() {
        List<Run> runs = sample();
        assertTrue(Summary.markdown("t", runs).contains("| numen.work.dig | 6 | 17% | 1 | — | 3 | 0 |"),
                Summary.markdown("t", runs));
        List<Run> after = new ArrayList<>(runs);
        after.replaceAll(r -> r.live() ? new Run(r.suite(), r.scenario(), r.variant(), r.attempt(), r.commit(),
                r.promptHash(), r.model(), r.passed(), r.checks(), r.subgoals(), r.end(), r.turns(), r.toolCalls(),
                r.toolErrors(), r.repeatedFailures(), r.consents(), r.tokensMiss(), r.tokensHit(), r.tokensOut(),
                r.cost(), r.currency(), r.wallMs(), r.gameTicks(), r.claimedDone(), r.tag(), r.finalWords(),
                r.transcript(), r.error(), r.metrics(), List.of(new FunctionUse("numen.work.dig", 1, Map.of(), 0, 0))) : r);
        String md = Compare.markdown("before", runs, "after", after);
        assertTrue(md.contains("| numen.work.dig | 6 | 3 | 17% | 0% | 17% | 0% | 50% | 0% |"), md);
    }

    private static List<Run> sample() {
        List<Run> runs = new ArrayList<>();
        runs.add(run("mine_iron", Variant.SOLUTION, 1, true, EndReason.DONE, null, null));
        runs.add(run("mine_iron", Variant.NOOP, 1, false, EndReason.DONE, null, null));
        runs.add(run("mine_iron", Variant.LIVE, 1, true, EndReason.DONE, 0.02, null));
        runs.add(run("mine_iron", Variant.LIVE, 2, false, EndReason.DONE, 0.02, null));
        runs.add(run("mine_iron", Variant.LIVE, 3, true, EndReason.DONE, 0.02, null));
        return runs;
    }

    @Test
    void aRunSurvivesTheJsonRoundTripIncludingNulls(@TempDir Path dir) {
        Path file = dir.resolve("r").resolve(Runs.FILE);
        Run original = run("mine_iron", Variant.LIVE, 2, false, EndReason.TURN_LIMIT, null, "D");
        Runs.append(file, original);
        Runs.append(file, sample().get(0));
        List<Run> back = Runs.read(file.getParent());
        assertEquals(2, back.size());
        assertEquals(original, back.get(0));
        assertNull(back.get(0).cost());
        assertTrue(Runs.toJson(original).contains("\"cost\":null"), "空值也写出来");
    }

    @Test
    void summaryHasTheSelfCheckAndOneLiveRowPerScenario() {
        String md = Summary.markdown("评测 2026-09-30", sample());
        assertTrue(md.contains("| vanilla/mine_iron | 1/1 | 0/1 | 可信 |"), md);
        assertTrue(md.contains("| vanilla/mine_iron | deepseek/deepseek-v4-flash | 2/3 | 67% ["), md);
        assertTrue(md.contains("| 0.00 |"), "2/3 的 pass^3 是 0:" + md);
        assertTrue(md.contains("| 0.0300 CNY |"), "三次共 0.06,成功两次,每次成功 0.03:" + md);
        assertTrue(md.contains("说完成没过:1 次"), md);
        assertTrue(md.contains("挖好了/给你"), "竖线换掉,不拆坏表格:" + md);
    }

    @Test
    void theSelfCheckCatchesAScenarioTheNoopPasses() {
        List<Run> solution = List.of(run("s", Variant.SOLUTION, 1, false, EndReason.DONE, null, null));
        List<Run> noop = List.of(run("s", Variant.NOOP, 1, true, EndReason.DONE, null, null));
        assertEquals("标准解没有全过;空操作骗过了断言", Summary.verdict(solution, noop));
    }

    @Test
    void costPerSuccessNeedsAPriceForEveryRun() {
        List<Run> priced = List.of(run("s", Variant.LIVE, 1, false, EndReason.DONE, 0.01, null),
                run("s", Variant.LIVE, 2, false, EndReason.DONE, 0.02, null));
        assertEquals("无成功(共 0.0300 CNY)", Summary.costPerSuccess(priced, 0));
        List<Run> unpriced = List.of(run("s", Variant.LIVE, 1, true, EndReason.DONE, null, null));
        assertEquals("—", Summary.costPerSuccess(unpriced, 1));
    }

    @Test
    void failureTagRulesOnlyNameWhatARuleCanTell() {
        assertNull(FailureTag.auto(true, EndReason.HARNESS_ERROR, true));
        assertEquals(FailureTag.H, FailureTag.auto(false, EndReason.HARNESS_ERROR, false));
        assertEquals(FailureTag.G, FailureTag.auto(false, EndReason.API_ERROR, true));
        assertEquals(FailureTag.G, FailureTag.auto(false, EndReason.CONTEXT_OVERFLOW, false));
        assertEquals(FailureTag.D, FailureTag.auto(false, EndReason.DONE, true));
        assertNull(FailureTag.auto(false, EndReason.TURN_LIMIT, false), "判不出的留给人工");
    }

    @Test
    void pricesComeFromTheTableAndUnknownModelsHaveNone() {
        Pricing pricing = Pricing.parse("{\"_note\":\"per million\",\"a/b\":{\"currency\":\"CNY\",\"miss\":2,"
                + "\"hit\":0.5,\"output\":8}}");
        assertEquals((1_000_000 * 2 + 2_000_000 * 0.5 + 500_000 * 8) / 1e6,
                pricing.of("a/b").cost(1_000_000, 2_000_000, 500_000), 1e-9);
        assertNull(pricing.of("c/d"));
    }

    @Test
    void compareListsFlipsAndABootstrapInterval() {
        List<Run> before = new ArrayList<>();
        List<Run> after = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            before.add(run("a", Variant.LIVE, i, true, EndReason.DONE, null, null));
            after.add(run("a", Variant.LIVE, i, i == 1, EndReason.DONE, null, null));
            before.add(run("b", Variant.LIVE, i, false, EndReason.DONE, null, null));
            after.add(run("b", Variant.LIVE, i, true, EndReason.DONE, null, null));
        }
        before.add(run("a", Variant.SOLUTION, 1, true, EndReason.DONE, null, null));
        String md = Compare.markdown("old", before, "new", after);
        assertTrue(md.contains("| vanilla/a | 3/3 | 1/3 | -67% |"), md);
        assertTrue(md.contains("| vanilla/b | 0/3 | 3/3 | +100% |"), md);
        assertTrue(md.contains("由过变挂:vanilla/a"), md);
        assertTrue(md.contains("由挂变过:vanilla/b"), md);
        assertTrue(md.contains("2 个场景配对"), md);
        assertFalse(md.contains("只在前"), "基线不参与对比:" + md);
    }

    /** 并行的几份并成一份:按组、场景、变体、第几次排好,记录文件搬到一处,汇总重写,各份的目录删掉。 */
    @Test
    void shardsMergeIntoOneResultInPlanOrder(@TempDir Path dir) throws java.io.IOException {
        Path one = dir.resolve(Merge.SHARDS).resolve("0");
        Path two = dir.resolve(Merge.SHARDS).resolve("1");
        Runs.append(one.resolve(Runs.FILE), run("mine_iron", Variant.NOOP, 1, false, EndReason.DONE, null, null));
        Runs.append(one.resolve(Runs.FILE), run("mine_iron", Variant.SOLUTION, 1, true, EndReason.DONE, null, null));
        Runs.append(two.resolve(Runs.FILE), run("build_hut", Variant.SOLUTION, 1, true, EndReason.DONE, null, null));
        java.nio.file.Files.createDirectories(two.resolve("transcripts"));
        java.nio.file.Files.writeString(two.resolve("transcripts").resolve("vanilla-build_hut-solution-1.jsonl"), "{}");

        assertEquals(3, Merge.merge(dir));
        List<Run> merged = Runs.read(dir);
        assertEquals(List.of("build_hut solution", "mine_iron solution", "mine_iron noop"),
                merged.stream().map(r -> r.scenario() + " " + r.variant()).toList());
        assertTrue(java.nio.file.Files.exists(dir.resolve("transcripts").resolve("vanilla-build_hut-solution-1.jsonl")));
        assertTrue(java.nio.file.Files.exists(dir.resolve(Summary.FILE)));
        assertFalse(java.nio.file.Files.exists(dir.resolve(Merge.SHARDS)));
    }
}
