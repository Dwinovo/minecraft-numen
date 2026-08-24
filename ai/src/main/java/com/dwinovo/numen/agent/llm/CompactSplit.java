package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.List;

/**
 * 压缩的切分:把历史分成"要总结的旧段"和"原文保留的近段"。参考 pi 的做法——
 * 触发线不变,但摘要只替换旧段,最近约 {@code budget} tokens 的消息逐字跨过压缩边界,
 * 主人刚说的话和她刚给的回执不会被压成转述。
 *
 * <h2>切点规则</h2>
 * 保留段的第一条只能是 User(轮边界,首选)或 Assistant(单轮超预算时的劈轮点);
 * 永远不能是 Tool——工具结果必须跟着它的调用走,拆开的历史下一次请求就是 400。
 * 预算内找不到任何合法切点(极端:一条消息就超预算)时保留段为空,退化成全量总结。
 *
 * <h2>token 估算</h2>
 * 与自动压缩闸门的兜底估算同一把尺(全仓唯一一份):CJK 约 1 token/字,ASCII 约
 * 4 字符/token,每条消息记 8 token 的结构开销。精度不是目标,预算的粗粒度吸收误差。
 */
public final class CompactSplit {

    private CompactSplit() {}

    /** 切分结果:{@code toSummarize} 交给摘要请求,{@code kept} 原文保留在摘要之后。 */
    public record Split(List<ConvoState.Msg> toSummarize, List<ConvoState.Msg> kept) {}

    /**
     * 从最新往回攒,攒到 {@code budgetTokens} 为止;在预算内选<b>最早的</b>合法切点。
     * 整段历史都在预算内时 {@code toSummarize} 为空——调用方自行决定退化行为。
     */
    public static Split byRecentBudget(List<ConvoState.Msg> history, int budgetTokens) {
        int cutUser = -1;
        int cutAssistant = -1;
        long acc = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            acc += estimateTokens(history.get(i));
            if (acc > budgetTokens) {
                break;
            }
            if (history.get(i) instanceof ConvoState.Msg.User) {
                cutUser = i;
            } else if (history.get(i) instanceof ConvoState.Msg.Assistant) {
                cutAssistant = i;
            }
        }
        int cut = cutUser >= 0 ? cutUser : cutAssistant >= 0 ? cutAssistant : history.size();
        return new Split(List.copyOf(history.subList(0, cut)),
                List.copyOf(history.subList(cut, history.size())));
    }

    /** 一条消息的粗略 token 数(含 8 token 的角色/结构开销)。 */
    public static int estimateTokens(ConvoState.Msg msg) {
        String text;
        if (msg instanceof ConvoState.Msg.User u) {
            text = u.content();
        } else if (msg instanceof ConvoState.Msg.Tool t) {
            text = t.content();
        } else if (msg instanceof ConvoState.Msg.Assistant a) {
            StringBuilder sb = new StringBuilder(
                    a.turn().content() == null ? "" : a.turn().content());
            for (LlmToolCall tc : a.turn().toolCalls()) {
                sb.append(tc.name()).append(tc.arguments());
            }
            text = sb.toString();
        } else {
            text = "";
        }
        long cjk = 0, ascii = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 0x2E7F) cjk++; else ascii++;
            }
        }
        return (int) (cjk + ascii / 4 + 8);
    }

    /** 整段历史的粗略 token 数(不含系统提示/工具表的固定开销,那份由调用方加)。 */
    public static int estimateTokens(List<ConvoState.Msg> history) {
        long sum = 0;
        for (ConvoState.Msg m : history) {
            sum += estimateTokens(m);
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }
}
