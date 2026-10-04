package com.dwinovo.numen.bench.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 两份结果的对比(改了命令、提示词、回执之后,或者换了模型):只看真实模型的运行,按场景配对算成功率的差值,
 * 场景层 bootstrap 给差值均值的 95% 置信区间,列出由过变挂、由挂变过的场景。一个场景"过"指过半数的次数成功。
 *
 * <p>命令行:{@code Compare <前一份的目录或 runs.jsonl> <后一份的> [输出文件]};不给输出文件就打到标准输出。
 */
public final class Compare {

    /** bootstrap 重抽的轮数与种子:同样两份结果给同样的区间。 */
    static final int ROUNDS = 10_000;
    static final long SEED = 20260930L;

    private Compare() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Compare <before dir|runs.jsonl> <after dir|runs.jsonl> [output.md]");
            System.exit(2);
        }
        String md = markdown(args[0], Runs.read(Path.of(args[0])), args[1], Runs.read(Path.of(args[1])));
        if (args.length > 2) {
            Files.writeString(Path.of(args[2]), md, StandardCharsets.UTF_8);
        } else {
            System.out.print(md);
        }
    }

    public static String markdown(String beforeName, List<Run> before, String afterName, List<Run> after) {
        Map<String, List<Run>> a = liveByScenario(before);
        Map<String, List<Run>> b = liveByScenario(after);
        Set<String> scenarios = new LinkedHashSet<>(a.keySet());
        scenarios.retainAll(b.keySet());

        StringBuilder md = new StringBuilder("# 对比\n\n");
        md.append("- 前:").append(beforeName).append("(").append(describe(before)).append(")\n");
        md.append("- 后:").append(afterName).append("(").append(describe(after)).append(")\n\n");
        md.append("| 场景 | 前 c/n | 后 c/n | 差值 | 前 token/次 | 后 token/次 |\n|---|---|---|---|---|---|\n");
        double[] rateA = new double[scenarios.size()];
        double[] rateB = new double[scenarios.size()];
        List<String> broke = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        int i = 0;
        for (String s : scenarios) {
            List<Run> ra = a.get(s);
            List<Run> rb = b.get(s);
            rateA[i] = rate(ra);
            rateB[i] = rate(rb);
            md.append("| ").append(String.join(" | ", List.of(s,
                    Summary.passes(ra) + "/" + ra.size(), Summary.passes(rb) + "/" + rb.size(),
                    signedPct(rateB[i] - rateA[i]), Summary.tokens(tokensPerRun(ra)),
                    Summary.tokens(tokensPerRun(rb))))).append(" |\n");
            if (majority(ra) && !majority(rb)) {
                broke.add(s);
            } else if (!majority(ra) && majority(rb)) {
                fixed.add(s);
            }
            i++;
        }
        Stats.Interval diff = Stats.pairedBootstrap(rateA, rateB, ROUNDS, 0.95, SEED);
        md.append("\n- 成功率差值(后 − 前)均值:").append(signedPct(diff.estimate()))
                .append(",95% 区间 [").append(signedPct(diff.low())).append(", ").append(signedPct(diff.high()))
                .append("](场景层 bootstrap,").append(scenarios.size()).append(" 个场景配对)\n");
        md.append("- 由过变挂:").append(broke.isEmpty() ? "无" : String.join("、", broke)).append('\n');
        md.append("- 由挂变过:").append(fixed.isEmpty() ? "无" : String.join("、", fixed)).append('\n');
        List<String> onlyOne = new ArrayList<>();
        a.keySet().stream().filter(s -> !b.containsKey(s)).forEach(s -> onlyOne.add(s + "(只在前)"));
        b.keySet().stream().filter(s -> !a.containsKey(s)).forEach(s -> onlyOne.add(s + "(只在后)"));
        if (!onlyOne.isEmpty()) {
            md.append("- 没配上对的场景:").append(String.join("、", onlyOne)).append('\n');
        }
        functions(md, live(before), live(after));
        return md.toString();
    }

    /** 每个 API 函数前后用得怎样:调用、失败率、参数错率、调用前查帮助的次数;只在一边出现的那一边记为 —。 */
    private static void functions(StringBuilder md, List<Run> before, List<Run> after) {
        Map<String, FunctionUse> a = byFunction(before);
        Map<String, FunctionUse> b = byFunction(after);
        Set<String> names = new java.util.TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        if (names.isEmpty()) {
            return;
        }
        md.append("\n## 每个函数\n\n| 函数 | 前 调用 | 后 调用 | 前 失败率 | 后 失败率 | 前 参数错率 | 后 参数错率 "
                + "| 前 查帮助/调用 | 后 查帮助/调用 |\n|---|---|---|---|---|---|---|---|---|\n");
        for (String name : names) {
            FunctionUse x = a.get(name);
            FunctionUse y = b.get(name);
            md.append("| ").append(String.join(" | ", List.of(name, calls(x), calls(y),
                    ratio(x, x == null ? 0 : x.failed()), ratio(y, y == null ? 0 : y.failed()),
                    ratio(x, x == null ? 0 : x.failed("bad_argument")), ratio(y, y == null ? 0 : y.failed("bad_argument")),
                    ratio(x, x == null ? 0 : x.helpLookups()), ratio(y, y == null ? 0 : y.helpLookups()))))
                    .append(" |\n");
        }
    }

    private static List<Run> live(List<Run> runs) {
        return runs.stream().filter(Run::live).toList();
    }

    private static Map<String, FunctionUse> byFunction(List<Run> runs) {
        Map<String, FunctionUse> out = new LinkedHashMap<>();
        FunctionUse.total(runs).forEach(use -> out.put(use.function(), use));
        return out;
    }

    private static String calls(FunctionUse use) {
        return use == null ? "—" : String.valueOf(use.calls());
    }

    /** 这一项摊到每次调用上。 */
    private static String ratio(FunctionUse use, int count) {
        return use == null || use.calls() == 0 ? "—" : Summary.pct(count / (double) use.calls());
    }

    private static Map<String, List<Run>> liveByScenario(List<Run> runs) {
        Map<String, List<Run>> out = new LinkedHashMap<>();
        for (Run r : runs) {
            if (r.live()) {
                out.computeIfAbsent(r.suite() + "/" + r.scenario(), k -> new ArrayList<>()).add(r);
            }
        }
        return out;
    }

    private static String describe(List<Run> runs) {
        Set<String> commits = new LinkedHashSet<>();
        Set<String> models = new LinkedHashSet<>();
        for (Run r : runs) {
            commits.add(r.commit());
            if (r.live()) {
                models.add(r.model());
            }
        }
        return "提交 " + String.join("、", commits) + ",模型 " + (models.isEmpty() ? "—" : String.join("、", models));
    }

    private static double rate(List<Run> runs) {
        return runs.isEmpty() ? 0 : Summary.passes(runs) / (double) runs.size();
    }

    /** 过半数的次数成功。 */
    static boolean majority(List<Run> runs) {
        return Summary.passes(runs) * 2 > runs.size();
    }

    private static double tokensPerRun(List<Run> runs) {
        return runs.stream().mapToLong(r -> r.tokensMiss() + r.tokensHit() + r.tokensOut()).average().orElse(Double.NaN);
    }

    private static String signedPct(double v) {
        if (Double.isNaN(v)) {
            return "—";
        }
        long p = Math.round(v * 100);
        return String.format(Locale.ROOT, "%s%d%%", p > 0 ? "+" : "", p);
    }
}
