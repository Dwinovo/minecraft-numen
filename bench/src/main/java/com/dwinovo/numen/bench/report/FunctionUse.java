package com.dwinovo.numen.bench.report;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次运行里一个 API 函数用得怎样:她的程序调了它几次、失败的各是哪一种、第一次调它之前查了几次它的帮助、和之前一字不差又失败了几次。
 * 一个函数好不好用由这几样说——参数错({@code bad_argument})多说明签名或说明没写清,查帮助多说明索引里那一行不够。
 *
 * @param function         函数全名,{@code numen.work.dig}
 * @param calls            调了几次(参数读不成、当场失败的也算)
 * @param failures         失败的次数,按种类({@code bad_argument}、{@code out_of_reach}……)
 * @param helpLookups      第一次调它之前,{@code numen.api.help} 查它(或它的组、名字空间)的次数
 * @param repeatedFailures 和之前某次失败的调用写法一字不差、又失败了的次数
 */
public record FunctionUse(String function, int calls, Map<String, Integer> failures, int helpLookups,
                          int repeatedFailures) {

    public FunctionUse {
        failures = Map.copyOf(failures);
    }

    /** 一共失败了几次。 */
    public int failed() {
        return failures.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 这一种失败几次;没有是 0。 */
    public int failed(String kind) {
        return failures.getOrDefault(kind, 0);
    }

    /** 几次运行里同一个函数的用量加在一起,按函数名排。 */
    public static List<FunctionUse> total(List<Run> runs) {
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, Map<String, Integer>> kinds = new TreeMap<>();
        for (Run run : runs) {
            for (FunctionUse use : run.functions()) {
                int[] c = counts.computeIfAbsent(use.function(), f -> new int[3]);
                c[0] += use.calls();
                c[1] += use.helpLookups();
                c[2] += use.repeatedFailures();
                Map<String, Integer> k = kinds.computeIfAbsent(use.function(), f -> new TreeMap<>());
                use.failures().forEach((kind, n) -> k.merge(kind, n, Integer::sum));
            }
        }
        return counts.entrySet().stream().map(e -> new FunctionUse(e.getKey(), e.getValue()[0],
                kinds.get(e.getKey()), e.getValue()[1], e.getValue()[2])).toList();
    }
}
