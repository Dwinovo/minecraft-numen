package com.dwinovo.numen.bench;

import com.dwinovo.numen.bench.report.Run;
import com.dwinovo.numen.bench.report.Runs;
import com.dwinovo.numen.bench.report.Summary;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 这一次评测的结果目录 {@code <游戏目录>/results/<时间戳>/}(并行跑时是构建脚本给这一份的目录,{@code bench.results}):
 * {@code runs.jsonl} 每跑完一次追加一行,{@code summary.md} 随之重写,{@code transcripts/} 放每次的记录。一个进程一个目录,
 * 几组场景写进同一份;并行的几份跑完由构建脚本并成一份({@code Merge})。
 */
final class Results {

    private static Results instance;

    private final Path dir;
    private final String stamp;

    private Results(Path dir, String stamp) {
        this.dir = dir;
        this.stamp = stamp;
    }

    static synchronized Results get() {
        if (instance == null) {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path given = Settings.fromSystem().results();
            instance = given != null ? new Results(given, stamp)
                    : new Results(FMLPaths.GAMEDIR.get().resolve("results").resolve(stamp), stamp);
        }
        return instance;
    }

    Path dir() {
        return dir;
    }

    /** 这一次运行的记录文件放哪。 */
    Path transcript(String suite, String scenario, String variant, int attempt) {
        return dir.resolve("transcripts").resolve(suite + "-" + scenario + "-" + variant + "-" + attempt + ".jsonl");
    }

    /** 记下一次运行,重写汇总。 */
    synchronized void record(Run run) {
        Path runs = dir.resolve(Runs.FILE);
        Runs.append(runs, run);
        try {
            Files.writeString(dir.resolve(Summary.FILE), Summary.markdown("评测 " + stamp, Runs.read(runs)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
