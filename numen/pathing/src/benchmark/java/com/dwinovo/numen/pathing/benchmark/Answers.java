package com.dwinovo.numen.pathing.benchmark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.dwinovo.numen.pathing.Vanilla;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 标准答案:每道题在适用的规格下的结论(最优价,或区域内证明的无解),由 {@link Reference} 算,存成独立的答案文件,与题库分开。
 * 出题时({@link Generate})答案跟着题一起产出,只收有定论的题;这里的两步留给"成本模型变了,重算现有题"用,在哪台机器上算都行:
 * 只要 Java、构建好的评测类路径和数据集目录,不要游戏服务器。两步:
 * <ol>
 *   <li>{@code Answers solve <数据集目录> <分片目录> [--shard i/n] [--buckets short,medium] [--limit N]}:把"题 × 规格"按题号排序,
 *       取第 i 份(共 n 份),每算完一条就追加写进 {@code <分片目录>/shard-<i>-of-<n>.jsonl};重启时跳过已有的,中断不丢活。
 *       按内存预算排程({@link Solver});</li>
 *   <li>{@code Answers merge <数据集目录> <分片目录> <主仓库提交>}:把所有分片并起来、按题号排好、记下算出它们的主仓库提交,写进数据集目录的
 *       {@code answers.jsonl} 与 {@code answers-manifest.json};再按档 × 规格报每题用了多少 CPU 时间、整个数据集还要多少。</li>
 * </ol>
 * 评测读 {@code answers.jsonl}:没有这个文件是正常状态,报表里就没有次优比这一列({@link #load})。
 */
final class Answers {

    /** 标准答案的状态。题库里只有 {@code OPTIMAL} 与 {@code UNSOLVABLE}:别的状态是求解的结论,出题时拒题的理由。 */
    enum Status {
        /** 权重 1 的搜索到了目标:价钱是区域内这套成本模型下的最优价。 */
        OPTIMAL,
        /** 能走到的都搜完了没有目标、没碰到区域边:区域内证明过的无解。 */
        UNSOLVABLE,
        /** 能走到的都搜完了没有目标,可连通块碰到了区域边:这份数据里没有定论,加算力也没用。 */
        BEYOND_REGION,
        /** 节点上限(两档都)用完了,算不动。 */
        TOO_HARD,
        /** 算不动,但按生产权重搜到了一条路:价钱只是一个已知的上界,次优比只作参考。 */
        BEST_KNOWN
    }

    /** 一道题在一种规格下的标准答案。 */
    record Answer(String id, Spec spec, Status status, double cost, int expanded, double startEstimate) {

        String key() {
            return key(id, spec);
        }

        static String key(String id, Spec spec) {
            return id + "|" + spec.name();
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("spec", spec.name());
            o.addProperty("status", status.name().toLowerCase().replace('_', '-'));
            if (!Double.isNaN(cost)) {
                o.addProperty("cost", Math.round(cost * 1000) / 1000.0);
            }
            o.addProperty("expanded", expanded);
            o.addProperty("startEstimate", Math.round(startEstimate * 1000) / 1000.0);
            return o;
        }

        static Answer fromJson(JsonObject o) {
            return new Answer(o.get("id").getAsString(), Spec.valueOf(o.get("spec").getAsString()),
                    Status.valueOf(o.get("status").getAsString().toUpperCase().replace('-', '_')),
                    o.has("cost") ? o.get("cost").getAsDouble() : Double.NaN, o.get("expanded").getAsInt(), o.get("startEstimate").getAsDouble());
        }
    }

    /** 数据集里的答案文件:算出它们的主仓库提交,与每条答案。 */
    record Known(String commit, Map<String, Answer> answers) {

        Optional<Answer> of(Problem problem, Spec spec) {
            return Optional.ofNullable(answers.get(Answer.key(problem.id(), spec)));
        }
    }

    private static final String FILE = "answers.jsonl";
    private static final String MANIFEST = "answers-manifest.json";

    private Answers() {}

    /** 数据集里的答案文件;没有它是正常状态,交空。 */
    static Optional<Known> load(Path dataset) {
        Path file = dataset.resolve(FILE);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (JsonObject o : lines(file)) {
            Answer a = Answer.fromJson(o);
            answers.put(a.key(), a);
        }
        try (BufferedReader in = Files.newBufferedReader(dataset.resolve(MANIFEST), StandardCharsets.UTF_8)) {
            return Optional.of(new Known(JsonParser.parseReader(in).getAsJsonObject().get("commit").getAsString(), answers));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<JsonObject> lines(Path file) {
        List<JsonObject> out = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.isBlank()) {
                    out.add(JsonParser.parseString(line).getAsJsonObject());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * 一个节点连同它的边活在堆里约这么多字节。实测:一个求解器展开到 8M 个节点,整堆回收后活着的有 13 688 MB,即 1 794 字节一个节点;
     * v1 在 2M 档并行 19 个求解器的老年代约 48 GB,一个约 3.5 GB,同一个量级。
     */
    static final long NODE_BYTES = 1_800;

    /**
     * 求解器的内存预算:活着的节点最多占多少字节。并行收集器的老年代默认只占堆的三分之二,活着的节点都在那里,所以取三分之二的堆;
     * 装不下,收集器就在整堆回收里空转到"GC overhead limit exceeded"。{@link Solver} 按每个作业这一档的 {@code 上限 × NODE_BYTES} 从里面预留。
     */
    static long memoryBudget() {
        return Runtime.getRuntime().maxMemory() / 3 * 2;
    }

    /** 同时跑几个求解作业的核数上限:处理器数减一,至少一个。 */
    static int threads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    public static void main(String[] args) throws Exception {
        Vanilla.boot();
        switch (args[0]) {
            case "solve" -> solve(args);
            case "merge" -> merge(Path.of(args[1]), Path.of(args[2]), args[3]);
            default -> throw new IllegalArgumentException("要么 solve 要么 merge:" + args[0]);
        }
    }

    // ==================== 算 ====================

    private record Pair(Problem problem, Spec spec) {

        String key() {
            return Answer.key(problem.id(), spec);
        }
    }

    private static void solve(String[] args) throws Exception {
        Dataset dataset = new Dataset(Path.of(args[1]));
        Path shards = Path.of(args[2]);
        int index = 0;
        int count = 1;
        Set<String> buckets = null;
        int limit = Integer.MAX_VALUE;
        for (int i = 3; i < args.length; i += 2) {
            switch (args[i]) {
                case "--shard" -> {
                    String[] f = args[i + 1].split("/");
                    index = Integer.parseInt(f[0]);
                    count = Integer.parseInt(f[1]);
                }
                case "--buckets" -> buckets = Set.of(args[i + 1].split(","));
                case "--limit" -> limit = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("不认得的参数:" + args[i]);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (Dataset.Region region : dataset.regions()) {
            for (Problem p : dataset.problems(region.name())) {
                for (Spec spec : Spec.values()) {
                    if (p.applicable(spec) && (buckets == null || buckets.contains(p.bucket().label))) {
                        pairs.add(new Pair(p, spec));
                    }
                }
            }
        }
        pairs.sort(Comparator.comparing(Pair::key));
        List<Pair> mine = new ArrayList<>();
        for (int i = index; i < pairs.size() && mine.size() < limit; i += count) {
            mine.add(pairs.get(i));
        }
        Files.createDirectories(shards);
        Path file = shards.resolve("shard-" + index + "-of-" + count + ".jsonl");
        Set<String> done = new HashSet<>();
        if (Files.exists(file)) {
            lines(file).forEach(o -> done.add(Answer.fromJson(o).key()));
        }
        Map<String, List<Pair>> byRegion = mine.stream().filter(p -> !done.contains(p.key()))
                .collect(Collectors.groupingBy(p -> p.problem().region(), LinkedHashMap::new, Collectors.toList()));
        int pending = byRegion.values().stream().mapToInt(List::size).sum();
        System.out.println("[answers] 分片 " + index + "/" + count + ":" + mine.size() + " 条,已有 " + (mine.size() - pending) + " 条,线程 "
                + threads() + " 个,内存预算 " + memoryBudget() / (1 << 20) + " MB");
        try (Solver solver = Solver.standard(); BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (Map.Entry<String, List<Pair>> region : byRegion.entrySet()) {
                RegionWorld world = RegionWorld.load(region.getKey(), dataset.save(region.getKey()));
                var whole = world.whole();
                List<Pair> regionPairs = region.getValue();
                List<Solver.Job> jobs = regionPairs.stream()
                        .map(pair -> new Solver.Job(whole, pair.spec().model(), pair.problem().from(), pair.problem().goal(Spec.STATS))).toList();
                solver.solve(jobs, (i, outcome) -> {
                    Reference.Solved s = outcome.solved();
                    JsonObject o = new Answer(regionPairs.get(i).problem().id(), regionPairs.get(i).spec(), s.status(), s.cost(), s.expanded(), s.startEstimate())
                            .toJson();
                    o.addProperty("millis", outcome.millis());
                    o.addProperty("tier", outcome.tier());
                    try {
                        out.write(o + "\n");
                        out.flush();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    // ==================== 并与写 ====================

    /**
     * 把 {@code answers} 写成数据集目录里的 {@code answers.jsonl}(按题号与规格排好)与 {@code answers-manifest.json}(算出它们的主仓库提交、两档节点上限、
     * 各状态的条数)。出题与重算答案共用这一处。
     */
    static void write(Path dataset, String commit, java.util.Collection<Answer> answers) throws IOException {
        TreeMap<String, Answer> sorted = new TreeMap<>();
        answers.forEach(a -> sorted.put(a.key(), a));
        StringBuilder text = new StringBuilder();
        Map<Status, Integer> counts = new EnumMap<>(Status.class);
        sorted.values().forEach(a -> {
            text.append(a.toJson()).append('\n');
            counts.merge(a.status(), 1, Integer::sum);
        });
        Files.writeString(dataset.resolve(FILE), text.toString(), StandardCharsets.UTF_8);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("commit", commit);
        manifest.addProperty("nodeCap", Reference.NODE_CAP);
        manifest.addProperty("largeNodeCap", Reference.LARGE_NODE_CAP);
        manifest.addProperty("count", sorted.size());
        JsonObject by = new JsonObject();
        counts.forEach((k, v) -> by.addProperty(k.name().toLowerCase().replace('_', '-'), v));
        manifest.add("status", by);
        Files.writeString(dataset.resolve(MANIFEST), Dataset.GSON.toJson(manifest) + "\n", StandardCharsets.UTF_8);
        System.out.println("[answers] 写了 " + sorted.size() + " 条 " + counts);
    }

    private static void merge(Path dataset, Path shards, String commit) throws IOException {
        Map<String, Answer> merged = new TreeMap<>();
        Map<String, Long> millis = new LinkedHashMap<>();
        try (var files = Files.list(shards)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("shard-\\d+-of-\\d+\\.jsonl")).sorted().toList()) {
                for (JsonObject o : lines(file)) {
                    Answer a = Answer.fromJson(o);
                    if (merged.put(a.key(), a) != null) {
                        throw new IllegalStateException("同一条答案出现了两次:" + a.key());
                    }
                    millis.put(a.key(), o.get("millis").getAsLong());
                }
            }
        }
        write(dataset, commit, merged.values());
        report(new Dataset(dataset), millis);
    }

    /** 每题用了多少 CPU 时间(单线程墙钟,按档 × 规格的中位与 P90),整个数据集要算的有多少条、折合多少 CPU 小时。 */
    private static void report(Dataset dataset, Map<String, Long> millis) {
        Map<String, Integer> total = new LinkedHashMap<>();
        for (Dataset.Region region : dataset.regions()) {
            for (Problem p : dataset.problems(region.name())) {
                for (Spec spec : Spec.values()) {
                    if (p.applicable(spec)) {
                        total.merge(p.bucket().label + " " + spec, 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, List<Double>> solved = new LinkedHashMap<>();
        millis.forEach((key, ms) -> {
            String[] f = key.split("\\|");
            String bucket = f[0].split("-")[1];
            solved.computeIfAbsent(bucket + " " + f[1], k -> new ArrayList<>()).add(ms / 1000.0);
        });
        double projected = 0;
        System.out.println("[answers] 档 规格 | 已算 | 中位 s | P90 s | 平均 s | 全部条数 | 折合 CPU 小时");
        for (String key : total.keySet()) {
            List<Double> v = solved.getOrDefault(key, List.of());
            double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double hours = mean * total.get(key) / 3600;
            projected += hours;
            System.out.println(String.format(Locale.ROOT, "[answers] %s | %d | %.1f | %.1f | %.1f | %d | %.2f", key, v.size(), Stats.percentile(v, 0.5),
                    Stats.percentile(v, 0.9), mean, total.get(key), hours));
        }
        System.out.println(String.format(Locale.ROOT, "[answers] 合计折合 %.1f CPU 小时(只按已算的样本外推,没算过的档记 0)", projected));
    }
}
