package com.dwinovo.numen.bench.report;

import java.util.List;
import java.util.Set;

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
     * 只读、不动世界的那几组:她"看"用的就是它们。{@code numen.gui.view} 是读开着的那扇窗,同一类;
     * 组里别的函数(往窗口里搬东西)改世界,所以这一条写到函数名。
     */
    private static final Set<String> PERCEPTION = Set.of("numen.scan.", "numen.status.", "numen.locate.",
            "numen.gui.view");

    /**
     * 动了世界或动了身上东西的那几组。走路不在里面:她走到哪儿不改世界,而"主人给了坐标让她走过去"本来
     * 就不需要先看一眼,算进来会把寻路的失败误判成感知的。
     */
    private static final Set<String> CHANGES_WORLD = Set.of("numen.work.", "numen.build.", "numen.use.",
            "numen.fight.", "numen.creative.", "numen.gear.", "numen.inv.craft", "numen.inv.eat", "numen.inv.drop");

    /**
     * 规则判得出的那几种:评测出错是 H;API 错、超上下文是 G;有一行命令写错了(命令树读不通、参数不合)是 D;
     * 动了世界却一次都没看过世界是 B。通过的、判不出的给 null。
     *
     * @param commandError 这次里有没有哪个工具结果是命令写错了
     * @param functions    她的程序用到的每个函数({@link Run#functions});{@code null} 当没有
     */
    public static FailureTag auto(boolean passed, EndReason end, boolean commandError, List<FunctionUse> functions) {
        if (passed) {
            return null;
        }
        return switch (end) {
            case HARNESS_ERROR -> H;
            case API_ERROR, CONTEXT_OVERFLOW -> G;
            default -> commandError ? D : blind(functions) ? B : null;
        };
    }

    /**
     * 她挖了、放了、用了、打了,却整次运行一次都没看过世界——照着脑子里想当然的样子动手。看了却看错的这条规则判不出来,
     * 那要读记录,留给人工。
     */
    private static boolean blind(List<FunctionUse> functions) {
        if (functions == null) {
            return false;
        }
        boolean acted = false;
        for (FunctionUse use : functions) {
            if (use.calls() <= 0) {
                continue;
            }
            if (matches(use.function(), PERCEPTION)) {
                return false;
            }
            acted |= matches(use.function(), CHANGES_WORLD);
        }
        return acted;
    }

    private static boolean matches(String function, Set<String> names) {
        return names.stream().anyMatch(function::startsWith);
    }
}
