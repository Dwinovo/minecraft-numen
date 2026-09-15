package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.goal.GoalPrompts;
import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.inbox.JsonlJournal;
import com.dwinovo.numen.agent.llm.CompactSplit;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.HostPort;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.LoopStatus;
import com.dwinovo.numen.agent.loop.MemoryPort;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelPort;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.loop.Phase;
import com.dwinovo.numen.agent.loop.RunEnd;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.skill.SkillRegistry;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.platform.Services;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Per-entity agent loop running on the <strong>client</strong> — the companion-side facade around the
 * loop kernel {@link AgentLoop}. One instance per Numen the player talks to, keyed by the stable
 * {@code entity.getUUID()} in {@link AgentLoopRegistry} and resolved to the current body via
 * {@link ClientNumenLookup} (so it survives the int-id churn of dimension travel). The agent is bound
 * to that one entity for its whole lifetime — it talks directly to the owner, runs world-action tools
 * on its own body, and survives across many prompts.
 *
 * <h2>What lives here and what doesn't</h2>
 * When to call the model, when to run tools, what a stop / death / logout / takeover does, retries and
 * holds — all of that is the kernel's. This facade holds what needs Minecraft:
 * <ul>
 *   <li>the kernel's ports: assembling a request (system prompt, runtime state, resident tools, the
 *       callable set from the same snapshot), hopping model callbacks back to the main thread, the
 *       endpoint check, compaction and clearing, the body's live facts;</li>
 *   <li>the subscriber to the kernel's {@link LoopEvent}s that draws what happened (typewriter, voice,
 *       bubbles, chat lines, toasts), keeps the token ledger, harvests work-station coordinates and
 *       drives the long-term goal;</li>
 *   <li>persona / model binding, the external-driver intake, and the registry lifecycle.</li>
 * </ul>
 *
 * <h2>Threading rules</h2>
 * All mutations run on the client main thread: owner input from the chat screen, tool results
 * ({@link ToolDispatcher}), and model callbacks, which {@link Model#call} hops back via
 * {@code Minecraft.execute} before they reach the kernel.
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
    /** 长期目标;null = 没有。每次 run 做完时评估一次、没做完就推一条续跑,见 {@link #steerToGoal}。 */
    private GoalState goal;
    /**
     * 收件箱(宪法 §4):主人的话与世界事件的统一进箱口,内核按类型表的投递方式取件。
     * 条目、落盘、年龄标注、熟度规则全在 {@link EventQueue};这里直接用它的只有外接模型取件口
     * 和"排着几条整理/清空"这类只读查询。
     */
    private final EventQueue queue;
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

    /**
     * Runs a model reply's tool calls one at a time and reports each result back to the kernel — the
     * kernel's {@link com.dwinovo.numen.agent.loop.ToolPort}. All the tool-execution plumbing (serial
     * queue, ship-to-server, completion, timeout) lives in there, not here.
     */
    private final ToolDispatcher dispatcher;

    /** Context size of the last request as the API counted it (0 = unknown yet). */
    private long lastPromptTokens = 0;
    /** Consecutive compaction failures — circuit breaker for the auto path. */
    private int compactFailures = 0;

    /** Death cause recorded at death, replayed in the respawn event (null while alive). */
    private String deathCause;

    /**
     * The PHYSICAL transcript for the chat GUI: every message ever exchanged
     * this session (plus the persisted tail), in order, with compaction
     * boundaries as {@link ConvoLog#COMPACT_DIVIDER} sentinels. Compaction
     * rewires {@link #convo} (what the LLM sees) but only appends a divider
     * here — the owner's visible history never vanishes. Same split as the
     * append-only session log vs. the logical context in Claude Code.
     */
    private final List<ConvoState.Msg> display = new ArrayList<>();

    /** 表现层(打字机/气泡/说话位/语音)与 token 台账,循环之外的两件事。 */
    private final TurnPresenter presenter;
    private final TokenLedger tokens;

    /** 模型那一侧的端口:正常一轮、压缩、目标评估都从它发出。 */
    private final Model model;
    /** 循环内核:run、停牌、推进、切断都在它那里。 */
    private final AgentLoop loop;
    /** 上一个 tick 驾驶席在不在外接模型手里——只用来找"翻转成外接"的那一下。 */
    private boolean wasDriving;
    /** 在飞的目标评估;{@code null} = 没在判。开了新 run、被切断、换了目标都作废它。 */
    private CancelToken goalJudge;

    EntityAgentLoop(UUID entityUuid) {
        this.entityUuid = entityUuid;
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
        this.dispatcher = new ToolDispatcher(entityUuid, this::resolveEntity);
        this.presenter = new TurnPresenter(entityUuid, this::streaming, this::speaking, this::personaName);
        this.tokens = new TokenLedger(entityUuid);
        this.model = new Model();
        this.loop = new AgentLoop(entityUuid.toString(), model, dispatcher, convo, queue, new Memory(), new Host());
        this.wasDriving = McpMode.instance().driving();
        loop.subscribe(this::onLoopEvent);
        restoreFromDisk();
    }

    /**
     * 与自动压缩闸门同一口径的模型上下文窗口。真源是<b>这只同伴绑定的档案</b>
     * ({@link com.dwinovo.numen.agent.llm.ProviderLibrary.Entry#contextWindow()}),
     * 请求走哪份档案窗口就按哪份算;没有档案(遗留同伴)才回落旧的全局配置。
     */
    public int modelWindow() {
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

    /**
     * Replay the persisted conversation tail into memory exactly as it was recorded. A session that died
     * mid-turn leaves tool calls without results or a user message without a reply; both stay as they are —
     * {@link com.dwinovo.numen.agent.llm.ProtocolView} answers the dangling calls when the next request is built.
     */
    private void restoreFromDisk() {
        tokens.load();
        log.migrateIfNeeded();   // upgrade an older-format file in place before reading it (crash-safe, keeps a .v<N>.bak)
        personaId = CompanionHome.binding(entityUuid).personaId();
        // 重进后 loop 是全新的,死亡停牌按状态恢复:她死着的时候主人退出游戏,
        // 队列里可能躺着急件——不补这一下她会在还没复活的时候就开口。
        // 真源是名册说她死没死(状态),不是"我收到过死亡消息"(事件)。
        if (NumenRoster.instance().isDead(entityUuid)) {
            loop.halt(HaltReason.DEATH);
            Constants.LOG.info("[numen-entity#{}] 恢复时她还死着 — 停牌等复活", entityUuid);
        }
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (history.isEmpty()) return;
        convo.preload(history);
        // The visible transcript replays the raw file order (dividers included),
        // NOT the compacted view.
        display.addAll(log.loadDisplay(ConvoLog.DEFAULT_LOAD_LIMIT));
        Constants.LOG.info("[numen-entity#{}] restored {} msg(s) from disk", entityUuid, history.size());
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

    /** 四元累计用量(跨会话)——页脚的 ↑↓RW 取自它。 */
    public com.dwinovo.numen.agent.provider.Usage usageTotals() {
        return tokens.sum();
    }

    /** 最近一轮的用量——命中率取自它:累计命中率会被历史稀释,看不出刚才那轮打穿了缓存。 */
    public com.dwinovo.numen.agent.provider.Usage lastUsage() {
        return tokens.latest();
    }

    /** 缓存重计费的诊断数。 */
    public com.dwinovo.numen.agent.provider.CacheWaste cacheWaste() {
        return tokens.waste();
    }
    public ConvoState convo() { return convo; }

    /**
     * 这次工具调用的结果还会不会来——派发器还攥着它(在跑或排着)。历史里没结果、这里又答 false
     * 的调用是被切断的,聊天面板据此把它画成失败而不是一直转圈。
     */
    public boolean isToolCallOutstanding(String callId) {
        return dispatcher.holds(callId);
    }

    /** Read-only physical transcript for the GUI (see {@link #display}). */
    public List<ConvoState.Msg> display() {
        return java.util.Collections.unmodifiableList(display);
    }

    /** 内核此刻的只读快照——忙不忙、为什么不动、排着什么。 */
    public LoopStatus status() {
        return loop.status();
    }

    /** Snapshot of prompts (GUI or {@code NumenGateway}) still waiting in the queue — the GUI renders these as pending. */
    public List<String> queuedPrompts() {
        return loop.status().queuedPreview();
    }

    /**
     * 主人在聊天框里说话。
     *
     * <p>死着也照收——内核在死亡停牌时不开 run,话安安静静躺在收件箱里,聊天里显示成 ⌛ 待发气泡,
     * 复活时随死亡叙事一起送出。(外接大脑模式早就是这个做法:"收件箱照收不误,事件不丢"。)直接丢掉的话,
     * 死前一秒说的留着、死后一秒说的蒸发——而主人根本看不见那一 tick 的分界,
     * 只会觉得这模组有时候吞消息。
     *
     * @return 这句话有没有被压着(true = 内脑没能当场把请求发出去)。这是<b>观察</b>不是预测:
     *         看的是入队并推进之后内核是不是正在等模型回话。调用方拿 {@code isBusy()} 之类的东西
     *         自己猜是猜不准的——身体有后台任务不挡开 run,她在跟随时你说的话当场就发得出去。
     *
     *         <p>它只喂 {@link com.dwinovo.numen.api.Delivery} 那份给桥接看的汇报,
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
     * 主人的话进队列。{@code wire} 是拼好的原文(模型看到的),{@code logged} 是主人打的那句。
     * 急不急不在这里说:query 在类型表里恒为急件;解开哪些停牌、什么时候注入,都是内核按类型表定。
     */
    private boolean enqueueOwnerWords(String wire, String logged) {
        // Wrap the owner's words in <query> so the model can always tell real user input apart from
        // anything else numen injects into the same user turn (events, and future world-state/reminders).
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, wire, System.currentTimeMillis(), false)));
        // 外脑驱动期间面板画的是现场缓冲——主人的话得当场可见,不能等谁取走才出现。
        // 这里是所有主人话的单一咽喉(面板/快捷对话/语音/桥接),挂点只此一处。
        if (McpMode.instance().driving()) {
            com.dwinovo.numen.mcp.server.McpTranscript.owner(entityUuid, logged);
        }
        return loop.status().phase() != Phase.MODEL;
    }

    /**
     * 断线静默:{@code halt(DISCONNECT)}——作废在飞的回应、放弃未决调用并在历史里记下切断点,
     * <b>不叫停身体、不删目标、不置停牌、不清队列</b>。
     *
     * <p>她的身体还在服务器里 tick,任务照样跑完,收尾进离线出箱等主人回来
     * ——"我帮你把矿挖完了"这条链正是为此做的。登出时叫停她,恰好把它废掉;置了停牌的话,
     * 离线补发回来的 {@code task_finished} 也唤不醒她。
     */
    public void quiesce() {
        loop.halt(HaltReason.DISCONNECT);
    }

    /** 同伴离场或清表:{@code halt(DISPOSE)},在飞的回合作废,不再往她的会话里写任何东西。 */
    void dispose() {
        loop.halt(HaltReason.DISPOSE);
    }

    /**
     * Driven once per client tick (see {@code AgentLoopRegistry.tickAll}): tool backstop timeout,
     * presentation, the external-driver flip, and the kernel's tick ("waited long enough" ripeness).
     */
    public void clientTick() {
        dispatcher.tick();
        presenter.tick();
        // 驾驶席翻转成外接的那一下作废在飞的回合:接管之后内脑的回复不该再派工具、压缩不该再换历史。
        // 交还不用做什么——停牌是现算的,内核下一次推进自己看得见。
        boolean driving = McpMode.instance().driving();
        if (driving && !wasDriving) {
            loop.halt(HaltReason.EXTERNAL);
        }
        wasDriving = driving;
        loop.tick();
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

    // ---- status read by the GUI (from LoopStatus) ----

    /** The brain or body is actively working: LLM, tool round-trip, compaction, or background task. */
    public boolean isBusy() {
        return loop.status().busy();
    }

    /**
     * 此刻在干的那件事(工具名/长活任务名),没有具体动作时返回 null——
     * 头顶「正在回复中」气泡拿它当副文本:长任务跑几十秒时,主人得看见
     * 她在挖矿而不是卡死了。
     */
    public String currentActivity() {
        return loop.status().activity();
    }

    /** A summarization call is currently in flight (drives the GUI status line). */
    public boolean isCompacting() {
        return loop.status().phase() == Phase.COMPACT;
    }

    /** 整理记忆的进度 0~1(见 {@link LoopStatus#compactProgress})。 */
    public double compactProgress() {
        return loop.status().compactProgress();
    }

    // ---- 长期目标 ----

    /** 当前的长期目标;{@code null} = 没有。 */
    public GoalState goal() {
        return goal;
    }

    /**
     * 定一个目标。整份目标<b>只在这里</b>交给她一次;之后每轮只补评估器那句"还差什么"。
     *
     * @param echo 主人打的原文({@code /goal 挖 128 个钻石})。走 {@link #submitCommand} 是为了
     *             聊天里有个气泡——他打了字就该看见自己打了什么,跟 {@code /build} 一个待遇
     */
    public void setGoal(GoalState next, String echo) {
        this.goal = next;
        CompanionHome.setGoal(entityUuid, next);
        Hold hold = loop.hold();
        if (next == null || hold == Hold.DEAD || hold == Hold.EXTERNAL) {
            return;
        }
        next.countTurn();
        CompanionHome.setGoal(entityUuid, next);
        submitCommand(echo, GoalPrompts.initialDirective(next));
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
     * 一次 run 做完了:判一次目标达没达成。
     *
     * <p>判定<b>不由她自己做</b>——另开一次干净的调用(不带对话历史、不带人设、不带工具),
     * 只看条件、身体事实和最近几句。执行的人和判定的人分开,她才骗不了自己。
     *
     * <p>队列里还有别的排着就先不判——那些本来就会开起一次 run,那次做完时再说。
     */
    private void steerToGoal() {
        if (goal == null || loop.hold() != null || !queue.isEmpty() || goalJudge != null) {
            return;
        }
        // 身体还在干活就别催。
        //
        // 我们的工具是异步的:派发回执立刻回来,链条当场收尾,而她其实动都还没动完。不拦
        // 的话就是每隔一个 API 往返问一次"挖完了吗"——什么也没推进,纯烧 token。
        //
        // 醒来不用另写:任务干完会推 task_finished 进队列,那本来就会开起一次 run;那次
        // 做完时再走到这里,currentTask 已经空了,续跑自然接上。
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

    /**
     * 跑一次评估。用同伴自己绑的那个模型,但是<b>另一次调用</b>——"新鲜"指的是这个,
     * 不是换个更小的模型。它不是一次 run:不带历史与工具,不占内核。
     */
    private void judgeGoal() {
        GoalState target = goal;
        ModelRequest request = new ModelRequest(
                List.of(new ConvoState.Msg.User(
                        GoalPrompts.evaluatorQuery(target, runtimeStateXml(), sinceGoalForJudge()))),
                List.of(), GoalPrompts.evaluatorSystem(), Set.of());
        CancelToken cancel = new CancelToken();
        goalJudge = cancel;
        model.call(request, cancel, delta -> { }, outcome -> {
            goalJudge = null;
            finishJudging(target, outcome);
        });
    }

    /** 作废在飞的评估:评估期间开了新 run 或被切断,它判的已经不是眼前的局面。 */
    private void cancelGoalJudging() {
        if (goalJudge != null) {
            goalJudge.cancel();
            goalJudge = null;
        }
    }

    private void finishJudging(GoalState judged, ModelOutcome outcome) {
        // 判的是上一个目标 —— 这次结果作废。
        if (goal == null || goal != judged) {
            return;
        }
        if (outcome instanceof ModelOutcome.Failed failed) {
            // 判不出来不等于做完了。歇一轮,下次做完再判。
            Constants.LOG.warn("[numen-entity#{}] 目标评估失败,这一轮先不续:{}", entityUuid, failed.words());
            return;
        }
        ModelOutcome.Answered answered = (ModelOutcome.Answered) outcome;
        goal.addTokens(answered.usage().fresh());
        var verdict = GoalPrompts.readVerdict(answered.turn().content());
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
            clearGoal("跑够 " + GoalState.MAX_GOAL_TURNS
                    + " 轮还没完,先收工了(还差:" + verdict.reason() + ")—— 想接着做再说一次 /goal");
            return;
        }
        long now = System.currentTimeMillis();
        goal.countTurn();
        CompanionHome.setGoal(entityUuid, goal);
        // goal 在类型表里恒为急件、投递方式是接续,发送方不另标。
        loop.push(List.of(new EventQueue.Entry(EventTypes.GOAL,
                GoalPrompts.progress(verdict.reason(), goal, now), now, false)));
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
                // 切断也是证据:一轮没做完是被打断/死亡掐掉的,不是她放弃了
                case ConvoState.Msg.Halt h -> "interrupted: " + h.reason();
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

    /**
     * 现在不能整理记忆的理由;{@code null} = 能。
     *
     * <p>判据只有这一份。{@code /compact} 的补全行要把理由写出来,而"能不能"和"为什么
     * 不能"是同一个问题——分成两处迟早说不到一块儿去。
     */
    public String compactProblem() {
        Hold hold = loop.hold();
        if (hold == Hold.DEAD) return "她已经不在了";
        // 整理是对内脑说的:驾驶席在外接模型手里时内脑不开工,排上了也只会一直躺着。
        if (hold == Hold.EXTERNAL) return "外接模型正在驾驶她,整理记忆要等交还给内置大脑之后";
        if (isCompacting()) return "已经在整理了";
        if (queue.count(EventTypes.COMPACT) > 0) return "整理已经排上了";
        // 不看忙不忙:整理进队列排着,闲下来自己执行。按了就一定会发生,
        // 主人不必盯着什么时候能按。
        // 也不看记录长短:整理多少、什么时候整理是主人的事。条数门槛只属于自动整理
        // ——那是替他省一次没意义的请求,不是替他做决定。
        return endpointProblem();   // 整理要发一次请求,没绑模型/没填 key 一样做不了
    }

    /** {@code /clear} 现在按不按得下。同 {@link #compactProblem} 的形状,但不查端点:清空不发请求。 */
    public String clearProblem() {
        Hold hold = loop.hold();
        if (hold == Hold.DEAD) return "她已经不在了";
        // 同整理:清空的是内脑的上下文,外接模型驾驶时内脑不开工,排上了也执行不了。
        if (hold == Hold.EXTERNAL) return "外接模型正在驾驶她,清空上下文要等交还给内置大脑之后";
        if (queue.count(EventTypes.CLEAR) > 0) return "清空已经排上了";
        return null;
    }

    /**
     * 主人要求清空上下文。与 {@link #requestCompact} 同一走法:急件进队列,闲时执行,
     * 忙的时候也按得下。空闲时当场发生,调用返回时已经清完。
     *
     * @return 拒绝的理由;{@code null} = 已排上(空闲时当场清完)
     */
    public String requestClearContext() {
        String problem = clearProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] manual clear refused: {}", entityUuid, problem);
            return problem;
        }
        // clear 在类型表里恒为急件,发送方不另标。
        loop.push(List.of(new EventQueue.Entry(EventTypes.CLEAR, "清空上下文", System.currentTimeMillis(), false)));
        return null;
    }

    /** Owner prompts or commands are queued, waiting for the kernel to take them. */
    public boolean hasQueuedPrompts() {
        return !loop.status().queuedPreview().isEmpty();
    }

    /** There is something an interrupt would act on — drives the Stop button's enabled state. */
    public boolean canInterrupt() {
        return loop.status().canInterrupt();
    }

    /**
     * Owner-triggered interrupt — the chat GUI's "Stop" button: {@code halt(OWNER_STOP)}. The in-flight
     * model call is cancelled, outstanding tool calls are abandoned and the body is told to stop, the
     * history records where the turn was cut, superseded instructions (queued prompts, commands, a goal
     * continuation) are dropped busy or idle, the long-term goal ends, and no new run starts until the
     * owner speaks again. See {@link HaltReason}.
     */
    public void abort() {
        loop.halt(HaltReason.OWNER_STOP);
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
     * 外接大脑收件(get_events 的取货口):{@code urgentOnly} 时只在队里有给它的急件才取,
     * 长轮询靠它省着等;到点了不管急不急有什么给什么。渲染与内脑注入同一份 {@link EventQueue#render}
     * ——外脑看到的事件文本和内脑一字不差。
     *
     * <p>控制条目(整理/清空)是对内脑说的:跳过它们、留在队里等交还,文本照取。外接模型不会去执行
     * 控制条目,停在队首的话后面的话就永远取不到。
     *
     * @return 取走的事件拼段;这次没取到返回 null(继续等或如实说没有)
     */
    public String takeEventsForExternal(boolean urgentOnly) {
        java.util.function.Predicate<EventQueue.Entry> text =
                e -> EventTypes.get(e.type()).delivery() != EventTypes.Delivery.CONTROL;
        if (urgentOnly && queue.entries().stream().noneMatch(e -> e.urgent() && text.test(e))) return null;
        long now = System.currentTimeMillis();
        List<EventQueue.Entry> taken = queue.takeIf(text, now);
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
     * The body died — the server tells us via {@code NumenDeathPayload} with the death cause:
     * {@code halt(DEATH)}. SUSPEND (not dispose): the companion respawns at its owner shortly and
     * {@link #onRespawned} resumes us. The turn the death cut short is recorded as a Halt carrying the
     * cause; the queue keeps everything — every entry is timestamped, so the model can tell what happened
     * before the death, and judging what went stale for it would only delete useful narrative.
     */
    public void onEntityDied(String cause) {
        deathCause = cause;
        loop.halt(HaltReason.DEATH, cause);
    }

    /**
     * The body respawned at its owner after dying — push the death narrative as an urgent event, then
     * release the death hold so it goes out together with everything queued while dead.
     */
    public void onRespawned(String payloadCause) {
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
        loop.push(List.of(com.dwinovo.numen.event.NumenEvents.entry(dayTime, EventTypes.DEATH, null,
                "你刚才死了(" + cause + "),背包里的东西全掉在死亡地点了;"
                        + "现已在主人身边复活。先看看状况再决定下一步。",
                System.currentTimeMillis(), true)));
        loop.respawned();
    }

    /**
     * 服务端说她在做什么——直接照抄,不判断、不合并、不推断。
     *
     * <p>这是 {@code currentTask} 的写入点(打断与断线时清掉本地镜像除外,见 {@link #onHalted})。
     * 客户端不靠"我派出去过什么"自己记账:那样服务器重启重放、死亡复活重放起来的活它一概不知道,
     * 头顶没气泡、模型也看不见。
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
     * 收一批进队列的输入(事件侧)。什么时候倒出去由队列的熟度和内核的停牌说了算——
     * 这里不做任何"这条该不该立刻开轮"的判断。一批整个交给内核:离线补发的整批条目一次到达,
     * 逐条推进的话第一条急件就开了 run,只带走已经到的那几条。
     *
     * <p>死着也照收:每条都盖着真实时间戳,复活后模型看得出哪些发生在死亡之前。
     */
    public void pushEvents(List<EventQueue.Entry> entries) {
        loop.push(entries);
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
     *  and persist the assignment. Takes effect on the next request — no restart; a companion
     *  held because its endpoint was unusable gets to try again. */
    public void setProviderEntry(String entryId) {
        this.providerEntryId = entryId == null || entryId.isBlank() ? null : entryId;
        CompanionHome.bind(entityUuid,
                CompanionHome.binding(entityUuid).withProvider(this.providerEntryId));
        Constants.LOG.info("[numen-entity#{}] provider entry set to {}", entityUuid,
                this.providerEntryId == null ? "(global)" : this.providerEntryId);
        loop.bindingChanged();
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

    // ---- compaction ----

    /**
     * 主人要求整理记忆({@code /compact})。
     *
     * <p>不当场执行,<b>进队列排着</b>:她忙的时候也按得下,闲下来自己走。判据全在
     * {@link #compactProblem}。
     *
     * @return 拒绝的理由;{@code null} = 已经排上了
     */
    public String requestCompact() {
        String problem = compactProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] manual compact refused: {}", entityUuid, problem);
            return problem;
        }
        // compact 在类型表里恒为急件,发送方不另标。
        loop.push(List.of(new EventQueue.Entry(EventTypes.COMPACT, "整理记忆", System.currentTimeMillis(), false)));
        return null;
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
     * 这一轮临时挂载的运行期状态。全部现算,一个字都不入会话历史——包进同一个
     * {@code <runtime_state>} 里,模型只需认一个信封。
     */
    private String runtimeStateXml() {
        // 插件的现算片段也挂这一层:它们和背包、状态效果一样是"此刻的她",
        // 会变,所以不能进字节级稳定的系统提示。
        String body = currentTaskXml() + inventoryXml() + effectsXml() + ridingXml()
                + com.dwinovo.numen.api.NumenPlugins.stateFragments(entityUuid);
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
        // 人设层:同伴绑的人设 → 全局配置的人设 → 内置默认人设。空着的槽会让她退回通用助手的腔调,
        // 所以最后一档是一个具体的性格,不是"自由发挥"。
        String base = (personaText() != null && !personaText().isBlank())
                ? personaText() : Services.CONFIG.getSystemPrompt();
        if (base == null || base.isBlank()) base = com.dwinovo.numen.agent.prompt.NumenPrompts.DEFAULT_PERSONA;
        String skillsXml = SkillRegistry.instance().formatXml();

        // 系统提示只放会话内稳定的层——人设/操作核心/技能表/情绪词表。
        // 会变化的 <known_blocks> 随注入的 user 消息进历史(见 Host#injectionPreamble),
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
        // 延迟工具目录。它随注册表变(接了 MCP server 会多出几行),但不随回合变,
        // 所以仍然待得住这个稳定层——与技能表、本能名册同一档。
        String catalogue = com.dwinovo.numen.agent.tool.ToolDisclosure
                .catalog(ToolRegistry.deferred());
        if (!catalogue.isEmpty()) {
            sb.append("\n\n").append(catalogue);
        }
        // 怎么说话压在最末尾:长度与语气离生成位置越近,越不容易在长对话里被冲淡(见 NumenPrompts)
        sb.append(com.dwinovo.numen.agent.prompt.NumenPrompts.SPEAKING);
        return sb.toString();
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientNumenLookup.resolve(entityUuid);
    }

    /** 回复正在流式长出来——聊天框打字机的开关。 */
    private boolean streaming() {
        return loop.status().phase() == Phase.MODEL;
    }

    /** 大脑在输出(思考、生成、跑工具)——说话状态上报取它。整理记忆不算说话。 */
    private boolean speaking() {
        Phase phase = loop.status().phase();
        return phase == Phase.MODEL || phase == Phase.TOOLS;
    }

    // ---- kernel events: what the owner sees and what gets accounted ----

    /**
     * 内核的事件在这里变成表现层、记账与目标推进。这里不回头同步推进内核的步子——目标评估落地后
     * 推一条续跑,那已经是另一次主线程回调。
     */
    private void onLoopEvent(LoopEvent event) {
        switch (event) {
            case LoopEvent.RunStarted started -> cancelGoalJudging();
            case LoopEvent.TurnStarted turn -> presenter.beginTurn(turn.ownerSpoke());
            case LoopEvent.ModelDelta delta -> presenter.delta(delta.content(), delta.reasoning());
            case LoopEvent.AssistantMessage message -> showReply(message.turn());
            case LoopEvent.ToolStarted started -> { }
            case LoopEvent.ToolFinished finished -> harvestWorkBlocks(finished.call().name(), finished.resultJson());
            case LoopEvent.RunEnded ended -> {
                switch (ended.end()) {
                    // 链条收尾了——这正是长期目标该接上的时刻:"还没做完就接着做"要等这一轮真的说完才判断得了。
                    case RunEnd.Done done -> steerToGoal();
                    case RunEnd.Failed failed -> presenter.endTurn();
                    case RunEnd.Halted halted -> presenter.endTurn();
                }
            }
            case LoopEvent.TurnFailed failed -> showFailure(failed.words());
            case LoopEvent.Halted halted -> onHalted(halted.reason());
            case LoopEvent.HoldChanged changed -> {
                if (changed.hold() == Hold.BLOCKED) {
                    showBlocked(changed.reason());
                }
            }
            case LoopEvent.ModelUsed used -> account(used.usage(), used.purpose());
            case LoopEvent.TranscriptBoundary boundary -> onBoundary(boundary.kind());
        }
    }

    /** 模型的一条回复落地:头顶气泡是回复的主显示(附近玩家都看得见),聊天框回显一份当日志。 */
    private void showReply(AssistantTurn turn) {
        presenter.endTurn();   // committed 消息接管显示,半截打字与在飞行摘掉
        String shown = com.dwinovo.numen.client.chat.ChatDisplayModes.current()
                .assistantText(turn.content());
        if (!turn.hasToolCalls()) {
            Constants.LOG.info("[numen-entity#{}] assistant (final): {}", entityUuid, turn.content());
        }
        // 最终回复和开工前的顺嘴一句(tool_calls 旁附的 content)同一个画法:是话就上气泡 + 字幕行,
        // 超长折叠,悬停看全文,完整记录在 G 面板。开工前没话说就不动气泡——上一句正文泡留着走完
        // 生命周期,身体动起来本身就是反馈;最终回复滤完为空(全是动作记号)时收起思考泡。
        if (!shown.isBlank()) {
            com.dwinovo.numen.client.hud.SpeechBubbles.say(entityUuid, shown);
            com.dwinovo.numen.client.chat.ChatLines.companion(presenter.speakerName(), shown);
        } else if (!turn.hasToolCalls()) {
            com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
        }
    }

    /** 调用失败而且不再重试:必须让主人看见——沉进日志就是"已读不回"。 */
    private void showFailure(String words) {
        com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
        com.dwinovo.numen.client.chat.ChatLines.notice(presenter.speakerName(),
                "这次没连上(" + truncate(words, 90) + ")——稍后再试一句,详情见日志");
        // HUD toast:玩家多半没开面板(Y/V 快捷对话),这是唯一接得住他的通道。
        com.dwinovo.numen.client.hud.NumenHudToasts.push(
                com.dwinovo.numen.client.ui.NumenToasts.Severity.ERROR,
                presenter.speakerName() + ": " + truncate(words, 90));
    }

    /** 端点不可用:配置问题不能静默——快捷键用户不开面板,聊天栏警示行是唯一出口。 */
    private void showBlocked(String problem) {
        com.dwinovo.numen.client.chat.ChatLines.notice(presenter.speakerName(), truncate(problem, 160));
        com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
    }

    /**
     * 执行了一次切断(不管当时有没有 run):语音闭嘴、头顶的思考/残句气泡收起、在飞的目标评估作废,
     * 表里说要收工的目标收工。
     */
    private void onHalted(HaltReason reason) {
        presenter.interruptVoice();
        com.dwinovo.numen.client.hud.SpeechBubbles.clear(entityUuid);
        cancelGoalJudging();
        if (reason.endsGoal() && goal != null) {
            // 主人按停止 = 不要她接着跑了。目标跟着收工,否则这一轮刚断下一轮又自己续上,
            // 停止键就成了摆设。想接着做再说一次 /goal,成本就是一句话。
            clearGoal("按停止收工了:" + goal.objective());
        }
        if (reason == HaltReason.OWNER_STOP || reason == HaltReason.DISCONNECT) {
            // 按停止时服务端随后会推 idle 过来,这里先清,停止键当场灭;断线时清掉本地镜像:
            // 下一个存档跟这件活无关,而那时不会有服务端推送来纠正它。
            currentTask = null;
        }
    }

    /** 一次模型调用的用量进台账;对话调用的实测体量是自动压缩的判据,也记进目标的账单。 */
    private void account(Usage usage, LoopEvent.Purpose purpose) {
        tokens.add(usage);
        if (purpose == LoopEvent.Purpose.TURN) {
            // True context size of the request we just made — the auto-compaction signal.
            // 0 when the backend sent no usage frame (then the gate falls back to an estimate).
            if (usage.promptTokens() > 0) {
                lastPromptTokens = usage.promptTokens();
            }
            // 目标的账单:主人得看得见这个目标到现在烧了多少。
            if (goal != null) {
                goal.addTokens(usage.fresh());
            }
        }
    }

    /** 历史换了(压缩、清空):聊天流插一条分隔,上一次请求的体量与缓存诊断不再作数。 */
    private void onBoundary(LoopEvent.Boundary kind) {
        String divider = switch (kind) {
            case COMPACT -> ConvoLog.COMPACT_DIVIDER;
            case CLEAR -> ConvoLog.CLEAR_DIVIDER;
            case HALT -> null;   // 切断点经会话的 sink 已经进了显示记录
        };
        if (divider == null) {
            return;
        }
        display.add(new ConvoState.Msg.User(divider));
        lastPromptTokens = 0;       // unknown until the next request reports usage
        tokens.waste().reset();     // 前缀本来就换了,下一轮的未命中不算"白付"
        compactFailures = 0;
    }

    // ---- kernel ports ----

    /** 模型那一侧:组装请求、端点检查、发请求并把回调切回主线程。 */
    private final class Model implements ModelPort {

        @Override
        public String unavailable() {
            return endpointProblem();
        }

        /**
         * <b>发给模型的就是这一份</b>——会话上下文加上这一轮临时挂载的运行期状态
         * ({@code <runtime_state>}/{@code <current_task>})。源会话与落盘日志一个字不动。
         * 可调工具集从同一份消息里算:展开闸按模型这一次看见了什么判。
         */
        @Override
        public ModelRequest turnRequest() {
            List<ConvoState.Msg> messages = AgentRequestContext.attach(convo.snapshot(), runtimeStateXml());
            // 只发常驻工具:其余的在系统提示的 <deferred_tools> 目录里留一行摘要,
            // 模型调 find_tools 才取回完整定义(见 ToolDisclosure)。
            List<NumenTool> tools = ToolRegistry.resident();
            Set<String> callable = new LinkedHashSet<>();
            for (NumenTool t : tools) callable.add(t.name());
            callable.addAll(com.dwinovo.numen.agent.tool.ToolDisclosure.expandedIn(messages));
            return new ModelRequest(messages, tools, composeSystemPrompt(), callable);
        }

        @Override
        public void call(ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta,
                         Consumer<ModelOutcome> onDone) {
            NumenLlmClient llm = client();
            Minecraft mc = Minecraft.getInstance();
            llm.chatStreaming(request.messages(), request.tools(), request.systemPrompt(), cancel, chunk -> {
                // 增量在 HTTP 线程上按这次调用的服务商方言解开,再按顺序切回主线程
                String content = com.dwinovo.numen.client.voice.VoicePipeline.extractContentDelta(chunk);
                String reasoning = llm.provider().extractReasoningDelta(chunk);
                boolean hasContent = content != null && !content.isEmpty();
                boolean hasReasoning = reasoning != null && !reasoning.isEmpty();
                if (!hasContent && !hasReasoning) {
                    return;
                }
                Delta delta = new Delta(hasContent ? content : "", hasReasoning ? reasoning : "");
                mc.execute(() -> {
                    if (!cancel.isCancelled()) {
                        onDelta.accept(delta);
                    }
                });
            }).whenComplete((res, err) -> mc.execute(() -> {
                if (cancel.isCancelled()) {
                    return;   // 取消之后不再回调:发起这次调用的一方已经不要它了
                }
                if (err != null) {
                    // 面向主人的是分类人话;技术细节进日志(传输层还有全量)。
                    String words = LlmErrorWords.classify(err);
                    Constants.LOG.warn("[numen-entity#{}] LLM call failed: {} ({})", entityUuid, words, unwrap(err));
                    onDone.accept(new ModelOutcome.Failed(words));
                    return;
                }
                onDone.accept(new ModelOutcome.Answered(res.turn(), res.usage()));
            }));
        }
    }

    /** 上下文整理:自动压缩的判据、切分与摘要落地、清空。 */
    private final class Memory implements MemoryPort {

        /**
         * Auto-compaction gate: the last request's true context size (as the API counted it) is within
         * the buffer of the window. Mirrors Claude Code's autoCompactIfNeeded. Backends that never send a
         * usage frame leave lastPromptTokens at 0 — fall back to a local estimate so the gate still fires
         * instead of never.
         */
        @Override
        public boolean compactionDue() {
            int window = modelWindow();
            List<ConvoState.Msg> history = convo.snapshot();
            long contextTokens = lastPromptTokens > 0 ? lastPromptTokens : estimateContextTokens(history);
            boolean due = contextTokens >= window - AUTO_COMPACT_BUFFER_TOKENS
                    && history.size() >= MIN_COMPACT_MESSAGES
                    && compactFailures < MAX_COMPACT_FAILURES;
            if (due) {
                Constants.LOG.info("[numen-entity#{}] auto-compacting: {} context {} tokens >= {} - {}",
                        entityUuid, lastPromptTokens > 0 ? "measured" : "estimated",
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
            Constants.LOG.info("[numen-entity#{}] compaction started ({}, summarizing {} msgs, keeping {} verbatim)",
                    entityUuid, auto ? "auto" : "manual", toSummarize.size(), kept.size());
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
                    Constants.LOG.info(
                            "[numen-entity#{}] compaction done ({}): {} tokens → summary ({} chars) + {} preserved msg(s) in {} ms",
                            entityUuid, auto ? "auto" : "manual",
                            usage.promptTokens() > 0 ? String.valueOf(usage.promptTokens()) : "?",
                            wrapped.length(), kept.size(), System.currentTimeMillis() - startMs);
                    return true;
                }

                @Override
                public void failed(String why) {
                    compactFailures++;
                    // The conversation is untouched — the turn just runs uncompacted.
                    Constants.LOG.warn("[numen-entity#{}] compaction failed ({}/{}): {}",
                            entityUuid, compactFailures, MAX_COMPACT_FAILURES, why);
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
            Constants.LOG.info("[numen-entity#{}] 上下文清空(记录留档)", entityUuid);
        }
    }

    /** 同伴这一侧的现场事实。 */
    private final class Host implements HostPort {

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public int initiativeLevel() {
            return com.dwinovo.numen.client.data.ClientPrefs.initiativeLevel();
        }

        @Override
        public boolean externallyDriven() {
            return McpMode.instance().driving();
        }

        /**
         * {@code <known_blocks>} 随注入的 user 消息进历史,不放系统提示:它随放置/使用工作站而变,
         * 放系统提示会打碎请求前缀的 prompt cache。
         */
        @Override
        public String injectionPreamble() {
            AbstractClientPlayer body = resolveEntity();
            return workBlocks.formatXml(body != null ? body.level() : null);
        }

        @Override
        public boolean bodyTaskRunning() {
            return currentTask != null;
        }

        @Override
        public String activity() {
            if (currentTask != null) {
                // 服务端给的人话描述("挖 64 块泥土"),不是工具 id("mine")——
                // 气泡是给主人看的,他不该在头顶上读内部标识符。
                String d = currentTask.describe();
                return d != null && !d.isBlank() ? d : currentTask.tool();
            }
            return dispatcher.currentToolName();
        }
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
