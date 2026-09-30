package com.dwinovo.numen.bench.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** {@code runs.jsonl} 的读写:一次评测一行 JSON,只追加。 */
public final class Runs {

    /** 结果目录里记录文件的名字。 */
    public static final String FILE = "runs.jsonl";

    /** 空值也写出来:每一行的字段一眼看全,"没有"与"忘了写"分得开。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private Runs() {}

    public static String toJson(Run run) {
        return GSON.toJson(run);
    }

    public static Run fromJson(String line) {
        return GSON.fromJson(line, Run.class);
    }

    /** 追加一行;文件与目录没有就建。 */
    public static void append(Path file, Run run) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, toJson(run) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读一份记录;给的是目录就读其中的 {@link #FILE}。 */
    public static List<Run> read(Path fileOrDir) {
        Path file = Files.isDirectory(fileOrDir) ? fileOrDir.resolve(FILE) : fileOrDir;
        try {
            List<Run> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(fromJson(line));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
