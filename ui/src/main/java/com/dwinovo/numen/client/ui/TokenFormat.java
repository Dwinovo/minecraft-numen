package com.dwinovo.numen.client.ui;

import java.util.Locale;

/**
 * token 数的人读格式——**全仓唯一一份**。页脚、物品页、任何要显示 token 的地方都走它:
 * 各处各写一份的话,同一个数在两个界面上会显示成不同的样子。
 *
 * <p>分档规则:小数只在数字本身短的时候给,数字一大就没必要了——
 * {@code 999} → {@code 999}、{@code 1234} → {@code 1.2k}、{@code 132400} → {@code 132k}、
 * {@code 1_200_000} → {@code 1.2M}、{@code 12_000_000} → {@code 12M}。
 */
public final class TokenFormat {

    private TokenFormat() {}

    public static String tokens(long count) {
        if (count < 1_000) return Long.toString(count);
        if (count < 10_000) return trim1(count / 1_000.0) + "k";
        if (count < 1_000_000) return Math.round(count / 1_000.0) + "k";
        if (count < 10_000_000) return trim1(count / 1_000_000.0) + "M";
        return Math.round(count / 1_000_000.0) + "M";
    }

    /** 百分比,一位小数——{@code 0.873} → {@code "87.3"}。 */
    public static String percent1(double ratio) {
        return trim1(ratio * 100);
    }

    private static String trim1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
