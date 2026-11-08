package com.dwinovo.numen.pathing.benchmark;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 数据集目录:
 * <pre>
 * regions.json            有哪几块地(名字、是否只进封存);手写,随数据集走
 * manifest.json           出题时写出:数据集版本、生成它的主仓库提交、每块地的题数
 * terrain/&lt;名字&gt;/        region/r.X.Z.mca 与 terrain.properties(制作存档时写出)
 * problems/&lt;名字&gt;.jsonl   题库,一行一道题,带两种规格的标准答案
 * </pre>
 * 目录从 Gradle 属性来(本地目录,或下载并核过校验码的缓存),这里只读它。
 */
final class Dataset {

    /** 一块地。 */
    record Region(String name, boolean heldOnly) {}

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    final Path dir;

    Dataset(Path dir) {
        this.dir = dir;
    }

    List<Region> regions() {
        List<Region> out = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(dir.resolve("regions.json"), StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(in).getAsJsonArray();
            array.forEach(e -> {
                JsonObject o = e.getAsJsonObject();
                out.add(new Region(o.get("name").getAsString(), o.has("heldOnly") && o.get("heldOnly").getAsBoolean()));
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    Path save(String region) {
        return dir.resolve("terrain").resolve(region);
    }

    Path problemFile(String region) {
        return dir.resolve("problems").resolve(region + ".jsonl");
    }

    List<Problem> problems(String region) {
        List<Problem> out = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(problemFile(region), StandardCharsets.UTF_8)) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.isBlank()) {
                    out.add(Problem.fromJson(JsonParser.parseString(line).getAsJsonObject()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    void writeProblems(String region, List<Problem> problems) {
        try {
            Files.createDirectories(dir.resolve("problems"));
            try (BufferedWriter out = Files.newBufferedWriter(problemFile(region), StandardCharsets.UTF_8)) {
                for (Problem p : problems) {
                    out.write(p.toJson().toString());
                    out.write('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    JsonObject manifest() {
        try (BufferedReader in = Files.newBufferedReader(dir.resolve("manifest.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(in).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void writeManifest(JsonObject manifest) {
        try {
            Files.writeString(dir.resolve("manifest.json"), GSON.toJson(manifest) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
