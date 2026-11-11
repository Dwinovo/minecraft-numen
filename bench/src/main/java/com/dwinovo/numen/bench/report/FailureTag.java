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
     * 这几种结局是"照这次的规格想不出路来":搜遍了没有、预算用完、憋不住气、没料可垫、要改的格数超了规格。
     * 执行期受阻({@code Blocked}、{@code Grounded}、{@code NoLineOfSight})与待不住({@code Stranded})不在里面——
     * 那几种判不出是规划还是恢复,留给人工。
     */
    private static final Set<String> CANNOT_PLAN = Set.of("NoRoute", "OutOfBudget", "Breathless", "NoMaterials",
            "OverAlterBudget", "NeedsChanges");

    /** 场景把目标圈在她本来就不许进的地方:这是评测自己设错了,不是她的问题。 */
    private static final Set<String> SCENARIO_FENCED = Set.of("EXCLUDED", "FORBIDDEN");

    /**
     * 规则判得出的那几种:评测出错、场景把路封死了是 H;API 错、超上下文是 G;够不着是 D;照规格想不出路来是 C;
     * 她说做完了却没通过是 E;整次运行里有一次调用写错、或者真实模型动了世界却一次都没看过世界,是 D 与 B。
     * 通过的、判不出的给 null。
     *
     * @param live         这次是真实模型跑的({@link Run#live})
     * @param commandError 这次里有没有哪个工具结果是命令写错了
     * @param claimedDone  她自己收了工、断言却没过——嘴上说完成了
     * @param functions    她的程序用到的每个函数({@link Run#functions});{@code null} 当没有
     * @param path         她最后一趟路怎么收的场;一趟都没走过是 {@code null}
     */
    public static FailureTag auto(boolean passed, EndReason end, boolean live, boolean commandError,
                                  boolean claimedDone, List<FunctionUse> functions, PathEnd path) {
        if (passed) {
            return null;
        }
        return switch (end) {
            case HARNESS_ERROR -> H;
            case API_ERROR, CONTEXT_OVERFLOW -> G;
            default -> fromEvidence(live, end, commandError, claimedDone, functions, path);
        };
    }

    /**
     * 按证据从具体到笼统地判。轨迹说的是这一趟自己的事,排在最前;她说做完了却没通过,是她的自我评价出了错;
     * "整次运行里出过一次调用写错""整次一次都没看过世界"是整次运行的毛病,垫在最后——它们只说明哪里出过事,
     * 不说明败在哪。
     */
    private static FailureTag fromEvidence(boolean live, EndReason end, boolean commandError, boolean claimedDone,
                                           List<FunctionUse> functions, PathEnd path) {
        String reason = path == null ? null : path.reason();
        String outcome = path == null ? null : path.outcome();
        if (reason != null && SCENARIO_FENCED.contains(reason)) {
            return H;
        }
        if ("OUT_OF_REACH".equals(reason)) {
            return D;
        }
        if (outcome != null && CANNOT_PLAN.contains(outcome)) {
            return C;
        }
        // 只有她自己收的工才算"说做完了";轮数、刻数用完时她说的话不算数,那几种的毛病在别处。
        // 也只对真实模型问:基线的收场话是剧本写死的,不是她的自我评价
        if (live && claimedDone && end == EndReason.DONE) {
            return E;
        }
        if (commandError) {
            return D;
        }
        return live && blind(functions) ? B : null;
    }

    /**
     * 她挖了、放了、用了、打了,却整次运行一次都没看过世界——照着脑子里想当然的样子动手。只对真实模型问这一句:
     * 两种基线是写死的脚本,坐标在剧本里,本来就不必看,拿它们问"看没看过"是问错了对象。看了却看错的这条规则
     * 判不出来,那要读记录,留给人工。
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
