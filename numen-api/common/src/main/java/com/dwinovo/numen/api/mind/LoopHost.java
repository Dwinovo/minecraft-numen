package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.goal.GoalPrompts;
import com.dwinovo.numen.api.agent.goal.GoalState;
import com.dwinovo.numen.api.agent.goal.GoalSteward;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.inbox.JsonlJournal;
import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.AgentLoop;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.HaltReason;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.HostPort;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.loop.Phase;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.agent.memory.Compactor;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.request.AgentRequestContext;
import com.dwinovo.numen.api.agent.request.MemoryPreamble;
import com.dwinovo.numen.api.agent.request.SystemPromptComposer;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.agent.tool.CompanionToolPort;
import com.dwinovo.numen.api.agent.trace.TraceRecorder;
import com.dwinovo.numen.api.agent.trace.TraceRecorders;
import com.dwinovo.numen.api.agent.tool.QuestionDesk;
import com.dwinovo.numen.api.agent.tool.ToolAnchor;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.mind.Bindings;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.CompanionState;
import com.dwinovo.numen.api.mind.MemoryExport;
import com.dwinovo.numen.api.mind.Mind;
import com.dwinovo.numen.api.mind.OwnerSchedules;
import com.dwinovo.numen.api.mind.Slot;
import com.dwinovo.numen.api.mind.TokenLedger;
import com.dwinovo.numen.api.talk.Spoken;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.talk.Venue;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.script.HerModules;
import com.dwinovo.numen.api.seat.Catalog;
import com.dwinovo.numen.api.seat.Opened;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.api.seat.SeatEvent;
import com.dwinovo.numen.api.seat.SeatState;
import com.dwinovo.numen.api.sdk.ClientContext;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 一只同伴的大脑,在她的创建者的客户端上:循环内核 {@link AgentLoop} 的宿主。什么时候调模型、什么时候派工具、停止/死亡/登出/接管各做什么,
 * 全是内核的;内核只发 {@link LoopEvent},整理({@link Compactor})、长期目标({@link GoalSteward})、token 台账({@link TokenLedger})、
 * 札记索引重贴({@link MemoryPreamble})各自订阅。这里接的是需要座位的那些:
 * <ul>
 *   <li>世界上的事与别人的话从座位的事件({@link #onSeatEvent})进收件箱;身体的死、重生、被请出、下线、回来也是事件,
 *       按种类停牌与解开的表只在 {@link #onSeatEvent} 里;</li>
 *   <li>程序、叫停、切断交给座位({@link SeatPrograms});一轮开头 {@code state/get} 取她此刻的样子,这一轮里每份程序结局带着新的
 *       ({@link BodyMirror});</li>
 *   <li>模型用她绑的档案({@link Mind}),系统提示用座位上取来的目录({@link Catalog})、人设与她自己的模块、技能拼;</li>
 *   <li>她的上下文(日志、收件箱、台账、目标、定时、绑定、札记)落在主人电脑上她的家里({@link Home})。</li>
 * </ul>
 *
 * <p>她的脑子和记忆只在这里:服务端没有副本。座位还没坐上、或坐已经断了的时候内脑待命({@link Hold#AWAY}),输入照收进收件箱。
 *
 * <p>线程:全部在 {@link Parts#main} 上(产品里是客户端主线程)。座位的回调经它切回来。
 */
public final class LoopHost implements ClientContext {

    /**
     * 宿主给的东西。取值的都是现问现答,循环不存副本。
     *
     * @param creator    她的创建者(就是这台电脑上的玩家):绑定文件里只有 id 的值算他绑的
     * @param home       她的家:日志、收件箱、台账、目标、绑定、札记落在这里
     * @param main       大脑的线程的执行器:座位与模型的回调经它回来
     * @param seat       这条到服务器的座位连接
     * @param mind       怎么跟模型说话
     * @param tools      她看得到的工具
     * @param driving    驾驶席是不是在外接大脑手里
     * @param initiative 主动性档位 1~10
     * @param persona    她绑的人设的正文;没有这个人设返回 {@code null}
     * @param modules    她自己的模块(内置的原文在服务端,见 {@link SeatSources})
     * @param skills     她自己的技能(随发布的在座位那一头,见 {@link SeatSources})
     * @param playerName 一位玩家给人看的名字;不知道是空串
     */
    public record Parts(UUID companion, UUID creator, Home home, Executor main, SeatClient seat, Mind mind,
                        ToolBook tools, BooleanSupplier driving, IntSupplier initiative,
                        Function<Bound, String> persona, Function<HerModules.Builtins, HerModules> modules,
                        Function<SkillRegistry.Bundled, SkillRegistry> skills, Function<UUID, String> playerName) {}

    private static final String CHAT = "chat.jsonl";
    private static final String INBOX = "inbox.jsonl";
    private static final String STATS = "stats.json";
    private static final String GOAL = "goal.json";
    private static final String SCHEDULES = "schedules.json";
    private static final String MEMORY = "memory";

    private final Parts parts;
    private final UUID companion;
    private final ConvoLog log;
    private final ConvoState convo;
    private final EventQueue queue;
    private final MemoryPreamble memory;
    private final BodyMirror body = new BodyMirror();
    private final NoteBook notes;
    private final SeatSources sources;
    private final HerModules modules;
    private final SkillRegistry skills;
    private final SeatPrograms programs;
    private final CompanionToolPort tools;
    /** 她向主人提的问题挂在这里;面板读它。 */
    private final QuestionDesk questions = new QuestionDesk();
    /** 渠道此刻在来访她吗:由收渠道来访的那一头({@code LoopHands})按它的来访表回答,没有渠道就是 false。 */
    private volatile BooleanSupplier visiting = () -> false;
    private final TokenLedger tokens;
    /** 这个进程不记轨迹({@link TraceRecorders#recording})时是 {@code null}。 */
    private final TraceRecorder trace;
    private final Compactor compactor;
    private final GoalSteward goals;
    private final AgentLoop loop;
    private final JsonDoc goalDoc;
    private final JsonDoc bindingDoc;
    private final OwnerSchedules schedules;
    private final Clock clock = new Clock();

    /** 她的绑定,落盘在 {@code binding.json}:档案、人设、声线各是谁绑的哪一条。 */
    private Bindings bindings = Bindings.NONE;
    /** 座位上的状况:坐上之前没有目录、没有代数。 */
    private volatile Catalog catalog = Catalog.EMPTY;
    private volatile long generation;
    private volatile boolean seated;
    /** 她此刻在答的那句话是谁、在哪儿说的:回话回到那里,程序替他做事,日志盖那个会话的印。会话态,不落盘。 */
    private volatile Spoken spoken;
    /** 上一刻驾驶席在不在外接大脑手里,只用来找翻转的那一下。 */
    private boolean wasDriving;
    /** 外接大脑正在跑的那个调用要收的进展;没有在跑的是 {@code null}。 */

    /** 这一头的钟:最近一次座位上说的游戏刻与时刻,之后每刻前进一刻。 */
    private static final class Clock {
        private long gameTime;
        private long dayTime;

        void set(long game, long day) {
            if (game >= gameTime) {
                gameTime = game;
                dayTime = day;
            }
        }

        void tick() {
            gameTime++;
            dayTime++;
        }
    }

    public LoopHost(Parts parts) {
        this.parts = parts;
        this.companion = parts.companion();
        Home home = parts.home();
        this.log = ConvoLog.atFile(home.file(CHAT), home.io());
        this.convo = new ConvoState(msg -> log.append(msg, spoken.conversation()));
        this.notes = new NoteBook(new Home(home.file(MEMORY), home.io()), () -> (int) (clock.dayTime / 24000L));
        this.memory = new MemoryPreamble(notes);
        this.queue = new EventQueue(JsonlJournal.atFile(home.file(INBOX), home.io()));
        this.goalDoc = new JsonDoc(home.file(GOAL), JsonDoc.Format.of(1), home.io());
        this.bindingDoc = new JsonDoc(home.file(Bindings.FILE), JsonDoc.Format.of(1), home.io());
        JsonDoc scheduleDoc = new JsonDoc(home.file(SCHEDULES), JsonDoc.Format.of(1), home.io());
        this.schedules = new OwnerSchedules(scheduleDoc, readDoc(scheduleDoc));
        this.sources = new SeatSources(parts.seat(), companion, () -> catalog);
        this.modules = parts.modules().apply(sources.builtins);
        this.skills = parts.skills().apply(sources.skills);
        this.programs = new SeatPrograms(parts.seat(), companion, modules, parts.main(), () -> generation,
                this::stateArrived, () -> spoken.who());
        ToolAnchor anchor = new ToolAnchor() {
            @Override
            public UUID entityUuid() {
                return companion;
            }

            @Override
            public NoteBook notes() {
                return notes;
            }

            @Override
            public SkillRegistry skills() {
                return skills;
            }

            @Override
            public QuestionDesk questions() {
                return questions;
            }

            @Override
            public boolean channelVisit() {
                return visiting.getAsBoolean();
            }
        };
        this.tools = new CompanionToolPort(companion, () -> anchor, programs, parts.tools(), this::receiptAfterCut);
        this.tokens = new TokenLedger(companion, new JsonDoc(home.file(STATS), JsonDoc.Format.of(1), home.io()));
        this.compactor = new Compactor(companion.toString(), convo, log,
                () -> parts.mind().endpoint(bound(Slot.PROVIDER)));
        this.loop = new AgentLoop(companion.toString(), new Model(), tools, convo, queue, compactor, new Host());
        this.goals = new GoalSteward(companion.toString(), loop, convo, queue, body::xml, body::bodyOnFiniteTask,
                this::persistGoal, readGoal());
        this.spoken = Spoken.panel(parts.creator());
        loop.subscribe(tokens::on);
        loop.subscribe(this::voice);
        loop.subscribe(this::reportRun);
        loop.subscribe(compactor::on);
        loop.subscribe(goals::on);
        loop.subscribe(memory::on);
        this.trace = TraceRecorders.recording() ? new TraceRecorder(home.dir(), home.io(), companion) : null;
        if (trace != null) {
            loop.subscribe(trace::on);
        }
        this.wasDriving = parts.driving().getAsBoolean();
        restore();
        parts.seat().onEvent(event -> {
            if (event.companion().equals(companion)) {
                parts.main().execute(() -> onSeatEvent(event));
            }
        });
        parts.seat().serveContext(companion, this);
        parts.seat().serveModules(companion, hash -> modules.hers().values().stream()
                .filter(code -> com.dwinovo.numen.api.library.Fingerprint.of(code).equals(hash)).findFirst().orElse(null));
    }

    /** 把落盘的东西读回内存:绑定、台账、对话尾巴。一次死在半途的回合留下没结果的调用或没回复的 user 消息,原样留着。 */
    private void restore() {
        JsonObject binding = readDoc(bindingDoc);
        if (binding != null) {
            bindings = Bindings.read(binding, parts.creator());
        }
        tokens.load();
        log.migrateIfNeeded();
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (!history.isEmpty()) {
            convo.preload(history);
            Constants.LOG.info("[numen-mind#{}] restored {} msg(s) from disk", companion, history.size());
        }
    }

    private GoalState readGoal() {
        JsonObject o = readDoc(goalDoc);
        return o == null ? null : GoalState.fromJson(o);
    }

    private void persistGoal(GoalState goal) {
        if (goal == null) {
            goalDoc.delete();
        } else {
            goalDoc.write(goal.toJson());
        }
    }

    private static JsonObject readDoc(JsonDoc doc) {
        try {
            return doc.read().orElse(null);
        } catch (RuntimeException e) {
            Constants.LOG.warn("[numen-mind] cannot read {}: {}", doc.file().getFileName(), e.toString());
            return null;
        }
    }

    private void persistBinding() {
        if (bindings.isEmpty()) {
            bindingDoc.delete();
        } else {
            bindingDoc.write(bindings.toJson());
        }
    }

    // ---- 座位 ----

    /**
     * 座位坐上了:她的身体此刻的代数与服务端的目录到手,内脑从待命回到工作。
     */
    public void attach(Opened opened, Catalog fetched) {
        generation = opened.generation();
        catalog = fetched;
        sources.refresh();
        seated = true;
        Constants.LOG.info("[numen-mind#{}] seated at generation {}", companion, generation);
    }

    /** 目录变了,新的到手。 */
    public void catalogChanged(Catalog fetched) {
        catalog = fetched;
        sources.refresh();
    }

    /** 客户端函数在这里取她的大脑里的东西:她的模块、札记、技能、目录。 */
    @Override
    public <T> T of(Class<T> type) {
        Object found = type == HerModules.class ? modules
                : type == NoteBook.class ? notes
                : type == SkillRegistry.class ? skills
                : type == Catalog.class ? catalog : null;
        if (found == null) {
            throw new IllegalArgumentException("her brain has no " + type.getSimpleName());
        }
        return type.cast(found);
    }

    /** 座位上没人了(连接断了、她被遣散了):内脑待命,在飞的回合作废,输入照收。 */
    public void detach() {
        seated = false;
        loop.halt(HaltReason.DISCONNECT);
    }

    /**
     * 座位上来了一件事。事件只带时间,不带她的样子。<b>身体的变化也是事件</b>,按种类决定停牌与解开——这张表是大脑这一头决定的
     * 唯一一处:死了、被请出了服务器、主人让她下线要先停牌再入队(反过来这条急件会在停牌之前把她叫醒,开一轮模型调用再被作废);
     * 重生与回来要先入队再解开,死着、不在世界里期间攒下的事件和输入一起走。
     */
    private void onSeatEvent(SeatEvent event) {
        clock.set(event.gameTime(), event.dayTime());
        generation = Math.max(generation, event.generation());
        EventQueue.Entry entry = event.entry();
        if (event.spoken() != null && EventTypes.QUERY.equals(event.type())) {
            this.spoken = event.spoken();   // 她接下来答的是这句话:回话回给他、程序替他做事
        }
        switch (event.type()) {
            case EventTypes.DEATH -> {
                loop.halt(HaltReason.DEATH, entry.text());
                loop.push(List.of(entry));
            }
            case EventTypes.REMOVED -> {
                loop.halt(HaltReason.REMOVED, entry.text());
                loop.push(List.of(entry));
            }
            case EventTypes.OFFLINE -> {
                loop.halt(HaltReason.OFFLINE);
                loop.push(List.of(entry));
            }
            case EventTypes.RESPAWNED, EventTypes.RETURNED -> {
                loop.push(List.of(entry));
                loop.respawned();
            }
            case EventTypes.TASK_FINISHED -> {
                body.taskFinished();
                loop.push(List.of(entry));
            }
            default -> loop.push(List.of(entry));
        }
        parts.seat().ack(companion, event.seq()).whenComplete((done, failed) -> {
            if (failed != null) {
                Constants.LOG.debug("[numen-mind#{}] could not acknowledge event {}: {}", companion, event.seq(),
                        failed.toString());
            }
        });
    }

    /** 一份新的样子到手(一轮开头的 {@code state/get},或一份程序结局带来的)。 */
    private void stateArrived(SeatState state) {
        body.take(state);
        clock.set(state.gameTime(), state.dayTime());
    }

    // ---- 读 ----

    public UUID companion() {
        return companion;
    }

    /** 内核此刻的只读快照:忙不忙、为什么不动、排着什么。 */
    public LoopStatus status() {
        return loop.status();
    }

    /** 内核此刻在干哪一档。 */
    public Phase phase() {
        return loop.phase();
    }

    /** 她此刻在干的那件事。 */
    public LoopStatus.Doing activity() {
        return loop.activity();
    }

    /** 在她的写车道上读点东西:排在前面已交的写都落下去之后才读,所以刚写下的一定读得到。 */
    public <T> CompletableFuture<T> read(Supplier<T> job) {
        return CompletableFuture.supplyAsync(job, parts.home().io().executor());
    }

    /** 她此刻的样子,界面读的全部状态。 */
    public CompanionState state() {
        LoopStatus status = loop.status();
        GoalState goal = goals.goal();
        return new CompanionState(status.phase(), status.hold(), status.holdReason(), status.bodyTaskRunning(),
                status.activity(), status.compactProgress(), status.queuedPreview(), compactProblem(), clearProblem(),
                goal == null ? null : goal.toJson(), tokens.total(), tokens.sum(), tokens.latest(),
                tokens.waste().missedTokens(), tokens.waste().missCount(), contextPercent(), modelWindow(),
                shownBindings(), null, null, tools.outstanding(), convo.snapshot().size());
    }

    /** 状态里的绑定:每一项连同绑的人此刻的名字。 */
    private Map<Slot, CompanionState.Binding> shownBindings() {
        Map<Slot, CompanionState.Binding> shown = new EnumMap<>(Slot.class);
        bindings.slots().forEach((slot, bound) ->
                shown.put(slot, new CompanionState.Binding(bound, parts.playerName().apply(bound.by()))));
        return shown;
    }

    /** 她的对话日志:面板按 id 往前翻它。 */
    public ConvoLog log() {
        return log;
    }

    /** 她的收件箱(只读用:条目与编号)。 */
    public EventQueue inbox() {
        return queue;
    }

    /** 她自己的模块:{@code numen.module} 各函数读写它。 */
    public HerModules modules() {
        return modules;
    }

    /** 她的札记。 */
    public NoteBook notes() {
        return notes;
    }

    /** 她的技能:自己的加随发布的。 */
    public SkillRegistry skills() {
        return skills;
    }

    /** 服务端目录的此刻的样子。 */
    public Catalog catalog() {
        return catalog;
    }

    /** 这一轮请求的系统提示:人设、操作核心、技能表、API 索引……内置大脑读的就是它,外接大脑经 MCP 的 guide 工具取同一份。 */
    public String systemPrompt() {
        return SystemPromptComposer.compose(personaText(), catalog, modules.hers(), modules.disabled(),
                skills.formatXml());
    }

    /** 下一次请求里 {@code <runtime_state>} 此刻的样子:最近一次服务端说的。 */
    public String runtimeState() {
        return body.xml();
    }

    /** 她向主人提的问题:面板读它、主人在上面作答。 */
    public QuestionDesk questions() {
        return questions;
    }

    /** 渠道来访的来访表在谁手里:她被渠道来访着的时候,问主人的工具当场回绝。 */
    public void visiting(BooleanSupplier visiting) {
        this.visiting = visiting;
    }

    /** 订阅循环事件:界面、记账之外的人在这里接。 */
    public void subscribe(Consumer<? super LoopEvent> listener) {
        loop.subscribe(listener);
    }

    public TokenLedger tokens() {
        return tokens;
    }

    /** 上下文水位百分比。 */
    public int contextPercent() {
        return compactor.contextPercent();
    }

    /** 这次工具调用的结果还会不会来;历史里没结果、这里又答 false 的调用是被切断的。 */
    public boolean isToolCallOutstanding(String callId) {
        return tools.holds(callId);
    }

    /** 她这一项绑着什么(谁绑的、哪一条);没绑是 {@code null}。 */
    public Bound bound(Slot slot) {
        return bindings.bound(slot);
    }

    /** 主人最后一句话是在哪儿说的。 */
    public Spoken spoken() {
        return spoken;
    }

    /** 与自动整理同一口径的上下文窗口,取自她绑定的档案解析出的端点;没绑是 0。 */
    public int modelWindow() {
        return parts.mind().endpoint(bound(Slot.PROVIDER)).contextWindow();
    }

    /** 最近一轮的用量,给面板的命中率;累计在 {@link #tokens}。 */
    public Usage lastUsage() {
        return tokens.latest();
    }

    // ---- 输入 ----

    /**
     * 主人说话。死着、座位上没人也照收:话安安静静躺在收件箱里,她回来时随其余攒着的一起处理。
     *
     * @return 这句话有没有被压着(内脑没能当场把请求发出去);只是观察,不是预测
     */
    public boolean submitPrompt(String text) {
        this.spoken = Spoken.panel(parts.creator());
        return deliver(new EventQueue.Entry(EventTypes.QUERY, EventQueue.query(text, clock.dayTime, null),
                System.currentTimeMillis(), false));
    }

    /**
     * 主人打了一条斜杠命令。{@code echo} 进 {@code <query>}(聊天流画的就是它),{@code expanded}(技能正文等)
     * 跟在标记外面:模型看得到,聊天流不画。
     */
    public boolean submitCommand(String echo, String expanded) {
        this.spoken = Spoken.panel(parts.creator());
        return deliver(new EventQueue.Entry(EventTypes.QUERY, EventQueue.query(echo, clock.dayTime, null)
                + (expanded == null || expanded.isBlank() ? "" : "\n" + expanded), System.currentTimeMillis(), false));
    }

    /** 主人给她定的定时到点了,说那句话:和主人的话同一条路进收件箱,开标签上带来源,面板据此标"定时"。 */
    private boolean submitScheduled(String text) {
        this.spoken = Spoken.panel(parts.creator());
        return deliver(new EventQueue.Entry(EventTypes.QUERY, EventQueue.scheduledQuery(text, clock.dayTime),
                System.currentTimeMillis(), false));
    }

    /** 渠道(桥接、弹幕)带来的一件世界上发生的事,和服务端自己的事件同一个构造口。 */
    public boolean submitEvent(String type, String text) {
        return deliver(NumenEvents.entry(clock.dayTime, type, null, text, System.currentTimeMillis(), false));
    }

    private boolean deliver(EventQueue.Entry entry) {
        loop.push(List.of(entry));
        return loop.phase() != Phase.MODEL;
    }

    /** 主人按停止。 */
    public void abort() {
        loop.halt(HaltReason.OWNER_STOP);
    }

    /** 这只同伴离场或遣散:在飞的回合作废,不再往她的会话里写任何东西。 */
    public void dispose() {
        loop.halt(HaltReason.DISPOSE);
    }

    /** 这一轮做完了:没有在飞的回合,也没有马上要开的,见 {@link AgentLoop#settled}。 */
    public boolean settled() {
        return loop.settled();
    }

    /** 每个客户端刻一次:驾驶席翻转、定时到点、内核推进。 */
    public void tick() {
        clock.tick();
        // 驾驶席翻成外接的那一下作废在飞的回合:接管之后内脑的回复不该再派工具、整理不该再换历史。
        // 交还不用做什么——停牌是现算的,内核下一次推进自己看得见。
        boolean driving = parts.driving().getAsBoolean();
        if (driving && !wasDriving) {
            loop.halt(HaltReason.EXTERNAL);
        }
        wasDriving = driving;
        if (seated) {
            for (String words : schedules.due(clock.gameTime)) {
                submitScheduled(words);
            }
        }
        loop.tick();
    }

    // ---- 主人的定时 ----

    /** 主人给她定的几条定时。 */
    public List<OwnerSchedules.Schedule> schedules() {
        return schedules.list();
    }

    /**
     * 新增({@code id} 为 {@code null})或修改一条定时。
     *
     * @throws IllegalArgumentException 见 {@link OwnerSchedules#put}
     */
    public OwnerSchedules.Schedule putSchedule(String id, String text, long every) {
        return schedules.put(id, text, every, clock.gameTime);
    }

    /** 删一条定时;没有这一条返回 {@code false}。 */
    public boolean deleteSchedule(String id) {
        return schedules.delete(id);
    }

    // ---- 外接大脑 ----
    // 外脑驾驶的那段和内脑自己的一样进她的会话历史与日志(取走的事件是 user、替她说的话是 assistant、跑的程序是
    // assistant 的调用加 tool 的结果),面板读日志,交还之后内脑也知道这期间做了什么。

    /**
     * 外接大脑收件:取走此刻队里所有给模型看的文本(捎带的也交)。渲染与内脑注入同一份 {@link EventQueue#render}。
     * 控制条目(整理/清空)是对内脑说的:跳过它们、留在队里等交还。
     *
     * @return 取走的事件拼段;没有返回 null
     */
    public String takeEventsForExternal() {
        return took(queue.takeRenderedText(System.currentTimeMillis(), clock.dayTime));
    }

    /** 长等待停靠:有会叫醒她的条目就整批交出,规则见 {@link ExternalWait}。 */
    public ExternalWait.Parked parkForExternal() {
        ExternalWait.Parked parked = ExternalWait.park(queue, System::currentTimeMillis, () -> clock.dayTime);
        return new ExternalWait.Parked(parked.batch().thenApply(this::took), parked.cancel());
    }

    private String took(String batch) {
        if (batch != null) {
            convo.addUser(batch);
        }
        return batch;
    }

    /** 外接大脑跑一个工具调用:交给她自己的工具口,与内脑同一个派发器({@link AgentLoop#runAside})。 */
    public boolean runExternal(LlmToolCall call, Consumer<String> done) {
        if (!loop.asideFree()) {
            return false;
        }
        convo.addAssistant(new AssistantTurn("", List.of(call), null));
        return loop.runAside(call, result -> {
            convo.addToolResult(call.id(), result);
            done.accept(result);
        });
    }

    /**
     * 外接大脑要的她此刻的 {@code <runtime_state>}:向座位现取一份(身体的样子是服务端的,这里的镜像只在内脑开轮时更新),
     * 放进镜像再交出。读不到(座位没了)交 {@code null},外脑照样拿到别的。
     */
    public CompletableFuture<String> readState() {
        CompletableFuture<String> read = new CompletableFuture<>();
        parts.seat().state(companion).whenComplete((sitting, failed) -> parts.main().execute(() -> {
            if (failed != null) {
                Constants.LOG.warn("[numen-mind#{}] could not read her state for the external brain: {}", companion,
                        failed.toString());
                read.complete(null);
                return;
            }
            generation = sitting.generation();
            stateArrived(sitting.state());
            read.complete(runtimeState());
        }));
        return read;
    }

    /** 外接大脑替她说话:和内脑的一条回复同一个出口,见 {@link AgentLoop#said}。 */
    public void externalSay(String text) {
        loop.said(new AssistantTurn(text, List.of(), null));
    }

    /** 切断后程序交出的回执:这一批已经作废,她仍必须知道切断前做了什么,所以作为一条事件进收件箱。 */
    private void receiptAfterCut(String program, String receipt) {
        loop.push(List.of(NumenEvents.programStopped(clock.dayTime, program, Program.Outcome.messageOf(receipt),
                System.currentTimeMillis())));
    }

    // ---- 长期目标 ----

    public GoalState goal() {
        return goals.goal();
    }

    /** 定一个目标。整份目标只在这里交给她一次;走 {@link #submitCommand} 是为了聊天里有主人打的那行字。 */
    public void setGoal(GoalState next, String echo) {
        if (goals.set(next)) {
            submitCommand(echo, GoalPrompts.initialDirective(next));
        }
    }

    public void clearGoal(String why) {
        goals.clear(why);
    }

    // ---- 整理与清空 ----

    /** 现在不能整理记忆的理由(语言键,界面翻译);{@code null} = 能。 */
    public String compactProblem() {
        Hold hold = loop.hold();
        if (hold == Hold.DEAD) return Keys.LOOP_GONE;
        // 整理是对内脑说的:驾驶席在外接模型手里时内脑不开工,排上了也只会一直躺着。
        if (hold == Hold.EXTERNAL) return Keys.LOOP_EXTERNAL_COMPACT;
        if (loop.phase() == Phase.COMPACT) return Keys.LOOP_COMPACTING;
        if (queue.count(EventTypes.COMPACT) > 0) return Keys.LOOP_COMPACT_QUEUED;
        return null;
    }

    /** {@code /clear} 现在按不按得下,形状同 {@link #compactProblem},不查端点:清空不发请求。 */
    public String clearProblem() {
        Hold hold = loop.hold();
        if (hold == Hold.DEAD) return Keys.LOOP_GONE;
        if (hold == Hold.EXTERNAL) return Keys.LOOP_EXTERNAL_CLEAR;
        if (queue.count(EventTypes.CLEAR) > 0) return Keys.LOOP_CLEAR_QUEUED;
        return null;
    }

    /**
     * 主人要求整理记忆:进队列排着,闲下来自己走。
     *
     * @param focus 摘要要特别留意的;没有是 {@code null} 或空串
     * @return 拒绝的理由(语言键);{@code null} = 已排上
     */
    public String requestCompact(String focus) {
        String problem = compactProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-mind#{}] manual compact refused: {}", companion, problem);
            return problem;
        }
        loop.push(List.of(new EventQueue.Entry(EventTypes.COMPACT, EventTypes.controlText(Keys.LOOP_COMPACT, focus),
                System.currentTimeMillis(), false)));
        return null;
    }

    /** 主人要求清空上下文,走法同 {@link #requestCompact}。 */
    public String requestClearContext() {
        String problem = clearProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-mind#{}] manual clear refused: {}", companion, problem);
            return problem;
        }
        loop.push(List.of(new EventQueue.Entry(EventTypes.CLEAR, Keys.LOOP_CLEAR, System.currentTimeMillis(), false)));
        return null;
    }

    // ---- 她的记忆:导出与清空 ----

    /** 她的对话日志与札记打成的 zip。在她的写车道上读,所以排在前面已交的写之后——刚说的话也在里面。 */
    public CompletableFuture<byte[]> exportMemory() {
        return read(() -> {
            try {
                return MemoryExport.zip(parts.home().dir());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /**
     * 主人清空她的记忆:对话日志、札记、长期目标都删掉,然后如实告诉她——这条进她的上下文,她下一次开口之前就读到。她正在想事情的时候不清
     * (在飞的回合会把刚清掉的又写回日志),等她停下来。
     *
     * @return 拒绝的理由(语言键);{@code null} = 清了
     */
    public String wipeMemory() {
        String problem = clearProblem();
        if (problem == null && loop.phase() != null) {
            problem = Keys.LOOP_WIPE_BUSY;
        }
        if (problem != null) {
            Constants.LOG.info("[numen-mind#{}] memory wipe refused: {}", companion, problem);
            return problem;
        }
        goals.clear(null);
        for (NoteBook.Note note : notes.index()) {
            notes.forget(note.name());
        }
        log.wipe();
        requestClearContext();   // 内存里的上下文走清空的控制条目;日志已经是空的
        tell(WIPED_NOTICE);
        Constants.LOG.info("[numen-mind#{}] memory wiped by the owner", companion);
        return null;
    }

    /** 往她的收件箱放一条不叫醒她的知会(服务器消息);她下一次开口前读到。 */
    private void tell(String text) {
        loop.push(List.of(NumenEvents.entry(clock.dayTime, EventTypes.SERVER_MESSAGE, null, text,
                System.currentTimeMillis(), false)));
    }

    /** 主人在札记里新增或改了一条:如实告诉她,免得她按旧的印象再写一遍。 */
    public void ownerWroteNote(NoteBook.Note note, boolean replaced) {
        tell("Your owner " + (replaced ? "changed your note " : "added a note of yours, ") + note.name() + ": "
                + note.description() + ". It is their decision; go by your notes as they are now.");
        Constants.LOG.info("[numen-mind#{}] the owner {} the note {}", companion, replaced ? "changed" : "added", note.name());
    }

    /** 主人删了她一条札记:告诉她,她不该再把它写回去。 */
    public void ownerForgotNote(NoteBook.Note note) {
        tell("Your owner deleted your note " + note.name() + " (" + note.description()
                + "). It is their decision; do not write it back.");
        Constants.LOG.info("[numen-mind#{}] the owner deleted the note {}", companion, note.name());
    }

    /** 清空之后她读到的第一件事。 */
    private static final String WIPED_NOTICE =
            "Your owner wiped your memory: the conversation record, your notes and your long-term goal are all gone. "
                    + "You do not remember what you talked about or did before. It is their decision; ask them if you need to.";

    // ---- 绑定 ----

    /**
     * 换她的一项绑定({@code null} = 不绑),落盘在她的家里。
     * <ul>
     *   <li>模型档案:下一次请求生效;因端点不可用停着的会再试一次;</li>
     *   <li>人设:下一轮系统提示现取正文,不打断在飞的请求,再往聊天流插一条分隔记号;</li>
     *   <li>声线:界面读状态,客户端据此朗读。</li>
     * </ul>
     */
    public void bind(Slot slot, Bound bound) {
        boolean changed = !Objects.equals(bound(slot), bound);
        bindings = bindings.with(slot, bound);
        persistBinding();
        Constants.LOG.info("[numen-mind#{}] {} entry set to {}", companion, slot.wire(),
                bound == null ? "(none)" : bound.id());
        switch (slot) {
            case PROVIDER -> loop.bindingChanged();   // 再绑同一份也要再试:可能是补上了 key
            case PERSONA -> {
                if (changed) {
                    log.appendPersonaDivider();
                }
            }
            case VOICE -> { }
        }
    }

    /**
     * 库条目改了名(id 就是名字):她这一项绑的正是 {@code was} 就改指 {@code to}。
     *
     * @return 改了
     */
    public boolean repoint(Slot slot, Bound was, EntryId to) {
        if (!was.equals(bound(slot))) {
            return false;
        }
        bind(slot, was.to(to));
        return true;
    }

    private String personaText() {
        Bound persona = bound(Slot.PERSONA);
        return persona == null ? null : parts.persona().apply(persona);
    }

    // ---- 说出口与报告 ----

    /**
     * 她说的一句话,交给服务端在他问的地方说出来({@code say}):面板里创建者亲口问的,面板自己看得到,不经服务端;别的(私聊、群里、
     * 分享来的 keeper 的话)回给那一位。客户端从不碰聊天。
     */
    private void voice(LoopEvent event) {
        if (!(event instanceof LoopEvent.AssistantMessage message)) {
            return;
        }
        String said = message.turn().content() == null ? "" : message.turn().content().strip();
        Spoken to = spoken;
        if (said.isEmpty() || to.venue() == Venue.PANEL && to.who().equals(parts.creator())) {
            return;
        }
        parts.seat().say(companion, said, to).whenComplete((done, failed) -> {
            if (failed != null) {
                Constants.LOG.warn("[numen-mind#{}] could not say her words aloud: {}", companion, failed.toString());
            }
        });
    }

    /** 一次 run 的开收报给服务端,它把它告诉插件({@code RUN_STARTED}、{@code RUN_ENDED})。 */
    private void reportRun(LoopEvent event) {
        if (event instanceof LoopEvent.RunStarted started) {
            parts.seat().runStarted(companion, started.runId());
        } else if (event instanceof LoopEvent.RunEnded ended) {
            switch (ended.end()) {
                case RunEnd.Done done -> parts.seat().runEnded(companion, ended.runId(), "DONE", "");
                case RunEnd.Failed failed -> parts.seat().runEnded(companion, ended.runId(), "FAILED", failed.cause().code());
                case RunEnd.Halted halted -> parts.seat().runEnded(companion, ended.runId(), "HALTED", halted.reason().code());
            }
        }
    }

    // ---- 轨迹 ----

    /** 这一轮记进轨迹的其余字段:出处、档位、阶段、目标、队里有几条、发出去的那份运行期状态原文(身体的样子就在它里面)。 */
    private JsonObject turnBody(LlmEndpoint endpoint, String xml) {
        JsonObject o = new JsonObject();
        o.addProperty("model", endpoint.model().id());
        o.addProperty("thinking", endpoint.options().thinking().id());
        LoopStatus status = loop.status();
        o.addProperty("phase", status.phase() == null ? "IDLE" : status.phase().name());
        GoalState goal = goals.goal();
        o.addProperty("goal", goal == null ? "" : goal.objective());
        o.addProperty("inbox", queue.size());
        o.addProperty("runtime", xml);
        return o;
    }

    /** 这一只的轨迹记录;这个进程不记轨迹时是 {@code null}。评测与读轨迹的用例按它找文件。 */
    public TraceRecorder trace() {
        return trace;
    }

    // ---- 内核的端口 ----

    /** 模型那一侧:一轮开头取她此刻的样子、组装请求、问 {@link Mind}、把回调切回主线程。 */
    private final class Model implements ModelPort {

        @Override
        public Cause unavailable() {
            return parts.mind().unavailable(bound(Slot.PROVIDER));
        }

        /** 一轮开头 {@code state/get} 一次:这一轮第一次请求带的样子是此刻的。座位没了就是主人离开了。 */
        @Override
        public void prepare(long runId, CancelToken cancel, Runnable ready) {
            parts.seat().state(companion).whenComplete((sitting, failed) -> parts.main().execute(() -> {
                if (cancel.isCancelled()) {
                    return;
                }
                if (failed != null) {
                    Constants.LOG.warn("[numen-mind#{}] could not read her state, so this run does not start: {}",
                            companion, failed.toString());
                    loop.halt(HaltReason.DISCONNECT);
                    return;
                }
                generation = sitting.generation();
                stateArrived(sitting.state());
                ready.run();
            }));
        }

        @Override
        public ModelRequest turnRequest(long runId, int n) {
            LlmEndpoint endpoint = parts.mind().endpoint(bound(Slot.PROVIDER));
            // 这一轮用的端点和日志里最后记下的不同,就在日志里记一条换了模型 / 换了档位
            log.noteEndpoint(endpoint);
            // xml 只算一次:记下来的必须就是发出去的那一份
            String xml = body.xml();
            ModelRequest request = AgentRequestContext.turn(convo.snapshot(), xml, personaText(), catalog,
                    modules.hers(), modules.disabled(), skills.formatXml(), parts.tools().all());
            if (trace != null) {
                trace.turn(runId, n, turnBody(endpoint, xml));
            }
            return request;
        }

        @Override
        public void call(ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta,
                         Consumer<ModelOutcome> onDone) {
            parts.mind().ask(bound(Slot.PROVIDER), companion, request, cancel,
                    delta -> parts.main().execute(() -> {
                        if (!cancel.isCancelled()) {
                            onDelta.accept(delta);
                        }
                    }),
                    outcome -> parts.main().execute(() -> {
                        if (!cancel.isCancelled()) {
                            onDone.accept(outcome);
                        }
                    }));
        }
    }

    /** 同伴这一侧的现场事实。 */
    private final class Host implements HostPort {

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public long dayTime() {
            return clock.dayTime;
        }

        @Override
        public int initiativeLevel() {
            return parts.initiative().getAsInt();
        }

        @Override
        public boolean externallyDriven() {
            return parts.driving().getAsBoolean();
        }

        @Override
        public boolean ownerPresent() {
            return seated;
        }

        /** {@code <memory>} 索引,贴不贴见 {@link MemoryPreamble}。 */
        @Override
        public String injectionPreamble() {
            return memory.next();
        }

        @Override
        public boolean bodyTaskRunning() {
            return body.bodyTaskRunning();
        }

        @Override
        public LoopStatus.Doing activity() {
            if (questions.pending() != null) {
                return new LoopStatus.Doing(Keys.ACTIVITY_AWAITING_ANSWER, List.of());
            }
            return body.doing(tools.currentToolName(), tools.currentSummary());
        }
    }
}
