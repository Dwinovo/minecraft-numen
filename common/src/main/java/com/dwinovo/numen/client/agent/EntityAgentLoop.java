package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.ToolInvocation;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-entity agent loop running on the <strong>client</strong>. One instance
 * per Numen the player talks to, keyed by the stable {@code entity.getUUID()}
 * in {@link AgentLoopRegistry} and resolved to the current body via
 * {@link ClientNumenLookup} (so it survives the int-id churn of dimension
 * travel). The agent is bound to that one entity for its
 * whole lifetime — it talks directly to the owner, runs world-action tools on
 * its own body, and survives across many prompts (it is NOT a one-shot
 * sub-agent that self-destructs after a single task).
 *
 * <h2>Single-layer architecture</h2>
 * This is the deliberate roll-back from the short-lived PlayerAgent +
 * EntityAgent split. Each entity owns one conversation; the owner chats with
 * it directly (right-click → {@code EntityChatScreen}, or the Units tab Chat
 * button). The earlier two-tier design made debugging a single entity's AI
 * painful — interleaved logs, reports bouncing between agents, lifecycles
 * tearing down mid-task. Re-introduce the brain-on-the-entity model now; a
 * higher-level dispatcher can come back later once each body is solid.
 *
 * <h2>Threading rules</h2>
 * All mutations run on the client main thread:
 * <ul>
 *   <li>{@link #submitPrompt} — from {@code EntityChatScreen} (UI thread)</li>
 *   <li>tool results — routed to this loop's {@link ToolDispatcher.Sink#onResult},
 *       fed by {@link com.dwinovo.numen.agent.tool.ToolCall#complete} (e.g. from
 *       {@code TaskResultPayload.handle}, already bounced onto the main thread)</li>
 *   <li>LLM response — {@link NumenLlmClient#chatStreaming} resolves on the
 *       HTTP executor thread; {@link #bounceBackToMain} hops via
 *       {@code Minecraft.getInstance().execute} before any mutation</li>
 * </ul>
 */
public final class EntityAgentLoop {

    /**
     * Persona prompt for a single Numen body. Deliberately does NOT enumerate
     * tools — the live tool list (with full descriptions) rides along on every
     * request, and a prose copy here rotted badly once already. This prompt
     * carries only what the tool schemas can't: identity, working discipline,
     * and the voice toward the owner.
     */
    private static final String ENTITY_PROMPT = com.dwinovo.numen.agent.prompt.NumenPrompts.ENTITY_PROMPT;

    // ---- context compaction (mirrors Claude Code's /compact machinery) ----

    /**
     * The model context window now comes per-model from {@code ModelRegistry} (numen_models.json),
     * looked up from the configured provider+model at the auto-compaction gate; unknown/custom models
     * fall back to {@code ModelRegistry.DEFAULT_CTX} (64k).
     */
    /**
     * Headroom under the window at which auto-compaction fires (Claude Code's
     * {@code AUTOCOMPACT_BUFFER_TOKENS}): the next turn adds tool results and
     * a fresh system prompt on top of the last measured request, and the
     * summarization call itself must still fit.
     */
    private static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** Don't bother summarizing a conversation shorter than this. */
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
            请将以上整段对话压缩成一份详细摘要。这份摘要将完全替代之前的对话历史，\
            成为你后续行动的唯一记忆——任何没写进摘要的信息都会永久丢失，所以请把还会用到的信息全部保留。

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

    private final UUID entityUuid;
    /** JSONL persistence under {@code config/numen/conversations/<uuid>.jsonl}. */
    private final ConvoLog log;
    private final ConvoState convo;
    /** Functional-block coordinate memory, injected as {@code <known_blocks>}. */
    private final WorkBlockMemory workBlocks;
    /**
     * 收件箱(宪法 §4):主人的话与世界事件的统一进箱口。协议约束是它存在的
     * 底层原因——{@code assistant(tool_calls)} 后面必须直接跟 {@code tool}
     * 结果,user 消息不能插队,所以输入一律进箱,在 {@link #drainInbox} 的
     * 协议安全点一次倒空。三态路由(什么输入什么状态下配开轮)在
     * {@link #pushEvent};条目、落盘、年龄标注在 {@link Inbox}。
     */
    private Inbox inbox;
    /** 后台异步任务记账(派发回执置位,对上 id 的 task_finished 清零);null = 身体空闲。
     *  客户端自记账,不走新网络包:回执与事件本来就都经过这里。 */
    private CurrentTask currentTask;

    private record CurrentTask(String id, String tool, long sinceMs) {}

    /**
     * This companion's persona (per-companion, dynamic). Sourced from the last {@code persona-change}
     * event in the log on restore, mutated live by {@link #setPersona}. Null/blank → falls back to the
     * global {@code getSystemPrompt} default. Read fresh every turn in {@link #composeSystemPrompt}.
     */
    private String personaText;
    private String personaName;
    private String personaId;   // library id this persona came from (so a library edit can propagate here)

    /**
     * The {@link com.dwinovo.numen.agent.llm.ProviderLibrary} entry this companion
     * talks through, or null = the global settings. Resolved to a concrete endpoint
     * FRESH at every dispatch (entry edits and deletions take effect on the next
     * request, deletion degrading gracefully to global). Persisted as an assignment
     * in {@code providers.json}, restored in the constructor.
     */
    private String providerEntryId;

    private boolean awaitingLlmResponse = false;
    /** Why new turns are paused; preserves owner Stop while allowing system-failure recovery. */
    private AgentTurnPause turnPause = AgentTurnPause.NONE;
    /** One turn-level re-run per failure has been spent (reset when that turn settles). */
    private boolean turnRetried = false;

    /**
     * Set while an external driver (an MCP client / Claude) holds this body via
     * {@link com.dwinovo.numen.api.NumenActuator}. The internal brain is paused —
     * no LLM turn starts — until {@link #releaseExternal}. Distinct from
     * {@link #dead} (body gone) and {@link #turnPause} (one paused internal turn):
     * this is a deliberate hand-off of the whole body to an outside brain.
     */
    private boolean externallyDriven = false;

    /**
     * Runs this turn's tool calls one at a time and reports each result back
     * through a {@link ToolDispatcher.Sink} into the conversation. All the
     * tool-execution plumbing (serial queue, ship-to-server, completion,
     * timeout) lives in here, not in the loop.
     */
    private final ToolDispatcher dispatcher;

    /**
     * 本同伴的流式语音管线,懒创建：首次在声线库里 resolve 到这个 UUID 的
     * 绑定时才 new。未绑定 = 永远 null = 零开销。
     * 见 {@link com.dwinovo.numen.client.voice.VoicePipeline}。
     */
    private com.dwinovo.numen.client.voice.VoicePipeline voice;

    /** A summarization call is in flight; blocks normal turns until it lands. */
    private boolean compacting = false;
    /** Context size of the last request as the API counted it (0 = unknown yet). */
    private int lastPromptTokens = 0;
    /** 本同伴累计消耗的 token(每次请求的 total 之和,含压缩调用),持久化于
     *  conversations/&lt;uuid&gt;.stats.json。计费口径:每次请求都全量计费 prompt,
     *  所以按请求 total 累加才是真实开销。 */
    private long totalTokensUsed = 0;
    /** Consecutive compaction failures — circuit breaker for the auto path. */
    private int compactFailures = 0;

    /**
     * Set while the body is DEAD and awaiting its timed respawn (see {@link #onEntityDied} /
     * {@link #onRespawned}). The loop is frozen — no LLM turn starts — until the body comes back.
     */
    private boolean dead = false;

    /** Death cause recorded at death, replayed in the respawn event (null while alive). */
    private String deathCause;
    /** Tool calls that were in flight when the body died — resolved on respawn, not before. */
    private List<String> deathInterruptedCalls = List.of();

    /**
     * Bumped every time the owner interrupts a turn ({@link #abort}). Each LLM
     * dispatch captures the value at send time; when the streamed response
     * lands {@link #handleResponse} discards it if the generation no longer
     * matches — i.e. the turn it belongs to was cancelled. This is the
     * equivalent of Claude Code spinning up a fresh {@code AbortController} per
     * turn: an in-flight HTTP response from an interrupted turn must never be
     * spliced back into the conversation or dispatch its tool calls.
     */
    private int turnGeneration = 0;

    /**
     * The PHYSICAL transcript for the chat GUI: every message ever exchanged
     * this session (plus the persisted tail), in order, with compaction
     * boundaries as {@link ConvoLog#COMPACT_DIVIDER} sentinels. Compaction
     * rewires {@link #convo} (what the LLM sees) but only appends a divider
     * here — the owner's visible history never vanishes. Same split as the
     * append-only session log vs. the logical context in Claude Code.
     */
    private final List<ConvoState.Msg> display = new ArrayList<>();

    EntityAgentLoop(UUID entityUuid) {
        this.entityUuid = entityUuid;
        Path numenRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen");
        this.log = ConvoLog.forEntity(numenRoot.resolve("conversations"), entityUuid);
        this.convo = new ConvoState(msg -> {
            log.append(msg);
            display.add(msg);
        });
        this.workBlocks = WorkBlockMemory.forEntity(numenRoot.resolve("memory"), entityUuid);
        this.inbox = new Inbox(numenRoot.resolve("conversations"), entityUuid);
        this.providerEntryId = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().assignedEntry(entityUuid);
        this.dispatcher = new ToolDispatcher(entityUuid, new ToolDispatcher.Sink() {
            @Override public void onResult(ToolInvocation inv, String resultJson) {
                harvestWorkBlocks(inv.name(), resultJson);
                trackAsyncDispatch(inv.name(), resultJson);
                convo.addToolResult(inv.id(), resultJson);
            }
            @Override public void onAllSettled() {
                tryStartTurn();
            }
            @Override public AbstractClientPlayer entity() {
                return resolveEntity();
            }
        });
        restoreFromDisk();
    }

    /**
     * Replay the persisted conversation tail into memory and heal whatever a
     * dead session left dangling, so the first request after a relaunch is
     * protocol-valid:
     * <ul>
     *   <li>assistant tool_calls whose results never arrived (the game closed
     *       mid-task) get synthetic "interrupted" results — the same trick as
     *       the owner's Stop button; the synthetic results also append to the
     *       file, healing it on disk;</li>
     *   <li>a trailing user message (closed while waiting on the LLM) is
     *       capped with a short assistant note, mirroring {@link #abort}, so
     *       the next prompt doesn't create back-to-back user messages.</li>
     * </ul>
     */
    /** 与自动压缩闸门同一口径的模型上下文窗口。 */
    private static int modelWindow() {
        return com.dwinovo.numen.agent.model.ModelRegistry.contextWindow(
                com.dwinovo.numen.client.screen.LlmProviders.normalize(
                        com.dwinovo.numen.platform.Services.CONFIG.getProvider()),
                com.dwinovo.numen.platform.Services.CONFIG.getModel());
    }

    /** 上下文水位百分比(基于上次请求的实测 prompt tokens);usage 未知时返回 0。 */
    public int contextPercent() {
        if (lastPromptTokens <= 0) return 0;
        return Math.min(100, Math.round(lastPromptTokens * 100f / Math.max(1, modelWindow())));
    }

    /** 本同伴累计消耗的 token(跨会话持久化)。 */
    public long totalTokensUsed() {
        return totalTokensUsed;
    }

    private java.nio.file.Path statsFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen").resolve("conversations")
                .resolve(entityUuid + ".stats.json");
    }

    private void loadStats() {
        try {
            java.nio.file.Path f = statsFile();
            if (!java.nio.file.Files.isRegularFile(f)) return;
            JsonObject o = com.google.gson.JsonParser.parseString(
                    java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("totalTokens")) totalTokensUsed = Math.max(0, o.get("totalTokens").getAsLong());
        } catch (java.io.IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计读取失败: {}", entityUuid, ex.toString());
        }
    }

    /** 累加一次请求的计费等效 tokens 并写穿到 stats 文件(文件极小,每回合一写)。 */
    private void addTokens(long total) {
        if (total <= 0) return;
        totalTokensUsed += total;
        try {
            java.nio.file.Path f = statsFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, "{\"totalTokens\":" + totalTokensUsed + "}",
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            Constants.LOG.warn("[numen-entity#{}] token 统计写盘失败: {}", entityUuid, ex.toString());
        }
    }

    private void restoreFromDisk() {
        loadStats();
        log.migrateIfNeeded();   // upgrade a pre-v2 file in place before reading it (crash-safe, keeps a .v1.bak)
        ConvoLog.PersonaState p = log.loadCurrentPersona();   // independent of history — a persona may be set before any chat
        if (p != null && p.text() != null && !p.text().isBlank()) {
            personaId = p.id() == null || p.id().isBlank() ? null : p.id();
            personaText = p.text();
            personaName = p.name();
        }
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (history.isEmpty()) return;
        convo.preload(history);
        // The visible transcript replays the raw file order (dividers included),
        // NOT the compacted view — preload before healing so the synthetic
        // messages below land after it via the sink.
        display.addAll(log.loadDisplay(ConvoLog.DEFAULT_LOAD_LIMIT));

        List<String> dangling = ConvoLog.unansweredToolCallIds(history);
        for (String id : dangling) {
            convo.addToolResult(id,
                    "{\"success\":false,\"message\":\"interrupted: the game was closed before this finished\"}");
        }
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
        }
        Constants.LOG.info("[numen-entity#{}] restored {} msg(s) from disk{}",
                entityUuid, history.size(),
                dangling.isEmpty() ? "" : " (healed " + dangling.size() + " dangling tool call(s))");
    }

    public UUID entityUuid() { return entityUuid; }
    public ConvoState convo() { return convo; }

    /** Read-only physical transcript for the GUI (see {@link #display}). */
    public List<ConvoState.Msg> display() {
        return java.util.Collections.unmodifiableList(display);
    }

    /** Snapshot of prompts (GUI or {@code NumenGateway}) still waiting for the
     *  next protocol-valid splice point — the GUI renders these as pending. */
    public List<String> queuedPrompts() {
        return inbox.snapshot();
    }

    /** 在飞回复的已到 content 增量(流式打字机的数据源)。主线程读写:chunk 在
     *  HTTP 线程到达后经 {@code Minecraft.execute} 蹦回来追加,代际不符直接丢;
     *  回复落库/打断/死亡时清空——committed 消息接管显示,永不双份。 */
    private final StringBuilder livePartial = new StringBuilder();

    /** Live partial of the in-flight assistant reply ("" when idle) — GUI typewriter source. */
    public String livePartial() {
        return livePartial.toString();
    }

    /** onChunk 接线:content delta 直通 UI 的 live-partial,原始 chunk 原样继续喂语音。 */
    private java.util.function.Consumer<JsonObject> tapForUi(
            int gen, java.util.function.Consumer<JsonObject> voiceSink) {
        return chunk -> {
            String delta = com.dwinovo.numen.client.voice.VoicePipeline.extractContentDelta(chunk);
            if (delta != null && !delta.isEmpty()) {
                Minecraft.getInstance().execute(() -> {
                    if (gen == turnGeneration) livePartial.append(delta);
                });
            }
            if (voiceSink != null) voiceSink.accept(chunk);
        };
    }

    /** Owner typed a prompt in the chat GUI. */
    public void submitPrompt(String text) {
        if (dead) {
            Constants.LOG.info("[numen-entity#{}] prompt ignored — body is dead", entityUuid);
            return;
        }
        boolean wasAborted = turnPause.isPaused();
        turnPause = AgentTurnPause.NONE;
        // Always buffer first; tryStartTurn() splices buffered prompts into the
        // conversation only at a protocol-valid point. If we're mid-turn (the
        // guards in tryStartTurn fire), the prompt stays buffered and gets
        // flushed once the outstanding assistant/tool round-trip completes —
        // this avoids inserting a user message between assistant(tool_calls)
        // and its tool results (which the API rejects with HTTP 400).
        boolean deferred = awaitingLlmResponse || dispatcher.busy();
        // Wrap the owner's words in <query> so the model can always tell real user input apart from
        // anything else numen injects into the same user turn (events, and future world-state/reminders).
        inbox.pushPrompt("<query>" + text + "</query>");
        Constants.LOG.info("[numen-entity#{}] user prompt ({} chars){}{}: {}",
                entityUuid, text.length(),
                wasAborted ? " — reset previous abort" : "",
                deferred ? " — buffered (mid-turn)" : "",
                truncate(text, 200));
        tryStartTurn();
    }

    /** Driven once per client tick (see {@code AgentLoopRegistry.tickAll}) — backstop timeout. */
    public void clientTick() {
        dispatcher.tick();
        if (voice != null) voice.tick();
        syncSpeakingState();
    }

    /** 上次发给服务端的说话状态(翻转才发包,不逐 tick 刷)。 */
    private boolean lastSpeakingSent;

    /** 大脑在输出(思考/生成/跑工具/语音在播)→ 告诉身体,好在说话期间注视主人。 */
    private void syncSpeakingState() {
        // 退出游戏的最后几个 client tick 里连接已拆——此时发包会在
        // PacketDistributor.sendToServer 里 NPE 崩掉客户端。断线期不发,
        // 状态留在 lastSpeakingSent 里,重连后首次翻转自然补上。
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            return;
        }
        boolean speaking = awaitingLlmResponse || dispatcher.busy()
                || (voice != null && voice.isSpeaking());
        if (speaking != lastSpeakingSent) {
            lastSpeakingSent = speaking;
            com.dwinovo.numen.platform.Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.SpeakingStatePayload(entityUuid, speaking));
        }
    }

    // ---- streaming voice (TTS) ----

    /**
     * 一次 LLM 分发的语音接线：chunk 回调 + 收尾动作打包。语音未配置时是
     * {@link #SILENT_VOICE}（sink 为 null、finish 是空操作）,chatStreaming
     * 收到 null onChunk 与从前完全一样。
     */
    private record VoiceTurn(java.util.function.Consumer<JsonObject> sink, Runnable finish) {}

    private static final VoiceTurn SILENT_VOICE = new VoiceTurn(null, () -> {});

    /**
     * 为即将发出的 chat 请求开启一轮语音（若该同伴绑定了声线）。每次分发都
     * 重新 resolve——声线库/绑定的编辑下一轮生效;开新轮会打断上一轮还在
     * 播的残句（新内容优先,与打断语义一致）。
     */
    private VoiceTurn beginVoiceTurn() {
        com.dwinovo.numen.client.voice.VoiceLibrary.Entry cfg =
                com.dwinovo.numen.client.voice.VoiceLibrary.instance().resolve(entityUuid);
        if (cfg == null) {
            if (voice != null) voice.interrupt();   // 总开关关闭/解绑:静音存量队列
            return SILENT_VOICE;
        }
        if (voice == null) {
            voice = new com.dwinovo.numen.client.voice.VoicePipeline(entityUuid);
        }
        final var vp = voice;
        final int vgen = vp.beginTurn(cfg);
        return new VoiceTurn(vp.chunkSink(vgen), () -> vp.endTurn(vgen));
    }

    /**
     * Pull functional-block coordinates out of successful tool results into
     * {@link WorkBlockMemory}. The results already carry them — place_block
     * reports the block it placed, interact_at reports the station it activated
     * (a chest/furnace/table it opened) — this just stops the loop from
     * forgetting them once the result scrolls out of context. Both tools report
     * the same {@code block} + {@code x/y/z} shape; {@code workBlocks.record}
     * filters to tracked station types, so non-station interactions fall away.
     */
    private void harvestWorkBlocks(String toolName, String resultJson) {
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) return;
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : null;
            if (data == null) return;

            switch (toolName) {
                case "place_block", "interact_at" -> {
                    if (data.has("block") && data.has("x")) {
                        String path = data.get("block").getAsString();
                        int colon = path.indexOf(':');
                        if (colon >= 0) path = path.substring(colon + 1);
                        workBlocks.record(path, new net.minecraft.core.BlockPos(
                                data.get("x").getAsInt(),
                                data.get("y").getAsInt(),
                                data.get("z").getAsInt()));
                    }
                }
                default -> { /* nothing to harvest */ }
            }
        } catch (RuntimeException ex) {
            Constants.LOG.debug("[numen-entity#{}] work-block harvest skipped: {}",
                    entityUuid, ex.toString());
        }
    }

    // ---- interrupt (owner-triggered, from the chat GUI "Stop" button) ----

    /** The brain or body is actively working: LLM, tool round-trip, compaction, or background task. */
    public boolean isBusy() {
        return awaitingLlmResponse || compacting || dispatcher.busy() || currentTask != null;
    }

    /** A summarization call is currently in flight (drives the GUI status line). */
    public boolean isCompacting() {
        return compacting;
    }

    /** Manual compaction is actionable right now (drives the Compact button). */
    public boolean canCompact() {
        return !dead && !isBusy() && convo.snapshot().size() >= MIN_COMPACT_MESSAGES;
    }

    /** Owner prompts are queued, waiting to flush into the conversation. */
    public boolean hasQueuedPrompts() {
        return !inbox.isEmpty();
    }

    /** There is something an interrupt would act on — drives the Stop button's enabled state. */
    public boolean canInterrupt() {
        return isBusy() || hasQueuedPrompts();
    }

    /**
     * Owner-triggered interrupt — the chat GUI's "Stop" button. Mirrors Claude
     * Code's {@code handleCancel} (useCancelRequest.ts) two-priority rule:
     *
     * <ol>
     *   <li><b>A turn or background body task is active</b> → stop it. An in-flight
     *       LLM response is invalidated via {@link #turnGeneration} (discarded when
     *       it lands, so it can't dispatch tools after the fact); any world-action tool calls
     *       still awaiting a server result get a synthetic "interrupted" result
     *       so every {@code assistant(tool_calls)} keeps matching {@code tool}
     *       results and the next request stays protocol-valid. A
     *       {@code CancelTasksPayload} also ships to the server so the
     *       <em>body</em> stops too — without it the entity keeps walking/mining
     *       to its task deadline while only the conversation halts. Queued
     *       prompts are <em>preserved</em> — they flush on the next submit,
     *       exactly like Claude Code keeps its message queue across an
     *       interrupt.</li>
     *   <li><b>Idle but prompts are queued</b> (e.g. typed during a turn that was
     *       just interrupted and is now held) → drop the queue. Mirrors
     *       {@code popCommandFromQueue} when there's no running task to cancel.</li>
     * </ol>
     *
     * No-op when nothing is running and nothing is queued.
     */
    public void abort() {
        // 语音无条件先闭嘴:不管打断的是在飞的 turn 还是排队的 prompt,
        // 主人按下 Stop 时还在播/待播的语音都不该继续。
        if (voice != null) voice.interrupt();
        if (isBusy()) {
            // Priority 1: stop the running turn, in-flight compaction, or a body background task.
            // Responses are generation-stamped, so any in-flight one is discarded.
            turnGeneration++; // any in-flight LLM response is now stale → discarded on arrival
            boolean wasAwaitingLlm = awaitingLlmResponse;
            boolean wasBackgroundTask = currentTask != null;
            awaitingLlmResponse = false;
            compacting = false;
            livePartial.setLength(0);   // 半截打字随打断作废

            // Synthesize cancelled results for EVERY outstanding call (in flight AND
            // still-queued) so the assistant(tool_calls) message keeps matching tool
            // results — otherwise the next request is protocol-invalid (HTTP 400). Real
            // results arriving later are dropped as "late" by the dispatcher.
            List<String> cancelled = dispatcher.cancelAndDrain();
            for (String id : cancelled) {
                convo.addToolResult(id,
                        "{\"success\":false,\"message\":\"interrupted by owner\"}");
            }

            // Stop the BODY too, not just the conversation: cancelAndDrain fires
            // CompanionLifecycle.onAbort, which tool packs subscribe to so they can
            // halt their own server-side work. The engine sends no packet itself.

            // If we cut off an in-flight LLM call before its assistant turn was
            // recorded, the conversation now ends on a user message. Cap it with a
            // short assistant note so the next prompt doesn't create back-to-back
            // user messages (some backends reject those — see drainInbox).
            if (wasAwaitingLlm && cancelled.isEmpty()
                    && convo.lastMessage() instanceof ConvoState.Msg.User) {
                convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
            }

            convo.resetTurnCount();
            turnPause = AgentTurnPause.OWNER_INTERRUPT;
            Constants.LOG.info("[numen-entity#{}] interrupted by owner (awaitingLlm={}, backgroundTask={}, cancelledTools={}, queued={})",
                    entityUuid, wasAwaitingLlm, wasBackgroundTask, cancelled.size(), inbox.promptCount());
        } else if (inbox.promptCount() > 0) {
            // Priority 2: idle — drop the held PROMPT bucket only. Inboxed events are
            // facts, not superseded instructions: they stay and ride the next turn
            // (a death narrative wiped here left the model answering "啥情况" without
            // knowing it had died — the exact hole this split closes).
            int dropped = inbox.clearPrompts();
            Constants.LOG.info("[numen-entity#{}] interrupt cleared {} queued prompt(s) ({} event(s) kept)",
                    entityUuid, dropped, inbox.eventCount());
        }
    }

    // ---- external control (an MCP client / Claude drives the body directly) ----

    /**
     * An external driver takes control of this body. The internal brain stops
     * starting turns and any in-flight turn/task is aborted (via {@link #abort},
     * which also fires {@code CompanionLifecycle.onAbort} so the body itself
     * stops), leaving the body free for the external driver. Reverse with
     * {@link #releaseExternal}. Idempotent.
     */
    public void acquireExternal() {
        if (externallyDriven) return;
        externallyDriven = true;
        abort();   // stop any running internal turn + free the body
        Constants.LOG.info("[numen-entity#{}] external control acquired — internal brain paused", entityUuid);
    }

    /**
     * The external driver released control — the internal brain may act again.
     * Does not auto-start a turn; waits for the next owner prompt or event.
     * Idempotent.
     */
    public void releaseExternal() {
        if (!externallyDriven) return;
        externallyDriven = false;
        turnPause = AgentTurnPause.NONE; // clear the owner-interrupt latch set by acquireExternal
        Constants.LOG.info("[numen-entity#{}] external control released — internal brain resumed", entityUuid);
    }

    /** True while an external driver (MCP / Claude) holds this body. */
    public boolean isExternallyDriven() {
        return externallyDriven;
    }

    /**
     * The body died — the server tells us via {@code NumenDeathPayload} with the death cause. SUSPEND
     * (not dispose): the companion respawns at its owner shortly and {@link #onRespawned} resumes us.
     * Discard any in-flight LLM turn (bump {@link #turnGeneration}), then heal the conversation so it
     * stays protocol-valid AND the brain learns why it stopped — resolve every in-flight tool call with
     * the death cause, and cap a trailing user message (mirrors {@link #restoreFromDisk}). Latch
     * {@link #dead} so no turn starts until respawn.
     */
    public void onEntityDied(String cause) {
        // FREEZE hard: stop all LLM output/work and feed the model NOTHING now (adding a tool result
        // here would let the loop continue). Just record what was in flight + the cause; everything is
        // restored on respawn. The body is gone, so its tool results will never arrive — we'll synth
        // them at respawn instead.
        deathCause = cause;
        if (voice != null) voice.interrupt();   // 尸体不说话:停播 + 清队列
        // Resolve at respawn: every outstanding call (in flight + still queued) — all
        // are listed in the assistant message, so all need results.
        deathInterruptedCalls = dispatcher.cancelAndDrain();
        turnGeneration++;          // discard any in-flight LLM response (halt output)
        awaitingLlmResponse = false;
        compacting = false;
        livePartial.setLength(0);
        inbox.clearAll();
        dead = true;
        Constants.LOG.info("[numen-entity#{}] body died ({}) — loop frozen ({} call(s) in flight)",
                entityUuid, cause, deathInterruptedCalls.size());
    }

    /**
     * The body respawned at its owner after dying — thaw the frozen loop and ONLY NOW restore context:
     * resolve any tool call that was interrupted by the death (so the conversation is valid and the
     * brain learns its task was cut short), then inject a {@code <event>} detailing the death cause.
     * Nothing was fed to the model while dead, so it stayed fully stopped for the whole timer.
     */
    public void onRespawned(String payloadCause) {
        boolean wasFrozen = dead;                 // same-session death (mid-task) vs a fresh loop after relog
        dead = false;
        boolean hadSuspendedTurn = false;         // a turn was mid-flight when the body died
        if (wasFrozen) {
            hadSuspendedTurn = !deathInterruptedCalls.isEmpty();
            for (String id : deathInterruptedCalls) {
                convo.addToolResult(id, TaskResult.fail("任务因你死亡而中断").toJson());
            }
            deathInterruptedCalls = List.of();
            if (convo.lastMessage() instanceof ConvoState.Msg.User) {
                convo.addAssistant(new AssistantTurn("(已中断)", List.of(), null));
            }
        }
        // Prefer the cause carried by the respawn payload (survives a logout that cleared deathCause).
        String raw = (payloadCause != null && !payloadCause.isBlank()) ? payloadCause
                : (deathCause != null ? deathCause : "未知原因");
        String cause = raw.replace('<', '(').replace('>', ')');
        deathCause = null;
        currentTask = null;   // 死亡掉了后台任务(无收尾事件),记账一并清零
        Constants.LOG.info("[numen-entity#{}] respawned ({}) — loop thawed", entityUuid, cause);
        // The death narrative is ALWAYS ambient — never a wake (mind-model constitution §4: 死亡叙事
        // 无特权). Timing of who learns what, in order:
        //   1. a mid-task death already reaches the model through D1: the interrupted tool calls were
        //      resolved above with fail("任务因你死亡而中断"), so the suspended turn itself carries the
        //      death the moment it continues;
        //   2. this <event> is buffered BEFORE the resume below, so it splices into that same resumed
        //      request as the ambient rider — the turn right after the death, no extra LLM call;
        //   3. with no suspended turn (died idle, or a fresh loop after relog) it simply waits for the
        //      next owner input. The OWNER's awareness is vanilla's death broadcast, not the model's job.
        pushEvent("<event kind=\"death\">你刚才死了(" + cause
                + "),物品掉落在死亡地点,手头的任务中断了;现已在主人身边复活。先看看状况,继续或重新规划。</event>", false);
        // D1 resumes the suspended turn: the death-failure results above are exactly the tool results
        // the in-flight turn was waiting on — continuing it is turn completion, not a wake. (Before the
        // downgrade this resume rode the urgent flag; now it is explicit.)
        if (hadSuspendedTurn) {
            tryStartTurn();
        }
    }

    /** 派发回执识别:异步工具的受理结果带 data.async=true 与 data.task_id。 */
    private void trackAsyncDispatch(String toolName, String resultJson) {
        try {
            com.google.gson.JsonObject o =
                    com.google.gson.JsonParser.parseString(resultJson).getAsJsonObject();
            if (!o.has("data") || !o.get("data").isJsonObject()) return;
            com.google.gson.JsonObject data = o.getAsJsonObject("data");
            if (data.has("async") && data.get("async").getAsBoolean() && data.has("task_id")) {
                currentTask = new CurrentTask(data.get("task_id").getAsString(), toolName,
                        System.currentTimeMillis());
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 或形状不符——不是异步回执,不记账。
        }
    }

    /**
     * 收件箱唯一入口(事件侧)。三态路由——消费时机由**发生时的状态**决定,
     * 不由事件类型决定:
     * <ul>
     *   <li><b>回合进行中</b>:进箱躺着。{@link #tryStartTurn} 的守卫会挡下开轮,
     *       到工具批结算的边界自然一次倒箱——"直接发"的最快合法形态;</li>
     *   <li><b>身体在执行后台任务(大脑空闲)</b>:立刻开轮。任务期间的事是军情
     *       (呛水、被袭、任务收尾都可能要改链),模型有权当场重新决策;</li>
     *   <li><b>完全空闲</b>:进箱躺着,等下一个轮子搭车——僵尸击杀只是日记素材,
     *       不值得单独吵主人。只有 {@code principal}(活人在说话:外部桥接的
     *       弹幕/QQ 消息)例外,享受与主人同级的开轮资格。</li>
     * </ul>
     * push 即落盘(跨会话不失忆);死亡冻结期间丢弃。
     */
    public void pushEvent(String xml, boolean principal) {
        if (dead) return;
        // 后台任务收尾:对上 id 清记账。注意先取"发生时的状态"再清——收尾事件
        // 本身发生在任务态,有资格立刻开轮(主动汇报"挖完了")。
        boolean duringTask = currentTask != null;
        if (duringTask && xml.contains("kind=\"task_finished\"")
                && xml.contains("id=\"" + currentTask.id() + "\"")) {
            currentTask = null;
        }
        inbox.pushEvent(xml);
        Constants.LOG.info("[numen-entity#{}] event inboxed{}{}: {}",
                entityUuid, principal ? " (principal)" : "", duringTask ? " (during task)" : "",
                truncate(xml, 120));
        boolean wakeWorthy = principal || duringTask;
        AgentTurnPause previousPause = turnPause;
        turnPause = turnPause.afterWakeEvent(wakeWorthy);
        if (previousPause != turnPause) {
            // The failed LLM turn was already abandoned. This event starts a fresh turn and therefore
            // receives a fresh turn-level retry budget as well.
            turnRetried = false;
            Constants.LOG.info("[numen-entity#{}] wake event resumed chain after recoverable LLM failure",
                    entityUuid);
        }
        if (wakeWorthy) tryStartTurn();
    }

    /** This companion's current persona name (for the panel), or null. */
    public String personaName() {
        return personaName;
    }

    /** The library id this companion's persona came from, or null (legacy / default). */
    public String personaId() {
        return personaId;
    }

    // ---- per-companion LLM provider ----

    /** The client for THIS companion: its provider-library entry resolved fresh
     *  (blank fields → global), or plain global when nothing is assigned. */
    private NumenLlmClient client() {
        return NumenLlmClient.forEndpoint(
                com.dwinovo.numen.agent.llm.ProviderLibrary.instance().resolve(providerEntryId));
    }

    /** The provider-library entry id this companion talks through, or null (= global). */
    public String providerEntryId() {
        return providerEntryId;
    }

    /**
     * Why this companion CAN'T talk right now, in player-facing words — or null when
     * its endpoint is usable. The no-crash safety net for a companion that somehow
     * exists without a provider binding (legacy, bugs): sending a message surfaces
     * this instead of a silent stall.
     */
    public String endpointProblem() {
        var lib = com.dwinovo.numen.agent.llm.ProviderLibrary.instance();
        if (providerEntryId == null || lib.get(providerEntryId) == null) {
            return I18n.get(ModLanguageData.Keys.ENDPOINT_UNBOUND);
        }
        if (!lib.resolve(providerEntryId).hasApiKey()) {
            return I18n.get(ModLanguageData.Keys.ENDPOINT_NO_KEY, lib.get(providerEntryId).name());
        }
        return null;
    }

    /** Point this companion at a provider-library entry (null = back to global settings)
     *  and persist the assignment. Takes effect on the next request — no restart. */
    public void setProviderEntry(String entryId) {
        this.providerEntryId = entryId == null || entryId.isBlank() ? null : entryId;
        com.dwinovo.numen.agent.llm.ProviderLibrary.instance().assign(entityUuid, this.providerEntryId);
        Constants.LOG.info("[numen-entity#{}] provider entry set to {}", entityUuid,
                this.providerEntryId == null ? "(global)" : this.providerEntryId);
    }

    /**
     * Switch this companion's persona at runtime. Three things happen:
     * (1) the persona text/name update, so the next turn's system prompt recomposes with the new
     *     identity (read fresh in {@link #composeSystemPrompt} — no in-flight interruption);
     * (2) a {@code persona-change} event is logged, so a relaunch recovers the current persona
     *     ({@link #restoreFromDisk} via {@code loadCurrentPersona});
     * (3) a reconciliation user message is queued so the model is TOLD its identity was rewritten —
     *     otherwise it sees its own prior self-descriptions in history and contradicts itself.
     */
    public void setPersona(String id, String text, String name) {
        this.personaId = id;
        this.personaText = text;
        this.personaName = name;
        log.appendPersonaChange(id, text, name);
        display.add(new ConvoState.Msg.User(ConvoLog.PERSONA_DIVIDER));   // physical transcript gains a divider now
        String who = (name != null && !name.isBlank()) ? "「" + name + "」" : "新的设定";
        pushEvent("<persona-change>你的人设已更新为" + who
                + "。以上对话确实发生过，但从现在起请完全按新的人设继续，不必解释过去、不要延续旧的说话风格。</persona-change>", false);
    }

    /**
     * Set the companion's STARTING persona at summon — records it (event-sourced) but does NOT inject a
     * reconciliation or divider, since a brand-new companion has no prior history/identity to reconcile.
     * No-op if a persona is already set (avoids stomping a resumed companion).
     */
    public void setInitialPersona(String id, String text, String name) {
        if (personaText != null && !personaText.isBlank()) return;   // already has one
        this.personaId = id;
        this.personaText = text;
        this.personaName = name;
        log.appendPersonaChange(id, text, name);
    }

    // ---- internals ----

    /**
     * Splice any buffered owner prompts into the conversation as a single
     * {@code user} message. Only call this at a protocol-valid point (no
     * assistant reply in flight, no tool results pending) — the callers
     * ({@link #tryStartTurn}) guarantee that. Multiple buffered prompts are
     * joined with newlines into one message to avoid back-to-back {@code user}
     * messages that some backends reject.
     */
    private void drainInbox() {
        if (inbox.isEmpty()) return;
        List<String> parts = new ArrayList<>();
        // 身体正在后台跑异步任务:每个回合都把这行放在最前,模型不用调工具就知道
        // 手头有活(记账来自派发回执,收尾事件对上 id 即清,见 trackAsyncDispatch)。
        if (currentTask != null) {
            long secs = (System.currentTimeMillis() - currentTask.sinceMs()) / 1000;
            parts.add("<current_task>" + currentTask.id() + " " + currentTask.tool()
                    + " 后台进行中(已 " + secs + "s)。task_status 查进度,task_stop 叫停,"
                    + "完成会自动收到 task_finished 事件,不要轮询。</current_task>");
        }
        // <known_blocks> 随用户回合注入,不放系统提示:它随放置/使用工作站而变,
        // 放系统提示会打碎请求前缀的 prompt cache。系统提示(工具 schema+操作
        // 核心+人设)因此字节级稳定,支持缓存的服务商整段命中。
        AbstractClientPlayer envBody = resolveEntity();
        String knownBlocks = workBlocks.formatXml(envBody != null ? envBody.level() : null);
        if (!knownBlocks.isEmpty()) {
            parts.add(knownBlocks);
        }
        parts.addAll(inbox.drain());
        String merged = String.join("\n", parts);
        convo.addUser(merged);
        // A fresh owner directive starts a new tool-chain: restart the turn
        // counter (just log numbering now that the hard cap is gone).
        convo.resetTurnCount();
    }

    private void tryStartTurn() {
        if (dead) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: body dead", entityUuid);
            return;
        }
        if (turnPause.isPaused()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: pause={}", entityUuid, turnPause);
            return;
        }
        // 「外接大脑」模式的总闸:模式开着,身体归外部 MCP 驱动者,内置大脑一轮都不开。
        // 收件箱照收不误(事件不丢),模式关掉后主人下次说话就能带着这段空白期的见闻开轮。
        // 聊天框禁用只是体验层——弹幕/QQ 桥接送进来的 principal 消息只有这里拦得住。
        if (McpMode.instance().enabled()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: 外接大脑模式开启中", entityUuid);
            return;
        }
        if (awaitingLlmResponse) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: awaitingLlmResponse", entityUuid);
            return;
        }
        if (compacting) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: compacting", entityUuid);
            return;
        }
        if (dispatcher.busy()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: tool call(s) outstanding", entityUuid);
            return;
        }
        // Safe point: no assistant reply in flight and no tool results
        // outstanding, so the conversation ends with either a tool result or a
        // final assistant message — a user message can now be appended legally.
        drainInbox();
        if (convo.snapshot().isEmpty()) return;
        // No hard cap on tool-call turns and no loop guard — a capable agent
        // legitimately chains many tasks, and resuming a timed-out move_to
        // repeats the exact same call. Runaways are stopped by the owner's
        // interrupt.
        // Endpoint check against THIS companion's selected provider entry — error-driven
        // guidance, no fallback, no crash: a missing binding or keyless entry says
        // exactly what to do (same words the chat screen shows via endpointProblem()).
        String problem = endpointProblem();
        if (problem != null) {
            Constants.LOG.warn("[numen-entity#{}] can't start turn: {}", entityUuid, problem);
            turnPause = AgentTurnPause.BLOCKED;
            return;
        }

        // Auto-compaction gate: the last request's true context size (as the
        // API counted it) is within the buffer of the window — summarize FIRST,
        // then this method re-runs and dispatches the turn on the compacted
        // history. Mirrors Claude Code's autoCompactIfNeeded. Backends that
        // never send a usage frame leave lastPromptTokens at 0 — fall back to
        // a local estimate so the gate still fires instead of never.
        int window = modelWindow();
        int contextTokens = lastPromptTokens > 0
                ? lastPromptTokens
                : estimateContextTokens(convo.snapshot());
        if (contextTokens >= window - AUTO_COMPACT_BUFFER_TOKENS
                && convo.snapshot().size() >= MIN_COMPACT_MESSAGES
                && compactFailures < MAX_COMPACT_FAILURES) {
            Constants.LOG.info("[numen-entity#{}] auto-compacting: {} context {} tokens >= {} - {}",
                    entityUuid, lastPromptTokens > 0 ? "measured" : "estimated",
                    contextTokens, window, AUTO_COMPACT_BUFFER_TOKENS);
            startCompaction(true);
            return;
        }

        convo.incrementTurn();
        awaitingLlmResponse = true;

        var tools = ToolRegistry.all();
        var snapshot = convo.snapshot();
        String systemPrompt = composeSystemPrompt();

        Constants.LOG.info("[numen-entity#{}] turn {}: convo={} msgs, tools={}",
                entityUuid, convo.turnCount(), snapshot.size(), tools.size());

        // Capture the current generation; if the owner interrupts before this
        // call resolves, handleResponse sees the mismatch and discards it.
        final int gen = turnGeneration;
        final VoiceTurn vt = beginVoiceTurn();
        livePartial.setLength(0);
        client().chatStreaming(snapshot, tools, systemPrompt, tapForUi(gen, vt.sink()))
                .whenComplete((res, err) -> {
                    vt.finish().run();
                    bounceBackToMain(gen, res, err);
                });
    }

    // ---- compaction ----

    /**
     * Owner pressed the GUI's Compact button. Runs the same machinery as the
     * automatic path; silently ignored when a turn is in flight or there is
     * too little history to be worth a summarization call.
     */
    public void requestCompact() {
        if (!canCompact()) {
            Constants.LOG.info("[numen-entity#{}] manual compact ignored (busy={}, msgs={})",
                    entityUuid, isBusy(), convo.snapshot().size());
            return;
        }
        if (!NumenLlmClient.isConfigured()) return;
        startCompaction(false);
    }

    /**
     * Fire the summarization call: full history + the compact prompt as the
     * final user message, NO tools, a minimal system prompt (skills XML and the
     * persona would only waste the very tokens we're trying to reclaim).
     */
    private void startCompaction(boolean auto) {
        compacting = true;
        List<ConvoState.Msg> request = new ArrayList<>(convo.snapshot());
        request.add(new ConvoState.Msg.User(COMPACT_PROMPT));
        Constants.LOG.info("[numen-entity#{}] compaction started ({}, {} msgs)",
                entityUuid, auto ? "auto" : "manual", request.size() - 1);
        final int gen = turnGeneration;
        final long startMs = System.currentTimeMillis();
        client().chatStreaming(request, List.of(), COMPACT_SYSTEM_PROMPT, null)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(
                        () -> finishCompaction(gen, auto, startMs, res, err)));
    }

    private void finishCompaction(int gen, boolean auto, long startMs,
                                  NumenLlmClient.ChatResult res, Throwable err) {
        if (gen != turnGeneration) {
            Constants.LOG.info("[numen-entity#{}] discarding interrupted compaction (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;   // abort() already reset the compacting flag
        }
        compacting = false;

        String summary = (err == null && res != null)
                ? extractSummary(res.turn().content()) : null;
        if (summary == null || summary.isBlank()) {
            compactFailures++;
            Constants.LOG.warn("[numen-entity#{}] compaction failed ({}/{}): {}",
                    entityUuid, compactFailures, MAX_COMPACT_FAILURES,
                    err != null ? unwrap(err) : "empty summary");
            // The conversation is untouched — the next turn just runs uncompacted.
            if (auto || hasQueuedPrompts()) tryStartTurn();
            return;
        }

        addTokens(res.freshTokens());   // 压缩调用同样烧 token,计入累计
        String wrapped = SUMMARY_HEADER + summary.strip();
        // The summary is lossy, but the very next prompt is usually a follow-up
        // to the model's LAST reply ("那第三点展开讲讲") — so that reply crosses
        // the boundary VERBATIM, not summarized. Same as Claude Code's
        // preservedMessages whitelist: far history lossy, last output lossless.
        List<ConvoState.Msg> preserved = preservedTail();
        // Accounting for the boundary line (Claude Code's compactMetadata):
        // the summarization call's own prompt_tokens IS the exact size of the
        // history being compacted — more precise than the previous turn's count.
        JsonObject meta = new JsonObject();
        meta.addProperty("trigger", auto ? "auto" : "manual");
        meta.addProperty("droppedMessages", convo.snapshot().size() - preserved.size());
        meta.addProperty("durationMs", System.currentTimeMillis() - startMs);
        if (res.promptTokens() > 0) {
            meta.addProperty("preTokens", res.promptTokens());
            if (res.totalTokens() > res.promptTokens()) {
                meta.addProperty("summaryTokens", res.totalTokens() - res.promptTokens());
            }
        }
        // Boundary into the JSONL first (relaunches replay the compacted view;
        // the raw pre-compaction history stays in the file as an archive), then
        // swap the in-memory history without re-notifying the sink. The visible
        // transcript only gains a divider — the owner's chat never vanishes.
        log.appendCompactSummary(wrapped, preserved, meta);
        List<ConvoState.Msg> next = new ArrayList<>();
        next.add(new ConvoState.Msg.User(wrapped));
        next.addAll(preserved);
        convo.replaceAll(next);
        display.add(new ConvoState.Msg.User(ConvoLog.COMPACT_DIVIDER));
        lastPromptTokens = 0;   // unknown until the next request reports usage
        compactFailures = 0;
        Constants.LOG.info(
                "[numen-entity#{}] compaction done ({}): {} tokens → summary ({} chars) + {} preserved msg(s) in {} ms",
                entityUuid, auto ? "auto" : "manual",
                res.promptTokens() > 0 ? String.valueOf(res.promptTokens()) : "?",
                wrapped.length(), preserved.size(), System.currentTimeMillis() - startMs);

        // Auto-compaction interrupted a turn that was about to dispatch —
        // resume it so the task chain continues on the compacted history. After
        // a MANUAL compact we stay idle unless prompts queued up meanwhile.
        if (auto || hasQueuedPrompts()) tryStartTurn();
    }

    /**
     * Messages carried verbatim across a compaction boundary: the trailing
     * final assistant reply (no tool calls), when that is how the history
     * ends. Compaction only fires when the loop is idle, so a settled chain
     * ending in a spoken reply is the normal case; anything else (defensive)
     * preserves nothing and the summary stands alone. The slice must stay
     * protocol-valid on its own — a tool-calling assistant without its
     * results, or an orphan tool result, would 400 the next request.
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
        long cjk = 0, ascii = 0;
        for (ConvoState.Msg msg : history) {
            String text = switch (msg) {
                case ConvoState.Msg.User u -> u.content();
                case ConvoState.Msg.Tool t -> t.content();
                case ConvoState.Msg.Assistant a -> {
                    StringBuilder sb = new StringBuilder(
                            a.turn().content() == null ? "" : a.turn().content());
                    for (LlmToolCall tc : a.turn().toolCalls()) {
                        sb.append(tc.name()).append(tc.arguments());
                    }
                    yield sb.toString();
                }
            };
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 0x2E7F) cjk++; else ascii++;
            }
        }
        return (int) (cjk + ascii / 4 + history.size() * 8L) + ESTIMATED_FIXED_OVERHEAD_TOKENS;
    }

    /**
     * The compact prompt asks for a two-stage response: a private
     * {@code <analysis>} scratchpad, then the real {@code <summary>}. Only the
     * summary is kept — persisting the analysis would waste the very tokens
     * compaction reclaims. Tolerant of models that skip or mangle the tags:
     * an unclosed {@code <summary>} reads to the end, no tags at all falls
     * back to the whole text minus any analysis block.
     */
    private static String extractSummary(String raw) {
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

    private String composeSystemPrompt() {
        // Per-companion persona wins; fall back to the global default; with neither,
        // the persona slot says so EXPLICITLY — an unconfigured persona is a valid
        // state (自由发挥), not a missing one. Read fresh each turn so a live persona
        // switch takes effect next turn with no in-flight interruption.
        String base = (personaText != null && !personaText.isBlank())
                ? personaText : Services.CONFIG.getSystemPrompt();
        if (base == null || base.isBlank()) base = "未配置人设,可以自由发挥。";
        String skillsXml = SkillRegistry.instance().formatXml();

        // 系统提示只放会话内稳定的层——人设/操作核心/技能表/情绪词表。
        // 会变化的 <known_blocks> 随用户回合注入(drainInbox),
        // 让这里成为字节级稳定的缓存前缀。
        StringBuilder sb = new StringBuilder();
        // Persona = the mutable "who you are" layer, wrapped so it's clearly delimited from the
        // immutable operating core (ENTITY_PROMPT) that follows.
        sb.append("<persona>\n").append(base.strip()).append("\n</persona>");
        sb.append(ENTITY_PROMPT);
        if (!skillsXml.isEmpty()) {
            sb.append("\n\n").append(skillsXml);
        }
        return sb.toString();
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientNumenLookup.resolve(entityUuid);
    }

    /**
     * A turn died on a SYSTEM failure (network error / null response). Queued owner
     * prompts are pending intent and must not be held hostage by the dead turn — a
     * REPL that errors returns to idle and drains its command queue; same here: if
     * prompts are waiting, start a fresh turn carrying them. Only an OWNER interrupt
     * holds the queue (Stop means stop). With no inputs queued, latch a recoverable failure;
     * the next owner prompt or wake-worthy event resumes it without weakening explicit Stop.
     */
    private void failTurnKeepQueue() {
        // The failed turn is over. Any fresh turn started now or by a later wake event gets its own
        // one-retry allowance rather than inheriting the exhausted budget from this turn.
        turnRetried = false;
        if (inbox.isEmpty()) {
            turnPause = AgentTurnPause.RECOVERABLE_FAILURE;
            return;
        }
        // The failed turn may have left the conversation ending on a user message
        // (its prompts were flushed before dispatch). Cap it so the fresh turn's
        // flush doesn't create back-to-back user messages (some backends 400 those).
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(连接中断)", List.of(), null));
        }
        Constants.LOG.info("[numen-entity#{}] turn failed with {} inboxed item(s) — starting a fresh turn with them",
                entityUuid, inbox.promptCount() + inbox.eventCount());
        tryStartTurn();
    }

    private void bounceBackToMain(int gen, NumenLlmClient.ChatResult res, Throwable err) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> handleResponse(gen, res, err));
    }

    private void handleResponse(int gen, NumenLlmClient.ChatResult res, Throwable err) {
        // Owner interrupted this turn while the call was in flight: abort()
        // already settled the conversation (and, if a newer turn has since
        // started, awaitingLlmResponse belongs to *that* call). Discard wholesale
        // — do NOT touch awaitingLlmResponse here, or we'd clear the newer turn's.
        if (gen != turnGeneration) {
            Constants.LOG.info("[numen-entity#{}] discarding interrupted LLM response (gen {} != {})",
                    entityUuid, gen, turnGeneration);
            return;
        }
        awaitingLlmResponse = false;
        livePartial.setLength(0);   // committed 消息(下方 addAssistant)接管显示

        // World is unloading (owner quit / disconnected): the client→server channel is gone, so a
        // dispatched ExecuteToolPayload would NPE in the platform sender. Drop this turn quietly.
        if (Minecraft.getInstance().getConnection() == null) {
            Constants.LOG.info("[numen-entity#{}] client disconnected — dropping LLM turn", entityUuid);
            turnPause = AgentTurnPause.BLOCKED;
            return;
        }

        if (err != null) {
            Constants.LOG.warn("[numen-entity#{}] LLM call failed: {}",
                    entityUuid, unwrap(err));
            // MID-STREAM deaths (idle watchdog, connection reset after first tokens) are
            // outside the transport's retry scope — the SDKs surface them to the caller,
            // and the caller's standard answer is: discard the partial (never entered the
            // conversation) and re-run the whole turn. One turn-level retry, immediate;
            // the transport already backed off its own classes.
            if (!turnRetried) {
                turnRetried = true;
                Constants.LOG.info("[numen-entity#{}] re-running failed turn once", entityUuid);
                awaitingLlmResponse = true;
                final int gen2 = turnGeneration;
                final VoiceTurn vt2 = beginVoiceTurn();   // 重跑也重新开口(失败那次的半截语音随 beginTurn 作废)
                livePartial.setLength(0);                 // 失败那次的半截文字同理作废
                client().chatStreaming(convo.snapshot(), ToolRegistry.all(),
                                composeSystemPrompt(), tapForUi(gen2, vt2.sink()))
                        .whenComplete((r2, e2) -> {
                            vt2.finish().run();
                            bounceBackToMain(gen2, r2, e2);
                        });
                return;
            }
            failTurnKeepQueue();
            return;
        }
        turnRetried = false;   // a response landed — the next failure gets a fresh retry
        if (res == null || res.turn() == null) {
            Constants.LOG.warn("[numen-entity#{}] LLM returned null turn", entityUuid);
            failTurnKeepQueue();
            return;
        }
        AssistantTurn turn = res.turn();
        // True context size of the request we just made — the auto-compaction
        // signal. 0 when the backend sent no usage frame (then auto never fires).
        if (res.promptTokens() > 0) {
            lastPromptTokens = res.promptTokens();
        }
        addTokens(res.freshTokens());

        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            // Final text reply — spoken to the owner. Chain settles; the next
            // prompt resumes the same conversation with a fresh turn count.
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[numen-entity#{}] assistant (final): {}",
                        entityUuid, turn.content());
            } else {
                Constants.LOG.info("[numen-entity#{}] assistant (final, empty content)", entityUuid);
            }
            convo.resetTurnCount();
            // A prompt that arrived during this final turn was buffered; now that
            // the chain has settled, start a fresh turn to answer it.
            if (hasQueuedPrompts()) tryStartTurn();
            return;
        }

        // Hand this turn's calls to the dispatcher — it runs them serially and
        // reports each result back through the sink (into the conversation), then
        // calls onAllSettled so the loop starts the next turn.
        dispatcher.dispatch(turn.toolCalls().stream()
                .map(tc -> new ToolInvocation(tc.id(), tc.name(), tc.arguments()))
                .toList());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
