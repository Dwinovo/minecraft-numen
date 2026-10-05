package com.dwinovo.numen.bench.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 并行跑完的几份并成一份结果:{@code <结果目录>/shards/<i>/} 是第 i 个服务器写的({@code runs.jsonl}、{@code transcripts/}),
 * 并成 {@code <结果目录>/runs.jsonl}(按组、场景、变体、第几次排好)、{@code transcripts/} 与 {@code summary.md},和一个服务器
 * 串行跑出来的那一份同一个样子,{@code :bench:compare} 照常读。并完删掉 {@code shards/}。
 *
 * <pre>
 * java ... com.dwinovo.numen.bench.report.Merge &lt;结果目录&gt;
 * </pre>
 */
public final class Merge {

    /** 结果目录里放各份的子目录。 */
    public static final String SHARDS = "shards";

    private Merge() {}

    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        int runs = merge(dir);
        System.out.println("merged " + runs + " run(s) into " + dir);
    }

    /** 把 {@code dir/shards/*} 并进 {@code dir};返回并了几行。 */
    public static int merge(Path dir) {
        Path shards = dir.resolve(SHARDS);
        List<Run> all = new ArrayList<>();
        try {
            if (Files.isDirectory(shards)) {
                try (Stream<Path> list = Files.list(shards)) {
                    for (Path shard : list.sorted().toList()) {
                        if (Files.exists(shard.resolve(Runs.FILE))) {
                            all.addAll(Runs.read(shard));
                        }
                        moveTranscripts(shard.resolve("transcripts"), dir.resolve("transcripts"));
                    }
                }
            }
            all.sort(Comparator.comparing(Run::suite).thenComparing(Run::scenario)
                    .thenComparingInt(r -> Variant.of(r.variant()).ordinal()).thenComparingInt(Run::attempt));
            Files.createDirectories(dir);
            Path out = dir.resolve(Runs.FILE);
            Files.deleteIfExists(out);
            for (Run run : all) {
                Runs.append(out, run);
            }
            Files.writeString(dir.resolve(Summary.FILE), Summary.markdown("评测 " + dir.getFileName(), all),
                    StandardCharsets.UTF_8);
            deleteTree(shards);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return all.size();
    }

    private static void moveTranscripts(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Files.createDirectories(to);
        try (Stream<Path> list = Files.list(from)) {
            for (Path file : list.toList()) {
                Files.move(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
