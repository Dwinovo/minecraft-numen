package com.dwinovo.numen.agent.memory;

import com.dwinovo.numen.agent.llm.CompactSplit;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.MemoryPort;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.ai.AiLog;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * 上下文整理——循环内核的 {@link MemoryPort}:什么时候该自动压缩、怎么切、压缩提示怎么写、摘要怎么落地、
 * 连着失败几次就不再自己动手,以及清空。内核只管<b>什么时候</b>做(自动压缩在注入插话之前、主人按的整理
 * 在闲时),做什么都在这里。
 *
 * <p>自动压缩的判据是上一次对话请求的实测体量({@link #on} 从 {@code ModelUsed} 里记下);服务端不报用量时
 * 退到本地估算,好让闸门总能落下来。整理或清空之后这个数不再作数,熔断也从头计。
 *
 * <p>纯 JVM:窗口多大由宿主给({@code contextWindow}),落盘走 {@link ConvoLog}。
 */
public final class Compactor implements MemoryPort {

    /**
     * Headroom under the window at which auto-compaction fires (Claude Code's
     * {@code AUTOCOMPACT_BUFFER_TOKENS}): the next turn adds tool results and
     * a fresh system prompt on top of the last measured request, and the
     * summarization call itself must still fit.
     */
    private static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /**
     * 压缩时原文保留的近段预算(tokens,估算口径见 {@link CompactSplit})。参考 pi 的
     * keepRecentTokens:摘要只替换更早的部分,主人刚说的话逐字跨过压缩边界。
     */
    private static final int KEEP_RECENT_TOKENS = 20_000;
    /** 自动整理的下限:短于这个数不值得自己动手。手动 {@code /compact} 不看它。 */
    private static final int MIN_COMPACT_MESSAGES = 8;
    /** Circuit breaker: stop auto-retrying after this many consecutive failures. */
    private static final int MAX_COMPACT_FAILURES = 3;

    private static final String COMPACT_SYSTEM_PROMPT =
            "You are a helpful AI assistant tasked with summarizing conversations "
            + "between a Minecraft companion entity (the Numen) and its owner.";

    /**
     * The summarization request, appended as the final user message over the
     * full history. Adapted from Claude Code's compact prompt to what a
     * Minecraft body must never forget: coordinates, inventory, lessons.
     */
    private static final String COMPACT_PROMPT = """
            请将以上对话（这是完整历史中较早的部分，最近的消息会原文保留、跟在摘要之后）\
            压缩成一份详细摘要。这份摘要将完全替代这些较早的消息——任何没写进摘要的信息都会永久丢失，\
            所以请把还会用到的信息全部保留。

            分两步完成：

            第一步，在 <analysis> 标签内梳理整段对话：逐条核对有哪些指令、坐标、物品数量、\
            失败教训和未完成的任务必须保留，检查是否有容易遗漏的细节（数字、名称、约束条件）。\
            这一步是你的草稿，之后会被丢弃。

            第二步，在 <summary> 标签内输出正式摘要，按以下结构：
            1. 主人的指令与意图：所有明确的请求，以及当前正在执行哪一个。
            2. 世界知识：所有提到过的重要坐标（基地、传送门、熔炉、工作台、矿点、要塞等）、维度和地标。坐标数字必须逐字保留。
            3. 自身状态：最近已知的 HP、装备、背包中的关键物品及数量。
            4. 已完成的事项：按时间顺序简述。
            5. 失败与教训：失败过的操作、原因、以及学到的约束（例如某处有岩浆、某条路线不可达、某方块需要特定工具）。
            6. 待办任务：计划中尚未完成的事项及其状态。
            7. 当前工作与下一步：摘要请求前正在做什么，接下来的第一步是什么。

            不要调用工具，不要在两个标签之外输出任何内容。""";

    /** Wrapper that turns the raw summary into the new history's first user message. */
    private static final String SUMMARY_HEADER =
            "[对话历史已压缩] 以下是此前全部对话的摘要，请将其作为既成事实继续工作：\n\n";

    private final String name;
    private final ConvoState convo;
    private final ConvoLog log;
    private final IntSupplier contextWindow;
    /** 上一次对话请求的实测体量(服务端报的 prompt tokens);0 = 还不知道。 */
    private long lastPromptTokens;
    /** 自动压缩连着失败的次数——熔断用。 */
    private int failures;

    /**
     * @param name          日志里认这只同伴用的名字
     * @param convo         会话历史;压缩与清空换的就是它
     * @param log           会话日志;压缩与清空的边界落在这里
     * @param contextWindow 这只同伴绑定的模型的上下文窗口
     */
    public Compactor(String name, ConvoState convo, ConvoLog log, IntSupplier contextWindow) {
        this.name = name;
        this.convo = convo;
        this.log = log;
        this.contextWindow = contextWindow;
    }

    /**
     * 内核的事件里整理这一侧要接的:对话调用的实测体量是自动压缩的判据(服务端没报用量就留着上一个数,
     * 闸门退到估算);历史换了(整理、清空)之后它不再作数,熔断从头计。
     */
    public void on(LoopEvent event) {
        switch (event) {
            case LoopEvent.ModelUsed used -> {
                if (used.purpose() == LoopEvent.Purpose.TURN && used.usage().promptTokens() > 0) {
                    lastPromptTokens = used.usage().promptTokens();
                }
            }
            case LoopEvent.TranscriptBoundary boundary -> {
                if (boundary.kind() != LoopEvent.Boundary.HALT) {
                    lastPromptTokens = 0;
                    failures = 0;
                }
            }
            default -> { }
        }
    }

    /** 上下文水位百分比:上一次请求的实测体量占窗口多少;用量或窗口未知时返回 0。 */
    public int contextPercent() {
        int window = contextWindow.getAsInt();
        if (lastPromptTokens <= 0 || window <= 0) return 0;
        return Math.min(100, Math.round(lastPromptTokens * 100f / window));
    }

    /**
     * Auto-compaction gate: the last request's true context size (as the API counted it) is within
     * the buffer of the window. Mirrors Claude Code's autoCompactIfNeeded. Backends that never send a
     * usage frame leave lastPromptTokens at 0 — fall back to a local estimate so the gate still fires
     * instead of never.
     */
    @Override
    public boolean compactionDue() {
        int window = contextWindow.getAsInt();
        List<ConvoState.Msg> history = convo.snapshot();
        long contextTokens = lastPromptTokens > 0 ? lastPromptTokens : estimateContextTokens(history);
        boolean due = contextTokens >= window - AUTO_COMPACT_BUFFER_TOKENS
                && history.size() >= MIN_COMPACT_MESSAGES
                && failures < MAX_COMPACT_FAILURES;
        if (due) {
            AiLog.LOG.info("[numen-entity#{}] auto-compacting: {} context {} tokens >= {} - {}",
                    name, lastPromptTokens > 0 ? "measured" : "estimated",
                    contextTokens, window, AUTO_COMPACT_BUFFER_TOKENS);
        }
        return due;
    }

    /**
     * Cut the summarization call: the OLDER span of the history + the compact prompt as the final
     * user message, NO tools, a minimal system prompt (skills XML and the persona would only waste
     * the very tokens we're trying to reclaim). 最近约 {@link #KEEP_RECENT_TOKENS} 的消息不进请求也不被
     * 替换——它们原文跟在摘要之后(切分规则见 {@link CompactSplit})。整段都在近段预算内时(基本只有
     * 手动 /compact 会遇到)退化为全量总结,只逐字保留末尾那句回答。压缩期间内核不往历史里写,
     * 切好的这一份到摘要落地时仍然成立。
     */
    @Override
    public Compaction compaction(boolean auto) {
        List<ConvoState.Msg> history = convo.snapshot();
        CompactSplit.Split split = CompactSplit.byRecentBudget(history, KEEP_RECENT_TOKENS);
        final List<ConvoState.Msg> toSummarize;
        final List<ConvoState.Msg> kept;
        if (split.toSummarize().isEmpty()) {
            toSummarize = new ArrayList<>(history);
            kept = preservedTail();
            toSummarize.removeAll(kept);
        } else {
            toSummarize = new ArrayList<>(split.toSummarize());
            kept = split.kept();
        }
        List<ConvoState.Msg> request = new ArrayList<>(toSummarize);
        request.add(new ConvoState.Msg.User(COMPACT_PROMPT));
        AiLog.LOG.info("[numen-entity#{}] compaction started ({}, summarizing {} msgs, keeping {} verbatim)",
                name, auto ? "auto" : "manual", toSummarize.size(), kept.size());
        final long startMs = System.currentTimeMillis();
        return new Compaction() {
            @Override
            public ModelRequest request() {
                return new ModelRequest(request, List.of(), COMPACT_SYSTEM_PROMPT, Set.of());
            }

            @Override
            public boolean apply(AssistantTurn reply, Usage usage) {
                String summary = extractSummary(reply.content());
                if (summary == null || summary.isBlank()) {
                    return false;
                }
                String wrapped = SUMMARY_HEADER + summary.strip();
                // Accounting for the boundary line (Claude Code's compactMetadata):
                // the summarization call's own prompt_tokens IS the exact size of the
                // history being compacted — more precise than the previous turn's count.
                JsonObject meta = new JsonObject();
                meta.addProperty("trigger", auto ? "auto" : "manual");
                meta.addProperty("droppedMessages", convo.snapshot().size() - kept.size());
                meta.addProperty("durationMs", System.currentTimeMillis() - startMs);
                if (usage.promptTokens() > 0) {
                    meta.addProperty("preTokens", usage.promptTokens());
                    if (usage.total() > usage.promptTokens()) {
                        meta.addProperty("summaryTokens", usage.total() - usage.promptTokens());
                    }
                }
                // Boundary into the JSONL first (relaunches replay the compacted view;
                // the raw pre-compaction history stays in the file as an archive), then
                // swap the in-memory history without re-notifying the sink. The visible
                // transcript only gains a divider — the owner's chat never vanishes.
                log.appendCompactSummary(wrapped, kept, meta);
                List<ConvoState.Msg> next = new ArrayList<>();
                next.add(new ConvoState.Msg.User(wrapped));
                next.addAll(kept);
                convo.replaceAll(next);
                AiLog.LOG.info(
                        "[numen-entity#{}] compaction done ({}): {} tokens → summary ({} chars) + {} preserved msg(s) in {} ms",
                        name, auto ? "auto" : "manual",
                        usage.promptTokens() > 0 ? String.valueOf(usage.promptTokens()) : "?",
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
        };
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
    private List<ConvoState.Msg> preservedTail() {
        if (convo.lastMessage() instanceof ConvoState.Msg.Assistant a
                && !a.turn().hasToolCalls()) {
            return List.of(a);
        }
        return List.of();
    }

    /**
     * Tokens the history estimate can't see: system prompt (persona + skills
     * XML) and tool schemas. Deliberately generous — over-estimating fires
     * compaction a little early, under-estimating blows the context window.
     */
    private static final int ESTIMATED_FIXED_OVERHEAD_TOKENS = 8_000;

    /**
     * Rough token count of the history for backends that report no usage.
     * CJK sits near 1 token/char on modern tokenizers; ASCII (tool-result
     * JSON, coordinates) near 3.5–4 chars/token. Precision is not the goal —
     * the 13k {@link #AUTO_COMPACT_BUFFER_TOKENS} absorbs the error; what
     * matters is that the auto gate fires AT ALL without a usage frame.
     */
    private static int estimateContextTokens(List<ConvoState.Msg> history) {
        // 字尺只有一把:与压缩切分共用 CompactSplit 的估算(CJK ~1 token/字、ASCII ~4 字符/token、
        // 每条 8 token 结构开销),这里只加系统提示/工具表的固定开销。
        return CompactSplit.estimateTokens(history) + ESTIMATED_FIXED_OVERHEAD_TOKENS;
    }

    /**
     * The compact prompt asks for a two-stage response: a private
     * {@code <analysis>} scratchpad, then the real {@code <summary>}. Only the
     * summary is kept — persisting the analysis would waste the very tokens
     * compaction reclaims. Tolerant of models that skip or mangle the tags:
     * an unclosed {@code <summary>} reads to the end, no tags at all falls
     * back to the whole text minus any analysis block.
     */
    static String extractSummary(String raw) {
        if (raw == null) return null;
        int open = raw.indexOf("<summary>");
        if (open >= 0) {
            int bodyStart = open + "<summary>".length();
            int close = raw.indexOf("</summary>", bodyStart);
            String body = close >= 0 ? raw.substring(bodyStart, close) : raw.substring(bodyStart);
            if (!body.isBlank()) return body.strip();
        }
        return raw.replaceFirst("(?s)<analysis>.*?(</analysis>|$)", "").strip();
    }
}
