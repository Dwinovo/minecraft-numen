package com.dwinovo.numen.pathing.benchmark;

import java.util.Arrays;
import java.util.List;

/** 报表用的统计:成功率的 Wilson 区间、分位数。 */
final class Stats {

    /** 95% 置信水平的标准正态分位数。 */
    private static final double Z = 1.959964;

    private Stats() {}

    /** {@code k} 次成功、共 {@code n} 次的成功率的 Wilson 95% 区间 {@code [下限, 上限]};{@code n == 0} 是 {@code [0, 1]}。 */
    static double[] wilson(int k, int n) {
        if (n == 0) {
            return new double[] {0, 1};
        }
        double p = (double) k / n;
        double z2 = Z * Z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denom;
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[] {Math.max(0, center - half), Math.min(1, center + half)};
    }

    /** 最近秩法的分位数({@code p} 在 0..1);空表为 NaN。 */
    static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(1, Math.min(sorted.length, rank)) - 1];
    }
}
