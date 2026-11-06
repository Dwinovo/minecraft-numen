package com.dwinovo.numen.api.agent.llm;

import com.google.gson.JsonObject;

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
 *
 * <p>量的是<b>真正发出去的内容</b>:助手消息按 {@link LlmEndpoint#assistantWireItems} 的线格式量,
 * 回传的思考(DeepSeek/Kimi 的 reasoning_content、Anthropic 的思考块)和工具调用都算进去,
 * 换了模型被脱掉的私货则不算——哪些情况回传思考只有发送层一处说了算。
 */
public final class CompactSplit {

    private CompactSplit() {}

    /**
     * 切分结果:{@code toSummarize} 交给摘要请求,{@code kept} 原文保留在摘要之后。切点劈在一轮中间(落在 Assistant 上)时,
     * 那一轮留在保留段之前的那半截是 {@code turnPrefix}:它单独做一份前缀摘要(照 pi 的 split turn),不和更早的历史混在一起;
     * 没劈轮时是空。
     */
    public record Split(List<ConvoState.Msg> toSummarize, List<ConvoState.Msg> turnPrefix, List<ConvoState.Msg> kept) {}

    /**
     * 从最新往回攒,攒到 {@code budgetTokens} 为止;在预算内选<b>最早的</b>合法切点。
     * 整段历史都在预算内时 {@code toSummarize} 为空——调用方自行决定退化行为。
     */
    public static Split byRecentBudget(List<ConvoState.Msg> history, int budgetTokens, LlmEndpoint endpoint) {
        int cutUser = -1;
        int cutAssistant = -1;
        long acc = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            acc += estimateTokens(history.get(i), endpoint);
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
        // 劈轮:切点前最近的一条 User 是这一轮的开头,从它到切点是前缀
        int turnStart = cut;
        if (cut < history.size() && !(history.get(cut) instanceof ConvoState.Msg.User)) {
            for (int i = cut - 1; i >= 0; i--) {
                if (history.get(i) instanceof ConvoState.Msg.User) {
                    turnStart = i;
                    break;
                }
            }
        }
        return new Split(List.copyOf(history.subList(0, turnStart)), List.copyOf(history.subList(turnStart, cut)),
                List.copyOf(history.subList(cut, history.size())));
    }

    /**
     * 一条消息的粗略 token 数(含 8 token 的角色/结构开销)。
     *
     * <p>{@link ConvoState.Msg.Halt} 本身不发,但它的原因会被 {@link ProtocolView} 写进补的工具结果
     * 或下一条 user 的说明行,所以按原因的字数算。
     */
    public static int estimateTokens(ConvoState.Msg msg, LlmEndpoint endpoint) {
        String text = switch (msg) {
            case ConvoState.Msg.User u -> u.content();
            case ConvoState.Msg.Tool t -> ToolOutcome.modelText(t.content());
            case ConvoState.Msg.Halt h -> h.reason();
            case ConvoState.Msg.Assistant a -> {
                StringBuilder sb = new StringBuilder();
                for (JsonObject item : endpoint.assistantWireItems(a.turn())) {
                    sb.append(item);
                }
                yield sb.toString();
            }
        };
        return estimateText(text) + 8;
    }

    /** 一段文字的粗略 token 数(不含结构开销):CJK 约 1 token/字,其余约 4 字符/token。全仓只有这一把尺。 */
    public static int estimateText(String text) {
        long cjk = 0, ascii = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 0x2E7F) cjk++; else ascii++;
            }
        }
        return (int) (cjk + ascii / 4);
    }

    /** 整段历史的粗略 token 数(不含系统提示/工具表的固定开销,那份由调用方加)。 */
    public static int estimateTokens(List<ConvoState.Msg> history, LlmEndpoint endpoint) {
        long sum = 0;
        for (ConvoState.Msg m : history) {
            sum += estimateTokens(m, endpoint);
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }
}
