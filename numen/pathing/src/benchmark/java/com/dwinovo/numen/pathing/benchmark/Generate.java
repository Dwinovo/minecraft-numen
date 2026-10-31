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
 * {@code Generate <数据集目录> <主仓库提交> [--resume]}。每块地、每个距离档收 {@link #QUOTA} 道题。
 *
 * <p><b>只收有定论的题</b>:候选题在每个适用的规格下的标准答案都是最优价({@code OPTIMAL})或区域内证明的无解({@code UNSOLVABLE})才收;
 * 可连通块碰到区域边的({@code BEYOND_REGION})、两档节点上限都算不动的({@code TOO_HARD})、只有生产权重搜出的上界的({@code BEST_KNOWN})
 * 都拒掉,继续抽候选直到配额满。拒绝按原因计数写进 manifest。判据只看地形、种子与成本模型下的精确解({@link Reference}、{@link Solver}),
 * 不看生产寻路;所以题库里每道题评测失败了,说得清是寻路没做成,不会是本来就到不了或区域不够大。出题与答案同一份求解代码一次产出,
 * 答案文件的 {@code commit} 就是出题的主仓库提交。{@code --resume}:manifest 里已有的块跳过,接着往下做。
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
    /** 一批同时求解的候选数(与机器无关,收题的先后只看候选的抽样顺序,结果可复现)。 */
    private static final int BATCH = 48;
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

    public static void main(String[] args) throws IOException {
        Vanilla.boot();
        Dataset dataset = new Dataset(Path.of(args[0]));
        String commit = args[1];
        boolean resume = args.length > 2 && args[2].equals("--resume");
        Solver solver = Solver.standard();
        long t0 = System.nanoTime();
        JsonObject manifest = resume && Files.exists(dataset.dir.resolve("manifest.json")) ? dataset.manifest() : new JsonObject();
        manifest.addProperty("dataset", "numen-pathfinding-benchmark");
        manifest.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName());
        manifest.addProperty("commit", commit);
        manifest.addProperty("quota", QUOTA);
        manifest.addProperty("nodeCap", solver.cap());
        manifest.addProperty("largeNodeCap", solver.largeCap());
        manifest.addProperty("accepted", "每个适用规格下的标准答案都是最优价或区域内证明的无解;可连通块碰到区域边的、两档节点上限都算不动的、只有上界的、平凡的都拒掉,拒绝按原因计数在各块的 rejected 里");
        manifest.addProperty("notApplicableToA", "需要编辑才做得成的题:挖到某格,和走到封死的空腔或封闭在实心里的一格;规格 A 不挖不垫,这些题不给 A 跑");
        JsonObject regionStats = resume && manifest.has("regions") ? manifest.getAsJsonObject("regions") : new JsonObject();
        Map<String, Answers.Answer> answers = new LinkedHashMap<>();
        if (resume) {
            Answers.load(dataset.dir).ifPresent(known -> answers.putAll(known.answers()));
        }
        for (Dataset.Region region : dataset.regions()) {
            if (regionStats.has(region.name())) {
                System.out.println("[generate] " + region.name() + " 已做完,跳过");
                continue;
            }
            long r0 = System.nanoTime();
            RegionWorld world = RegionWorld.load(region.name(), dataset.save(region.name()));
            Regional gen = new Regional(world, solver);
            List<Problem> problems = gen.generate(region.heldOnly());
            dataset.writeProblems(region.name(), problems);
            answers.putAll(gen.answers);
            Answers.write(dataset.dir, commit, answers.values());
            JsonObject stats = gen.stats(problems);
            stats.addProperty("seconds", Math.round((System.nanoTime() - r0) / 1e8) / 10.0);
            regionStats.add(region.name(), stats);
            manifest.add("regions", regionStats);
            manifest.addProperty("generateSeconds", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
            dataset.writeManifest(manifest);
            System.out.println("[generate] " + region.name() + " " + stats);
        }
    }

    /** 一块地上的出题。 */
    private static final class Regional {

        private final RegionWorld world;
        private final SearchView whole;
        private final Solver solver;
        /** 收下的题的答案,按 {@code 题号|规格}。 */
        final Map<String, Answers.Answer> answers = new LinkedHashMap<>();
        private int candidates;
        private final Map<Problem.Bucket, EnumMap<Reject, Integer>> rejected = new EnumMap<>(Problem.Bucket.class);
        /** 各档上限(下标 1、2)用了几条、墙钟毫秒加总。 */
        private final long[] tierJobs = new long[3];
        private final long[] tierMillis = new long[3];

        Regional(RegionWorld world, Solver solver) {
            this.world = world;
            this.whole = world.whole();
            this.solver = solver;
        }

        private record Drawn(Candidate candidate, Straight.Facts facts, Problem draft) {}

        List<Problem> generate(boolean heldOnly) {
            List<Problem> out = new ArrayList<>();
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                SplittableRandom rng = new SplittableRandom(SEED ^ (world.name.hashCode() * 31L + bucket.ordinal()));
                int accepted = 0;
                int tried = 0;
                while (accepted < QUOTA && tried < MAX_CANDIDATES) {
                    List<Drawn> batch = new ArrayList<>();
                    int want = Math.min(BATCH, QUOTA - accepted);
                    while (batch.size() < want && tried < MAX_CANDIDATES) {
                        tried++;
                        Candidate c = draw(rng, bucket);
                        if (c == null) {
                            continue;
                        }
                        candidates++;
                        Straight.Facts facts = Straight.along(whole, c.from, c.to);
                        if (facts.plainWalk()) {
                            reject(bucket, Reject.PLAIN_WALK);
                            continue;
                        }
                        batch.add(new Drawn(c, facts, new Problem(world.name + "-" + bucket.label + "-draft", world.name, bucket, c.distance(), c.from,
                                c.type, c.to, c.radius, c.endClass, Problem.Split.DEV, facts.tags(c.endClass))));
                    }
                    List<Solver.Job> jobs = new ArrayList<>();
                    for (Drawn d : batch) {
                        for (Spec spec : Spec.values()) {
                            if (d.draft.applicable(spec)) {
                                jobs.add(new Solver.Job(whole, spec.model(), d.draft.from(), d.draft.goal(Spec.STATS)));
                            }
                        }
                    }
                    List<Solver.Outcome> outcomes = solver.solve(jobs, (i, o) -> { });
                    int next = 0;
                    for (Drawn d : batch) {
                        List<Spec> specs = new ArrayList<>();
                        List<Solver.Outcome> mine = new ArrayList<>();
                        for (Spec spec : Spec.values()) {
                            if (d.draft.applicable(spec)) {
                                specs.add(spec);
                                Solver.Outcome o = outcomes.get(next++);
                                mine.add(o);
                                tierJobs[o.tier()]++;
                                tierMillis[o.tier()] += o.millis();
                            }
                        }
                        Optional<Reject> why = verdict(mine.stream().map(Solver.Outcome::solved).toList());
                        if (why.isPresent()) {
                            reject(bucket, why.get());
                            continue;
                        }
                        String id = world.name + "-" + bucket.label + "-" + String.format("%03d", ++accepted);
                        Candidate c = d.candidate;
                        out.add(new Problem(id, world.name, bucket, c.distance(), c.from, c.type, c.to, c.radius, c.endClass,
                                heldOnly ? Problem.Split.HELD : Problem.split(id), d.facts.tags(c.endClass)));
                        for (int k = 0; k < specs.size(); k++) {
                            Reference.Solved s = mine.get(k).solved();
                            Answers.Answer a = new Answers.Answer(id, specs.get(k), s.status(), s.cost(), s.expanded(), s.startEstimate());
                            answers.put(a.key(), a);
                        }
                    }
                    System.out.println("[generate] " + world.name + " " + bucket.label + " 收到 " + accepted + "/" + QUOTA + ",试了 " + tried);
                }
                if (accepted < QUOTA) {
                    System.out.println("[generate] " + world.name + " " + bucket.label + " 只收到 " + accepted + " 道");
                }
            }
            return out;
        }

        private void reject(Problem.Bucket bucket, Reject why) {
            rejected.computeIfAbsent(bucket, b -> new EnumMap<>(Reject.class)).merge(why, 1, Integer::sum);
        }

        JsonObject stats(List<Problem> problems) {
            JsonObject o = new JsonObject();
            o.addProperty("problems", problems.size());
            o.addProperty("candidates", candidates);
            JsonObject rejects = new JsonObject();
            JsonObject total = new JsonObject();
            for (Reject why : Reject.values()) {
                total.addProperty(why.key, rejected.values().stream().mapToInt(m -> m.getOrDefault(why, 0)).sum());
            }
            rejects.add("total", total);
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                JsonObject b = new JsonObject();
                for (Reject why : Reject.values()) {
                    b.addProperty(why.key, rejected.getOrDefault(bucket, new EnumMap<>(Reject.class)).getOrDefault(why, 0));
                }
                rejects.add(bucket.label, b);
            }
            o.add("rejected", rejects);
            JsonObject solve = new JsonObject();
            for (int tier = 1; tier <= 2; tier++) {
                JsonObject t = new JsonObject();
                t.addProperty("jobs", tierJobs[tier]);
                t.addProperty("cpuSeconds", Math.round(tierMillis[tier] / 100.0) / 10.0);
                solve.add("tier" + tier, t);
            }
            o.add("solve", solve);
            Map<Problem.EndClass, Integer> byClass = new EnumMap<>(Problem.EndClass.class);
            Map<Problem.Split, Integer> bySplit = new EnumMap<>(Problem.Split.class);
            Map<Problem.GoalType, Integer> byGoal = new EnumMap<>(Problem.GoalType.class);
            Map<Problem.Bucket, Integer> byBucket = new EnumMap<>(Problem.Bucket.class);
            Map<Answers.Status, Integer> byStatus = new EnumMap<>(Answers.Status.class);
            int notApplicable = 0;
            for (Problem p : problems) {
                byClass.merge(p.endClass(), 1, Integer::sum);
                bySplit.merge(p.split(), 1, Integer::sum);
                byGoal.merge(p.goalType(), 1, Integer::sum);
                byBucket.merge(p.bucket(), 1, Integer::sum);
                notApplicable += p.applicable(Spec.A) ? 0 : 1;
                for (Spec spec : Spec.values()) {
                    if (p.applicable(spec)) {
                        byStatus.merge(answers.get(Answers.Answer.key(p.id(), spec)).status(), 1, Integer::sum);
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
