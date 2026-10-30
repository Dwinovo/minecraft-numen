package com.dwinovo.numen.pathing.benchmark;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.drive.Halt;
import com.dwinovo.numen.pathing.drive.Journal;
import com.google.gson.JsonObject;

/**
 * 规划评测:题库里每道题、每种规格,走一遍真实的趟逻辑({@link Rehearsal}),身体不走,记指标。用法:
 * {@code Bench <数据集目录> <输出目录> <dev|held|all> <主仓库提交>}。写 {@code results.jsonl}(一行一个"题 × 规格",按题号排序,
 * 不含墙钟的字段逐字节可复现)、{@code summary.md}(分档报表,见 {@link Report})与 {@code meta.json}(提交、题库版本、墙钟耗时)。
 *
 * <p>只跑有标准答案可比、不是平凡题的"题 × 规格",无解的一类也跑(量多快认输)。题之间并行,每道题的搜索按表(展开数折算)
 * 想多久,与线程快慢无关。
 */
final class Bench {

    private Bench() {}

    public static void main(String[] args) throws Exception {
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        Vanilla.boot();
        Dataset dataset = new Dataset(Path.of(args[0]));
        Path out = Path.of(args[1]);
        String which = args[2];
        String commit = args[3];
        Files.createDirectories(out);
        Optional<Answers.Known> answers = Answers.load(dataset.dir);
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() - 2));
        List<Row> rows = new ArrayList<>();
        long loadMillis = 0;
        long runStart = 0;
        try {
            for (Dataset.Region region : dataset.regions()) {
                long l0 = System.currentTimeMillis();
                RegionWorld world = RegionWorld.load(region.name(), dataset.save(region.name()));
                loadMillis += System.currentTimeMillis() - l0;
                if (runStart == 0) {
                    runStart = System.currentTimeMillis();
                }
                List<Future<Row>> jobs = new ArrayList<>();
                for (Problem p : dataset.problems(region.name())) {
                    if (!which.equals("all") && p.split() != Problem.Split.valueOf(which.toUpperCase())) {
                        continue;
                    }
                    for (Spec spec : Spec.values()) {
                        Answers.Answer answer = answers.flatMap(k -> k.of(p, spec)).orElse(null);
                        jobs.add(pool.submit(() -> run(world, p, spec, answer)));
                    }
                }
                for (Future<Row> job : jobs) {
                    rows.add(job.get());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        long end = System.currentTimeMillis();
        rows.sort(Comparator.comparing(Row::id).thenComparing(Row::spec));
        StringBuilder jsonl = new StringBuilder();
        for (Row row : rows) {
            jsonl.append(row.toJson()).append('\n');
        }
        Files.writeString(out.resolve("results.jsonl"), jsonl.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("summary.md"), Report.summary(rows, which, commit, answers.map(Answers.Known::commit)), StandardCharsets.UTF_8);
        JsonObject meta = new JsonObject();
        meta.addProperty("commit", commit);
        meta.addProperty("split", which);
        meta.addProperty("dataset", dataset.manifest().get("commit").getAsString());
        answers.ifPresent(k -> meta.addProperty("answers", k.commit()));
        meta.addProperty("rows", rows.size());
        JsonObject wall = new JsonObject();
        wall.addProperty("startupMs", runStart - jvmStart);
        wall.addProperty("loadSavesMs", loadMillis);
        wall.addProperty("totalMs", end - jvmStart);
        meta.add("wallClock", wall);
        Files.writeString(out.resolve("meta.json"), Dataset.GSON.toJson(meta) + "\n", StandardCharsets.UTF_8);
        System.out.println("[bench] " + rows.size() + " 行,启动 " + (runStart - jvmStart) + " ms,总 " + (end - jvmStart) + " ms");
    }

    private static Row run(RegionWorld world, Problem p, Spec spec, Answers.Answer answer) {
        Rehearsal.Result r = new Rehearsal(new Rehearsal.Scene(world::snapshot, spec.model(), spec.route(), p.goal(Spec.STATS), p.from(), 0), p.id()).run();
        boolean priced = r.arrived() && answer != null && !Double.isNaN(answer.cost());
        return new Row(p.id(), p.region(), p.bucket(), spec, p.goalType(), p.endClass(), p.split(), p.tags(), p.unsolvable(spec),
                answer == null ? null : answer.status(), r.arrived(),
                r.halt() == null ? "arrived" : halt(r.halt()), ms(r.firstMove()), ms(r.maxThink()), ms(r.waited()), ms(r.ticks()), r.searches(),
                r.expanded(), r.replans(Journal.Replan.DEAD_END), r.replansTotal(), Math.round(r.cost() * 1000) / 1000.0,
                priced ? Math.round(r.cost() / answer.cost() * 10000) / 10000.0 : Double.NaN, r.dug(), r.placed(), r.steps());
    }

    /** 游戏刻折成毫秒,取到 0.1 毫秒。 */
    private static double ms(double ticks) {
        return Math.round(ticks * 50 * 10) / 10.0;
    }

    private static String halt(Halt halt) {
        return halt instanceof Halt.Searched s ? "searched:" + s.stop().name().toLowerCase() : halt.getClass().getSimpleName().toLowerCase();
    }
}
