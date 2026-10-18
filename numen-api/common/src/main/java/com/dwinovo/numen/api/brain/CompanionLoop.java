package com.dwinovo.numen.api.brain;

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
import com.dwinovo.numen.api.agent.memory.Compactor;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.request.AgentRequestContext;
import com.dwinovo.numen.api.agent.request.BodySnapshot;
import com.dwinovo.numen.api.agent.request.MemoryPreamble;
import com.dwinovo.numen.api.agent.request.RuntimeState;
import com.dwinovo.numen.api.agent.request.TaskReport;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.agent.tool.CompanionToolPort;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.agent.tool.ToolAnchor;
import com.dwinovo.numen.api.data.ModLanguageData.Keys;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.program.ProgramHost;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 一只同伴的服务端大脑:循环内核 {@link AgentLoop} 的宿主,跑在服务端主线程上,用服主的模型档案想事。
 * 什么时候调模型、什么时候派工具、停止/死亡/登出/接管各做什么,全是内核的;内核只发 {@link LoopEvent},整理
 * ({@link Compactor})、长期目标({@link GoalSteward})、token 台账({@link TokenLedger})、札记索引重贴
 * ({@link MemoryPreamble})、运行期状态({@link RuntimeState})各自订阅。这里接的是需要世界的那些:
 * <ul>
 *   <li>内核的模型端口(请求由 {@link AgentRequestContext#turn} 组装,发请求交给 {@link Mind},回调切回主线程)与宿主端口;</li>
 *   <li>输入:主人的话与命令、世界上的事、死亡与复活;</li>
 *   <li>绑定的档案与人设,和她的家({@link Home}:日志、收件箱、台账、目标、绑定都落在那里,写走她的车道)。</li>
 * </ul>
 *
 * <p>她的一切只在这里:没有客户端的副本。主人下线时内脑待命({@link Hold#AWAY}),输入照收进收件箱,他回来再处理。
 *
 * <p>线程:全部在服务端主线程上。模型回调经 {@link Parts#main} 切回来。
 */
public final class CompanionLoop {

    /** 会话成员表那一侧:这句话还有谁听得见(群聊),见 {@code docs/group-chat.md}。 */
    @FunctionalInterface
    public interface Rooms {

        /**
         * 挂在 {@code <query>} 标记外面的"谁在场"那一行:模型看得到,聊天流不画。就他俩时没人听得见,说了反而是噪音:空串。
         *
         * @param conversation 她此刻在哪个会话里;{@code null} = 就他俩
         */
        String audienceLine(UUID companion, String conversation);
    }

    /**
     * 宿主给的东西。取值的都是现问现答,循环不存副本。
     *
     * @param home        她的家:日志、收件箱、台账、目标、绑定落在这里
     * @param main        服务端主线程的执行器:模型回调经它回来
     * @param body        她此刻的身体;没加载是 {@code null}
     * @param snapshot    她的身体状态,给 {@code <runtime_state>};没有是 {@code null}
     * @param absent      她的身体现在为什么不在世界里(死着、被外面请出了服务器);在就是 {@code null}。重进世界时按它恢复停牌
     * @param ownerOnline 主人在不在场({@link OwnerPresence})
     * @param driving     驾驶席是不是在外接大脑手里
     * @param initiative  主动性档位 1~10
     * @param persona     人设 id 对应的正文;没有这个人设返回 {@code null}
     * @param tools       她看得到的工具
     * @param dayTime     游戏内时刻,给事件盖时间戳
     */
    public record Parts(UUID companion, Home home, Executor main, Mind mind, ProgramHost programs, ToolBook tools,
                        Supplier<NumenPlayer> body, Supplier<BodySnapshot> snapshot, Supplier<HaltReason> absent,
                        BooleanSupplier ownerOnline, BooleanSupplier driving, IntSupplier initiative,
                        Function<String, String> persona, LongSupplier dayTime, Rooms rooms) {}

    private static final String CHAT = "chat.jsonl";
    private static final String INBOX = "inbox.jsonl";
    private static final String STATS = "stats.json";
    private static final String GOAL = "goal.json";
    private static final String BINDING = "binding.json";

    private final Parts parts;
    private final UUID companion;
    private final ConvoLog log;
    private final ConvoState convo;
    private final EventQueue queue;
    private final MemoryPreamble memory;
    private final RuntimeState runtime;
    private final CompanionToolPort tools;
    private final TokenLedger tokens;
    private final Compactor compactor;
    private final GoalSteward goals;
    private final AgentLoop loop;
    private final JsonDoc goalDoc;
    private final JsonDoc bindingDoc;

    /**
     * 她此刻在哪个会话里——最后一次是被哪个会话叫醒的。{@code null} = 就他俩(她的单成员会话)。
     * 记录盖印(见 {@link ConvoLog#append})和她说出口的话该让谁听见,读的是同一个事实。会话态,不落盘。
     */
    private String conversation;
    /**
     * 主人最后一句话是在哪儿说的({@link Venue})——她的回话回到那里({@link Voice})。和 {@link #conversation} 一样是
     * "最后一次被怎么叫醒"的会话态,不落盘。
     */
    private Venue venue = Venue.PANEL;
    /** 绑定的档案 id;{@code null} = 没绑。落盘在 {@code binding.json},四项绑定一个文件。 */
    private String providerId;
    /** 绑定的人设 id;{@code null} = 默认人格。人设正文每轮现取,没有副本。 */
    private String personaId;
    /** 绑定的声线 id;{@code null} = 不出声。声线定义在库里,朗读在主人的客户端。 */
    private String voiceId;
    /** 绑定的皮肤 id;{@code null} = 默认皮肤。穿在身上的签名数据在名册里,这里记的是库里的哪一条。 */
    private String skinId;
    /** 死亡原因,复活时放进复活事件(活着是 {@code null})。 */
    private String deathCause;
    /** 上一刻驾驶席在不在外接大脑手里,只用来找翻转的那一下。 */
    private boolean wasDriving;

    public CompanionLoop(Parts parts) {
        this.parts = parts;
        this.companion = parts.companion();
        Home home = parts.home();
        this.log = ConvoLog.atFile(home.file(CHAT), home.io());
        this.convo = new ConvoState(msg -> log.append(msg, conversation));
        this.memory = new MemoryPreamble(NoteBook.of(companion));
        this.runtime = new RuntimeState(companion, parts.snapshot());
        this.queue = new EventQueue(JsonlJournal.atFile(home.file(INBOX), home.io()));
        this.goalDoc = new JsonDoc(home.file(GOAL), JsonDoc.Format.of(1), home.io());
        this.bindingDoc = new JsonDoc(home.file(BINDING), JsonDoc.Format.of(1), home.io());
        ToolAnchor anchor = () -> companion;
        this.tools = new CompanionToolPort(companion, () -> anchor, parts.programs(), parts.tools(),
                this::receiptAfterCut);
        this.tokens = new TokenLedger(companion, new JsonDoc(home.file(STATS), JsonDoc.Format.of(1), home.io()));
        this.compactor = new Compactor(companion.toString(), convo, log, this::modelWindow);
        this.loop = new AgentLoop(companion.toString(), new Model(), tools, convo, queue, compactor, new Host());
        this.goals = new GoalSteward(companion.toString(), loop, convo, queue, runtime::xml,
                runtime::bodyOnFiniteTask, this::persistGoal, readGoal());
        loop.subscribe(tokens::on);
        loop.subscribe(compactor::on);
        loop.subscribe(goals::on);
        loop.subscribe(memory::on);
        loop.subscribe(runtime::on);
        this.wasDriving = parts.driving().getAsBoolean();
        restore();
    }

    /**
     * 把落盘的东西读回内存:绑定、台账、对话尾巴。一次死在半途的回合留下没结果的调用或没回复的 user 消息,原样留着——
     * {@code ProtocolView} 在下一次组请求时给悬空的调用补结果。
     */
    private void restore() {
        JsonObject binding = readDoc(bindingDoc);
        if (binding != null) {
            for (Slot slot : Slot.values()) {
                set(slot, text(binding, slot.wire()));
            }
        }
        tokens.load();
        log.migrateIfNeeded();
        // 重进后内核是全新的,身体不在的停牌按状态恢复:她不在的时候队列里可能躺着急件,不补这一下她会在身体回来之前就开口。
        HaltReason absent = parts.absent().get();
        if (absent != null) {
            loop.halt(absent);
            Constants.LOG.info("[numen-entity#{}] 恢复时她的身体还不在世界里({}) — 停牌等它回来", companion, absent);
        }
        List<ConvoState.Msg> history = log.load(ConvoLog.DEFAULT_LOAD_LIMIT);
        if (!history.isEmpty()) {
            convo.preload(history);
            Constants.LOG.info("[numen-entity#{}] restored {} msg(s) from disk", companion, history.size());
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
            Constants.LOG.warn("[numen-entity] {} 读不了: {}", doc.file().getFileName(), e.toString());
            return null;
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && !o.get(key).getAsString().isBlank()
                ? o.get(key).getAsString() : null;
    }

    private void persistBinding() {
        if (providerId == null && personaId == null && voiceId == null && skinId == null) {
            bindingDoc.delete();
            return;
        }
        JsonObject o = new JsonObject();
        for (Slot slot : Slot.values()) {
            if (bound(slot) != null) {
                o.addProperty(slot.wire(), bound(slot));
            }
        }
        bindingDoc.write(o);
    }

    // ---- 读 ----

    public UUID companion() {
        return companion;
    }

    /** 内核此刻的只读快照:忙不忙、为什么不动、排着什么。 */
    public LoopStatus status() {
        return loop.status();
    }

    /**
     * 在她的写车道上读点东西:排在前面已交的写都落下去之后才读,所以刚写下的一定读得到。结果在车道的线程上算好。
     */
    public <T> java.util.concurrent.CompletableFuture<T> read(Supplier<T> job) {
        return java.util.concurrent.CompletableFuture.supplyAsync(job, parts.home().io().executor());
    }

    /** 她此刻的样子,界面读的全部状态;变了才推给主人的客户端。 */
    public CompanionState state() {
        LoopStatus status = loop.status();
        GoalState goal = goals.goal();
        return new CompanionState(status.phase(), status.hold(), status.holdReason(), status.bodyTaskRunning(),
                status.activity(), status.compactProgress(), status.queuedPreview(), compactProblem(), clearProblem(),
                goal == null ? null : goal.toJson(), tokens.total(), tokens.sum(), tokens.latest(),
                tokens.waste().missedTokens(), tokens.waste().missCount(), contextPercent(), modelWindow(),
                personaId, providerId, voiceId, skinId, conversation, tools.outstanding(), convo.snapshot().size());
    }

    /** 她的对话日志:面板按 id 往前翻它。 */
    public ConvoLog log() {
        return log;
    }

    /** 她的收件箱(只读用:条目与编号)。 */
    public EventQueue inbox() {
        return queue;
    }

    /** 订阅循环事件:界面、记账之外的人(推给主人客户端的那一层)在这里接。 */
    public void subscribe(java.util.function.Consumer<? super LoopEvent> listener) {
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

    /** 她绑定的这一项的库条目 id({@code 命名空间:名字});没绑是 {@code null}。 */
    public String bound(Slot slot) {
        return switch (slot) {
            case PROVIDER -> providerId;
            case PERSONA -> personaId;
            case VOICE -> voiceId;
            case SKIN -> skinId;
        };
    }

    /** 她此刻在哪个会话里;{@code null} = 就他俩。 */
    public String conversation() {
        return conversation;
    }

    /** 主人在哪个会话里跟她说话,由会话服务在送话之前定下。 */
    public void inConversation(String conversationId) {
        this.conversation = conversationId;
    }

    /** 主人最后一句话是在哪儿说的。 */
    public Venue venue() {
        return venue;
    }

    /** 与自动整理同一口径的上下文窗口,取自她绑定的档案解析出的端点;没绑是 0。 */
    public int modelWindow() {
        return parts.mind().endpoint(providerId).contextWindow();
    }

    // ---- 输入 ----

    /**
     * 主人说话。死着、主人不在线也照收:话安安静静躺在收件箱里,复活或他回来时随其余攒着的一起处理。
     *
     * @return 这句话有没有被压着(内脑没能当场把请求发出去);只是观察,不是预测
     */
    public boolean submitPrompt(String text) {
        return submitPrompt(text, Venue.PANEL);
    }

    /** 同上,指明是在哪儿说的:她的回话回到那里。 */
    public boolean submitPrompt(String text, Venue spokenIn) {
        this.venue = spokenIn;
        return enqueueOwnerWords(EventQueue.query(text));
    }

    /**
     * 主人打了一条斜杠命令。{@code echo} 进 {@code <query>}(聊天流画的就是它),{@code expanded}(技能正文等)
     * 跟在标记外面:模型看得到,聊天流不画。
     */
    public boolean submitCommand(String echo, String expanded) {
        this.venue = Venue.PANEL;
        return enqueueOwnerWords(EventQueue.query(echo)
                + (expanded == null || expanded.isBlank() ? "" : "\n" + expanded));
    }

    private boolean enqueueOwnerWords(String wire) {
        String audience = parts.rooms().audienceLine(companion, conversation);
        return deliver(new EventQueue.Entry(EventTypes.QUERY,
                wire + (audience.isEmpty() ? "" : "\n" + audience), System.currentTimeMillis(), false));
    }

    /** 渠道(桥接、弹幕)带来的一件世界上发生的事,和服务端自己的事件同一个构造口。 */
    public boolean submitEvent(String type, String text) {
        return deliver(NumenEvents.entry(parts.dayTime().getAsLong(), type, null, text,
                System.currentTimeMillis(), false));
    }

    /** 收一批进队列的输入。什么时候倒出去由队列的熟度和内核的停牌说了算。 */
    public void push(List<EventQueue.Entry> entries) {
        loop.push(entries);
    }

    private boolean deliver(EventQueue.Entry entry) {
        loop.push(List.of(entry));
        return loop.status().phase() != Phase.MODEL;
    }

    /** 主人按停止。 */
    public void abort() {
        loop.halt(HaltReason.OWNER_STOP);
    }

    /** 主人下线:作废在飞的回应、放弃未决调用并记下切断点,不叫停身体、不删目标、不清队列。 */
    public void ownerLeft() {
        loop.halt(HaltReason.DISCONNECT);
    }

    /** 同伴离场或遣散:在飞的回合作废,不再往她的会话里写任何东西。 */
    public void dispose() {
        loop.halt(HaltReason.DISPOSE);
    }

    /** 这一轮做完了:没有在飞的回合,也没有马上要开的,见 {@link AgentLoop#settled}。 */
    public boolean settled() {
        return loop.settled();
    }

    /** 每个服务器刻一次:驾驶席翻转、停牌变化的播报,和"攒够时长"的熟度。 */
    public void tick() {
        // 驾驶席翻成外接的那一下作废在飞的回合:接管之后内脑的回复不该再派工具、整理不该再换历史。
        // 交还不用做什么——停牌是现算的,内核下一次推进自己看得见。
        boolean driving = parts.driving().getAsBoolean();
        if (driving && !wasDriving) {
            loop.halt(HaltReason.EXTERNAL);
        }
        wasDriving = driving;
        loop.tick();
    }

    // ---- 外接大脑 ----
    // 外脑驾驶的那段和内脑自己的一样进她的会话历史与日志(取走的事件是 user、替她说的话是 assistant、跑的程序是
    // assistant 的调用加 tool 的结果),面板读日志,交还之后内脑也知道这期间做了什么。

    /**
     * 外接大脑收件:取走此刻队里所有给模型看的文本(捎带的也交)。长轮询的到点清扫与程序回执的捎带都走这一个口,
     * 渲染与内脑注入同一份 {@link EventQueue#render}——外脑看到的事件文本和内脑一字不差。
     *
     * <p>控制条目(整理/清空)是对内脑说的:跳过它们、留在队里等交还。
     *
     * @return 取走的事件拼段;没有返回 null
     */
    public String takeEventsForExternal() {
        return took(queue.takeRenderedText(System.currentTimeMillis()));
    }

    /** 长等待停靠:有会叫醒她的条目就整批交出,规则见 {@link ExternalWait}。 */
    public ExternalWait.Parked parkForExternal() {
        ExternalWait.Parked parked = ExternalWait.park(queue, System::currentTimeMillis);
        return new ExternalWait.Parked(parked.batch().thenApply(this::took), parked.cancel());
    }

    private String took(String batch) {
        if (batch != null) {
            convo.addUser(batch);
        }
        return batch;
    }

    /**
     * 外接大脑跑一个工具调用:交给她自己的工具口,与内脑同一个派发器({@link AgentLoop#runAside})。
     *
     * @return 收下了;内脑正在干活、或已有一个外接调用在跑时为 false,什么都没记
     */
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

    /** 外接大脑替她说话:和内脑的一条回复同一个出口,见 {@link AgentLoop#said}。 */
    public void externalSay(String text) {
        loop.said(new AssistantTurn(text, List.of(), null));
    }

    /** 身体死了:内核冻结,收件箱照收。 */
    public void onDied(String cause) {
        deathCause = cause;
        loop.halt(HaltReason.DEATH, cause);
    }

    /** 身体在主人身边复活:死亡的叙事作为急件进收件箱,解开停牌后和死着时攒的一起走。 */
    public void onRespawned(String payloadCause) {
        String raw = payloadCause != null && !payloadCause.isBlank() ? payloadCause
                : (deathCause != null ? deathCause : "未知原因");
        String cause = raw.replace('<', '(').replace('>', ')');
        deathCause = null;
        Constants.LOG.info("[numen-entity#{}] respawned ({}) — loop thawed", companion, cause);
        // 死亡是急件——她关于自己处境的认知几乎每一条都作废了:物品掉在死亡地点、位置从矿洞变成了主人身边、
        // 手上的任务没了、血量装备全变了。这不分"任务中死"还是"空闲死",所以这里没有任何判据。
        loop.push(List.of(NumenEvents.entry(parts.dayTime().getAsLong(), EventTypes.DEATH, null,
                "你刚才死了(" + cause + "),背包里的东西全掉在死亡地点了;现已在主人身边复活。先看看状况再决定下一步。",
                System.currentTimeMillis(), true)));
        loop.respawned();
    }

    /**
     * 身体被外面的人或插件请出了服务器:内核冻结(同死亡),收件箱照收,并留下这条事实——她再回来时第一眼看到它。
     * 先停牌再入队:反过来这条急件会在停牌之前把她叫醒,开一轮模型调用再被作废。
     */
    public void onRemoved(String reason) {
        loop.halt(HaltReason.REMOVED, reason);
        loop.push(List.of(NumenEvents.entry(parts.dayTime().getAsLong(), EventTypes.REMOVED, null,
                "你的身体被服务器上的管理员或插件请出去了(" + reason.replace('<', '(').replace('>', ')')
                        + ")。这不是主人的决定,也不是你做错了什么;从那以后你不在世界里,主人再召唤你才能回来,背包和落点照旧。",
                System.currentTimeMillis(), true)));
    }

    /** 主人再召唤了被请出去的她:身体回到世界里,解开停牌,刚才留下的那条事实一并交给她。 */
    public void onReturned() {
        Constants.LOG.info("[numen-entity#{}] summoned back after removal — loop thawed", companion);
        loop.respawned();
    }

    /** 任务槽说她在做什么(见 {@link RuntimeState#onCurrentTask})。 */
    public void onCurrentTask(TaskReport p) {
        runtime.onCurrentTask(p);
    }

    /** 切断后程序交出的回执:这一批已经作废,她仍必须知道切断前做了什么,所以作为一条事件进收件箱。 */
    private void receiptAfterCut(String program, String receipt) {
        loop.push(List.of(NumenEvents.programStopped(parts.dayTime().getAsLong(), program,
                Program.Outcome.messageOf(receipt), System.currentTimeMillis())));
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

    /**
     * 现在不能整理记忆的理由(语言键,界面翻译);{@code null} = 能。"能不能"和"为什么不能"是同一个问题,判据只有这一份。
     */
    public String compactProblem() {
        Hold hold = loop.hold();
        if (hold == Hold.DEAD) return Keys.LOOP_GONE;
        // 整理是对内脑说的:驾驶席在外接模型手里时内脑不开工,排上了也只会一直躺着。
        if (hold == Hold.EXTERNAL) return Keys.LOOP_EXTERNAL_COMPACT;
        if (loop.status().phase() == Phase.COMPACT) return Keys.LOOP_COMPACTING;
        if (queue.count(EventTypes.COMPACT) > 0) return Keys.LOOP_COMPACT_QUEUED;
        // 不看忙不忙:整理进队列排着,闲下来自己执行。也不看记录长短、不查端点:没绑模型时内核停在 BLOCKED 并说明原因,
        // 绑好了排着的整理自己接着走。
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
     * 主人要求整理记忆:进队列排着,闲下来自己走。条目的字是语言键,界面按主人的语言翻译(控制条目不进模型)。
     *
     * @return 拒绝的理由(语言键);{@code null} = 已排上
     */
    public String requestCompact() {
        String problem = compactProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] manual compact refused: {}", companion, problem);
            return problem;
        }
        loop.push(List.of(new EventQueue.Entry(EventTypes.COMPACT, Keys.LOOP_COMPACT, System.currentTimeMillis(),
                false)));
        return null;
    }

    /** 主人要求清空上下文,走法同 {@link #requestCompact}。 */
    public String requestClearContext() {
        String problem = clearProblem();
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] manual clear refused: {}", companion, problem);
            return problem;
        }
        loop.push(List.of(new EventQueue.Entry(EventTypes.CLEAR, Keys.LOOP_CLEAR, System.currentTimeMillis(),
                false)));
        return null;
    }

    // ---- 她的记忆:导出与清空(只有主人能做) ----

    /**
     * 她的对话日志与札记打成的 zip。在她的写车道上读,所以排在前面已交的写之后——刚说的话也在里面。
     */
    public java.util.concurrent.CompletableFuture<byte[]> exportMemory() {
        return read(() -> {
            try {
                return MemoryExport.zip(parts.home().dir());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /**
     * 主人清空她的记忆:对话日志、札记、长期目标都删掉,名册与身体不动,然后如实告诉她——这条进她的上下文,她下一次开口
     * 之前就读到。她正在想事情的时候不清(在飞的回合会把刚清掉的又写回日志),等她停下来。
     *
     * @return 拒绝的理由(语言键);{@code null} = 清了
     */
    public String wipeMemory() {
        String problem = clearProblem();
        if (problem == null && loop.status().phase() != null) {
            problem = Keys.LOOP_WIPE_BUSY;
        }
        if (problem != null) {
            Constants.LOG.info("[numen-entity#{}] memory wipe refused: {}", companion, problem);
            return problem;
        }
        goals.clear(null);
        NoteBook notes = NoteBook.of(companion);
        for (NoteBook.Note note : notes.index()) {
            notes.forget(note.name());
        }
        log.wipe();
        requestClearContext();   // 内存里的上下文走清空的控制条目;日志已经是空的
        loop.push(List.of(new EventQueue.Entry(EventTypes.SERVER_MESSAGE, WIPED_NOTICE, System.currentTimeMillis(),
                false)));
        Constants.LOG.info("[numen-entity#{}] 记忆被主人清空", companion);
        return null;
    }

    /** 清空之后她读到的第一件事。 */
    private static final String WIPED_NOTICE =
            "主人清空了你的记忆:对话记录、札记和长期目标都没有了。你不记得之前聊过什么、做过什么,这是他的决定;需要的话可以问他。";

    // ---- 绑定 ----

    /**
     * 换她的一项绑定({@code null} = 不绑),落盘在她的家里。这里只记"绑的是哪一条";条目在不在库里、皮肤穿到身上
     * 这些是 {@link CompanionBinding} 的事。
     * <ul>
     *   <li>模型档案:下一次请求生效;因端点不可用停着的会再试一次;</li>
     *   <li>人设:下一轮系统提示现取正文,不打断在飞的请求,再往聊天流插一条分隔记号。不给模型注入"从现在起你是…"的和解消息——
     *       新系统提示本身就是最强的指令;</li>
     *   <li>声线、皮肤:界面读状态,客户端据此朗读。</li>
     * </ul>
     */
    public void bind(Slot slot, String entryId) {
        String id = entryId == null || entryId.isBlank() ? null : entryId;
        boolean changed = !Objects.equals(bound(slot), id);
        set(slot, id);
        persistBinding();
        Constants.LOG.info("[numen-entity#{}] {} entry set to {}", companion, slot.wire(), id == null ? "(none)" : id);
        switch (slot) {
            case PROVIDER -> loop.bindingChanged();   // 再绑同一份也要再试:可能是补上了 key
            case PERSONA -> {
                if (changed) {
                    log.appendPersonaDivider();
                }
            }
            case VOICE, SKIN -> { }
        }
    }

    private void set(Slot slot, String id) {
        switch (slot) {
            case PROVIDER -> providerId = id;
            case PERSONA -> personaId = id;
            case VOICE -> voiceId = id;
            case SKIN -> skinId = id;
        }
    }

    private String personaText() {
        return personaId == null ? null : parts.persona().apply(personaId);
    }

    // ---- 内核的端口 ----

    /** 模型那一侧:组装请求、问 {@link Mind}、把回调切回主线程。 */
    private final class Model implements ModelPort {

        @Override
        public Cause unavailable() {
            return parts.mind().unavailable(providerId);
        }

        /** 这一轮的请求:组装只在 {@link AgentRequestContext#turn} 一处,评测调的也是它。 */
        @Override
        public ModelRequest turnRequest() {
            // 这一轮用的端点和日志里最后记下的不同,就在日志里记一条换了模型 / 换了档位
            log.noteEndpoint(parts.mind().endpoint(providerId));
            return AgentRequestContext.turn(convo.snapshot(), runtime.xml(), personaText(), Modules.of(companion),
                    com.dwinovo.numen.api.agent.skill.SkillRegistry.of(companion), parts.tools().all());
        }

        @Override
        public void call(ModelRequest request, CancelToken cancel, java.util.function.Consumer<Delta> onDelta,
                         java.util.function.Consumer<ModelOutcome> onDone) {
            parts.mind().ask(providerId, companion, request, cancel,
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
        public int initiativeLevel() {
            return parts.initiative().getAsInt();
        }

        @Override
        public boolean externallyDriven() {
            return parts.driving().getAsBoolean();
        }

        @Override
        public boolean ownerPresent() {
            return parts.ownerOnline().getAsBoolean();
        }

        /** {@code <memory>} 索引,贴不贴见 {@link MemoryPreamble}。 */
        @Override
        public String injectionPreamble() {
            return memory.next();
        }

        @Override
        public boolean bodyTaskRunning() {
            return runtime.bodyTaskRunning();
        }

        @Override
        public LoopStatus.Doing activity() {
            return runtime.doing(tools.currentToolName());
        }
    }

    /** 最近一轮的用量,给面板的命中率;累计在 {@link #tokens}。 */
    public Usage lastUsage() {
        return tokens.latest();
    }
}
