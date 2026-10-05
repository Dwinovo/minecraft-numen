package com.dwinovo.numen.bench.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * 一份结果的 {@code summary.md}:自检(两种基线)一张表、真实模型每个场景一行、再是失败分布与每次失败的去处。
 * 只读 {@link Run},不看游戏。
 */
public final class Summary {

    /** 结果目录里汇总文件的名字。 */
    public static final String FILE = "summary.md";

    /** 汇总表里 pass^k 的 k。 */
    public static final int K = 3;

    private Summary() {}

    public static String markdown(String title, List<Run> runs) {
        StringBuilder md = new StringBuilder("# ").append(title).append("\n\n");
        md.append("- 提交:").append(distinct(runs, Run::commit)).append('\n');
        md.append("- 系统提示哈希:").append(distinct(runs, Run::promptHash)).append('\n');
        md.append("- 模型:").append(distinct(runs.stream().filter(Run::live).toList(), Run::model)).append('\n');
        md.append("- 记录:").append(Runs.FILE).append(",共 ").append(runs.size()).append(" 次\n\n");

        Map<String, List<Run>> byScenario = group(runs, r -> r.suite() + "/" + r.scenario());
        selfCheck(md, byScenario);
        live(md, byScenario);
        functions(md, runs.stream().filter(Run::live).toList());
        failures(md, runs.stream().filter(Run::live).toList());
        return md.toString();
    }

    /**
     * 每个 API 函数在真实模型的运行里用得怎样,调得多的在前:失败率与参数错率说签名与说明写清没有,调用前查帮助说索引里那一行够不够,
     * 重复失败说她有没有从回执里学到。
     */
    private static void functions(StringBuilder md, List<Run> live) {
        List<FunctionUse> uses = new ArrayList<>(FunctionUse.total(live));
        md.append("## 每个函数(真实模型)\n\n");
        if (uses.isEmpty()) {
            md.append("没有 API 调用的记录。\n\n");
            return;
        }
        uses.sort(java.util.Comparator.comparingInt(FunctionUse::calls).reversed()
                .thenComparing(FunctionUse::function));
        md.append("| 函数 | 调用 | 失败率 | 参数错 | 其他失败 | 调用前查帮助 | 重复失败 |\n|---|---|---|---|---|---|---|\n");
        for (FunctionUse use : uses) {
            md.append(functionRow(use)).append('\n');
        }
        md.append('\n');
    }

    /** 一个函数的那一行。 */
    static String functionRow(FunctionUse use) {
        Map<String, Integer> others = new java.util.TreeMap<>(use.failures());
        others.remove("bad_argument");
        String other = others.isEmpty() ? "—" : others.entrySet().stream().map(e -> e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(", "));
        return "| " + String.join(" | ", List.of(use.function(), String.valueOf(use.calls()),
                pct(use.calls() == 0 ? Double.NaN : use.failed() / (double) use.calls()),
                String.valueOf(use.failed("bad_argument")), other, String.valueOf(use.helpLookups()),
                String.valueOf(use.repeatedFailures()))) + " |";
    }

    /** 两种基线:标准解必须全过、空操作必须全挂,否则这个场景的结论不可信。 */
    private static void selfCheck(StringBuilder md, Map<String, List<Run>> byScenario) {
        md.append("## 自检(不花 API)\n\n");
        md.append("| 场景 | 标准解 | 空操作 | 结论 |\n|---|---|---|---|\n");
        for (Map.Entry<String, List<Run>> e : byScenario.entrySet()) {
            List<Run> solution = of(e.getValue(), Variant.SOLUTION);
            List<Run> noop = of(e.getValue(), Variant.NOOP);
            md.append("| ").append(e.getKey()).append(" | ").append(ratio(solution)).append(" | ").append(ratio(noop))
                    .append(" | ").append(verdict(solution, noop)).append(" |\n");
        }
        md.append('\n');
    }

    /** 两种基线合在一起说这个场景可不可信。 */
    public static String verdict(List<Run> solution, List<Run> noop) {
        if (solution.isEmpty() || noop.isEmpty()) {
            return "没跑全";
        }
        List<String> wrong = new ArrayList<>();
        if (passes(solution) < solution.size()) {
            wrong.add("标准解没有全过");
        }
        if (passes(noop) > 0) {
            wrong.add("空操作骗过了断言");
        }
        return wrong.isEmpty() ? "可信" : String.join(";", wrong);
    }

    private static void live(StringBuilder md, Map<String, List<Run>> byScenario) {
        md.append("## 真实模型\n\n");
        md.append("| 场景 | 模型 | c/n | 成功率 [Wilson 95%] | pass^").append(K)
                .append(" | 子目标 | 中位轮数 | 中位命令数 | 命令出错 | 重复失败 | 征询 | token 未命中/命中/输出(每次) | 每次成功成本 "
                        + "| 中位游戏刻 | 中位墙钟 | 说完成没过 | 结束原因 | 失败类型 |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Map.Entry<String, List<Run>> e : byScenario.entrySet()) {
            Map<String, List<Run>> byModel = group(of(e.getValue(), Variant.LIVE), Run::model);
            for (Map.Entry<String, List<Run>> m : byModel.entrySet()) {
                md.append(row(e.getKey(), m.getKey(), m.getValue())).append('\n');
            }
        }
        md.append('\n');
    }

