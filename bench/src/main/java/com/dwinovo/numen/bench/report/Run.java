package com.dwinovo.numen.bench.report;

import java.util.List;
import java.util.Map;

/**
 * 一次评测的全部记录,{@code runs.jsonl} 里的一行。报告与对比只读它,不回头看游戏。
 *
 * <p>不含模型的思考:评测从不读、也不落任何思考流。她对主人说的话({@link #finalWords})与工具结果的前 200 字在
 * {@link #transcript} 指的那份记录里。
 *
 * @param suite        场景属于哪一组(原版、各个联动)
 * @param scenario     场景名
 * @param variant      {@link Variant} 的名字:标准解、空操作,或真实模型
 * @param attempt      这一变体下的第几次,从 1 数
 * @param commit       跑的是哪个提交(工作区有改动时带 {@code +dirty})
 * @param promptHash   这次的系统提示的哈希(十二位十六进制):提示词一改就变,对比时认得出
 * @param model        {@code 服务商/模型};基线是变体名
 * @param passed       成功断言全过、负面断言全过
 * @param checks       每条断言的结论与说明
 * @param subgoals     子目标各自达成没有
 * @param end          {@link EndReason} 的名字
 * @param turns        调了几次模型(一次 run 里的对话调用)
 * @param toolCalls    派了几个工具调用
 * @param toolErrors   其中结果是失败的
 * @param repeatedFailures 和之前某个失败的调用一字不差、又失败了的次数
 * @param consents     身体向主人征询了几次
 * @param tokensMiss   提示词里缓存没命中的 token
 * @param tokensHit    提示词里缓存命中的 token
 * @param tokensOut    输出 token
 * @param cost         按单价折的花费;没配单价是 null
 * @param currency     花费的币种;没配单价是 null
 * @param wallMs       墙钟毫秒
 * @param gameTicks    游戏刻
 * @param claimedDone  她自己收了工,断言却没过——嘴上说完成了
 * @param tag          失败分类({@link FailureTag} 的字母);通过、或规则判不出的是 null,留给人工
 * @param finalWords   她最后对主人说的话;一句都没说是空串
 * @param transcript   这次的记录文件,相对结果目录
 * @param error        评测出错或 API 出错时的原话;其余为 null
 * @param metrics      场景自己记的数(如收场时主人剩多少血),只是指标、不决定成败;更早的记录里没有这一项,读进来是空表
 * @param functions    她的程序用到的每个 API 函数用得怎样,按函数名;更早的记录里没有这一项,读进来是空表
 */
public record Run(String suite, String scenario, String variant, int attempt, String commit, String promptHash,
                  String model, boolean passed, List<Check> checks, List<Check> subgoals, String end,
                  int turns, int toolCalls, int toolErrors, int repeatedFailures, int consents,
                  long tokensMiss, long tokensHit, long tokensOut, Double cost, String currency,
                  long wallMs, long gameTicks, boolean claimedDone, String tag, String finalWords,
                  String transcript, String error, Map<String, Double> metrics, List<FunctionUse> functions) {

    public Run {
        functions = functions == null ? List.of() : List.copyOf(functions);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    /**
     * 一条断言或一个子目标的结论。
     *
     * @param kind   {@code success}(成功断言)、{@code guard}(负面断言)或 {@code subgoal}
     * @param detail 没过时说明看到了什么;过了为空串
     */
    public record Check(String name, String kind, boolean passed, String detail) {}

    /** 这一次是真实模型跑的(不是两种基线)。 */
    public boolean live() {
        return Variant.of(variant) == Variant.LIVE;
    }

    /** 子目标达成的比例;没有子目标是 1。 */
    public double subgoalRatio() {
        if (subgoals == null || subgoals.isEmpty()) {
            return 1.0;
        }
        return subgoals.stream().filter(Check::passed).count() / (double) subgoals.size();
    }
}
