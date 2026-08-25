package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.event.EventQueue;
import com.dwinovo.numen.event.EventTypes;
import com.dwinovo.numen.event.JsonlJournal;
import com.dwinovo.numen.agent.llm.CompactSplit;
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
     * The model context window now comes per-model from {@code ProviderRegistry} (numen_providers.json),
     * looked up from the configured provider+model at the auto-compaction gate; unknown/custom models
     * fall back to {@code ProviderRegistry.DEFAULT_CTX} (64k).
     */
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
    /** 给目标评估器看的对话上限。够装下整个目标期间,又不至于把整段会话都发一遍。 */
    private static final int JUDGE_WINDOW_CHARS = 8000;
    /** 每条截到这个长度:工具结果可能上千字,评估器不需要读完。 */
    private static final int JUDGE_LINE_CHARS = 400;
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

    private final UUID entityUuid;
    /** JSONL persistence under {@code config/numen/conversations/<uuid>.jsonl}. */
    private final ConvoLog log;
    private final ConvoState convo;
    /** Functional-block coordinate memory, injected as {@code <known_blocks>}. */
    private final WorkBlockMemory workBlocks;
    /** 长期目标;null = 没有。每轮收尾自己续上,见 {@link #steerToGoal}。 */
    private com.dwinovo.numen.agent.goal.GoalState goal;
    /**
     * 收件箱(宪法 §4):主人的话与世界事件的统一进箱口。协议约束是它存在的
     * 底层原因——{@code assistant(tool_calls)} 后面必须直接跟 {@code tool}
     * 结果,user 消息不能插队,所以输入一律进箱,在 {@link #drainInbox} 的
     * 协议安全点一次倒空。三态路由(什么输入什么状态下配开轮)在
     * {@link #pushEvent};条目、落盘、年龄标注、排空规则全在 {@link EventQueue}。
     *
     * <p>什么时候<b>熟</b>是队列自己的规则(急件 / 攒够条数 / 攒够时长),它不认识
     * "死亡""外接大脑"这些概念——内脑此刻能不能来取件是 {@link #paused()} 的事,
     * 队列只答熟度、只管台账。
     */
    private EventQueue queue;
    /** 后台异步任务记账(派发回执置位,对上 id 的 task_finished 清零);null = 身体空闲。
     *  客户端自记账,不走新网络包:回执与事件本来就都经过这里。 */
    private CurrentTask currentTask;

    /**
     * 在跑的后台任务的客户端记账。{@code standing} = 这件活没有终点(不会有
     * task_finished),只能被换掉——模型必须分得清,否则会干等一个永不到来的事件。
     */
    /**
     * 她此刻在做什么——<b>服务端推来的镜像</b>,不是本地推断的账本
     * (见 {@link com.dwinovo.numen.network.payload.CurrentTaskPayload})。
     */
    private record CurrentTask(String id, String tool, String describe, long sinceMs,
                               boolean standing) {}

    /**
     * 绑定的人设 id——<b>真源在人设库</b>,这里只记 id,正文用时现取(落盘在
     * {@link CompanionHome} 的 {@code binding.json})。于是编辑人设对所有同伴立即生效,
     * 不管它这会儿加载没加载:没有副本,就没有"把修改推给每个实例"这种要写代码维护的同步。
     *
     * <p>人设文件被删/改名 → 这里悬空 → 回落全局默认人格。不留兜底快照:那会变成
     * 第二真源,改人设时必然对不上,而"我把人设删了"是主人自己的选择。
     */
    private String personaId;

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

    /**
     * Runs this turn's tool calls one at a time and reports each result back
     * through a {@link ToolDispatcher.Sink} into the conversation. All the
     * tool-execution plumbing (serial queue, ship-to-server, completion,
     * timeout) lives in here, not in the loop.
     */
    private final ToolDispatcher dispatcher;

    /** A summarization call is in flight; blocks normal turns until it lands. */
    private boolean compacting = false;
    /** Context size of the last request as the API counted it (0 = unknown yet). */
    private int lastPromptTokens = 0;
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

    /** 表现层(打字机/气泡/说话位/语音)与 token 台账,回合机之外的两件事。 */
    private final TurnPresenter presenter;
    private final TokenLedger tokens;

    EntityAgentLoop(UUID entityUuid) {
        this.entityUuid = entityUuid;
        Path numenRoot = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("numen");
        this.log = ConvoLog.atFile(CompanionHome.chat(entityUuid));
        this.convo = new ConvoState(msg -> {
            log.append(msg);
            display.add(msg);
        });
        this.workBlocks = WorkBlockMemory.forEntity(entityUuid);
        this.queue = new EventQueue(JsonlJournal.atFile(CompanionHome.inbox(entityUuid)));
        // 目标跨重进游戏活着 —— 长期目标就该是长期的,重启不该把它弄丢。
        this.goal = CompanionHome.goal(entityUuid);
        this.providerEntryId = CompanionHome.binding(entityUuid).providerId();
        this.dispatcher = new ToolDispatcher(entityUuid, new ToolDispatcher.Sink() {
            @Override public void onResult(ToolInvocation inv, String resultJson) {
                harvestWorkBlocks(inv.name(), resultJson);
                convo.addToolResult(inv.id(), resultJson);
            }
            @Override public void onAllSettled() {
                tryStartTurn();
            }
            @Override public AbstractClientPlayer entity() {
                return resolveEntity();
            }
        });
        this.presenter = new TurnPresenter(entityUuid,
                () -> awaitingLlmResponse,
                () -> awaitingLlmResponse || dispatcher.busy(),
                () -> turnGeneration,
                this::personaName);
        this.tokens = new TokenLedger(entityUuid);
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
    /**
     * 与自动压缩闸门同一口径的模型上下文窗口。真源是<b>这只同伴绑定的档案</b>
     * ({@link com.dwinovo.numen.agent.llm.ProviderLibrary.Entry#contextWindow()}),
     * 请求走哪份档案窗口就按哪份算;没有档案(遗留同伴)才回落旧的全局配置。
     */
    private int modelWindow() {
        var entry = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().get(providerEntryId);
        if (entry != null) {
            return entry.contextWindow();
        }
        return com.dwinovo.numen.agent.provider.ProviderRegistry.contextWindow(
                com.dwinovo.numen.client.screen.LlmProviders.normalize(
                        com.dwinovo.numen.platform.Services.CONFIG.getProvider()),
                com.dwinovo.numen.platform.Services.CONFIG.getModel());
    }

    /** 上下文水位百分比(基于上次请求的实测 prompt tokens);usage 未知时返回 0。 */
    public int contextPercent() {
        if (lastPromptTokens <= 0) return 0;
        return Math.min(100, Math.round(lastPromptTokens * 100f / Math.max(1, modelWindow())));
    }

    private void restoreFromDisk() {
        tokens.load();
        log.migrateIfNeeded();   // upgrade a pre-v2 file in place before reading it (crash-safe, keeps a .v1.bak)
        personaId = CompanionHome.binding(entityUuid).personaId();
        // 重进后 loop 是全新的,死亡停牌按状态恢复:她死着的时候主人退出游戏,
        // 队列里可能躺着急件——不补这一下她会在还没复活的时候就开口。
        // 真源是名册说她死没死(状态),不是"我收到过死亡消息"(事件)。
        if (NumenRoster.instance().isDead(entityUuid)) {
            dead = true;
            Constants.LOG.info("[numen-entity#{}] 恢复时她还死着 — 停牌等复活", entityUuid);
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

    /** Live partial of the in-flight assistant reply ("" when idle) — GUI typewriter source. */
    public String livePartial() {
        return presenter.livePartial();
    }

    /** 在飞回合的思考流("" = 没有或已落库)——G 面板思考块的流式数据源。 */
    public String liveReasoning() {
        return presenter.liveReasoning();
    }

    /** 本同伴累计消耗的 token(跨会话持久化)。 */
    public long totalTokensUsed() {
        return tokens.total();
    }
    public ConvoState convo() { return convo; }

    /** Read-only physical transcript for the GUI (see {@link #display}). */
    public List<ConvoState.Msg> display() {
        return java.util.Collections.unmodifiableList(display);
    }

    /** Snapshot of prompts (GUI or {@code NumenGateway}) still waiting for the
     *  next protocol-valid splice point — the GUI renders these as pending. */
    public List<String> queuedPrompts() {
        return queue.chatPreview();
    }

    /**
     * 主人在聊天框里说话。
     *
     * <p>死着也照收——{@link #tryStartTurn} 第一道守卫 {@link #paused()} 就含死亡,开不起来轮,
     * 话安安静静躺在收件箱里,聊天里显示成 ⌛ 待发气泡,复活时随死亡叙事一起送出。
     * (外接大脑模式早就是这个做法:"收件箱照收不误,事件不丢"。)直接丢掉的话,
     * 死前一秒说的留着、死后一秒说的蒸发——而主人根本看不见那一 tick 的分界,
     * 只会觉得这模组有时候吞消息。
     */
    /**
     * @return 这句话有没有被压着(true = 内脑没能当场把请求发出去)。这是<b>观察</b>不是预测:
     *         {@code tryStartTurn} 之后有没有真的发出请求,看的就是它自己的状态。调用方拿
     *         {@code isBusy()} 之类的东西自己猜是猜不准的——那里面的 {@code currentTask != null}
     *         并不在开轮的闸门里,她在跟随时你说的话当场就发得出去。闸门以后再加几道,这里也不会跑偏。
     *
     *         <p>它只喂 {@link com.dwinovo.numen.api.NumenGateway.Delivery} 那份给桥接看的汇报,
     *         不驱动任何界面。外脑驾驶时内脑整体停牌,它恒为 true——那不是"她忙",是她不在这条线上,
     *         所以 {@code Delivery} 在那种情况下单报 {@code TO_EXTERNAL_BRAIN}。
     */
    public boolean submitPrompt(String text) {
        return enqueueOwnerWords("<query>" + text + "</query>", text);
    }

    /**
     * 主人打了一条斜杠命令(见 {@code ChatCommands})。
     *
     * <p>命令是主人对<b>客户端</b>说的话,展开成什么由客户端决定。两半分开放:
     * <ul>
     *   <li>{@code echo} 进 {@code <query>} 里 —— 聊天流显示的就是它
     *       ({@link com.dwinovo.numen.client.chat.OwnerWordsMode} 只取标记内的内容);</li>
     *   <li>{@code expanded} 跟在标记<b>外面</b> —— 模型看得到,聊天流不显示。</li>
     * </ul>
     * 技能正文几千字,塞进气泡里主人没法看;而模型必须拿到全文。一条消息两种读法,
     * 正是 {@code <query>} 这个标记存在的意义。
     *
     * @param echo     主人打的原文,例如 {@code /build 在河边盖个木屋}
     * @param expanded 客户端替他展开的内容(技能正文等);空则退化成一句普通的话
     */
    public boolean submitCommand(String echo, String expanded) {
        String wire = "<query>" + echo + "</query>"
                + (expanded == null || expanded.isBlank() ? "" : "\n" + expanded);
        return enqueueOwnerWords(wire, echo);
    }

    /**
     * 主人的话进队列。{@code wire} 是拼好的原文(模型看到的),{@code logged} 只用于日志。
     */
    private boolean enqueueOwnerWords(String wire, String logged) {
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
        // 主人的话恒为急件:人说话了就该有回应。队列不区分类型,只看这个标记。
        queue.push(EventTypes.QUERY, wire, System.currentTimeMillis(), true);
        // 外脑驱动期间面板画的是现场缓冲——主人的话得当场可见,不能等谁取走才出现。
        // 这里是所有主人话的单一咽喉(面板/快捷对话/语音/桥接),挂点只此一处。
        if (McpMode.instance().driving()) {
            com.dwinovo.numen.mcp.server.McpTranscript.owner(entityUuid, logged);
        }
        Constants.LOG.info("[numen-entity#{}] user prompt ({} chars){}{}: {}",
                entityUuid, wire.length(),
                wasAborted ? " — reset previous abort" : "",
                deferred ? " — buffered (mid-turn)" : "",
                truncate(logged, 200));
        tryStartTurn();
        return !awaitingLlmResponse;
    }

    /**
     * 断线静默:只收拾<b>客户端</b>——作废在飞的回应、给未决调用补取消结果、
     * 清半截打字和语音。<b>不叫停身体</b>。
     *
     * <p>她的身体还在服务器里 tick,任务照样跑完,收尾进离线出箱等主人回来
     * ——"我帮你把矿挖完了"这条链正是为此做的。登出时叫停她,恰好把它废掉。
     *
     * <p>所以它跟 {@link #abort()} 是两件事,不能互相复用:登出时连接已经断了,
     * 往那儿发叫停包会抛 NPE 打断 {@code onLoggingOut} 的后半段(花名册清空等等
     * 一律不执行)。这个问题的答案不是"让发包静默失败",而是登出根本不该叫停。
     */
    public void quiesce() {
        abort(false);
    }

    /** Driven once per client tick (see {@code AgentLoopRegistry.tickAll}) — backstop timeout. */
    public void clientTick() {
        dispatcher.tick();
        presenter.tick();
        // 攒够时长也要开口:光靠"输入到达"触发的话,最后一条之后就再没人问了
        if (!awaitingLlmResponse && !dispatcher.busy()) {
            maybeDrain();
        }
    }

    /**
     * Pull functional-block coordinates out of successful tool results into
     * {@link WorkBlockMemory}. The result already carries them — interact_at
     * reports the station it activated (a chest/furnace/table it opened) as
     * {@code block} + {@code x/y/z} — this just stops the loop from forgetting
     * them once the result scrolls out of context. {@code workBlocks.record}
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
                case "interact_at" -> {
                    if (data.has("block") && data.has("x")) {
                        // id 的归一化(去命名空间、模组包一层的路径)全在 record 里做
                        workBlocks.record(data.get("block").getAsString(), new net.minecraft.core.BlockPos(
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

    /**
     * 此刻在干的那件事(工具名/长活任务名),没有具体动作时返回 null——
     * 头顶「正在回复中」气泡拿它当副文本:长任务跑几十秒时,主人得看见
     * 她在挖矿而不是卡死了。
     */
    public String currentActivity() {
        if (currentTask != null) {
            // 服务端给的人话描述("挖 64 块泥土"),不是工具 id("mine")——
            // 气泡是给主人看的,他不该在头顶上读内部标识符。
            String d = currentTask.describe();
            return d != null && !d.isBlank() ? d : currentTask.tool();
        }
        return dispatcher.currentToolName();
    }

    /** A summarization call is currently in flight (drives the GUI status line). */
    public boolean isCompacting() {
        return compacting;
    }

    /** 已经流回来的摘要字数。流式回调在网络线程上加,渲染在主线程上读。 */
    private final java.util.concurrent.atomic.AtomicInteger compactChars =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 整理记忆的进度 0~1。
     *
     * <p><b>它不是"完成了百分之几"</b>——摘要多长事先不知道,没有分母。这是一条随
     * 流回来的字数逼近 1 的曲线:永远差一点,收尾时整条消失。给的是"还在动"这个事实,
     * 不是一个会食言的承诺。
     */
    public double compactProgress() {
        if (!compacting) return 0.0;
        return 1.0 - Math.exp(-(compactChars.get() / 4.0) / 1200.0);
    }

    /**
     * 现在不能整理记忆的理由;{@code null} = 能。
     *
     * <p>判据只有这一份。{@code /compact} 的补全行要把理由写出来,而"能不能"和"为什么
     * 不能"是同一个问题——分成两处迟早说不到一块儿去。
     */
    // ---- 长期目标 ----

    /** 当前的长期目标;{@code null} = 没有。 */
    public com.dwinovo.numen.agent.goal.GoalState goal() {
        return goal;
    }

    /**
     * 定一个目标。整份目标<b>只在这里</b>交给她一次;之后每轮只补评估器那句"还差什么"。
     *
     * @param echo 主人打的原文({@code /goal 挖 128 个钻石})。走 {@link #submitCommand} 是为了
     *             聊天里有个气泡——他打了字就该看见自己打了什么,跟 {@code /build} 一个待遇
     */
    public void setGoal(com.dwinovo.numen.agent.goal.GoalState next, String echo) {
        this.goal = next;
        CompanionHome.setGoal(entityUuid, next);
        if (next == null || paused()) {
            return;
        }
        next.countTurn();
        CompanionHome.setGoal(entityUuid, next);
        submitCommand(echo, com.dwinovo.numen.agent.goal.GoalPrompts.initialDirective(next));
    }

    /**
     * 收工。目标只有"在"和"不在"两种,所以做完、放弃、跑够轮次、主人喊停——<b>结果都是这里</b>,
     * 区别只在 {@code why} 那句话。
     *
     * @param why 收工的原因,只进日志。<b>不往聊天栏说</b>——目标是后台跑着的东西,
     *            结束时不该弹一句打断主人;面板顶上那行消失本身就是信号,想追问 {@code /goal}
     */
    public void clearGoal(String why) {
        if (goal == null) {
            return;
        }
        Constants.LOG.info("[numen-entity#{}] 目标收工({} 轮,{}):{}",
                entityUuid, goal.turnsExecuted(), why == null ? "主人清掉" : why, goal.objective());
        goal = null;
        CompanionHome.setGoal(entityUuid, null);
    }

    /**
     * 一轮收尾了:判一次目标达没达成。
     *
     * <p>判定<b>不由她自己做</b>——另开一次干净的调用(不带对话历史、不带人设、不带工具),
     * 只看条件、身体事实和最近几句。执行的人和判定的人分开,她才骗不了自己。
     *
     * <p>队列里还有别的排着就先不判——那些本来就会开起一轮,那一轮收尾时再说。
     */
    private void steerToGoal() {
        if (goal == null || paused() || !queue.isEmpty() || goalJudging) {
            return;
        }
        // 身体还在干活就别催。
        //
        // 我们的工具是异步的:派发回执立刻回来,链条当场收尾,而她其实动都还没动完。不拦
        // 的话就是每隔一个 API 往返问一次"挖完了吗"——什么也没推进,纯烧 token。
        //
        // 醒来不用另写:任务干完会推 task_finished 进队列,那本来就会开起一轮;那一轮
        // 收尾时再走到这里,currentTask 已经空了,续跑自然接上。
        //
        // 常驻任务(跟随这种)要放行:它永远不报完成,等它等于永远不续。
        if (currentTask != null && !currentTask.standing()) {
            Constants.LOG.debug("[numen-entity#{}] 目标续跑让位:身体在做 {}",
                    entityUuid, currentTask.tool());
            return;
        }
        // 额度不在这儿拦:每一轮的成果都要判过再说。拦在判定前面的话,最后一轮白干——
        // 而那恰恰是最可能已经做完的一轮。额度只管"还要不要再推下一轮",见 finishJudging。
        judgeGoal();
    }

    /** 评估在飞:一轮只判一次,回来之前不再发第二次。 */
    private boolean goalJudging;

    /**
     * 跑一次评估。用同伴自己绑的那个模型,但是<b>另一次调用</b>——"新鲜"指的是这个,
     * 不是换个更小的模型。
     */
    private void judgeGoal() {
        var target = goal;
        String facts = runtimeStateXml();
        String since = sinceGoalForJudge();
        goalJudging = true;
        final int gen = turnGeneration;
        client().chatStreaming(
                        List.of(new ConvoState.Msg.User(
                                com.dwinovo.numen.agent.goal.GoalPrompts.evaluatorQuery(
                                        target, facts, since))),
                        List.of(),
                        com.dwinovo.numen.agent.goal.GoalPrompts.evaluatorSystem(),
                        null)
                .whenComplete((res, err) -> Minecraft.getInstance().execute(
                        () -> finishJudging(gen, target, res, err)));
    }

    private void finishJudging(int gen, com.dwinovo.numen.agent.goal.GoalState judged,
                               NumenLlmClient.ChatResult res, Throwable err) {
        goalJudging = false;
        // 判的是上一个目标,或者中途被打断/换了目标 —— 这次结果作废。
        if (gen != turnGeneration || goal == null || goal != judged) {
            return;
        }
        if (err != null || res == null) {
            // 判不出来不等于做完了。歇一轮,下次收尾再判。
            Constants.LOG.warn("[numen-entity#{}] 目标评估失败,这一轮先不续:{}",
                    entityUuid, unwrap(err));
            return;
        }
        goal.addTokens(res.freshTokens());
        var verdict = com.dwinovo.numen.agent.goal.GoalPrompts.readVerdict(res.turn().content());
        goal.setLastReason(verdict.reason());
        boolean giveUp = goal.noteStuck(verdict.stuck());
        Constants.LOG.info("[numen-entity#{}] 目标评估 第{}轮 {}:{}", entityUuid, goal.turnsExecuted(),
                verdict.met() ? "达成" : verdict.stuck() ? "打转 x" + goal.stuckStreak() : "还差",
                verdict.reason());
        if (verdict.met()) {
            clearGoal("目标达成:" + verdict.reason());
            return;
        }
        if (giveUp) {
            // 连着几轮同一堵墙:告诉主人卡在哪,别再转了。判"没进展"的是评估器,不是她自报
            // ——她报不准,前面验过。
            clearGoal("过不去,先收工了:" + verdict.reason() + " —— 换个说法或者搭把手再 /goal");
            return;
        }
        if (!goal.hasTurnsLeft()) {
            // 还没做完,但额度到顶了:停下来告诉主人,不是闷头继续——她"以为没做完"是
            // 会一直转的,而每轮主请求两万 token 起。
            clearGoal("跑够 " + com.dwinovo.numen.agent.goal.GoalState.MAX_GOAL_TURNS
                    + " 轮还没完,先收工了(还差:" + verdict.reason() + ")—— 想接着做再说一次 /goal");
            return;
        }
        long now = System.currentTimeMillis();
        goal.countTurn();
        CompanionHome.setGoal(entityUuid, goal);
        queue.push(EventTypes.GOAL,
                com.dwinovo.numen.agent.goal.GoalPrompts.progress(verdict.reason(), goal, now),
                now, true);
        maybeDrain();
    }

    /**
     * 给评估器看的:<b>目标设定以来</b>发生的一切。
     *
     * <p>不是"最近几句"。她可能分三次才凑够数,只看末尾就永远拼不出累计的证据——实测过
     * 一次:第一轮挖到 64/128 那条早滚出窗口,后面几轮评估器咬定"没有挖矿证据",把她赶去
     * 满世界找矿四分钟。
     *
     * <p>从末尾往回扫到目标设定那条({@code <goal>} 就在里面),字数封顶兜底——整理记忆
     * 会把那条冲掉,不封顶就一路扫到会话开头。
     */
    private String sinceGoalForJudge() {
        List<ConvoState.Msg> all = convo.snapshot();
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        int budget = JUDGE_WINDOW_CHARS;
        for (int i = all.size() - 1; i >= 0 && budget > 0; i--) {
            ConvoState.Msg msg = all.get(i);
            String line = switch (msg) {
                case ConvoState.Msg.User u -> "owner/system: " + u.content();
                case ConvoState.Msg.Assistant a -> "companion: " + a.turn().content();
                case ConvoState.Msg.Tool t -> "tool result: " + t.content();
            };
            line = truncate(line, JUDGE_LINE_CHARS);
            lines.addFirst(line);
            budget -= line.length();
            if (msg instanceof ConvoState.Msg.User u && u.content().contains("<goal>")) {
                break;   // 扫到目标设定那条了,再往前跟这个目标无关
            }
        }
        return String.join("\n", lines).strip();
    }

    public String compactProblem() {
        if (dead) return "她已经不在了";
        if (compacting) return "已经在整理了";
        if (queue.count(EventTypes.COMPACT) > 0) return "整理已经排上了";
        // 不看忙不忙:整理进队列排着,到安全点自己执行。按了就一定会发生,
        // 主人不必盯着什么时候能按。
        // 也不看记录长短:整理多少、什么时候整理是主人的事。条数门槛只属于自动整理
        // ——那是替他省一次没意义的请求,不是替他做决定。
        return endpointProblem();   // 整理要发一次请求,没绑模型/没填 key 一样做不了
    }

    /** {@code /clear} 现在按不按得下。同 {@link #compactProblem} 的形状,但不查端点:清空不发请求。 */
    public String clearProblem() {
        if (dead) return "她已经不在了";
        if (queue.count(EventTypes.CLEAR) > 0) return "清空已经排上了";
        return null;
    }

    /**
     * 主人要求清空上下文。与 {@link #requestCompact} 同一走法:急件进队列,到安全点执行,
     * 忙的时候也按得下。空闲时排空当场发生,调用返回时已经清完。
     *
     * @return 拒绝的理由;{@code null} = 已排上(空闲时当场清完)
     */
    public String requestClearContext() {
        String problem = clearProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] manual clear refused: {}", entityUuid, problem);
            return problem;
        }
        queue.push(EventTypes.CLEAR, "清空上下文", System.currentTimeMillis(), true);
        maybeDrain();
        return null;
    }

    /**
     * 清空上下文——她带进下一轮的历史清成白纸,而<b>记录一个字不删</b>:日志 append-only,
     * 落一条边界事件,重启后 {@code load} 从边界起步、{@code loadDisplay} 照常给全量。
     * 绑定/人设/技能全不动:清的是对话,不是她是谁。只能在安全点调(排空路径保证)。
     */
    private void performClear() {
        log.appendClearBoundary();
        convo.replaceAll(List.of());
        display.add(new ConvoState.Msg.User(ConvoLog.CLEAR_DIVIDER));
        lastPromptTokens = 0;
        compactFailures = 0;
        Constants.LOG.info("[numen-entity#{}] 上下文清空(记录留档)", entityUuid);
    }


    /** Owner prompts are queued, waiting to flush into the conversation. */
    public boolean hasQueuedPrompts() {
        return !queue.isEmpty();
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
        abort(true);
    }

    private void abort(boolean stopBody) {
        // 主人按停止 = 不要她接着跑了。目标跟着收工,否则这一轮刚断下一轮又自己续上,
        // 停止键就成了摆设。想接着做再说一次 /goal,成本就是一句话。
        clearGoal(goal == null ? null : "按停止收工了:" + goal.objective());
        // 语音无条件先闭嘴:不管打断的是在飞的 turn 还是排队的 prompt,
        // 主人按下 Stop 时还在播/待播的语音都不该继续。
        presenter.interruptVoice();
        // 头顶的思考/残句气泡同理随打断收起
        com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
        if (isBusy()) {
            // Priority 1: stop the running turn, in-flight compaction, or a body background task.
            // Responses are generation-stamped, so any in-flight one is discarded.
            turnGeneration++; // any in-flight LLM response is now stale → discarded on arrival
            boolean wasAwaitingLlm = awaitingLlmResponse;
            boolean wasBackgroundTask = currentTask != null;
            // 断线时清掉本地镜像:下一个存档跟这件活无关,而那时不会有服务端推送来纠正它。
            // (按停止走的是服务端顶替/取消,那边会推 idle 过来。)
            currentTask = null;
            awaitingLlmResponse = false;
            compacting = false;
            presenter.clearPartial();   // 半截打字随打断作废
            presenter.finishStreamLine();

            // Synthesize cancelled results for EVERY outstanding call (in flight AND
            // still-queued) so the assistant(tool_calls) message keeps matching tool
            // results — otherwise the next request is protocol-invalid (HTTP 400). Real
            // results arriving later are dropped as "late" by the dispatcher.
            // stopBody=true(主人按停止):cancelAndDrain 顺手触发 CompanionLifecycle.onAbort,
            // 内容包据此停掉身体那边的活。断线登出不走这条 —— 见 quiesce。
            List<String> cancelled = dispatcher.cancelAndDrain(stopBody);
            String why = stopBody ? "interrupted by owner" : "owner disconnected";
            for (String id : cancelled) {
                convo.addToolResult(id, "{\"success\":false,\"message\":\"" + why + "\"}");
            }

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
            Constants.LOG.info("[numen-entity#{}] {} (awaitingLlm={}, backgroundTask={}, cancelledTools={}, queued={})",
                    entityUuid, why, wasAwaitingLlm, wasBackgroundTask, cancelled.size(),
                    queue.count(EventTypes.QUERY));
        } else if (queue.count(EventTypes.QUERY) > 0) {
            // 空闲时打断:清掉被取代的指令,事实留着。清哪些不在这里判断——
            // 由类型表的 clearedByInterrupt 决定,加一种新类型不用回来改这儿。
            int dropped = queue.clearInterrupted();
            Constants.LOG.info("[numen-entity#{}] interrupt cleared {} queued prompt(s) ({} left)",
                    entityUuid, dropped, queue.size());
        }
    }

    // ---- external control (an MCP client / Claude drives the body directly) ----

    /**
     * 外接大脑此刻是不是驾驶席上的那个脑——现算自 {@code McpMode.driving()},
     * <b>不存副本、不做同步</b>:存一份就有两个答案,而两个答案迟早不一致。
     * 失联回退的接管与交还也在同一口径里(driving 翻转即生效,零滞后)。
     */
    public boolean isExternallyDriven() {
        return McpMode.instance().driving();
    }

    /**
     * 外接大脑收件(get_events 的取货口):{@code urgentOnly} 时只在队里有急件才取,
     * 长轮询靠它省着等;到点了不管急不急有什么给什么。渲染与内脑
     * {@code drainInbox} 同一份 {@link EventQueue#render}——外脑看到的事件文本
     * 和内脑一字不差。控制条目(整理/清空)是对内脑说的,留在队里等模式关闭,
     * 与 drainInbox 同一条 takeWhile 规则。
     *
     * @return 取走的事件拼段;这次没取到返回 null(继续等或如实说没有)
     */
    public String takeEventsForExternal(boolean urgentOnly) {
        if (urgentOnly && !queue.hasUrgent()) return null;
        long now = System.currentTimeMillis();
        List<EventQueue.Entry> taken = queue.takeWhile(e -> !isControlEntry(e.type()), now);
        if (taken.isEmpty()) return null;
        List<String> parts = EventQueue.render(taken, now);
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /** 急件叫醒挂点直通(get_events 长轮询停靠用)。主线程调用。 */
    public void addUrgentListener(Runnable listener) {
        queue.addUrgentListener(listener);
    }

    public void removeUrgentListener(Runnable listener) {
        queue.removeUrgentListener(listener);
    }

    /**
     * 外接大脑替她说话(say 工具):头顶气泡 + 聊天栏定格行 + 现场缓冲 + 语音,
     * 走的全是内脑说话的同一套表现层。语音整段排队尾——连续的 say 连着播,
     * 不互相掐;主人的打断键照样一刀切停。
     */
    public void externalSay(String text) {
        String shown = com.dwinovo.numen.client.chat.ChatDisplayModes.current().assistantText(text);
        if (shown.isBlank()) shown = text;   // 全是动作记号也别无声吞掉——原样示人
        com.dwinovo.numen.mcp.server.McpTranscript.say(entityUuid, shown);
        com.dwinovo.numen.client.chat.ChatLines.companion(presenter.speakerName(), shown);
        com.dwinovo.numen.client.hud.SpeechBubbles.say(entityUuid, shown);
        presenter.sayExternal(text);
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
        presenter.interruptVoice();   // 尸体不说话:停播 + 清队列
        // Resolve at respawn: every outstanding call (in flight + still queued) — all
        // are listed in the assistant message, so all need results.
        deathInterruptedCalls = dispatcher.cancelAndDrain();
        turnGeneration++;          // discard any in-flight LLM response (halt output)
        awaitingLlmResponse = false;
        compacting = false;
        presenter.clearPartial();
        // 箱子一样不清:每条都盖着时间戳,模型自己看得出哪些是死之前的。
        // 我们替它判断"哪些信息过期了",反而会删掉有用的叙事("我死前刚吃了东西")。
        dead = true;   // 停牌:开轮/排空/目标推进全过 paused(),死着一轮不开
        Constants.LOG.info("[numen-entity#{}] body died ({}) — 停牌 ({} call(s) in flight)",
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
        Constants.LOG.info("[numen-entity#{}] respawned ({}) — loop thawed", entityUuid, cause);
        // 死亡是急件——她关于自己处境的认知几乎每一条都作废了:物品掉在死亡地点、
        // 位置从矿洞变成了主人身边、手上的任务没了、血量装备全变了。这不分"任务中死"
        // 还是"空闲死",所以这里没有任何判据。
        AbstractClientPlayer body = resolveEntity();
        long dayTime = body != null ? body.level().getDayTime() : 0L;
        pushEvent(EventTypes.EVENT, com.dwinovo.numen.event.NumenEvents.compose(
                dayTime, com.dwinovo.numen.event.NumenEvents.Kind.DEATH, null,
                "你刚才死了(" + cause + "),背包里的东西全掉在死亡地点了;"
                        + "现已在主人身边复活。先看看状况再决定下一步。"),
                System.currentTimeMillis(), true);
        // dead 在开头已复位,停牌自动解除:下个 tick 一问熟度就发现急件,连同死亡
        // 期间攒下的一切(事件、主人说的话)一起走。
    }

    /**
     * 服务端说她在做什么——直接照抄,不判断、不合并、不推断。
     *
     * <p>这是 {@code currentTask} 的<b>唯一</b>写入点。客户端不靠"我派出去过什么"
     * 自己记账:那样服务器重启重放、死亡复活重放起来的活它一概不知道,头顶没气泡、
     * 模型也看不见。
     */
    public void onCurrentTask(com.dwinovo.numen.network.payload.CurrentTaskPayload p) {
        if (p.idle()) {
            currentTask = null;
            return;
        }
        // 用服务端给的已耗时回推起点,重放回来的活也不会从这一刻重新计时
        currentTask = new CurrentTask(p.taskId(), p.tool(), p.describe(),
                System.currentTimeMillis() - p.elapsedMs(), p.standing());
    }

    /**
     * 收一条进队列的输入(事件侧)。什么时候倒出去<b>由队列自己说了算</b>——
     * 急件、攒够条数、攒够时长,锁着就等。这里不做任何"这条该不该立刻开轮"的判断:
     * 那种判据正是会漏的东西(它漏掉过"死亡打断了后台任务")。
     *
     * <p>死着也照收:每条都盖着真实时间戳,复活后模型看得出哪些发生在死亡之前。
     */
    public void pushEvent(String type, String text, long ts, boolean urgent) {
        queue.push(type, text, ts > 0 ? ts : System.currentTimeMillis(), urgent);
        Constants.LOG.info("[numen-entity#{}] queued {}{}: {}",
                entityUuid, type, urgent ? " URGENT" : "", truncate(text, 120));
        if (urgent) {
            AgentTurnPause previousPause = turnPause;
            turnPause = turnPause.afterWakeEvent(true);
            if (previousPause != turnPause) {
                // 上一轮的失败已经作废,这是新的一轮,重试预算跟着重置。
                turnRetried = false;
                Constants.LOG.info("[numen-entity#{}] urgent 输入让链条从失败中恢复", entityUuid);
            }
        }
        maybeDrain();
    }

    /**
     * 问队列一次:现在该不该主动开一轮。
     *
     * <p>"该排空"不等于"立刻发出"——协议不允许时(assistant 的 tool_calls 中间不能插
     * user 消息)这一 tick 排不成,下一 tick 再问。{@code shouldDrain} 只读状态、
     * 可以反复问,所以<b>不存在"错过的排空"</b>,也就不需要记住"我刚才想排空"。
     */
    private void maybeDrain() {
        if (paused()) {
            return;   // 停牌不开口——也别把"主动开轮"的日志刷成噪音
        }
        int level = com.dwinovo.numen.client.data.ClientPrefs.initiativeLevel();
        long now = System.currentTimeMillis();
        if (!queue.shouldDrain(now, level)) {
            return;
        }
        Constants.LOG.info("[numen-queue#{}] 主动开轮:{}(攒了 {} 条/阈值 {},最老 {}s/上限 {}s,档位 {})",
                entityUuid,
                queue.hasUrgent() ? "有急件"
                        : (queue.size() >= EventQueue.thresholdOf(level) ? "攒够了" : "攒久了"),
                queue.size(), EventQueue.thresholdOf(level),
                queue.oldestAgeMs(now) / 1000L, EventQueue.maxWaitMsOf(level) / 1000L, level);
        tryStartTurn();
    }


    /** 人设正文:库里现取(编辑立即生效);没绑或条目没了 → null,回落全局默认人格。 */
    private String personaText() {
        var p = persona();
        return p == null ? null : p.text();
    }

    /** 人设名(面板显示用),没绑或条目没了则 null。 */
    public String personaName() {
        var p = persona();
        return p == null ? null : p.name();
    }

    private com.dwinovo.numen.persona.PersonaLibrary.Persona persona() {
        return personaId == null ? null
                : com.dwinovo.numen.persona.PersonaLibrary.instance().get(personaId);
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
        CompanionHome.bind(entityUuid,
                CompanionHome.binding(entityUuid).withProvider(this.providerEntryId));
        Constants.LOG.info("[numen-entity#{}] provider entry set to {}", entityUuid,
                this.providerEntryId == null ? "(global)" : this.providerEntryId);
    }

    /**
     * 运行时换人设。只做两件事:改绑定(下一轮 {@link #composeSystemPrompt} 现取正文,
     * 不打断在飞的请求),再往聊天流插一条分隔记号。
     *
     * <p>不给模型注入"从现在起你是…"的和解消息——新系统提示本身就是最强的指令,
     * 历史口吻要不要接得上是主人自己的选择,不由我们替他兜。
     */
    public void setPersona(String id) {
        this.personaId = id;
        CompanionHome.bind(entityUuid, CompanionHome.binding(entityUuid).withPersona(id));
        log.appendPersonaDivider();   // 落盘的记号:重启后回看也知道这儿换过
        display.add(new ConvoState.Msg.User(ConvoLog.PERSONA_DIVIDER));
    }

    /**
     * 召唤时定下的初始人设——不插分隔记号:全新的同伴没有"之前"可分隔。
     * 已经有人设就不动(别把恢复出来的同伴冲掉)。
     */
    public void setInitialPersona(String id) {
        if (personaId != null) return;
        this.personaId = id;
        CompanionHome.bind(entityUuid, CompanionHome.binding(entityUuid).withPersona(id));
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
    /** 本轮是否由主人夺话触发——drainInbox 取件时按队列的 QUERY 标记判定,
     *  空排空的接续轮为 false;beginVoiceTurn 据此选硬停或句界衔接。 */
    private boolean ownerSpokeThisTurn;

    private boolean drainInbox() {
        if (queue.isEmpty()) {
            ownerSpokeThisTurn = false;   // 接续轮:她接自己的话,不硬停
            return false;
        }
        long now = System.currentTimeMillis();
        // 先到先得。遇到一条不该当文本处理的(整理/清空)就停下:前面排着的先走完,它留在
        // 队首等下一个安全点。不插队——插队一旦开了口子,以后每加一种条目都要重新回答
        // "它插不插队"。
        List<EventQueue.Entry> text = queue.takeWhile(e -> !isControlEntry(e.type()), now);
        if (text.isEmpty()) {
            // 队首是控制条目,轮到它了。连着按的几次算一次;批里混着清空就清空说了算
            // ——整理要的是腾地方,清空把地方全腾出来了。
            //
            // 返回 true 是<b>必须的</b>:调用方那道 compacting 闸在这句之前,这里再置位已经
            // 拦不住它了——只从本方法 return 的话,整理会和一次普通请求并排跑起来。
            // 清空虽是同步的也返回 true:排在它后面的话该进崭新的上下文,留给下一次排空。
            List<EventQueue.Entry> control = queue.takeWhile(e -> isControlEntry(e.type()), now);
            if (control.stream().anyMatch(e -> EventTypes.CLEAR.equals(e.type()))) {
                performClear();
                return true;
            }
            Constants.LOG.info("[numen-entity#{}] 整理记忆:排到了,开始", entityUuid);
            startCompaction(false);
            return true;
        }
        ownerSpokeThisTurn = text.stream().anyMatch(e -> EventTypes.QUERY.equals(e.type()));
        List<String> parts = new ArrayList<>();
        // current_task is live runtime state. It is attached request-locally by
        // modelContextSnapshot(), never written into conversation history or JSONL.
        // <known_blocks> 随用户回合注入,不放系统提示:它随放置/使用工作站而变,
        // 放系统提示会打碎请求前缀的 prompt cache。系统提示(工具 schema+操作
        // 核心+人设)因此字节级稳定,支持缓存的服务商整段命中。
        AbstractClientPlayer envBody = resolveEntity();
        String knownBlocks = workBlocks.formatXml(envBody != null ? envBody.level() : null);
        if (!knownBlocks.isEmpty()) {
            parts.add(knownBlocks);
        }
        parts.addAll(EventQueue.render(text, now));
        String merged = String.join("\n", parts);
        convo.addUser(merged);
        // A fresh owner directive starts a new tool-chain: restart the turn
        // counter (just log numbering now that the hard cap is gone).
        convo.resetTurnCount();
        return false;
    }

    /** 队列里不当文本、要在安全点单独处理的条目(整理/清空)。 */
    private static boolean isControlEntry(String type) {
        return EventTypes.COMPACT.equals(type) || EventTypes.CLEAR.equals(type);
    }

    /**
     * 内脑此刻停牌:她死了,或驾驶席在外接大脑手里。<b>暂停判定的单一出口</b>——
     * 开轮({@link #tryStartTurn})、主动排空({@link #maybeDrain})、目标推进
     * ({@link #setGoal}/{@code steerToGoal})全走这一处;加一种新的暂停理由 =
     * 这里加一个条件,不是在哪条路径上再长一个 if(从前散着的三个特例就是那么
     * 长出来的)。队列不认识这些:停牌是消费者自己的事,队列只答熟度。
     */
    private boolean paused() {
        return dead || isExternallyDriven();
    }

    private void tryStartTurn() {
        if (paused()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: 停牌 (dead={}, external={})",
                    entityUuid, dead, isExternallyDriven());
            return;
        }
        if (turnPause.isPaused()) {
            Constants.LOG.debug("[numen-entity#{}] tryStartTurn skipped: pause={}", entityUuid, turnPause);
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
        // 排空可能自己接管这一次(整理记忆):那就到此为止,别再叠一次普通请求上去。
        if (drainInbox()) return;
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
            // 配置问题不能静默:快捷键用户不开面板,聊天栏警示行是唯一出口
            com.dwinovo.numen.client.chat.ChatLines.notice(presenter.speakerName(), truncate(problem, 160));
            com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
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
        var snapshot = modelContextSnapshot();
        String systemPrompt = composeSystemPrompt();

        Constants.LOG.info("[numen-entity#{}] turn {}: convo={} msgs, tools={}",
                entityUuid, convo.turnCount(), snapshot.size(), tools.size());

        // Capture the current generation; if the owner interrupts before this
        // call resolves, handleResponse sees the mismatch and discards it.
        final int gen = turnGeneration;
        final TurnPresenter.VoiceTurn vt = presenter.beginVoiceTurn(ownerSpokeThisTurn);
        presenter.clearPartial();
        // 头顶挂思考气泡:从发出请求到回应落地的整个空窗都有反馈
        NumenLlmClient llm = client();
        llm.chatStreaming(snapshot, tools, systemPrompt,
                        presenter.tapForUi(gen, vt.sink(), llm.provider()::extractReasoningDelta))
                .whenComplete((res, err) -> {
                    vt.finish().run();
                    bounceBackToMain(gen, res, err);
                });
    }

    // ---- compaction ----

    /**
     * 主人要求整理记忆({@code /compact})。
     *
     * <p>不当场执行,<b>进队列排着</b>:她忙的时候也按得下,到了安全点自己走。判据全在
     * {@link #compactProblem}——原来那道额外的 apiKey 检查是静默 return 的,表现就是
     * "按了没反应"。
     *
     * @return 拒绝的理由;{@code null} = 已经排上了
     */
    public String requestCompact() {
        String problem = compactProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] manual compact refused: {}", entityUuid, problem);
            return problem;
        }
        // 恒为急件:主人明确要求的事不该跟世界事件一起攒着等阈值。
        queue.push(EventTypes.COMPACT, "整理记忆", System.currentTimeMillis(), true);
        maybeDrain();
        return null;
    }

    /**
     * Fire the summarization call: the OLDER span of the history + the compact
     * prompt as the final user message, NO tools, a minimal system prompt (skills
     * XML and the persona would only waste the very tokens we're trying to
     * reclaim). 最近约 {@link #KEEP_RECENT_TOKENS} 的消息不进请求也不被替换——
     * 它们原文跟在摘要之后(切分规则见 {@link CompactSplit})。整段都在近段预算内
     * 时(基本只有手动 /compact 会遇到)退化为全量总结,只逐字保留末尾那句回答。
     */
    private void startCompaction(boolean auto) {
        compacting = true;
        compactChars.set(0);
        CompactSplit.Split split = CompactSplit.byRecentBudget(convo.snapshot(), KEEP_RECENT_TOKENS);
        final List<ConvoState.Msg> toSummarize;
        final List<ConvoState.Msg> kept;
        if (split.toSummarize().isEmpty()) {
            toSummarize = new ArrayList<>(convo.snapshot());
            kept = preservedTail();
            toSummarize.removeAll(kept);
        } else {
            toSummarize = new ArrayList<>(split.toSummarize());
            kept = split.kept();
        }
        List<ConvoState.Msg> request = new ArrayList<>(toSummarize);
        request.add(new ConvoState.Msg.User(COMPACT_PROMPT));
        Constants.LOG.info("[numen-entity#{}] compaction started ({}, summarizing {} msgs, keeping {} verbatim)",
                entityUuid, auto ? "auto" : "manual", toSummarize.size(), kept.size());
        final int gen = turnGeneration;
        final long startMs = System.currentTimeMillis();
        client().chatStreaming(request, List.of(), COMPACT_SYSTEM_PROMPT, chunk -> {
            String delta = com.dwinovo.numen.client.voice.VoicePipeline.extractContentDelta(chunk);
            if (delta != null && !delta.isEmpty()) compactChars.addAndGet(delta.length());
        }).whenComplete((res, err) -> Minecraft.getInstance().execute(
                () -> finishCompaction(gen, auto, startMs, kept, res, err)));
    }

    private void finishCompaction(int gen, boolean auto, long startMs, List<ConvoState.Msg> kept,
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

        tokens.add(res.freshTokens());   // 压缩调用同样烧 token,计入累计
        String wrapped = SUMMARY_HEADER + summary.strip();
        // 近段原文跨过压缩边界(startCompaction 切好的那份):压缩只在空闲时跑,期间
        // compacting 闸挡住新回合,历史不会在等待摘要的路上变化。
        List<ConvoState.Msg> preserved = kept;
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

    /**
     * <b>发给模型的就是这一份</b>——会话上下文加上这一轮临时挂载的运行期状态
     * ({@code <runtime_state>}/{@code <current_task>})。源会话与落盘日志一个字不动。
     *
     * <p>私有:它是<b>现算</b>的,只在发请求那一刻成立。拿去给别人展示,得到的会是
     * "历史上那条消息 + 此刻的状态"——一条从未被发送过的消息。
     */
    private List<ConvoState.Msg> modelContextSnapshot() {
        return AgentRequestContext.attach(convo.snapshot(), runtimeStateXml());
    }

    /**
     * 这一轮临时挂载的运行期状态。全部现算,一个字都不入会话历史——包进同一个
     * {@code <runtime_state>} 里,模型只需认一个信封。
     */
    private String runtimeStateXml() {
        String body = currentTaskXml() + inventoryXml() + effectsXml() + ridingXml();
        String xml = body.isEmpty() ? "" : "<runtime_state>" + body + "</runtime_state>";
        // 原样打出来。"她看到的世界"平时完全不可见,于是"她怎么会这么说"只能靠猜——
        // 而她说的数跟事件对不上时,分不清是她编的还是我们喂错了。开一次 debug 就有答案。
        //
        // (靠它抓到过一次:任务完成事件和 <current_task> 镜像在同一条请求里打架,
        //  镜像还停在旧进度,于是她照着旧数说"还差一点"。)
        Constants.LOG.debug("[numen-ctx#{}] runtime_state → {}", entityUuid, xml);
        return xml;
    }

    /** Live async-task state, recomputed for every worker request and never persisted. */
    private String currentTaskXml() {
        CurrentTask task = currentTask;
        if (task == null) return "";
        long elapsed = Math.max(0, System.currentTimeMillis() - task.sinceMs()) / 1000;
        // 有没有"干完"这回事,决定她该等还是该换:有终点的活等它的 task_finished;
        // 常驻的活(跟随 / 一直钓鱼)永远不会有那条事件,只能被换掉。分不清这一点,
        // 她要么干等一个永不到来的事件,要么把还没干完的活当成已经结束。
        // 两支只差在「会不会有 task_finished」。怎么换是一样的 —— 直接派新的。
        String tail = task.standing()
                ? "This is a STANDING job — it has no finish line and will NEVER send a "
                  + "task_finished event. It keeps running until something replaces it."
                : "This background call is ACTIVE and will send a task_finished event when it ends; "
                  + "use task_status only when the owner asks for progress.";
        // 身体只有一个槽，派新活自然顶掉旧活，所以这里必须说「直接派」而不是
        // 「别再派」——后者会让模型先 task_stop 再派，白跑一轮。
        // 只有「停下来什么也不干」才需要 task_stop。
        String swap = " There is only ONE body: dispatching another body action REPLACES this one "
                + "outright — you do NOT need to stop it first. Use task_stop only when the owner "
                + "wants her to stop and do nothing.";
        return "<current_task id=\"" + xml(task.id()) + "\" tool=\""
                + xml(task.tool()) + "\" state=\"running\" standing=\"" + task.standing()
                + "\" elapsed_s=\"" + elapsed
                + "\">" + xml(truncate(task.describe(), 600)) + ". "
                + tail + swap + "</current_task>";
    }

    /** 上一次渲染背包块用的那份快照本身。收到新包时缓存会换一个新对象,比身份就够,
     *  不用拿时间戳去凑版本号(同一毫秒两次推送会撞号,而且读起来像在判断时效)。 */
    private ClientNumenState.Snapshot inventoryRenderedFrom;
    private String inventoryRendered = "";
    /** "请求里没背包"只说一次,别把每一轮都刷满。 */
    private boolean inventoryMissingLogged;

    /**
     * 她此刻带着什么。服务端在背包真变化时推一份过来({@code CompanionStateWatch}),
     * 这里只负责渲染——所以"换没换"只有一个信号:快照的时间戳。
     *
     * <p>放进请求而不是让她调 {@code get_self_status},省的是<b>一整轮</b>(请求 + 工具结果 +
     * 再请求)。合并同类计数,不报耐久附魔:要精确到槽位时她该调 {@code inspect_gui}。
     */
    private String inventoryXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded()) {
            // 链路断在客户端这一节:服务端没推过,或者推的是别的同伴。请求里就没有背包这回事,
            // 她只能靠对话历史猜——这条日志的存在就是为了不用再靠猜去查它。只在进入这个
            // 状态时说一次,别把每一轮都刷满。
            if (!inventoryMissingLogged) {
                inventoryMissingLogged = true;
                Constants.LOG.info("[numen-inv] {} 请求里没有背包块({})", entityUuid,
                        snapshot == null ? "客户端一份快照都没收到" : "身体未加载");
            }
            return "";
        }
        inventoryMissingLogged = false;
        if (snapshot == inventoryRenderedFrom) return inventoryRendered;
        inventoryRendered = renderInventory(snapshot);
        inventoryRenderedFrom = snapshot;
        // 这行只在快照真换了新的时才打,所以"年龄"读的是"这段时间背包没变过",不是延迟。
        // 背包明明变了却不见这一行,才是链路断了。
        Constants.LOG.info("[numen-inv] 背包块进请求:{} 字符,这份快照 {}ms 前收到",
                inventoryRendered.length(), System.currentTimeMillis() - snapshot.receivedAtMs());
        return inventoryRendered;
    }

    /**
     * 她身上这一刻在生效的东西。<b>只能现挂,不能进历史</b> —— 它带倒计时,沉进对话历史
     * 之后十轮再读到的不只是过时,是一个理直气壮的错秒数。
     *
     * <p>没有效果就一个字都不发:空块也是要读的 token,而"没写"和"写了没有"对模型是一样的。
     */
    private String effectsXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded() || snapshot.effects().isEmpty()) {
            return "";
        }
        return "<effects>" + renderEffects(snapshot, System.currentTimeMillis()) + "</effects>";
    }

    /**
     * 她这一刻骑没骑着东西。与效果同一纪律:<b>只能现挂,不能进历史</b>——上下船是
     * 随时翻转的身体事实,沉进历史就成了理直气壮的错。没骑就一个字都不发。
     * 有这一行,模型不会再对自己坐着的船发第二次 interact_entity,也知道 goto
     * 会驾着它走、任何要走路的动作都会自己下来。
     */
    private String ridingXml() {
        var snapshot = ClientNumenState.get(entityUuid).orElse(null);
        if (snapshot == null || !snapshot.loaded() || snapshot.vehicleId() < 0) {
            return "";
        }
        return "<riding>" + xml(snapshot.vehicleType()) + " (entity id " + snapshot.vehicleId()
                + "). goto pilots a boat over water toward the target; any action that needs "
                + "walking steps off by itself — no need to click the vehicle again.</riding>";
    }

    static String renderEffects(ClientNumenState.Snapshot snapshot, long nowMs) {
        StringBuilder out = new StringBuilder();
        for (var effect : snapshot.effects()) {
            int left = snapshot.remainingTicks(effect, nowMs);
            if (left == 0) {
                continue;   // 收到之后已经走完了
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(effect.getEffect().unwrapKey()
                    .map(key -> key.location().getPath()).orElse("unknown"));
            if (effect.getAmplifier() > 0) {
                out.append(" ").append(effect.getAmplifier() + 1);   // 原版 UI 的口径:0 级显示 I
            }
            out.append(left < 0 ? " (infinite)" : " (" + (left / 20) + "s left)");
        }
        return out.toString();
    }

    static String renderInventory(ClientNumenState.Snapshot snapshot) {
        java.util.Map<String, Integer> totals = new java.util.TreeMap<>();
        for (net.minecraft.world.item.ItemStack stack : snapshot.items()) {
            if (!stack.isEmpty()) {
                totals.merge(itemId(stack), stack.getCount(), Integer::sum);
            }
        }
        StringBuilder items = new StringBuilder();
        totals.forEach((id, count) -> {
            if (items.length() > 0) items.append(", ");
            items.append(id).append(" x").append(count);
        });
        // 手上那份不带数量,是刻意的:它本来就是 carrying 里的一堆,写上数量她会当成另一堆
        // 加起来(实测她把主手 64 个熔炉和清单里同一批数成了 128)。总数只有一处,手只指
        // 向它,结构上就没什么可重复计的。
        return "<inventory>Everything your body carries right now, totalled across all 36 backpack "
                + "slots — trust it and do not spend a call on get_self_status to rediscover it. "
                + "Call inspect_gui only when exact slots matter. A newer tool result wins over this."
                + "\ncarrying=" + (items.length() == 0 ? "nothing" : items)
                + "\nholding (already counted above)=main " + describe(snapshot.mainHand())
                + ", off " + describe(snapshot.offhand())
                + "</inventory>";
    }

    /** 手上拿的<b>是什么</b>,不含数量——数量归 {@code carrying} 一处管。 */
    private static String describe(net.minecraft.world.item.ItemStack stack) {
        return stack.isEmpty() ? "(empty)" : xml(itemId(stack));
    }

    private static String itemId(net.minecraft.world.item.ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        String brew = brewLabel(stack);
        return brew.isEmpty() ? id : id + "[" + brew + "]";
    }

    /**
     * 瓶子里装的是什么。<b>治疗、剧毒、夜视的 item id 全都是 {@code minecraft:potion}</b> ——
     * 内容在 {@code POTION_CONTENTS} 组件里,只印 id 的话她背包里三瓶完全不同的东西长得
     * 一模一样,选不出该喝哪瓶。药箭同理。
     *
     * <p>印的是原版药水的<b>注册名</b>({@code strong_healing}、{@code long_poison}),不是
     * "安全/危险"那种结论 —— 该不该喝是她的判断,身体只负责说清楚这是什么。喷溅型和滞留型
     * 本来就是另外的 item id,照实印就分开了,不用另写判据。
     */
    private static String brewLabel(net.minecraft.world.item.ItemStack stack) {
        var contents = stack.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        contents.potion().ifPresent(held -> label.append(held.unwrapKey()
                .map(key -> key.location().getPath()).orElse("unknown")));
        // 酿造出来的、模组的药水没有预设名,效果只在自定义列表里 —— 两处都读,不用维护白名单。
        for (var effect : contents.customEffects()) {
            if (label.length() > 0) {
                label.append('+');
            }
            label.append(effect.getEffect().unwrapKey()
                    .map(key -> key.location().getPath()).orElse("unknown"));
        }
        return label.toString();
    }

    private String composeSystemPrompt() {
        // Per-companion persona wins; fall back to the global default; with neither,
        // the persona slot says so EXPLICITLY — an unconfigured persona is a valid
        // state (自由发挥), not a missing one.
        String base = (personaText() != null && !personaText().isBlank())
                ? personaText() : Services.CONFIG.getSystemPrompt();
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
        // 本能名册。宪法 §6 定的那份自述一直在注册表里躺着,从来没送到模型眼前 —— 于是它
        // 不知道身体会自己做哪些事,既可能重复去做,也可能对"我怎么突然挪了二十格"毫无头绪。
        // 名册是纯注册表内容、两端都注册,所以这里本地就算得出来,不需要任何网络。
        String reflexes = com.dwinovo.numen.task.reflex.ReflexRegistry.overview();
        if (!reflexes.isEmpty()) {
            sb.append("\n\n<instincts>\n").append(reflexes).append("\n</instincts>");
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
    /** 最近一次回合失败的人话原因(驱动聊天栏的警示行)。 */
    private String lastTurnError;

    private void failTurnKeepQueue() {
        // The failed turn is over. Any fresh turn started now or by a later wake event gets its own
        // one-retry allowance rather than inheriting the exhausted budget from this turn.
        turnRetried = false;
        com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
        // 失败必须让主人看见——沉进日志就是"已读不回"
        String why = lastTurnError == null ? "连接中断" : lastTurnError;
        lastTurnError = null;
        com.dwinovo.numen.client.chat.ChatLines.notice(presenter.speakerName(),
                "这次没连上(" + truncate(why, 90) + ")——稍后再试一句,详情见日志");
        // HUD toast:玩家多半没开面板(Y/V 快捷对话),这是唯一接得住他的通道。
        com.dwinovo.numen.client.hud.NumenHudToasts.push(
                com.dwinovo.numen.client.ui.NumenToasts.Severity.ERROR,
                presenter.speakerName() + ": " + truncate(why, 90));
        if (queue.isEmpty()) {
            turnPause = AgentTurnPause.RECOVERABLE_FAILURE;
            return;
        }
        // The failed turn may have left the conversation ending on a user message
        // (its prompts were flushed before dispatch). Cap it so the fresh turn's
        // flush doesn't create back-to-back user messages (some backends 400 those).
        if (convo.lastMessage() instanceof ConvoState.Msg.User) {
            convo.addAssistant(new AssistantTurn("(连接中断)", List.of(), null));
        }
        Constants.LOG.info("[numen-entity#{}] turn failed with {} queued item(s) — starting a fresh turn with them",
                entityUuid, queue.size());
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
        presenter.clearPartial();   // committed 消息(下方 addAssistant)接管显示
        presenter.finishStreamLine();         // 聊天框在飞行同步摘掉(定格行随分支落地)

        // World is unloading (owner quit / disconnected): the client→server channel is gone, so a
        // dispatched ExecuteToolPayload would NPE in the platform sender. Drop this turn quietly.
        if (Minecraft.getInstance().getConnection() == null) {
            Constants.LOG.info("[numen-entity#{}] client disconnected — dropping LLM turn", entityUuid);
            turnPause = AgentTurnPause.BLOCKED;
            return;
        }

        if (err != null) {
            // 面向主人的是分类人话;技术细节进日志(传输层还有全量)。
            lastTurnError = LlmErrorWords.classify(err);
            Constants.LOG.warn("[numen-entity#{}] LLM call failed: {} ({})",
                    entityUuid, lastTurnError, unwrap(err));
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
                final TurnPresenter.VoiceTurn vt2 = presenter.beginVoiceTurn(ownerSpokeThisTurn);   // 重跑也重新开口(失败那次的半截语音随 beginTurn 作废)
                presenter.clearPartial();                 // 失败那次的半截文字同理作废
                NumenLlmClient llm2 = client();
                llm2.chatStreaming(modelContextSnapshot(), ToolRegistry.all(),
                                composeSystemPrompt(),
                                presenter.tapForUi(gen2, vt2.sink(), llm2.provider()::extractReasoningDelta))
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
            lastTurnError = "服务端返回了空回应";
            failTurnKeepQueue();
            return;
        }
        AssistantTurn turn = res.turn();
        // 全空 turn(HTTP 200 但既无内容也无工具调用——逐 chunk 解析全败或后端
        // 抽风):与 null turn 同罪同罚,必须报错,不能思考泡一收就装无事发生。
        if (!turn.hasToolCalls() && turn.content().isEmpty()) {
            Constants.LOG.warn("[numen-entity#{}] LLM returned an empty turn (no content, no tool calls)",
                    entityUuid);
            lastTurnError = "服务端返回了空回应";
            failTurnKeepQueue();
            return;
        }
        // True context size of the request we just made — the auto-compaction
        // signal. 0 when the backend sent no usage frame (then auto never fires).
        if (res.promptTokens() > 0) {
            lastPromptTokens = res.promptTokens();
        }
        tokens.add(res.freshTokens());
        // 目标的账单:主人得看得见这个目标到现在烧了多少。
        if (goal != null) goal.addTokens(res.freshTokens());

        convo.addAssistant(turn);

        if (!turn.hasToolCalls()) {
            // Final text reply — spoken to the owner. Chain settles; the next
            // prompt resumes the same conversation with a fresh turn count.
            if (!turn.content().isEmpty()) {
                Constants.LOG.info("[numen-entity#{}] assistant (final): {}",
                        entityUuid, turn.content());
                // 双通道落地:头顶气泡是回复的主显示(附近玩家都看得见),
                // 聊天框回显一份当日志;超长折叠,悬停看全文,完整记录在 G 面板
                String shown = com.dwinovo.numen.client.chat.ChatDisplayModes.current()
                        .assistantText(turn.content());
                if (!shown.isBlank()) {
                    com.dwinovo.numen.client.hud.SpeechBubbles.say(entityUuid, shown);
                    com.dwinovo.numen.client.chat.ChatLines.companion(presenter.speakerName(), shown);
                } else {
                    com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
                }
            } else {
                // 模型交了白卷(无工具调用、正文为空,部分后端偶发)——不能无声
                // 咽下变成"已读不回",给主人一条透明的提示
                Constants.LOG.info("[numen-entity#{}] assistant (final, empty content)", entityUuid);
                com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
                com.dwinovo.numen.client.hud.TalkHint.flash(
                        presenter.speakerName() + " 想了想,什么也没说——再问一句试试", 3500);
            }
            convo.resetTurnCount();
            // A prompt that arrived during this final turn was buffered; now that
            // the chain has settled, start a fresh turn to answer it.
            if (hasQueuedPrompts()) tryStartTurn();
            // 链条收尾了——这正是长期目标该接上的时刻。放在这里而不是发请求前:
            // "还没做完就接着做"要等这一轮真的说完才判断得了。
            steerToGoal();
            return;
        }

        // 开工前的顺嘴一句(tool_calls 旁附的 content):是话就上气泡+字幕行;
        // 没话说就 SETTLE——只收思考泡,上一句正文泡留着走完生命周期,
        // 工具执行期不显示"…"(身体动起来本身就是反馈)
        String aside = com.dwinovo.numen.client.chat.ChatDisplayModes.current()
                .assistantText(turn.content() == null ? "" : turn.content());
        if (!aside.isBlank()) {
            com.dwinovo.numen.client.hud.SpeechBubbles.say(entityUuid, aside);
            com.dwinovo.numen.client.chat.ChatLines.companion(presenter.speakerName(), aside);
        } else {
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

    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
