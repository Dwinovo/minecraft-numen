package com.dwinovo.numen.pathing.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.google.gson.JsonParser;

/**
 * 对比两次结果:每个 档 × 规格 的指标变化、哪些题从成功变失败(以及反过来)、次优比变化最大的 20 道。
 * 用法:{@code Diff <旧 results.jsonl> <新 results.jsonl> [输出 md]}。以后"这个改动是不是正面优化"看这张表。
 * 两份结果里都有的"题 × 规格"才比,只在一边有的单列出数目。
 */
final class Diff {

    private Diff() {}

    public static void main(String[] args) throws IOException {
        String report = compare(read(Path.of(args[0])), read(Path.of(args[1])), args[0], args[1]);
        System.out.println(report);
        if (args.length > 2) {
            Files.writeString(Path.of(args[2]), report, StandardCharsets.UTF_8);
        }
    }

    static Map<String, Row> read(Path file) throws IOException {
        Map<String, Row> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                Row row = Row.fromJson(JsonParser.parseString(line).getAsJsonObject());
                out.put(row.key(), row);
            }
        }
        return out;
    }

    static String compare(Map<String, Row> before, Map<String, Row> after, String beforeName, String afterName) {
        List<String> common = new ArrayList<>();
        before.keySet().stream().filter(after::containsKey).forEach(common::add);
        StringBuilder md = new StringBuilder();
        md.append("# 寻路规划评测对比\n\n旧:").append(beforeName).append("\n新:").append(afterName).append("\n\n共有 ").append(common.size())
                .append(" 行;只在旧里 ").append(before.size() - common.size()).append(" 行,只在新里 ").append(after.size() - common.size())
                .append(" 行。\n\n");
        md.append("## 各档指标变化(新 − 旧;时间 ms;只比两边都有的行,无解的不比)\n\n");
        md.append("| 档 | 规格 | 题数 | 成功率 | 起步中位 | 起步 P99 | 单次最长思考 P99 | 展开数中位 | 展开数 P99 | 次优比中位 | 挖均值 | 垫均值 |\n");
        md.append("|---|---|---:|---|---|---|---|---|---|---|---|---|\n");
        for (Spec spec : Spec.values()) {
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                List<String> keys = new ArrayList<>();
                for (String k : common) {
                    Row r = before.get(k);
                    if (r.spec() == spec && r.bucket() == bucket && r.attempted()) {
                        keys.add(k);
                    }
                }
                if (keys.isEmpty()) {
                    continue;
                }
                md.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n", bucket.label, spec, keys.size(),
                        rate(keys, before, after), delta(keys, before, after, Row::firstMoveMs, 0.5), delta(keys, before, after, Row::firstMoveMs, 0.99),
                        delta(keys, before, after, Row::maxThinkMs, 0.99), delta(keys, before, after, r -> r.expanded(), 0.5),
                        delta(keys, before, after, r -> r.expanded(), 0.99), delta(keys, before, after, Row::subopt, 0.5),
                        mean(keys, before, after, Row::dug), mean(keys, before, after, Row::placed)));
            }
        }
        List<String> lost = new ArrayList<>();
        List<String> gained = new ArrayList<>();
        for (String k : common) {
            boolean was = before.get(k).arrived();
            boolean is = after.get(k).arrived();
            if (was && !is) {
                lost.add(k);
            } else if (!was && is) {
                gained.add(k);
            }
        }
        md.append("\n## 从成功变失败(").append(lost.size()).append(")\n\n");
        lost.forEach(k -> md.append("- ").append(k).append(" → ").append(after.get(k).ended()).append('\n'));
        md.append("\n## 从失败变成功(").append(gained.size()).append(")\n\n");
        gained.forEach(k -> md.append("- ").append(k).append('\n'));
        List<String> moved = new ArrayList<>();
        for (String k : common) {
            if (!Double.isNaN(before.get(k).subopt()) && !Double.isNaN(after.get(k).subopt())) {
                moved.add(k);
            }
        }
        moved.sort(Comparator.comparingDouble((String k) -> -Math.abs(after.get(k).subopt() - before.get(k).subopt())).thenComparing(k -> k));
        md.append("\n## 次优比变化最大的 20 道\n\n| 题 | 规格 | 旧 | 新 | 变化 |\n|---|---|---|---|---|\n");
        for (int i = 0; i < Math.min(20, moved.size()); i++) {
            Row a = before.get(moved.get(i));
            Row b = after.get(moved.get(i));
            if (a.subopt() == b.subopt()) {
                break;
            }
            md.append(String.format(Locale.ROOT, "| %s | %s | %.3f | %.3f | %+.3f |%n", a.id(), a.spec(), a.subopt(), b.subopt(), b.subopt() - a.subopt()));
        }
        return md.toString();
    }

    private static String rate(List<String> keys, Map<String, Row> before, Map<String, Row> after) {
        long a = keys.stream().filter(k -> before.get(k).arrived()).count();
        long b = keys.stream().filter(k -> after.get(k).arrived()).count();
        return String.format(Locale.ROOT, "%.1f%% → %.1f%% (%+d)", 100.0 * a / keys.size(), 100.0 * b / keys.size(), b - a);
    }

    private static String delta(List<String> keys, Map<String, Row> before, Map<String, Row> after, ToDoubleFunction<Row> metric, double p) {
        double a = percentile(keys, before, metric, p);
        double b = percentile(keys, after, metric, p);
        return Double.isNaN(a) || Double.isNaN(b) ? "-" : String.format(Locale.ROOT, "%.3g → %.3g (%+.3g)", a, b, b - a);
    }

    /** 分位数只取有这个指标的行(次优比不是每行都有)。 */
    private static double percentile(List<String> keys, Map<String, Row> rows, ToDoubleFunction<Row> metric, double p) {
        List<Double> values = new ArrayList<>();
        for (String k : keys) {
            double v = metric.applyAsDouble(rows.get(k));
            if (!Double.isNaN(v)) {
                values.add(v);
            }
        }
        return Stats.percentile(values, p);
    }

    private static String mean(List<String> keys, Map<String, Row> before, Map<String, Row> after, ToDoubleFunction<Row> metric) {
        double a = keys.stream().mapToDouble(k -> metric.applyAsDouble(before.get(k))).average().orElse(0);
        double b = keys.stream().mapToDouble(k -> metric.applyAsDouble(after.get(k))).average().orElse(0);
        return String.format(Locale.ROOT, "%.2f → %.2f", a, b);
    }
}
