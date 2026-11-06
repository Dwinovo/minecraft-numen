package com.dwinovo.numen.api.agent.memory;

import com.dwinovo.numen.api.agent.llm.CompactSplit;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.MemoryPort;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.ai.AiLog;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 上下文整理——循环内核的 {@link MemoryPort}:什么时候该自动压缩、怎么切、压缩提示怎么写、摘要怎么落地、
 * 连着失败几次就不再自己动手,以及清空。内核只管<b>什么时候</b>做(自动压缩在注入插话之前、主人按的整理
 * 在闲时),做什么都在这里。整套做法照 pi 的压缩:触发线、切分、三份提示词、对话序列化成文字、前缀摘要。
 *
 * <p>自动压缩的判据是上一次对话回复的实测体量({@link #on} 从 {@code ModelUsed} 里记下),超过 窗口 − 预留
 * ({@link LlmEndpoint#compactionThreshold})就压;服务端不报用量时退到本地估算,好让闸门总能落下来。
 * <b>一次回复之后只问一次</b>:压缩之后的第一次请求没有新回复,旧回复的用量(压缩之前的、更大的)不再触发。
 *
 * <p>摘要请求不另设输出上限:输出多长由服务商层按这次请求剩下的窗口定,普通一轮与摘要共用
 * ({@link LlmEndpoint#optionsFor})。
 *
 * <p>纯 JVM:窗口多大、预留与近段预算多少、历史怎么量,都取自她此刻绑定的端点({@code endpoint},宿主给),落盘走 {@link ConvoLog}。
 */
public final class Compactor implements MemoryPort {

    /** 自动整理的下限:短于这个数不值得自己动手。手动 {@code /compact} 不看它。 */
    private static final int MIN_COMPACT_MESSAGES = 8;
    /** Circuit breaker: stop auto-retrying after this many consecutive failures. */
    private static final int MAX_COMPACT_FAILURES = 3;

    /** 摘要请求的系统提示:只写摘要,不接着对话往下说。 */
    static final String SUMMARIZATION_SYSTEM_PROMPT =
            "你是上下文摘要助手。你的任务是读一段 Minecraft 里的同伴(她是由大语言模型驱动的服务端假玩家)与她的主人之间的对话,"
            + "然后严格按指定的格式写出结构化摘要。\n\n"
            + "不要接着对话往下说。不要回答对话里的任何问题。只输出结构化摘要。";

    /** 第一次整理的提示词:摘要是供另一个 LLM 接着干活用的检查点。 */
    static final String SUMMARIZATION_PROMPT = """
            以上消息是要摘要的对话。写一份结构化的上下文检查点摘要,供另一个 LLM 据此接着干活。

            严格使用这个格式:

            ## 目标
            [主人想让她完成什么?这段对话涉及不同的事时可以列多项。]

            ## 约束与偏好
            - [主人提过的任何约束、偏好或要求,包括没写进札记的口头约定]
            - [没有就写"(无)"]

            ## 进展
            ### 已完成
            - [x] [做完的事]

            ### 进行中
            - [ ] [她手上正在做的事]

            ### 受阻
            - [阻碍进展的问题,有的话]

            ## 关键决定
            - **[决定]**: [简短理由]

            ## 下一步
            1. [接下来该做什么,按顺序]

            ## 关键信息
            - [继续下去需要的数据、示例或参照:坐标与维度、基地和地标的位置、背包里的关键物品与数量、失败过的路线与教训]
            - [没有就写"(无)"]

            每一节保持简洁。原样保留坐标、物品与方块的名字、任务与技能的名字、错误信息。""";

    /** 第二次起的提示词:在 {@code <previous-summary>} 里的上一份摘要之上合并新消息。 */
    static final String UPDATE_SUMMARIZATION_PROMPT = """
            以上消息是要并入已有摘要的新对话,已有摘要在 <previous-summary> 标签里。

            在已有的结构化摘要之上用新信息更新。规则:
            - 保留已有摘要里的全部信息
            - 把新消息里的进展、决定和上下文加进去
            - 更新"进展":做完的从"进行中"挪到"已完成"
            - 按已经做成的事更新"下一步"
            - 原样保留坐标、物品与方块的名字、任务与技能的名字、错误信息
            - 确实不再相关的可以删掉

            严格使用这个格式:

            ## 目标
            [保留已有的目标,事情扩大了就加上新的]

            ## 约束与偏好
            - [保留已有的,把新发现的加上]

            ## 进展
            ### 已完成
            - [x] [之前做完的和新做完的都写上]

            ### 进行中
            - [ ] [手上正在做的,按进展更新]

            ### 受阻
            - [眼下的阻碍,解决了就删掉]

            ## 关键决定
            - **[决定]**: [简短理由](之前的都保留,新的加上)

            ## 下一步
            1. [按现在的状态更新]

            ## 关键信息
            - [保留重要的信息,需要的话加上新的]

            每一节保持简洁。原样保留坐标、物品与方块的名字、任务与技能的名字、错误信息。""";

    /** 切点劈在一轮中间时,那一轮切点之前的半截用的提示词:只写这半截里有的,不去推后面的。 */
    static final String TURN_PREFIX_SUMMARIZATION_PROMPT = """
            以上消息是一段进行中的对话里较早的部分。较晚的消息另外存着,不需要在这里重建。

            写一份简洁的检查点,说明主人交代的事和上面这些消息里能看到的进展。这份检查点会放在较晚的消息之前,
            让对话带着必要的背景接着往下走。

            ## 原始请求
            [主人交代了什么?]

            ## 目前的进展
            - [这些消息里的关键决定和做完的事]

            ## 接着做所需的背景
            - [理解较晚的内容需要从这些消息里带上的信息]

            只摘要上面明确出现过的信息。不要推断或重建较晚的消息。""";

    /** 主人给了关注点({@code /compact <关注点>})时拼在提示词末尾的前缀。 */
    private static final String FOCUS_PREFIX = "\n\n额外关注点:";

    /** 一轮被切开时,历史摘要与前缀摘要合成一份用的分隔。 */
    private static final String TURN_CONTEXT_HEADER = "\n\n---\n\n**本轮上下文(被切开的一轮):**\n\n";

    /** 没有更早的历史、只有被切开的一轮前缀时,代替历史摘要的那句话。 */
    private static final String NO_PRIOR_HISTORY = "(此前没有历史。)";

    /** Wrapper that turns the raw summary into the new history's first user message. */
    private static final String SUMMARY_HEADER =
            "[对话历史已压缩] 以下是此前全部对话的摘要，请将其作为既成事实继续工作：\n\n";

    /**
     * Tokens the history estimate can't see: system prompt (persona + skills
     * XML) and tool schemas. Deliberately generous — over-estimating fires
     * compaction a little early, under-estimating blows the context window.
     */
    private static final int ESTIMATED_FIXED_OVERHEAD_TOKENS = 8_000;

    private final String name;
    private final ConvoState convo;
    private final ConvoLog log;
    private final Supplier<LlmEndpoint> endpoint;
    /** 上一次对话请求的实测体量(服务端报的 prompt tokens);0 = 还不知道。 */
    private long lastPromptTokens;
    /** 有一条还没被闸门问过的对话回复;问过一次就清掉——一次回复之后最多触发一次。 */
    private boolean responseUnchecked;
    /** 那条回复带着实测用量。没有就退到估算。 */
    private boolean responseMeasured;
    /** 自动压缩连着失败的次数——熔断用。 */
    private int failures;

    /**
     * @param name          日志里认这只同伴用的名字
     * @param convo         会话历史;压缩与清空换的就是它
     * @param log           会话日志;压缩与清空的边界落在这里
     * @param endpoint      这只同伴此刻绑定的端点:上下文窗口、预留与近段预算取自它,历史按它真正会发出去的样子估量
     */
    public Compactor(String name, ConvoState convo, ConvoLog log, Supplier<LlmEndpoint> endpoint) {
        this.name = name;
        this.convo = convo;
        this.log = log;
        this.endpoint = endpoint;
    }

    /**
     * 内核的事件里整理这一侧要接的:对话回复的实测体量是自动压缩的判据(服务端没报用量就记下"这条没有",闸门退到估算);
     * 历史换了(整理、清空)之后旧回复的用量不再作数——它量的是换掉之前的、更大的上下文——熔断也从头计。
     */
    public void on(LoopEvent event) {
        switch (event) {
            case LoopEvent.ModelUsed used -> {
                if (used.purpose() == LoopEvent.Purpose.TURN) {
                    responseUnchecked = true;
                    responseMeasured = used.usage().promptTokens() > 0;
                    if (responseMeasured) {
                        lastPromptTokens = used.usage().promptTokens();
                    }
                }
            }
            case LoopEvent.TranscriptBoundary boundary -> {
                if (boundary.kind() != LoopEvent.Boundary.HALT) {
                    lastPromptTokens = 0;
                    responseUnchecked = false;
                    failures = 0;
                }
            }
            default -> { }
        }
    }

    /** 上下文水位百分比:上一次请求的实测体量占窗口多少;用量或窗口未知时返回 0。 */
    public int contextPercent() {
        int window = endpoint.get().contextWindow();
        if (lastPromptTokens <= 0 || window <= 0) return 0;
        return Math.min(100, Math.round(lastPromptTokens * 100f / window));
    }

    /**
     * 自动压缩的闸门(照 pi 的 {@code _checkCompaction}):最近一条回复之后的上下文体量超过 窗口 − 预留就放行。
     * 体量取那条回复的实测用量;它没带用量(有的后端从不发用量帧)就退到本地估算,闸门不至于永远不落。
     *
     * <p><b>每条回复只问一次</b>:压缩之后、或者还没有新回复时,这里一律不放行——否则压缩之前那条回复的旧用量
     * 会在压完的第一次请求前又触发一次。
     */
    @Override
    public boolean compactionDue() {
        if (!responseUnchecked) {
            return false;
        }
        responseUnchecked = false;
        LlmEndpoint current = endpoint.get();
        List<ConvoState.Msg> history = convo.snapshot();
        long contextTokens = responseMeasured ? lastPromptTokens : estimateContextTokens(history, current);
        int threshold = current.compactionThreshold();
        // 切分后没有新消息可总结(只剩上一份摘要)时压不出东西:同一份判据 plan 给,压了也是白烧一次请求
        boolean due = contextTokens > threshold
                && history.size() >= MIN_COMPACT_MESSAGES
                && failures < MAX_COMPACT_FAILURES
                && plan(history, current) != null;
        if (due) {
            AiLog.LOG.info("[numen-entity#{}] auto-compacting: {} context {} tokens > {} (window {} - reserve {})",
                    name, responseMeasured ? "measured" : "estimated", contextTokens, threshold,
                    current.contextWindow(), current.reserveTokens());
        }
        return due;
    }

    /**
     * 这一次压缩切出来的几份:{@code toSummarize} 是要总结的新消息,{@code previous} 是上一份摘要(没有则 null),
     * {@code turnPrefix} 是被切开的那一轮在切点之前的半截(没劈轮是空),{@code kept} 是原文跟在摘要之后的近段。
     */
    private record Plan(List<ConvoState.Msg> toSummarize, String previous, List<ConvoState.Msg> turnPrefix,
                        List<ConvoState.Msg> kept) {}

    /**
     * 切分并摘出上一份摘要;<b>除上一份摘要外没有新消息可总结就返回 null</b>——不压、不调模型
     * (参考 pi 的 prepareCompaction)。自动闸门与 {@link #compaction} 共用这一处判据,
     * 所以闸门不会在"压不出东西"时反复放行。
     *
     * <p>最近约 {@link LlmEndpoint#keepRecentTokens} 的消息原文保留(切分规则见 {@link CompactSplit},按真正发出去的量算,
     * 回传的思考也在内)。整段都在近段预算内时(基本只有手动 /compact 会遇到)退化为全量总结,
     * 只逐字保留末尾那句回答。
     */
    private Plan plan(List<ConvoState.Msg> history, LlmEndpoint current) {
        CompactSplit.Split split = CompactSplit.byRecentBudget(history, current.keepRecentTokens(), current);
        final List<ConvoState.Msg> older;
        final List<ConvoState.Msg> turnPrefix;
        final List<ConvoState.Msg> kept;
        if (split.toSummarize().isEmpty() && split.turnPrefix().isEmpty()) {
            kept = preservedTail(history);
            older = history.subList(0, history.size() - kept.size());
            turnPrefix = List.of();
        } else {
            older = split.toSummarize();
            turnPrefix = split.turnPrefix();
            kept = split.kept();
        }
        // 上一份摘要摘出来单独给:它是累积的账,要在它之上更新,不是再被总结一遍
        String previous = null;
        List<ConvoState.Msg> toSummarize = new ArrayList<>();
        for (ConvoState.Msg msg : older) {
            if (msg instanceof ConvoState.Msg.User u && u.content() != null
                    && u.content().startsWith(SUMMARY_HEADER)) {
                previous = u.content().substring(SUMMARY_HEADER.length());
            } else {
                toSummarize.add(msg);
            }
        }
        return toSummarize.isEmpty() && turnPrefix.isEmpty() ? null : new Plan(toSummarize, previous, turnPrefix, kept);
    }

    /** 一份摘要请求:不带工具,系统提示只说"写摘要";要摘要的历史写成文字放在一条 user 消息里。 */
    private static ModelRequest summaryRequest(String promptText) {
        return new ModelRequest(List.of(new ConvoState.Msg.User(promptText)), List.of(), SUMMARIZATION_SYSTEM_PROMPT);
    }

    private static String withFocus(String prompt, String focus) {
        return focus == null || focus.isBlank() ? prompt : prompt + FOCUS_PREFIX + focus.strip();
    }

    /**
     * 切好这一次压缩:较早的历史一份摘要请求(有上一份摘要就在它之上更新),劈了轮就再一份前缀摘要请求;
     * 近段原文不进请求也不被替换(切法见 {@link #plan})。{@code focus} 非空时拼在每份提示词末尾,摘要照主人的关注点取舍。
     * 压缩期间内核不往历史里写,切好的这一份到摘要落地时仍然成立。没有新消息可总结时返回空。
     */
    @Override
    public Optional<Compaction> compaction(boolean auto, String focus) {
        Plan plan = plan(convo.snapshot(), endpoint.get());
        if (plan == null) {
            AiLog.LOG.info("[numen-entity#{}] compaction skipped ({}): nothing new since the last summary",
                    name, auto ? "auto" : "manual");
            return Optional.empty();
        }
        final String previous = plan.previous();
        final List<ConvoState.Msg> kept = plan.kept();
        List<ModelRequest> requests = new ArrayList<>();
        if (!plan.toSummarize().isEmpty()) {
            // 要摘要的历史写成文字,包在 <conversation> 里再给指令,免得模型接着对话往下说
            StringBuilder prompt = new StringBuilder("<conversation>\n")
                    .append(ConversationText.of(plan.toSummarize())).append("\n</conversation>\n\n");
            if (previous != null) {
                prompt.append("<previous-summary>\n").append(previous.strip()).append("\n</previous-summary>\n\n");
            }
            prompt.append(withFocus(previous == null ? SUMMARIZATION_PROMPT : UPDATE_SUMMARIZATION_PROMPT, focus));
            requests.add(summaryRequest(prompt.toString()));
        }
        if (!plan.turnPrefix().isEmpty()) {
            requests.add(summaryRequest("# Conversation\n" + ConversationText.of(plan.turnPrefix())
                    + "\n\n# Instructions\n" + withFocus(TURN_PREFIX_SUMMARIZATION_PROMPT, focus)));
        }
        AiLog.LOG.info("[numen-entity#{}] compaction started ({}, {} msgs{}{}, keeping {} verbatim)",
                name, auto ? "auto" : "manual", plan.toSummarize().size(),
                previous == null ? "" : " over the last summary", plan.turnPrefix().isEmpty() ? ""
                        : ", split turn prefix " + plan.turnPrefix().size() + " msgs", kept.size());
        final long startMs = System.currentTimeMillis();
        final boolean hasHistory = !plan.toSummarize().isEmpty();
        final boolean hasPrefix = !plan.turnPrefix().isEmpty();
        return Optional.of(new Compaction() {
            @Override
            public List<ModelRequest> requests() {
                return requests;
            }

            @Override
            public boolean apply(List<ModelOutcome.Answered> answers) {
                int at = 0;
                String history = hasHistory ? answers.get(at++).turn().content().strip() : null;
                String prefix = hasPrefix ? answers.get(at).turn().content().strip() : null;
                if (history != null && history.isEmpty() || prefix != null && prefix.isEmpty()) {
                    return false;
                }
                String summary = !hasPrefix ? history
                        : (history != null ? history : previous != null ? previous.strip() : NO_PRIOR_HISTORY)
                                + TURN_CONTEXT_HEADER + prefix;
                String wrapped = SUMMARY_HEADER + summary;
                // Accounting for the boundary line (Claude Code's compactMetadata)
                JsonObject meta = new JsonObject();
                meta.addProperty("trigger", auto ? "auto" : "manual");
                meta.addProperty("droppedMessages", convo.snapshot().size() - kept.size());
                meta.addProperty("durationMs", System.currentTimeMillis() - startMs);
                long output = answers.stream().mapToLong(a -> a.usage().output()).sum();
                if (lastPromptTokens > 0) {
                    meta.addProperty("preTokens", lastPromptTokens);
                }
                if (output > 0) {
                    meta.addProperty("summaryTokens", output);
                }
                // Boundary into the JSONL first (relaunches replay the compacted view;
                // the raw pre-compaction history stays in the file as an archive; the kept
                // tail is the last kept.size() messages of the history, which the boundary
                // points at instead of copying), then
                // swap the in-memory history without re-notifying the sink. The visible
                // transcript only gains a divider — the owner's chat never vanishes.
                log.appendCompactSummary(wrapped, kept.size(), meta);
                List<ConvoState.Msg> next = new ArrayList<>();
                next.add(new ConvoState.Msg.User(wrapped));
                next.addAll(kept);
                convo.replaceAll(next);
                AiLog.LOG.info(
                        "[numen-entity#{}] compaction done ({}): {} tokens → summary ({} chars) + {} preserved msg(s) in {} ms",
                        name, auto ? "auto" : "manual",
                        lastPromptTokens > 0 ? String.valueOf(lastPromptTokens) : "?",
                        wrapped.length(), kept.size(), System.currentTimeMillis() - startMs);
                return true;
            }

            @Override
            public void failed(String why) {
                failures++;
                // The conversation is untouched — the turn just runs uncompacted.
                AiLog.LOG.warn("[numen-entity#{}] compaction failed ({}/{}): {}",
                        name, failures, MAX_COMPACT_FAILURES, why);
            }
        });
    }

    /**
     * 清空上下文——她带进下一轮的历史清成白纸,而<b>记录一个字不删</b>:日志 append-only,
     * 落一条边界事件,重启后 {@code load} 从边界起步、{@code loadDisplay} 照常给全量。
     * 绑定/人设/技能全不动:清的是对话,不是她是谁。
     */
    @Override
    public void clear() {
        log.appendClearBoundary();
        convo.replaceAll(List.of());
        AiLog.LOG.info("[numen-entity#{}] 上下文清空(记录留档)", name);
    }

    /**
     * 整段都在近段预算内、只能全量总结时,逐字跨过边界的那一截:历史以一句说完的回答(没有工具调用)
     * 收尾时就是那一句,否则什么也不留,摘要独自成立。走到这一支的基本是闲时主人按的整理,链条已经说完,
     * 正常就是以一句回答收尾。这一截自己必须合乎协议——带工具调用却没有结果的回复、孤零零的工具结果,
     * 下一次请求都会被拒。
     */
    private static List<ConvoState.Msg> preservedTail(List<ConvoState.Msg> history) {
        if (!history.isEmpty() && history.get(history.size() - 1) instanceof ConvoState.Msg.Assistant a
                && !a.turn().hasToolCalls()) {
            return List.of(a);
        }
        return List.of();
    }

    /**
     * Rough token count of the history for backends that report no usage, plus the fixed overhead
     * ({@link #ESTIMATED_FIXED_OVERHEAD_TOKENS}). Precision is not the goal — what matters is that
     * the auto gate fires AT ALL without a usage frame. 字尺只有一把:{@link CompactSplit}。
     */
    private static int estimateContextTokens(List<ConvoState.Msg> history, LlmEndpoint current) {
        return CompactSplit.estimateTokens(history, current) + ESTIMATED_FIXED_OVERHEAD_TOKENS;
    }
}
