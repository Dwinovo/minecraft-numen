package com.dwinovo.numen.pathing.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * 分档报表。主表按 规格 × 档 × 终点种类(地表、洞穴、空腔、封闭的一格)分组,次表按 规格 × 档 × 标签;总表只作补充。每组报:
 * 结局的四类计数(到了 / 证明到不了 / 区域外 / 放弃,见 {@link Row.Outcome})、成功率 = 到了 ÷ (到了 + 放弃)(附 Wilson 95% 区间;证明到不了的、要区域之外地形的不是规划的失败,不进分母)、
 * 起步耗时、单次最长思考、展开数、次优比各自的中位 / P90 / P99 / 最大(只取到了或放弃的行),以及挖、垫块数的平均。证明到不了的另有一张表,
 * 量证出来用了多久。
 *
 * <p>次优比只在数据集里有答案文件时才有这一列(没有答案文件是正常状态,不是错误):报表头写明答案是哪个提交算的;只取标准答案是最优价的行
 * ({@link Row#optimal}),已知最好的只是上界,算进去会让次优比偏小。时间都是按表折算的毫秒(不是墙钟),与机器快慢无关。
 */
final class Report {

    /** 次表用的标签。 */
    private static final List<Problem.Tag> TAGS = List.of(Problem.Tag.WATER, Problem.Tag.ROUGH, Problem.Tag.DIG, Problem.Tag.UNDERGROUND);

    private Report() {}

    /**
     * @param notApplicable 数据集里按构造不适用于规格 A 的题数(需要编辑的:挖到某格、走到封死的一格),没有给评测跑
     */
    static String summary(List<Row> rows, String split, String commit, Optional<String> answersCommit, int notApplicable) {
        boolean priced = answersCommit.isPresent();
        StringBuilder md = new StringBuilder();
        md.append("# 寻路规划评测\n\n提交 `").append(commit).append("`,题集 ").append(split).append(",").append(rows.size())
                .append(" 行(题 × 规格)。时间是按表折算的毫秒,不是墙钟。四个数依次是 中位/P90/P99/最大。\n\n");
        md.append(answersCommit.map(c -> "标准答案由提交 `" + c + "` 算出。\n\n").orElse("数据集里没有答案文件:没有次优比这一列。\n\n"));
        md.append("结局四类:到了;证明到不了(一次搜索把能走到的都搜完了没有目标,没读到区域边缘或快照边缘外的列;不是规划的失败,不进成功率);")
                .append("区域外(没到,有一次搜索停在没加载、它的窗口伸出了区域:评测的固定世界在区域之外永远不加载,游戏里区块随身体走动加载;")
                .append(answersCommit.isPresent() ? "标准答案说区域内有解或无解的不算这一类;" : "").append("不进成功率);放弃(别的一切)。")
                .append("成功率 = 到了 ÷ (到了 + 放弃)。规格 A 不挖不垫,需要编辑的题(挖到某格、走到封死的一格)按构造不适用于 A,没有给 A 跑:本题集 ")
                .append(notApplicable).append(" 道。\n\n");
        md.append("## 主表:按 规格 × 档 × 终点种类\n\n").append(header("终点", priced));
        for (Spec spec : Spec.values()) {
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                for (Problem.EndClass end : Problem.EndClass.values()) {
                    append(md, bucket, spec, end.name().toLowerCase(), select(rows, r -> r.spec() == spec && r.bucket() == bucket && r.endClass() == end), priced);
                }
                append(md, bucket, spec, "总(补充)", select(rows, r -> r.spec() == spec && r.bucket() == bucket), priced);
            }
        }
        md.append("\n## 证明到不了的(用了多久证出来)\n\n| 规格 | 档 | 终点 | 题数 | 证出来 ms | 展开数 |\n|---|---|---|---:|---|---|\n");
        for (Spec spec : Spec.values()) {
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                for (Problem.EndClass end : Problem.EndClass.values()) {
                    List<Row> group = select(rows, r -> r.outcome() == Row.Outcome.UNREACHABLE && r.spec() == spec && r.bucket() == bucket && r.endClass() == end);
                    if (!group.isEmpty()) {
                        md.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s | %s |%n", spec, bucket.label, end.name().toLowerCase(), group.size(),
                                quad(group, Row::tripMs, "%.1f"), quad(group, r -> r.expanded(), "%.0f")));
                    }
                }
            }
        }
        md.append("\n## 次表:按 规格 × 档 × 标签\n\n").append(header("标签", priced));
        for (Spec spec : Spec.values()) {
            for (Problem.Bucket bucket : Problem.Bucket.values()) {
                for (Problem.Tag tag : TAGS) {
                    append(md, bucket, spec, tag.label, select(rows, r -> r.spec() == spec && r.bucket() == bucket && r.tags().contains(tag)), priced);
                }
            }
        }
        return md.toString();
    }

    private static String header(String group, boolean priced) {
        return "| 规格 | 档 | " + group + " | 题数 | 到了 | 证明到不了 | 区域外 | 放弃 | 成功率 [Wilson 95%] | 起步 ms | 单次最长思考 ms | 展开数 |" + (priced ? " 次优比(最优价 n) |" : "")
                + " 挖 | 垫 |\n|---|---|---|---:|---:|---:|---:|---:|---|---|---|---|" + (priced ? "---|" : "") + "---:|---:|\n";
    }

    private static void append(StringBuilder md, Problem.Bucket bucket, Spec spec, String label, List<Row> group, boolean priced) {
        if (!group.isEmpty()) {
            md.append(line(bucket, spec, label, group, priced));
        }
    }

    private static String line(Problem.Bucket bucket, Spec spec, String label, List<Row> group, boolean priced) {
        List<Row> attempted = select(group, Row::attempted);
        int arrived = (int) attempted.stream().filter(Row::arrived).count();
        int unreachable = (int) group.stream().filter(r -> r.outcome() == Row.Outcome.UNREACHABLE).count();
        int beyond = (int) group.stream().filter(r -> r.outcome() == Row.Outcome.BEYOND).count();
        String rate = attempted.isEmpty() ? "-" : String.format(Locale.ROOT, "%.1f%% [%.1f, %.1f]", 100.0 * arrived / attempted.size(),
                100 * Stats.wilson(arrived, attempted.size())[0], 100 * Stats.wilson(arrived, attempted.size())[1]);
        List<Row> optimal = select(group, Row::optimal);
        String price = !priced ? "" : String.format(Locale.ROOT, " %s (%d) |", optimal.isEmpty() ? "-" : quad(optimal, Row::subopt, "%.3f"), optimal.size());
        return String.format(Locale.ROOT, "| %s | %s | %s | %d | %d | %d | %d | %d | %s | %s | %s | %s |%s %.2f | %.2f |%n", spec, bucket.label, label, group.size(), arrived,
                unreachable, beyond, attempted.size() - arrived, rate, quad(attempted, Row::firstMoveMs, "%.1f"), quad(attempted, Row::maxThinkMs, "%.1f"),
                quad(attempted, r -> r.expanded(), "%.0f"), price, attempted.stream().mapToDouble(Row::dug).average().orElse(0),
                attempted.stream().mapToDouble(Row::placed).average().orElse(0));
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

    /** 中位 / P90 / P99 / 最大;空为四个 "-"。 */
    static String quad(List<Row> group, ToDoubleFunction<Row> metric, String format) {
        List<Double> values = new ArrayList<>();
        group.forEach(r -> values.add(metric.applyAsDouble(r)));
        if (values.isEmpty()) {
            return "-/-/-/-";
        }
        String f = format + "/" + format + "/" + format + "/" + format;
        return String.format(Locale.ROOT, f, Stats.percentile(values, 0.5), Stats.percentile(values, 0.9), Stats.percentile(values, 0.99),
                Stats.percentile(values, 1.0));
    }
}
