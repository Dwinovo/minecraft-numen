package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * 分档报表:按 档 × 规格 × 标签 分组,每组报成功率(附 Wilson 95% 区间)、起步耗时、单次最长思考、展开数、次优比各自的中位 / P90 / P99 / 最大,
 * 以及挖、垫块数的平均。总表只作补充,以分档表为准。无解的题(按构造到不了的)单独成表,量多快认输,不混进成功率。
 *
 * <p>次优比只在数据集里有答案文件时才有这一列(没有答案文件是正常状态,不是错误):报表头写明答案是哪个提交算的;只取标准答案是最优价的行
 * ({@link Row#optimal}),已知最好的只是上界,算进去会让次优比偏小。时间都是按表折算的毫秒(不是墙钟),与机器快慢无关。
 */
final class Report {

    /** 分组用的标签:{@code null} 是不分标签的全部。 */
    private static final List<Problem.Tag> TAGS = List.of(Problem.Tag.WATER, Problem.Tag.ROUGH, Problem.Tag.DIG, Problem.Tag.UNDERGROUND);

    private Report() {}

    static String summary(List<Row> rows, String split, String commit, Optional<String> answersCommit) {
        boolean priced = answersCommit.isPresent();
        StringBuilder md = new StringBuilder();
        md.append("# 寻路规划评测\n\n提交 `").append(commit).append("`,题集 ").append(split).append(",").append(rows.size())
                .append(" 行(题 × 规格)。时间是按表折算的毫秒,不是墙钟。四个数依次是 中位/P90/P99/最大。\n\n");
        md.append(answersCommit.map(c -> "标准答案由提交 `" + c + "` 算出。\n\n").orElse("数据集里没有答案文件:没有次优比这一列。\n\n"));
        md.append("## 可达的题(按 档 × 规格 × 标签)\n\n");
        md.append("| 档 | 规格 | 标签 | 题数 | 到了 | 成功率 [Wilson 95%] | 起步 ms | 单次最长思考 ms | 展开数 |" + (priced ? " 次优比(最优价 n) |" : "") + " 挖 | 垫 |\n");
        md.append("|---|---|---|---:|---:|---|---|---|---|" + (priced ? "---|" : "") + "---:|---:|\n");
        for (Spec spec : Spec.values()) {
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                for (int t = -1; t < TAGS.size(); t++) {
                    Problem.Tag tag = t < 0 ? null : TAGS.get(t);
                    List<Row> group = select(rows, r -> !r.unsolvable() && r.spec() == spec && r.bucket() == bucket && (tag == null || r.tags().contains(tag)));
                    if (!group.isEmpty()) {
                        md.append(line(bucket, spec, tag == null ? "all" : tag.label, group, priced));
                    }
                }
            }
        }
        md.append("\n## 无解的题(按构造到不了)\n\n到了的应当是 0;认输用的时间是整趟的虚拟耗时。\n\n");
        md.append("| 档 | 规格 | 题数 | 错到了 | 认输 ms | 展开数 |\n|---|---|---:|---:|---|---|\n");
        for (Spec spec : Spec.values()) {
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                List<Row> group = select(rows, r -> r.unsolvable() && r.spec() == spec && r.bucket() == bucket);
                if (!group.isEmpty()) {
                    md.append(String.format(Locale.ROOT, "| %s | %s | %d | %d | %s | %s |%n", bucket.label, spec, group.size(),
                            group.stream().filter(Row::arrived).count(), quad(group, Row::tripMs, "%.1f"), quad(group, r -> r.expanded(), "%.0f")));
                }
            }
        }
        return md.toString();
    }

    private static String line(Problem.Bucket bucket, Spec spec, String tag, List<Row> group, boolean priced) {
        int ok = (int) group.stream().filter(Row::arrived).count();
        double[] w = Stats.wilson(ok, group.size());
        List<Row> optimal = select(group, Row::optimal);
        String price = !priced ? "" : String.format(Locale.ROOT, " %s (%d) |", optimal.isEmpty() ? "-" : quad(optimal, Row::subopt, "%.3f"), optimal.size());
        return String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %.1f%% [%.1f, %.1f] | %s | %s | %s |%s %.2f | %.2f |%n", bucket.label, spec, tag,
                group.size(), ok, 100.0 * ok / group.size(), 100 * w[0], 100 * w[1], quad(group, Row::firstMoveMs, "%.1f"),
                quad(group, Row::maxThinkMs, "%.1f"), quad(group, r -> r.expanded(), "%.0f"), price,
                group.stream().mapToDouble(Row::dug).average().orElse(0), group.stream().mapToDouble(Row::placed).average().orElse(0));
    }

    static List<Row> select(List<Row> rows, Predicate<Row> keep) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (keep.test(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 中位 / P90 / P99 / 最大。 */
    static String quad(List<Row> group, ToDoubleFunction<Row> metric, String format) {
        List<Double> values = new ArrayList<>();
        group.forEach(r -> values.add(metric.applyAsDouble(r)));
        String f = format + "/" + format + "/" + format + "/" + format;
        return String.format(Locale.ROOT, f, Stats.percentile(values, 0.5), Stats.percentile(values, 0.9), Stats.percentile(values, 0.99),
                Stats.percentile(values, 1.0));
    }
}
