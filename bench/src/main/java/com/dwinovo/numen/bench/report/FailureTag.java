package com.dwinovo.numen.bench.report;

/**
 * 失败分类。规则判得出的自动打({@link #auto}),其余留空,由人读记录后补。
 */
public enum FailureTag {
    A("理解"),
    B("感知"),
    C("规划"),
    D("执行参数"),
    E("监控核验"),
    F("恢复"),
    G("系统环境"),
    H("评测自身");

    private final String words;

    FailureTag(String words) {
        this.words = words;
    }

    public String words() {
        return words;
    }

    /**
     * 规则判得出的那几种:评测出错是 H;API 错、超上下文是 G;有一行命令写错了(命令树读不通、参数不合)是 D。
     * 通过的、判不出的给 null。
     *
     * @param commandError 这次里有没有哪个工具结果是命令写错了
     */
    public static FailureTag auto(boolean passed, EndReason end, boolean commandError) {
        if (passed) {
            return null;
        }
        return switch (end) {
            case HARNESS_ERROR -> H;
            case API_ERROR, CONTEXT_OVERFLOW -> G;
            default -> commandError ? D : null;
        };
    }
}