    /** 一个场景、一个模型的那一行。 */
    public static String row(String scenario, String model, List<Run> runs) {
        int n = runs.size();
        int c = passes(runs);
        Stats.Interval w = Stats.wilson(c, n, 1.96);
        double passK = n >= K ? Stats.passHatK(n, c, K) : Double.NaN;
        return "| " + String.join(" | ", List.of(
                scenario, model, c + "/" + n,
                pct(w.estimate()) + " [" + pct(w.low()) + ", " + pct(w.high()) + "]",
                Double.isNaN(passK) ? "—" : String.format(Locale.ROOT, "%.2f", passK),
                pct(mean(runs, Run::subgoalRatio)),
                num(median(runs, r -> r.turns())),
                num(median(runs, r -> r.toolCalls())),
                String.valueOf(runs.stream().mapToInt(Run::toolErrors).sum()),
                String.valueOf(runs.stream().mapToInt(Run::repeatedFailures).sum()),
                String.valueOf(runs.stream().mapToInt(Run::consents).sum()),
                tokens(mean(runs, r -> r.tokensMiss())) + " / " + tokens(mean(runs, r -> r.tokensHit())) + " / "
                        + tokens(mean(runs, r -> r.tokensOut())),
                costPerSuccess(runs, c),
                num(median(runs, r -> r.gameTicks())),
                num(median(runs, r -> r.wallMs() / 1000.0)) + "s",
                String.valueOf(runs.stream().filter(Run::claimedDone).count()),
                counts(runs, r -> EndReason.valueOf(r.end()).words()),
                counts(runs.stream().filter(r -> !r.passed()).toList(),
                        r -> r.tag() == null ? "待人工" : r.tag() + " " + FailureTag.valueOf(r.tag()).words())))
                + " |";
    }

    /** 每次成功花多少:这些次的总花费摊到成功的次数上;没配单价是 —,一次都没成是"无成功"并附总花费。 */
    static String costPerSuccess(List<Run> runs, int successes) {
        if (runs.isEmpty() || runs.stream().anyMatch(r -> r.cost() == null)) {
            return "—";
        }
        double total = runs.stream().mapToDouble(Run::cost).sum();
        String currency = runs.get(0).currency();
        if (successes == 0) {
            return "无成功(共 " + money(total) + " " + currency + ")";
        }
        return money(total / successes) + " " + currency;
    }

    private static void failures(StringBuilder md, List<Run> live) {
        List<Run> failed = live.stream().filter(r -> !r.passed()).toList();
        md.append("## 失败分布\n\n");
        if (failed.isEmpty()) {
            md.append(live.isEmpty() ? "没有真实模型的运行。\n" : "没有失败。\n");
            return;
        }
        md.append("- 失败类型:").append(counts(failed,
                r -> r.tag() == null ? "待人工" : r.tag() + " " + FailureTag.valueOf(r.tag()).words())).append('\n');
        md.append("- 结束原因:").append(counts(failed, r -> EndReason.valueOf(r.end()).words())).append('\n');
        md.append("- 说完成没过:").append(failed.stream().filter(Run::claimedDone).count()).append(" 次\n\n");
        md.append("| 场景 | 第几次 | 结束原因 | 类型 | 第一条没过的断言 | 她最后说的话 | 记录 |\n|---|---|---|---|---|---|---|\n");
        for (Run r : failed) {
            String firstFailed = r.checks().stream().filter(ch -> !ch.passed())
                    .map(ch -> ch.name() + (ch.detail().isEmpty() ? "" : ":" + ch.detail()))
                    .findFirst().orElse(r.error() == null ? "" : r.error());
            md.append("| ").append(String.join(" | ", List.of(r.suite() + "/" + r.scenario(),
                    String.valueOf(r.attempt()), EndReason.valueOf(r.end()).words(),
                    r.tag() == null ? "待人工" : r.tag(), cell(firstFailed, 120), cell(r.finalWords(), 80),
                    r.transcript()))).append(" |\n");
        }
    }

    // ---- 小工具 ----

    /** 其中这一种变体的那几次。 */
    public static List<Run> of(List<Run> runs, Variant variant) {
        return runs.stream().filter(r -> Variant.of(r.variant()) == variant).toList();
    }

    static int passes(List<Run> runs) {
        return (int) runs.stream().filter(Run::passed).count();
    }

    private static String ratio(List<Run> runs) {
        return runs.isEmpty() ? "—" : passes(runs) + "/" + runs.size();
    }

    private static <K> Map<K, List<Run>> group(List<Run> runs, Function<Run, K> key) {
        Map<K, List<Run>> out = new LinkedHashMap<>();
        for (Run r : runs) {
            out.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    private static String distinct(List<Run> runs, Function<Run, String> field) {
        Set<String> values = new LinkedHashSet<>();
        runs.forEach(r -> values.add(field.apply(r)));
        return values.isEmpty() ? "—" : String.join("、", values);
    }

    private static String counts(List<Run> runs, Function<Run, String> key) {
        if (runs.isEmpty()) {
            return "—";
        }
        return group(runs, key).entrySet().stream().map(e -> e.getKey() + "×" + e.getValue().size())
                .collect(Collectors.joining(", "));
    }

    private static double mean(List<Run> runs, ToDoubleFunction<Run> f) {
        return runs.stream().mapToDouble(f).average().orElse(Double.NaN);
    }

    private static double median(List<Run> runs, ToDoubleFunction<Run> f) {
        return Stats.median(runs.stream().map(r -> f.applyAsDouble(r)).toList());
    }

    static String pct(double v) {
        return Double.isNaN(v) ? "—" : Math.round(v * 100) + "%";
    }

    static String num(double v) {
        if (Double.isNaN(v)) {
            return "—";
        }
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    static String tokens(double v) {
        if (Double.isNaN(v)) {
            return "—";
        }
        return v >= 1000 ? String.format(Locale.ROOT, "%.1fk", v / 1000) : String.valueOf(Math.round(v));
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    /** 放进表格的一格:竖线与换行会拆坏表格,换掉;太长截断。 */
    static String cell(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}
