package com.dwinovo.numen.bench.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 评测用到的统计,都是纯函数。 */
public final class Stats {

    private Stats() {}

    /** 一个区间:点估计与上下界。 */
    public record Interval(double estimate, double low, double high) {}

    /**
     * pass^k(τ-bench 的定义):同一个场景独立跑 k 次全都成功的概率,用 n 次里成功 c 次做无偏估计
     * {@code C(c,k) / C(n,k)}。它罚的是"有时成有时不成":成功率 2/3 的场景 pass^3 是 0。
     *
     * @return k 大于 n 时没有定义,给 {@link Double#NaN}
     */
    public static double passHatK(int n, int c, int k) {
        if (n < 0 || c < 0 || c > n || k < 0) {
            throw new IllegalArgumentException("n=" + n + " c=" + c + " k=" + k);
        }
        if (k > n) {
            return Double.NaN;
        }
        double p = 1.0;
        for (int i = 0; i < k; i++) {
            p *= (double) (c - i) / (n - i);
            if (p <= 0) {
                return 0.0;
            }
        }
        return p;
    }

    /**
     * 成功率的 Wilson 区间。次数少的时候正态近似的区间会越出 [0,1]、在 0/n 与 n/n 处缩成一个点,Wilson 不会。
     *
     * @param z 分位数,95% 是 1.96
     * @return n 为 0 时是 [0,1],点估计 NaN
     */
    public static Interval wilson(int c, int n, double z) {
        if (n == 0) {
            return new Interval(Double.NaN, 0.0, 1.0);
        }
        double p = (double) c / n;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new Interval(p, Math.max(0.0, center - half), Math.min(1.0, center + half));
    }

    /** 中位数;空的是 NaN。 */
    public static double median(List<? extends Number> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values.size());
        for (Number v : values) {
            sorted.add(v.doubleValue());
        }
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /**
     * 两份报告按场景配对的差值(后 − 前)的均值,与场景层 bootstrap 的百分位置信区间:有放回地重抽场景,每一轮算一次
     * 差值的均值,取 {@code (1−level)/2} 与 {@code (1+level)/2} 两个分位。重抽的是场景而不是单次运行——同一个场景的几次
     * 不独立,把它们当独立样本会把区间估窄。
     *
     * @param before 每个场景在前一份里的值(成功率)
     * @param after  同一个场景在后一份里的值,与 {@code before} 一一对应
     * @param rounds 重抽几轮
     * @param seed   随机种子:同样的输入给同样的区间
     */
    public static Interval pairedBootstrap(double[] before, double[] after, int rounds, double level, long seed) {
        if (before.length != after.length) {
            throw new IllegalArgumentException("paired samples differ in length");
        }
        int n = before.length;
        if (n == 0) {
            return new Interval(Double.NaN, Double.NaN, Double.NaN);
        }
        double[] diff = new double[n];
        double mean = 0;
        for (int i = 0; i < n; i++) {
            diff[i] = after[i] - before[i];
            mean += diff[i];
        }
        mean /= n;
        Random random = new Random(seed);
        double[] means = new double[rounds];
        for (int r = 0; r < rounds; r++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                sum += diff[random.nextInt(n)];
            }
            means[r] = sum / n;
        }
        java.util.Arrays.sort(means);
        return new Interval(mean, quantile(means, (1 - level) / 2), quantile(means, (1 + level) / 2));
    }

    /** 排好序的样本的分位数(线性插值)。 */
    static double quantile(double[] sorted, double q) {
        double at = q * (sorted.length - 1);
        int lo = (int) Math.floor(at);
        int hi = (int) Math.ceil(at);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (at - lo);
    }
}
