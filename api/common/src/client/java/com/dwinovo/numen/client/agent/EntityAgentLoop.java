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
    /** 每一轮挂在请求里的现场;她手上那件活的镜像也在这里。 */
    private final RuntimeState runtime;

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
     * 面板的对话记录:读盘那一截,加上之后日志写下的每一条(经 {@link ConvoLog#onDisplay},与读盘同一个换法)。
     * 整理记忆换的是 {@link #convo}(模型看到的),这里只多一条分隔——主人看得见的记录不会消失。
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
        this.convo = new ConvoState(log::append);
        this.workBlocks = WorkBlockMemory.forEntity(entityUuid);
        this.runtime = new RuntimeState(entityUuid);
        this.queue = new EventQueue(JsonlJournal.atFile(CompanionHome.inbox(entityUuid)));
        // 目标跨重进游戏活着 —— 长期目标就该是长期的,重启不该把它弄丢。
        this.goal = CompanionHome.goal(entityUuid);
        this.providerEntryId = CompanionHome.binding(entityUuid).providerId();
        this.dispatcher = new ToolDispatcher(entityUuid, this::resolveEntity);
        this.presenter = new TurnPresenter(entityUuid, this::status, this::personaName);
        this.tokens = new TokenLedger(entityUuid);
        this.model = new Model();
        this.loop = new AgentLoop(entityUuid.toString(), model, dispatcher, convo, queue, new Memory(), new Host());
        this.wasDriving = McpMode.instance().driving();
        loop.subscribe(presenter::on);
        loop.subscribe(tokens::on);
        loop.subscribe(this::onLoopEvent);
        restoreFromDisk();
    }

    /**
     * 与自动压缩闸门同一口径的模型上下文窗口,真源是<b>这只同伴绑定的档案</b>
     * ({@link com.dwinovo.numen.agent.llm.ProviderLibrary.Entry#contextWindow()})。没绑档案就是不可用
     * (见 {@link #endpointProblem}),没有窗口可言,返回 0。
     */
    public int modelWindow() {
        var entry = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().get(providerEntryId);
        return entry == null ? 0 : entry.contextWindow();
    }

    /** 上下文水位百分比(基于上次请求的实测 prompt tokens);用量或窗口未知时返回 0。 */
    public int contextPercent() {
        int window = modelWindow();
        if (lastPromptTokens <= 0 || window <= 0) return 0;
        return Math.min(100, Math.round(lastPromptTokens * 100f / window));
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
        // 面板的对话记录:读盘那一截在前,之后日志写下的每一条经同一个换法接上(分隔、切断点都在里面)。
        // 读的是原始文件顺序,不是整理后的模型视图——主人的聊天记录不会因为整理记忆而消失。
        display.addAll(log.loadDisplay(ConvoLog.DEFAULT_LOAD_LIMIT));
        log.onDisplay(display::add);
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (history.isEmpty()) return;
        convo.preload(history);
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
        // 外脑驱动期间面板画的是现场缓冲——主人的话得当场可见,不能等谁取走才出现。
        // 这里是所有主人话的单一咽喉(面板/快捷对话/语音/桥接),挂点只此一处。
        if (McpMode.instance().driving()) {
            com.dwinovo.numen.mcp.server.McpTranscript.owner(entityUuid, logged);
        }
        // Wrap the owner's words in <query> so the model can always tell real user input apart from
        // anything else numen injects into the same user turn (events, and future world-state/reminders).
        return deliver(new EventQueue.Entry(EventTypes.QUERY, wire, System.currentTimeMillis(), false));
    }

    /**
     * 主人客户端上的来源(桥接转发的群消息、直播弹幕)发来的一件世界上发生的事。和服务端的事件同一个构造口:
     * {@code <event kind="type">},盖上游戏内时间戳;急不急看类型表的那一行。种类必须登记过且不是主人那几行。
     *
     * @return 同 {@link #submitPrompt}:这条输入有没有被压着
     */
    public boolean submitEvent(String type, String text) {
        return deliver(com.dwinovo.numen.event.NumenEvents.entry(gameDayTime(), type, null, text,
                System.currentTimeMillis(), false));
    }

    /** 交给内核并观察:推进之后内核是不是正在等模型回话(见 {@link #submitPrompt} 的返回值说明)。 */
    private boolean deliver(EventQueue.Entry entry) {
        loop.push(List.of(entry));
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
        if (runtime.bodyOnFiniteTask()) {
            Constants.LOG.debug("[numen-entity#{}] 目标续跑让位:身体在做 {}", entityUuid, runtime.activity());
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
                        GoalPrompts.evaluatorQuery(target, runtime.xml(), sinceGoalForJudge()))),
                List.of(), GoalPrompts.evaluatorSystem(), Set.of());
        CancelToken cancel = new CancelToken();
        goalJudge = cancel;
        loop.consult(LoopEvent.Purpose.GOAL, request, cancel, outcome -> {
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
        if (loop.status().phase() == Phase.COMPACT) return "已经在整理了";
        if (queue.count(EventTypes.COMPACT) > 0) return "整理已经排上了";
        // 不看忙不忙:整理进队列排着,闲下来自己执行。按了就一定会发生,主人不必盯着什么时候能按。
        // 也不看记录长短:整理多少、什么时候整理是主人的事。条数门槛只属于自动整理
        // ——那是替他省一次没意义的请求,不是替他做决定。
        // 也不查端点:没绑模型时内核会停在 BLOCKED 并说明原因,绑好了排着的整理自己接着走。
        return null;
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

    /** 外接大脑替她说话(say 工具)——画法与内脑说话同一套表现层,见 {@link TurnPresenter#sayExternal}。 */
    public void externalSay(String text) {
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
        loop.push(List.of(com.dwinovo.numen.event.NumenEvents.entry(gameDayTime(), EventTypes.DEATH, null,
                "你刚才死了(" + cause + "),背包里的东西全掉在死亡地点了;"
                        + "现已在主人身边复活。先看看状况再决定下一步。",
                System.currentTimeMillis(), true)));
        loop.respawned();
    }


    /** 服务端说她在做什么(见 {@link RuntimeState#onCurrentTask})。 */
    public void onCurrentTask(com.dwinovo.numen.network.payload.CurrentTaskPayload p) {
        runtime.onCurrentTask(p);
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
     * 这只同伴现在发不了请求的理由(没绑档案、档案没填 key),给主人看的话;{@code null} = 能发。
     * 只有内核在要发请求时问它({@link ModelPort#unavailable}):不可用就停在 BLOCKED,原因进
     * {@link LoopStatus#holdReason},界面从那里读。
     */
    private String endpointProblem() {
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
        log.appendPersonaDivider();   // 落盘的记号,也经日志进面板的对话记录:重启后回看也知道这儿换过
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



    /** 事件时间戳用的游戏内时刻;身体不在客户端视野里时记 0。 */
    private long gameDayTime() {
        AbstractClientPlayer body = resolveEntity();
        return body != null ? body.level().getDayTime() : 0L;
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientNumenLookup.resolve(entityUuid);
    }

    // ---- kernel events: what the owner sees and what gets accounted ----

    /**
     * 内核的事件里同伴这一侧要接的:工作站坐标、长期目标、用量、历史边界。界面由 {@link TurnPresenter}
     * 自己订阅。这里不回头同步推进内核的步子——目标评估落地后推一条续跑,那已经是另一次主线程回调。
     */
    private void onLoopEvent(LoopEvent event) {
        switch (event) {
            case LoopEvent.RunStarted started -> cancelGoalJudging();
            case LoopEvent.ToolFinished finished -> harvestWorkBlocks(finished.call().name(), finished.resultJson());
            case LoopEvent.RunEnded ended -> {
                // 链条收尾了——这正是长期目标该接上的时刻:"还没做完就接着做"要等这一轮真的说完才判断得了。
                if (ended.end() instanceof RunEnd.Done) {
                    steerToGoal();
                }
            }
            case LoopEvent.Halted halted -> onHalted(halted.reason());
            case LoopEvent.ModelUsed used -> account(used.usage(), used.purpose());
            case LoopEvent.TranscriptBoundary boundary -> onBoundary(boundary.kind());
            default -> { }
        }
    }

    /** 执行了一次切断(不管当时有没有 run):在飞的目标评估作废,表里说要收工的目标收工。 */
    private void onHalted(HaltReason reason) {
        cancelGoalJudging();
        if (reason.endsGoal() && goal != null) {
            // 主人按停止 = 不要她接着跑了。目标跟着收工,否则这一轮刚断下一轮又自己续上,
            // 停止键就成了摆设。想接着做再说一次 /goal,成本就是一句话。
            clearGoal("按停止收工了:" + goal.objective());
        }
        if (reason == HaltReason.OWNER_STOP || reason == HaltReason.DISCONNECT) {
            runtime.clearCurrentTask();
        }
    }

    /** 对话调用的实测体量是自动压缩的判据,也记进目标的账单(台账自己订阅,不在这里记)。 */
    private void account(Usage usage, LoopEvent.Purpose purpose) {
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

    /** 历史换了(压缩、清空):上一次请求的体量不再作数,整理的熔断从头计。分隔线由日志自己交给显示记录。 */
    private void onBoundary(LoopEvent.Boundary kind) {
        if (kind == LoopEvent.Boundary.HALT) {
            return;
        }
        lastPromptTokens = 0;       // unknown until the next request reports usage
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
            List<ConvoState.Msg> messages = AgentRequestContext.attach(convo.snapshot(), runtime.xml());
            // 只发常驻工具:其余的在系统提示的 <deferred_tools> 目录里留一行摘要,
            // 模型调 find_tools 才取回完整定义(见 ToolDisclosure)。
            List<NumenTool> tools = ToolRegistry.resident();
            Set<String> callable = new LinkedHashSet<>();
            for (NumenTool t : tools) callable.add(t.name());
            callable.addAll(com.dwinovo.numen.agent.tool.ToolDisclosure.expandedIn(messages));
            return new ModelRequest(messages, tools, SystemPromptComposer.compose(personaText()), callable);
        }

        /**
         * 发出去,结果恰好一次交回(没被取消的话)。连请求都没组装出来就出的错——服务商配置对不上、
         * 历史转不成线格式——也是一次失败的调用,走同一个出口:同步抛出去的话,内核会永远等在 MODEL。
         */
        @Override
        public void call(ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta,
                         Consumer<ModelOutcome> onDone) {
            Minecraft mc = Minecraft.getInstance();
            java.util.concurrent.CompletableFuture<NumenLlmClient.ChatResult> result;
            try {
                result = stream(request, cancel, onDelta, mc);
            } catch (RuntimeException ex) {
                result = java.util.concurrent.CompletableFuture.failedFuture(ex);
            }
            result.whenComplete((res, err) -> mc.execute(() -> {
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

        private java.util.concurrent.CompletableFuture<NumenLlmClient.ChatResult> stream(
                ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta, Minecraft mc) {
            NumenLlmClient llm = client();
            return llm.chatStreaming(request.messages(), request.tools(), request.systemPrompt(), cancel, chunk -> {
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
            });
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
            return runtime.bodyTaskRunning();
        }

        /** 后台活优先(几十秒的长活,主人得看见她在挖矿而不是卡死了),没有才是手上这一个工具调用。 */
        @Override
        public String activity() {
            String task = runtime.activity();
            return task != null ? task : dispatcher.currentToolName();
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
