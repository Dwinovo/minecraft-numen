package com.dwinovo.numen.pathing.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.SplittableRandom;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.SearchView;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 出题:在每块地上用固定随机种子撒点,写成题库(见 {@link Dataset})和标准答案(答案文件见 {@link Answers})。用法:
 * {@code Generate <数据集目录> <主仓库提交>}。每块地、每个距离档收 {@link #QUOTA} 道题。
 *
 * <p><b>只收有定论的题</b>:候选题在每个适用的规格下的标准答案都是最优价({@code OPTIMAL})或区域内证明的无解({@code UNSOLVABLE})才收;
 * 可连通块碰到区域边的({@code BEYOND_REGION})、两档节点上限都算不动的({@code TOO_HARD})、只有生产权重搜出的上界的({@code BEST_KNOWN})
 * 都拒掉,继续抽候选直到配额满。拒绝按原因计数写进 manifest。判据只看地形、种子与成本模型下的精确解({@link Reference}、{@link Solver}),
 * 不看生产寻路;所以题库里每道题评测失败了,说得清是寻路没做成,不会是本来就到不了或区域不够大。出题与答案同一份求解代码一次产出,
 * 答案文件的 {@code commit} 就是出题的主仓库提交。
 *
 * <p><b>断点续跑</b>:每块地的每一档收完就把它收下的题、答案与计数写进 {@code <数据集目录>/.generate/<地块>-<档>.json},重启时已有的档直接读回、不再求解
 * (题库、答案文件与 manifest 总是从这些档文件汇出来,新跑与续跑是同一条路)。要整个重来就删 {@code .generate}。{@code .generate} 是过程文件,不进数据集的发布包。
 *
 * <p><b>怎么撒</b>:先随机一个起点(七成地表,三成洞穴地面;下界没有地表,什么站得住的格都算),再按档的距离范围随机一个方向与水平距离定出终点所在的列,
 * 在那一列上按终点的种类({@link Problem.EndClass})取一格:
 * <ul>
 *   <li>地表:这一列最高的站得住的格(含水面);洞穴:地表以下站得住、连着大片空间的地面;</li>
 *   <li>地下空腔:站得住、周围一团空气和流体不连着别处(六邻接泛洪到 {@link #POCKET} 格以内就到头)的地面;</li>
 *   <li>封闭的一格:它和六个邻居都是实心方块。</li>
 * </ul>
 * 目标种类照模型实际会下的令:走到某格、走到某格附近 r 格内(跟随,r 取 2–5)、挖到某格(只对封闭的一格)。
 * 起终点都离区域边缘至少 {@link #MARGIN} 格,搜索的快照读不到区域外。
 *
 * <p><b>平凡题</b>:起终点之间的直线明摆着一路平走过去(没有水、没有高于一格的台阶、没有无地面的列,见 {@link Straight})的不进题库。
 * 判据只看地形,与搜索算法无关。直线上的事实同时给报表的标签。
 *
 * <p><b>不适用于 A</b>:规格 A 不挖不垫,需要编辑才做得成的题(挖到某格、走到封死的一格)按构造不适用({@link Problem#applicable}),出题时分出来,
 * 数目记进 manifest,评测不给 A 跑。
 */
final class Generate {

    /** 每块地、每个距离档收几道题。 */
    static final int QUOTA = 40;
    /** 起终点离区域边缘至少这么多格(4 个区块)。 */
    static final int MARGIN = 64;
    /** 空腔泛洪最多数这么多格:超过就算连着别处(洞穴)。 */
    static final int POCKET = 4000;
    /** 每个档最多试这么多个候选(抽到的,含被拒的),试不满就少收,不凑数。 */
    private static final int MAX_CANDIDATES = 20_000;
    /** 同时在求解的候选最多这么多个(与机器无关;收题的先后只看候选的抽样顺序,结果可复现)。 */
    private static final int WINDOW = 32;
    private static final long SEED = 0x4E554D454E504233L;

    /** 拒题的理由,按该先说哪个的次序(多个规格各有各的结论时说最靠前的)。 */
    enum Reject {
        PLAIN_WALK("plainWalk"), BEYOND_REGION("beyondRegion"), TOO_HARD("tooHard"), UPPER_BOUND_ONLY("upperBoundOnly");

        final String key;

        Reject(String key) {
            this.key = key;
        }
    }

    private Generate() {}

    /**
     * 候选题的结论:各适用规格的答案都有定论({@link Reference.Solved#conclusive})才收({@code empty});否则按 {@link Reject} 的次序说最靠前的一个理由。
     * 判据只看精确解,不看生产寻路。
     */
    static Optional<Reject> verdict(List<Reference.Solved> solved) {
        Reject worst = null;
        for (Reference.Solved s : solved) {
            Reject r = switch (s.status()) {
                case OPTIMAL, UNSOLVABLE -> null;
                case BEYOND_REGION -> Reject.BEYOND_REGION;
                case TOO_HARD -> Reject.TOO_HARD;
                case BEST_KNOWN -> Reject.UPPER_BOUND_ONLY;
            };
            if (r != null && (worst == null || r.ordinal() < worst.ordinal())) {
                worst = r;
            }
        }
        return Optional.ofNullable(worst);
    }

    /**
     * 一块地一档的收获:收下的题、它们的答案,与出这一档用掉的候选与求解的账。这一档收完就写进 {@code .generate},续跑时读回。
     *
     * @param tierJobs       各档上限(下标 1、2)解了几条
     * @param tierMillis     各档上限的墙钟毫秒加总
     * @param tier2Conclusive 第二档的作业里最后有了定论(最优或区域内无解)的条数
     * @param tier2JobMillis  第二档每条作业用的墙钟毫秒(看耗时分布、定第二档值不值)
     */
    record Unit(List<Problem> problems, List<Answers.Answer> answers, int candidates, Map<Reject, Integer> rejected, long[] tierJobs, long[] tierMillis,
                int tier2Conclusive, List<Long> tier2JobMillis) {

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            com.google.gson.JsonArray ps = new com.google.gson.JsonArray();
            problems.forEach(p -> ps.add(p.toJson()));
            o.add("problems", ps);
            com.google.gson.JsonArray as = new com.google.gson.JsonArray();
            answers.forEach(a -> as.add(a.toJson()));
            o.add("answers", as);
            o.addProperty("candidates", candidates);
            JsonObject rejects = new JsonObject();
            rejected.forEach((why, n) -> rejects.addProperty(why.key, n));
            o.add("rejected", rejects);
            o.addProperty("tier1Jobs", tierJobs[1]);
            o.addProperty("tier2Jobs", tierJobs[2]);
            o.addProperty("tier1Millis", tierMillis[1]);
            o.addProperty("tier2Millis", tierMillis[2]);
            o.addProperty("tier2Conclusive", tier2Conclusive);
            com.google.gson.JsonArray ms = new com.google.gson.JsonArray();
            tier2JobMillis.forEach(ms::add);
            o.add("tier2JobMillis", ms);
            return o;
        }

        static Unit fromJson(JsonObject o) {
            List<Problem> problems = new ArrayList<>();
            o.getAsJsonArray("problems").forEach(e -> problems.add(Problem.fromJson(e.getAsJsonObject())));
            List<Answers.Answer> answers = new ArrayList<>();
            o.getAsJsonArray("answers").forEach(e -> answers.add(Answers.Answer.fromJson(e.getAsJsonObject())));
            Map<Reject, Integer> rejected = new EnumMap<>(Reject.class);
            for (Reject why : Reject.values()) {
                rejected.put(why, o.getAsJsonObject("rejected").has(why.key) ? o.getAsJsonObject("rejected").get(why.key).getAsInt() : 0);
            }
            List<Long> tier2JobMillis = new ArrayList<>();
            o.getAsJsonArray("tier2JobMillis").forEach(e -> tier2JobMillis.add(e.getAsLong()));
            return new Unit(problems, answers, o.get("candidates").getAsInt(), rejected,
                    new long[] {0, o.get("tier1Jobs").getAsLong(), o.get("tier2Jobs").getAsLong()},
                    new long[] {0, o.get("tier1Millis").getAsLong(), o.get("tier2Millis").getAsLong()},
                    o.get("tier2Conclusive").getAsInt(), tier2JobMillis);
        }
    }

    /** 一档的过程文件:数据集目录里的 {@code .generate/<地块>-<档>.json}。 */
    private static Path unitFile(Dataset dataset, String region, Problem.Bucket bucket) {
        return dataset.dir.resolve(".generate").resolve(region + "-" + bucket.label + ".json");
    }

    /**
     * 候选流水线地求解、按抽到的顺序收:{@code draw} 一个一个抽候选(抽不出了为 null),每抽到一个就交给 {@code judge} 去求解(同时最多 {@code window} 个在途),
     * 结论按候选的序号一个一个取出来,前面的候选都有了结论才轮到后面的;取到第 {@code quota} 个收下的就停(后面在途的结论丢弃),抽不出了也停。
     * 交回按序取出的(候选的)结论,含被拒的;收不收由 {@code accepts} 说。所以结果只看候选的序号与各自的结论,与求解的快慢、先后无关——与一个一个串行地做一样。
     */
    static <C, R> List<R> sequence(java.util.function.Supplier<C> draw, java.util.function.Function<C, CompletableFuture<R>> judge,
                                   java.util.function.Predicate<R> accepts, int quota, int window) {
        List<R> taken = new ArrayList<>();
        java.util.ArrayDeque<CompletableFuture<R>> inFlight = new java.util.ArrayDeque<>();
        boolean exhausted = false;
        int accepted = 0;
        while (accepted < quota) {
            while (!exhausted && inFlight.size() < window) {
                C next = draw.get();
                if (next == null) {
                    exhausted = true;
                } else {
                    inFlight.add(judge.apply(next));
                }
            }
            if (inFlight.isEmpty()) {
                break;
            }
            R result = inFlight.poll().join();
            taken.add(result);
            if (accepts.test(result)) {
                accepted++;
            }
        }
        return taken;
    }

    public static void main(String[] args) throws IOException {
        Vanilla.boot();
        Dataset dataset = new Dataset(Path.of(args[0]));
        String commit = args[1];
        try (Solver solver = Solver.standard()) {
            generate(dataset, commit, solver);
        }
    }

    private static void generate(Dataset dataset, String commit, Solver solver) throws IOException {
        long t0 = System.nanoTime();
        JsonObject manifest = new JsonObject();
        manifest.addProperty("dataset", "numen-pathfinding-benchmark");
        manifest.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName());
        manifest.addProperty("commit", commit);
        manifest.addProperty("quota", QUOTA);
        manifest.addProperty("nodeCap", solver.cap());
        manifest.addProperty("largeNodeCap", solver.largeCap());
        manifest.addProperty("accepted", "每个适用规格下的标准答案都是最优价或区域内证明的无解;可连通块碰到区域边的、两档节点上限都算不动的、只有上界的、平凡的都拒掉,拒绝按原因计数在各块的 rejected 里");
        manifest.addProperty("notApplicableToA", "需要编辑才做得成的题:挖到某格,和走到封死的空腔或封闭在实心里的一格;规格 A 不挖不垫,这些题不给 A 跑");
        JsonObject regionStats = new JsonObject();
        Map<String, Answers.Answer> answers = new LinkedHashMap<>();
        for (Dataset.Region region : dataset.regions()) {
            long r0 = System.nanoTime();
            RegionWorld world = RegionWorld.load(region.name(), dataset.save(region.name()));
            Regional gen = new Regional(world, solver, dataset, region.heldOnly());
            List<Problem> problems = gen.generate();
            dataset.writeProblems(region.name(), problems);
            gen.answers().forEach(a -> answers.put(a.key(), a));
            JsonObject stats = gen.stats(problems);
            stats.addProperty("seconds", Math.round((System.nanoTime() - r0) / 1e8) / 10.0);
            regionStats.add(region.name(), stats);
            System.out.println("[generate] " + region.name() + " " + stats);
        }
        Answers.write(dataset.dir, commit, answers.values());
        manifest.add("regions", regionStats);
        manifest.addProperty("generateSeconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
        dataset.writeManifest(manifest);
    }

    /** 一块地上的出题。 */
    private static final class Regional {

        private final RegionWorld world;
        private final SearchView whole;
        private final Solver solver;
        private final Dataset dataset;
        private final boolean heldOnly;
        private final Map<Problem.Bucket, Unit> units = new EnumMap<>(Problem.Bucket.class);

        Regional(RegionWorld world, Solver solver, Dataset dataset, boolean heldOnly) {
            this.world = world;
            this.whole = world.whole();
            this.solver = solver;
            this.dataset = dataset;
            this.heldOnly = heldOnly;
        }

        /** 三个档的题,按档的顺序;已经收完的档(有过程文件)读回,不再求解。 */
        List<Problem> generate() throws IOException {
            List<Problem> out = new ArrayList<>();
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                Path file = unitFile(dataset, world.name, bucket);
                Unit unit;
                if (Files.exists(file)) {
                    unit = Unit.fromJson(com.google.gson.JsonParser.parseString(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
                    System.out.println("[generate] " + world.name + " " + bucket.label + " 已收完(" + unit.problems().size() + " 道),读回");
                } else {
                    unit = bucket(bucket);
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, Dataset.GSON.toJson(unit.toJson()) + "\n", java.nio.charset.StandardCharsets.UTF_8);
                }
                units.put(bucket, unit);
                out.addAll(unit.problems());
            }
            return out;
        }

        List<Answers.Answer> answers() {
            return units.values().stream().flatMap(u -> u.answers().stream()).toList();
        }

        /** 一个候选:抽到的序号、候选本身与几何事实;{@code futures} 是它在各适用规格下的求解(平凡题没有)。 */
        private record Drawn(int index, Candidate candidate, Straight.Facts facts, Problem draft, List<Spec> specs, List<CompletableFuture<Solver.Outcome>> futures) {}

        /** 一个候选的结论:拒的理由(收了为 empty)与各规格的求解结果。 */
        private record Verdict(Drawn drawn, Optional<Reject> why, List<Solver.Outcome> outcomes) {}

        /**
         * 一档:按固定种子抽候选,候选一抽到就排进求解器(同时在求解的最多 {@link #WINDOW} 个),结论按候选的序号收,收满配额或试满候选为止
         * ({@link #sequence})。收了的题的号、拒绝的计数只看序号在它之前(含)的候选,所以与一个一个串行地做结果一样。
         */
        private Unit bucket(Problem.Bucket bucket) {
            SplittableRandom rng = new SplittableRandom(SEED ^ (world.name.hashCode() * 31L + bucket.ordinal()));
            int[] drawn = {0};
            AtomicBoolean stop = new AtomicBoolean();
            List<Verdict> taken = sequence(() -> {
                while (drawn[0] < MAX_CANDIDATES) {
                    int index = ++drawn[0];
                    Candidate c = draw(rng, bucket);
                    if (c == null) {
                        continue;
                    }
                    Straight.Facts facts = Straight.along(whole, c.from, c.to);
                    Problem draft = new Problem(world.name + "-" + bucket.label + "-draft", world.name, bucket, c.distance(), c.from, c.type, c.to, c.radius,
                            c.endClass, Problem.Split.DEV, facts.tags(c.endClass));
                    List<Spec> specs = new ArrayList<>();
                    List<CompletableFuture<Solver.Outcome>> futures = new ArrayList<>();
                    if (!facts.plainWalk()) {
                        for (Spec spec : Spec.values()) {
                            if (draft.applicable(spec)) {
                                specs.add(spec);
                                futures.add(solver.submit(new Solver.Job(whole, spec.model(), draft.from(), draft.goal(Spec.STATS)), index, stop::get));
                            }
                        }
                    }
                    return new Drawn(index, c, facts, draft, specs, futures);
                }
                return null;
            }, d -> judge(d), v -> v.why().isEmpty(), QUOTA, WINDOW);
            stop.set(true);

            List<Problem> out = new ArrayList<>();
            List<Answers.Answer> answers = new ArrayList<>();
            Map<Reject, Integer> rejected = new EnumMap<>(Reject.class);
            for (Reject why : Reject.values()) {
                rejected.put(why, 0);
            }
            long[] tierJobs = new long[3];
            long[] tierMillis = new long[3];
            int tier2Conclusive = 0;
            List<Long> tier2JobMillis = new ArrayList<>();
            int accepted = 0;
            int tried = 0;
            for (Verdict v : taken) {
                Drawn d = v.drawn();
                tried = d.index();
                for (Solver.Outcome o : v.outcomes()) {
                    tierJobs[o.tier()]++;
                    tierMillis[o.tier()] += o.millis();
                    if (o.tier() == 2) {
                        tier2JobMillis.add(o.millis());
                        tier2Conclusive += o.solved().conclusive() ? 1 : 0;
                    }
                }
                if (v.why().isPresent()) {
                    rejected.merge(v.why().get(), 1, Integer::sum);
                    continue;
                }
                String id = world.name + "-" + bucket.label + "-" + String.format("%03d", ++accepted);
                Candidate c = d.candidate();
                out.add(new Problem(id, world.name, bucket, c.distance(), c.from, c.type, c.to, c.radius, c.endClass,
                        heldOnly ? Problem.Split.HELD : Problem.split(id), d.facts().tags(c.endClass)));
                for (int k = 0; k < d.specs().size(); k++) {
                    Reference.Solved s = v.outcomes().get(k).solved();
                    answers.add(new Answers.Answer(id, d.specs().get(k), s.status(), s.cost(), s.expanded(), s.startEstimate()));
                }
            }
            if (accepted < QUOTA) {
                tried = drawn[0];
                System.out.println("[generate] " + world.name + " " + bucket.label + " 只收到 " + accepted + " 道");
            }
            System.out.println("[generate] " + world.name + " " + bucket.label + " 收到 " + accepted + "/" + QUOTA + ",试了 " + tried);
            return new Unit(out, answers, taken.size(), rejected, tierJobs, tierMillis, tier2Conclusive, tier2JobMillis);
        }

        /** 等一个候选各规格的求解都完,说它收不收、不收为什么。平凡题没有求解,直接是 {@link Reject#PLAIN_WALK}。 */
        private static CompletableFuture<Verdict> judge(Drawn d) {
            if (d.facts().plainWalk()) {
                return CompletableFuture.completedFuture(new Verdict(d, Optional.of(Reject.PLAIN_WALK), List.of()));
            }
            return CompletableFuture.allOf(d.futures().toArray(new CompletableFuture[0])).thenApply(ignored -> {
                List<Solver.Outcome> outcomes = d.futures().stream().map(CompletableFuture::join).toList();
                return new Verdict(d, verdict(outcomes.stream().map(Solver.Outcome::solved).toList()), outcomes);
            });
        }

        JsonObject stats(List<Problem> problems) {
            JsonObject o = new JsonObject();
            o.addProperty("problems", problems.size());
            o.addProperty("candidates", units.values().stream().mapToInt(Unit::candidates).sum());
            JsonObject rejects = new JsonObject();
            JsonObject total = new JsonObject();
            for (Reject why : Reject.values()) {
                total.addProperty(why.key, units.values().stream().mapToInt(u -> u.rejected().getOrDefault(why, 0)).sum());
            }
            rejects.add("total", total);
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                JsonObject b = new JsonObject();
                for (Reject why : Reject.values()) {
                    b.addProperty(why.key, units.get(bucket).rejected().getOrDefault(why, 0));
                }
                rejects.add(bucket.label, b);
            }
            o.add("rejected", rejects);
            JsonObject solve = new JsonObject();
            for (int tier = 1; tier <= 2; tier++) {
                int t = tier;
                JsonObject tj = new JsonObject();
                tj.addProperty("jobs", units.values().stream().mapToLong(u -> u.tierJobs()[t]).sum());
                tj.addProperty("cpuSeconds", Math.round(units.values().stream().mapToLong(u -> u.tierMillis()[t]).sum() / 100.0) / 10.0);
                if (tier == 2) {
                    tj.addProperty("conclusive", units.values().stream().mapToInt(Unit::tier2Conclusive).sum());
                    List<Double> seconds = units.values().stream().flatMap(u -> u.tier2JobMillis().stream()).map(ms -> ms / 1000.0).toList();
                    tj.addProperty("medianSeconds", Math.round(Stats.percentile(seconds, 0.5) * 10) / 10.0);
                    tj.addProperty("p90Seconds", Math.round(Stats.percentile(seconds, 0.9) * 10) / 10.0);
                    tj.addProperty("maxSeconds", Math.round(Stats.percentile(seconds, 1.0) * 10) / 10.0);
                }
                solve.add("tier" + tier, tj);
            }
            o.add("solve", solve);
            Map<Problem.EndClass, Integer> byClass = new EnumMap<>(Problem.EndClass.class);
            Map<Problem.Split, Integer> bySplit = new EnumMap<>(Problem.Split.class);
            Map<Problem.GoalType, Integer> byGoal = new EnumMap<>(Problem.GoalType.class);
            Map<Problem.Bucket, Integer> byBucket = new EnumMap<>(Problem.Bucket.class);
            Map<Answers.Status, Integer> byStatus = new EnumMap<>(Answers.Status.class);
            Map<String, Answers.Answer> byKey = new LinkedHashMap<>();
            answers().forEach(a -> byKey.put(a.key(), a));
            int notApplicable = 0;
            for (Problem p : problems) {
                byClass.merge(p.endClass(), 1, Integer::sum);
                bySplit.merge(p.split(), 1, Integer::sum);
                byGoal.merge(p.goalType(), 1, Integer::sum);
                byBucket.merge(p.bucket(), 1, Integer::sum);
                notApplicable += p.applicable(Spec.A) ? 0 : 1;
                for (Spec spec : Spec.values()) {
                    if (p.applicable(spec)) {
                        byStatus.merge(byKey.get(Answers.Answer.key(p.id(), spec)).status(), 1, Integer::sum);
                    }
                }
            }
            o.addProperty("notApplicableToA", notApplicable);
            o.add("byBucket", count(byBucket));
            o.add("byEndClass", count(byClass));
            o.add("byGoal", count(byGoal));
            o.add("bySplit", count(bySplit));
            o.add("byAnswer", count(byStatus));
            return o;
        }

        private static <E extends Enum<E>> JsonObject count(Map<E, Integer> counts) {
            JsonObject o = new JsonObject();
            counts.forEach((k, v) -> o.addProperty(k.name().toLowerCase(), v));
            return o;
        }

        // ==================== 撒点 ====================

        private record Candidate(BlockPos from, BlockPos to, Problem.GoalType type, int radius, Problem.EndClass endClass) {

            /** 三维直线距离:分档的依据。 */
            double distance() {
                double dx = to.getX() - from.getX();
                double dy = to.getY() - from.getY();
                double dz = to.getZ() - from.getZ();
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        private Candidate draw(SplittableRandom rng, Problem.Bucket bucket) {
            BlockPos from = startCell(rng);
            if (from == null) {
                return null;
            }
            double low = Math.max(bucket.min, 6);
            double distance = low + rng.nextDouble() * (bucket.max - low);
            double angle = rng.nextDouble() * 2 * Math.PI;
            int x = from.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = from.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (x < world.minX() + MARGIN || x > world.maxX() - MARGIN || z < world.minZ() + MARGIN || z > world.maxZ() - MARGIN) {
                return null;
            }
            Problem.EndClass endClass = endClass(rng);
            BlockPos to = endCell(rng, endClass, x, z);
            if (to == null) {
                return null;
            }
            Candidate c = switch (endClass) {
                case BURIED -> new Candidate(from, to, rng.nextInt(100) < 40 ? Problem.GoalType.AT : Problem.GoalType.DIG, 0, endClass);
                default -> rng.nextInt(100) < 55 ? new Candidate(from, to, Problem.GoalType.AT, 0, endClass)
                        : new Candidate(from, to, Problem.GoalType.NEAR, 2 + rng.nextInt(4), endClass);
            };
            return Problem.Bucket.of(c.distance()) == bucket ? c : null;
        }

        /** 终点的种类:地表四成半、洞穴两成半、空腔一成、封闭的一格两成。 */
        private Problem.EndClass endClass(SplittableRandom rng) {
            int roll = rng.nextInt(100);
            return roll < 45 ? Problem.EndClass.SURFACE : roll < 70 ? Problem.EndClass.CAVE : roll < 80 ? Problem.EndClass.CAVITY
                    : Problem.EndClass.BURIED;
        }

        private BlockPos startCell(SplittableRandom rng) {
            int x = world.minX() + MARGIN + rng.nextInt(world.maxX() - world.minX() - 2 * MARGIN);
            int z = world.minZ() + MARGIN + rng.nextInt(world.maxZ() - world.minZ() - 2 * MARGIN);
            return rng.nextInt(100) < 70 ? surface(x, z) : cave(rng, x, z);
        }

        private BlockPos endCell(SplittableRandom rng, Problem.EndClass endClass, int x, int z) {
            return switch (endClass) {
                case SURFACE -> surface(x, z);
                case CAVE -> cave(rng, x, z);
                case CAVITY -> cavity(rng, x, z);
                case BURIED -> buried(rng, x, z);
            };
        }

        private boolean standable(int x, int y, int z) {
            return Stance.at(whole, Spec.STATS, x, y, z) != null;
        }

        /** 这一列最高的站得住的格;没有为 null。 */
        private BlockPos surface(int x, int z) {
            for (int y = world.scanTop; y >= world.heights.getMinBuildHeight(); y--) {
                if (standable(x, y, z)) {
                    return new BlockPos(x, y, z);
                }
            }
            return null;
        }

        /** 地表以下站得住的地面(脚下是实心的格,不在水里),随机一格;没有为 null。 */
        private BlockPos floor(SplittableRandom rng, int x, int z) {
            BlockPos top = surface(x, z);
            if (top == null) {
                return null;
            }
            List<BlockPos> cells = new ArrayList<>();
            for (int y = top.getY() - 5; y >= world.heights.getMinBuildHeight(); y--) {
                Stance stance = Stance.at(whole, Spec.STATS, x, y, z);
                if (stance != null && stance.kind() == Stance.Kind.GROUND) {
                    cells.add(new BlockPos(x, y, z));
                }
            }
            return cells.isEmpty() ? null : cells.get(rng.nextInt(cells.size()));
        }

        /** 洞穴:地表以下站得住、连着大片空间(不是封闭的空腔)的地面;没有为 null。 */
        private BlockPos cave(SplittableRandom rng, int x, int z) {
            BlockPos c = floor(rng, x, z);
            return c == null || closed(c) ? null : c;
        }

        /** 地下空腔:地表以下站得住、四周一团空气与流体不大于 {@link #POCKET} 格(不连着别处)的地面;没有为 null。 */
        private BlockPos cavity(SplittableRandom rng, int x, int z) {
            BlockPos c = floor(rng, x, z);
            return c == null || !closed(c) ? null : c;
        }

        /** 从 {@code c} 起六邻接的空气与流体连成的一团不大于 {@link #POCKET} 格,也没有碰到没加载的区块:封闭的。 */
        private boolean closed(BlockPos c) {
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            HashSet<BlockPos> seen = new HashSet<>();
            queue.add(c);
            seen.add(c);
            while (!queue.isEmpty()) {
                BlockPos at = queue.poll();
                for (Direction d : Direction.values()) {
                    BlockPos next = at.relative(d);
                    if (seen.contains(next)) {
                        continue;
                    }
                    if (!whole.isLoaded(next.getX(), next.getZ())) {
                        return false;
                    }
                    BlockState state = whole.getBlockState(next);
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        if (seen.size() >= POCKET) {
                            return false;
                        }
                        seen.add(next);
                        queue.add(next);
                    }
                }
            }
            return true;
        }

        /** 它和六个邻居都是实心方块的一格;没有为 null。 */
        private BlockPos buried(SplittableRandom rng, int x, int z) {
            BlockPos top = surface(x, z);
            if (top == null) {
                return null;
            }
            int low = world.heights.getMinBuildHeight() + 6;
            int high = top.getY() - 8;
            if (high <= low) {
                return null;
            }
            BlockPos c = new BlockPos(x, low + rng.nextInt(high - low), z);
            if (!solid(c)) {
                return null;
            }
            for (Direction d : Direction.values()) {
                if (!solid(c.relative(d))) {
                    return null;
                }
            }
            return c;
        }

        private boolean solid(BlockPos pos) {
            BlockState state = whole.getBlockState(pos);
            return state.isSolid() && state.getFluidState().isEmpty();
        }
    }
}
