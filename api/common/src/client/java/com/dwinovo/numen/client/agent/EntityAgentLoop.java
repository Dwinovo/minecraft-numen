package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.goal.GoalPrompts;
import com.dwinovo.numen.agent.goal.GoalState;
import com.dwinovo.numen.agent.goal.GoalSteward;
import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.inbox.JsonlJournal;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.memory.Compactor;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.HostPort;
import com.dwinovo.numen.agent.loop.LoopStatus;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelPort;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.loop.Phase;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpMode;
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
 * holds — all of that is the kernel's. The kernel only emits events, and each concern subscribes itself:
 * {@link TurnPresenter} (what the owner sees and hears), {@link TokenLedger}, {@link Compactor}
 * (compaction and clearing), {@link GoalSteward} (the long-term goal), {@link WorkBlockMemory} and
 * {@link RuntimeState} (the per-turn runtime state, including the mirror of her current task).
 * This facade wires them together and keeps what needs Minecraft:
 * <ul>
 *   <li>the kernel's model port (a request from {@link SystemPromptComposer} and {@link RuntimeState},
 *       the endpoint check, hopping callbacks back to the main thread) and host port;</li>
 *   <li>the inputs: the owner's words and commands, world events from the owner's client, death and
 *       respawn, the external driver's intake;</li>
 *   <li>persona / model binding and the registry lifecycle.</li>
 * </ul>
 *
 * <h2>Threading rules</h2>
 * All mutations run on the client main thread: owner input from the chat screen, tool results
 * ({@link ToolDispatcher}), and model callbacks, which {@link Model#call} hops back via
 * {@code Minecraft.execute} before they reach the kernel.
 */
public final class EntityAgentLoop {




    private final UUID entityUuid;
    /** JSONL persistence under {@code config/numen/conversations/<uuid>.jsonl}. */
    private final ConvoLog log;
    private final ConvoState convo;
    /** Functional-block coordinate memory, injected as {@code <known_blocks>}. */
    private final WorkBlockMemory workBlocks;
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
    /**
     * 她这一局已经调用过的工具名——此后它们的完整定义每轮都随请求发出,不再需要重新
     * {@code find_tools}。
     *
     * <h2>为什么这份状态可以留在边上</h2>
     * "展开过没有"必须从对话里推(压缩把那条 find_tools 结果总结掉,模型手里就真没有参数
     * 定义了,副本集合会骗人)。而这里相反:名字一进这个集合,工具的完整定义就<b>回到了
     * 每一轮的工具表里</b>——定义是随请求发出去的,压缩拿不走它,所以它不可能骗人。
     *
     * <p>不这么做就会绕死循环:find_tools → 调用 → 压缩吃掉那条结果 → 同一个工具被判"没展开"
     * 而挡下 → 再 find_tools……每压缩一次重来一遍(issue #109)。pi 的做法同义:被调用过的
     * 工具不再算 deferred。
     */
    private final Set<String> usedTools = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final ToolDispatcher dispatcher;


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
    /** 上下文整理:自动压缩的判据、切分、摘要落地、清空。 */
    private final Compactor compactor;
    /** 长期目标:设定、评估、续跑、收工。 */
    private final GoalSteward goals;
    /** 循环内核:run、停牌、推进、切断都在它那里。 */
    private final AgentLoop loop;
    /** 上一个 tick 驾驶席在不在外接模型手里——只用来找"翻转成外接"的那一下。 */
    private boolean wasDriving;

    EntityAgentLoop(UUID entityUuid) {
        this.entityUuid = entityUuid;
        this.log = ConvoLog.atFile(CompanionHome.chat(entityUuid));
        this.convo = new ConvoState(log::append);
        this.workBlocks = WorkBlockMemory.forEntity(entityUuid);
        this.runtime = new RuntimeState(entityUuid);
        this.queue = new EventQueue(JsonlJournal.atFile(CompanionHome.inbox(entityUuid)));
        this.providerEntryId = CompanionHome.binding(entityUuid).providerId();
        this.dispatcher = new ToolDispatcher(entityUuid, this::resolveEntity, usedTools::add);
        this.presenter = new TurnPresenter(entityUuid, this::status, this::personaName);
        this.tokens = new TokenLedger(entityUuid);
        this.model = new Model();
        this.compactor = new Compactor(entityUuid.toString(), convo, log, this::modelWindow);
        this.loop = new AgentLoop(entityUuid.toString(), model, dispatcher, convo, queue, compactor, new Host());
        // 目标跨重进游戏活着 —— 长期目标就该是长期的,重启不该把它弄丢。
        this.goals = new GoalSteward(entityUuid.toString(), loop, convo, queue, runtime::xml,
                runtime::bodyOnFiniteTask, g -> CompanionHome.setGoal(entityUuid, g), CompanionHome.goal(entityUuid));
        this.wasDriving = McpMode.instance().driving();
        // 内核只发事件,各管一摊的各自订阅:界面、台账、整理、目标、工作站坐标、她手上那件活的镜像
        loop.subscribe(presenter::on);
        loop.subscribe(tokens::on);
        loop.subscribe(compactor::on);
        loop.subscribe(goals::on);
        loop.subscribe(workBlocks::on);
        loop.subscribe(runtime::on);
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

    /** 上下文水位百分比(见 {@link Compactor#contextPercent})。 */
    public int contextPercent() {
        return compactor.contextPercent();
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
     *         看的是入队并推进之后内核是不是正在等模型回话。调用方拿 {@code status().busy()} 之类的东西
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
    void quiesce() {
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
    void clientTick() {
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


    // ---- 长期目标 ----

    /** 当前的长期目标;{@code null} = 没有。 */
    public GoalState goal() {
        return goals.goal();
    }

    /**
     * 定一个目标。整份目标<b>只在这里</b>交给她一次;之后每轮只补评估器那句"还差什么"。
     *
     * @param echo 主人打的原文({@code /goal 挖 128 个钻石})。走 {@link #submitCommand} 是为了
     *             聊天里有个气泡——他打了字就该看见自己打了什么,跟 {@code /build} 一个待遇
     */
    public void setGoal(GoalState next, String echo) {
        if (goals.set(next)) {
            submitCommand(echo, GoalPrompts.initialDirective(next));
        }
    }

    /** 收工(见 {@link GoalSteward#clear})。 */
    public void clearGoal(String why) {
        goals.clear(why);
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
     * 运行时换人设。只做两件事:改绑定(下一轮 {@link SystemPromptComposer} 现取正文,
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




    /** 事件时间戳用的游戏内时刻;身体不在客户端视野里时记 0。 */
    private long gameDayTime() {
        AbstractClientPlayer body = resolveEntity();
        return body != null ? body.level().getDayTime() : 0L;
    }

    private AbstractClientPlayer resolveEntity() {
        return ClientNumenLookup.resolve(entityUuid);
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
            // 常驻工具 + 她这一局用过的那些:其余的在系统提示的 <deferred_tools> 目录里
            // 留一行摘要,模型调 find_tools 才取回完整定义(见 ToolDisclosure)。
            List<NumenTool> tools = com.dwinovo.numen.agent.tool.ToolDisclosure.sendable(
                    ToolRegistry.resident(), ToolRegistry.deferred(), usedTools);
            Set<String> callable = com.dwinovo.numen.agent.tool.ToolDisclosure.callable(tools, messages);
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
