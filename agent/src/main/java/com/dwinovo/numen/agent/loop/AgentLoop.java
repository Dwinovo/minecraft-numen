package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.ai.AiLog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 同伴大脑的循环内核:一个 ReAct 循环加一个事件队列。设计见 {@code docs/agent-loop.md}。
 *
 * <h2>状态只有两样</h2>
 * <ul>
 *   <li><b>{@link Run}</b>——手上正在做的那件事;{@code null} 就是闲。"在不在跑"只问它,异步回调带着
 *       run 的编号回来,对不上就丢。</li>
 *   <li><b>{@link Hold}</b>——为什么不开 run,谁解开写在 {@link Hold#releasedBy} 的表里。</li>
 * </ul>
 *
 * <h2>唯一的推进点 {@link #pump}</h2>
 * 入队、run 结束、停牌解开、每个 tick 之后都只调它。它只在真的开 run 或执行控制条目时打日志——
 * 停牌时每 tick 调一次也没有输出。
 *
 * <h2>一次 run 是两层循环</h2>
 * <pre>
 * startRun → turn:要不要调模型 →(先看自动压缩)→ 注入 → 调模型
 *            onModel:有工具调用 → 串行跑工具 → 全部结算 → turn
 *                     最终回复   → turn(队里有要她回应的就接着走,没有才结束)
 * </pre>
 * 每次调模型带上哪些条目、本来要停时要不要接着走,只看各投递档自己的声明
 * ({@link EventTypes.Delivery#wakes}/{@link EventTypes.Delivery#joins}),取件在 {@link EventQueue#takeForCall}
 * 一处;带上的一次取光合成一条 user。控制条目是循环在闲时自己执行的,排在它后面的等它执行完。
 *
 * <h2>切断只有一个入口 {@link #halt}</h2>
 * 主人停止、死亡、登出、外接接管、遣散的差别只在 {@link HaltReason} 那张表。
 *
 * <h2>对外只发事件</h2>
 * 界面、记账、落盘都订阅 {@link LoopEvent};内核读写的只有端口、会话历史和队列。
 *
 * <p>纯 JVM。一切状态只在一个线程(客户端主线程)上读写,端口负责把异步回调切回这个线程。
 */
public final class AgentLoop {

    /** 模型回了一个既没正文也没工具调用的空回复:算失败,不重试——重试的范围是调用本身出错。 */
    static final String EMPTY_REPLY = "服务端返回了空回应";

    private final String name;
    private final ModelPort model;
    private final ToolPort tools;
    private final ConvoState transcript;
    private final EventQueue inbox;
    private final MemoryPort memory;
    private final HostPort host;
    private final List<Consumer<? super LoopEvent>> listeners = new ArrayList<>();

    private Run run;
    private long lastRunId;
    /** 身体死着(身体事实,复活才解开)。 */
    private boolean dead;
    /** 后三种停牌:OWNER_STOP / BLOCKED / FAILED;{@code null} = 没有。 */
    private Hold stopped;
    /** 进入 {@link #stopped} 时带的那句话(端点不可用、失败的原因);解开时一起清。 */
    private String stoppedReason;
    /** 最近一次对外报过的停牌。只用来找变化沿发 {@link LoopEvent.HoldChanged},不参与任何判断。 */
    private Hold announced;
    /** 这次整理记忆已经流回来的摘要字数。 */
    private int compactChars;

    /**
     * @param name       日志里认这只同伴用的名字
     * @param transcript 会话历史;落盘由它的 sink 负责
     * @param inbox      主人的话与世界事件共用的队列;条目怎么投递查 {@link EventTypes}
     */
    public AgentLoop(String name, ModelPort model, ToolPort tools, ConvoState transcript, EventQueue inbox,
                     MemoryPort memory, HostPort host) {
        this.name = name;
        this.model = model;
        this.tools = tools;
        this.transcript = transcript;
        this.inbox = inbox;
        this.memory = memory;
        this.host = host;
        this.announced = hold();
    }

    /**
     * 订阅事件。事件在内核的线程上按发生顺序同步送达。内核发事件时正走在一步的中间,
     * 订阅者只做自己那一份(画、记账、落盘),不在回调里同步推进内核。
     */
    public void subscribe(Consumer<? super LoopEvent> listener) {
        listeners.add(listener);
    }

    // ---- 输入 ----

    /**
     * 收一批输入。整批先入队、再推进一次:离线补发的条目一次到达,逐条推进的话第一条急件就开了 run,
     * 只带走已经到的那几条。
     *
     * <p>来自主人的插话解开 {@link Hold.Release#OWNER_SPOKE} 那几种停牌,急件解开 FAILED。死着、外接驾驶时也照收:
     * 条目盖着真实时间戳,之后模型看得出哪些是那期间发生的。
     */
    public void push(List<EventQueue.Entry> entries) {
        long now = host.now();
        boolean ownerSpoke = false;
        boolean urgent = false;
        for (EventQueue.Entry e : entries) {
            if (e.text() == null || e.text().isBlank()) {
                continue;
            }
            boolean asUrgent = inbox.push(e.type(), e.text(), e.ts() > 0 ? e.ts() : now, e.urgent());
            AiLog.LOG.info("[numen-entity#{}] queued {}{}: {}", name, e.type(), asUrgent ? " URGENT" : "",
                    brief(e.text(), 120));
            urgent |= asUrgent;
            ownerSpoke |= EventTypes.get(e.type()).ownerWords();
        }
        if (ownerSpoke) {
            release(Hold.Release.OWNER_SPOKE);
        }
        if (urgent) {
            release(Hold.Release.URGENT);
        }
        announceHold(null);
        pump();
    }

    /** 身体复活了。复活的叙事事件由调用方在这之前推进队列,解开后一起走。 */
    public void respawned() {
        release(Hold.Release.RESPAWN);
        announceHold(null);
        pump();
    }

    /** 这只同伴改绑了模型档案。 */
    public void bindingChanged() {
        release(Hold.Release.BINDING_CHANGED);
        announceHold(null);
        pump();
    }

    /** 每个客户端 tick 一次:报告现算停牌的变化(外接交还),并为"攒够时长"再问一次熟度。 */
    public void tick() {
        announceHold(null);
        pump();
    }

    // ---- 推进 ----

    /** 能开 run 就开,能执行控制条目就执行;否则什么也不做,也不说话。 */
    public void pump() {
        if (run != null) {
            return;   // run 里的边界自己取队列
        }
        Hold hold = hold();
        if (hold == Hold.DEAD || hold == Hold.EXTERNAL) {
            return;
        }
        if (headIsControl()) {
            runControl();   // 清空/整理是主人对内脑的直接要求,按了停止之后照样立即执行
            return;
        }
        if (hold != null) {
            return;
        }
        long now = host.now();
        int level = host.initiativeLevel();
        if (!inbox.shouldDrain(now, level)) {
            return;
        }
        AiLog.LOG.info("[numen-queue#{}] 主动开轮:{}(攒了 {} 条/阈值 {},最老 {}s/上限 {}s,档位 {})",
                name,
                inbox.hasUrgent() ? "有急件"
                        : (inbox.size() >= EventQueue.thresholdOf(level) ? "攒够了" : "攒久了"),
                inbox.size(), EventQueue.thresholdOf(level),
                inbox.oldestAgeMs(now) / 1000L, EventQueue.maxWaitMsOf(level) / 1000L, level);
        startRun(false, false);
    }

    /**
     * 开一次 run。重试也走这里,于是过同样的端点检查,第一次调模型前同样先看自动压缩。
     * 端点不可用就进 BLOCKED 并把原因随事件交出去,不开 run。
     */
    private void startRun(boolean retried, boolean ownerSpoke) {
        String problem = model.unavailable();
        if (problem != null) {
            block(problem);
            return;
        }
        run = new Run(++lastRunId, false, retried, ownerSpoke);
        emit(new LoopEvent.RunStarted(run.id));
        // 重试接着回应失败那次的输入;闲时开的 run 是队里要她回应的条目引起的
        turn(retried);
    }

    /**
     * 内层循环的一个边界。这次调不调模型由边界自己知道的事定:有待回应的东西(一批工具结果、失败重试的那次输入)
     * 就调;没有的话(run 开头、模型给了最终回复),只在队里有要她回应的条目时调
     * ({@link EventQueue#wantsAnswer})——都没有就收尾。
     *
     * <p>不从历史末尾去猜有没有待回应的:整理记忆留下的摘要、被切断的那句话都停在末尾,看上去"待回应",
     * 却不是这次调用的原因。
     *
     * @param answerDue 有待回应的东西,这次调用无论如何都会发生
     */
    private void turn(boolean answerDue) {
        if (!answerDue && !inbox.wantsAnswer()) {
            end(RunEnd.DONE);
            return;
        }
        callModel(answerDue);
    }

    /**
     * 调一次模型:先压缩(要压的话)、再注入、再调。压缩放在注入之前:切分会把近段原文留下、更早的总结掉,
     * 先注入的话主人刚说的话也可能被总结进去。
     *
     * @param answerDue 同 {@link #turn}:取件按进边界时定下的原因,压缩改写历史不影响它
     */
    private void callModel(boolean answerDue) {
        if (memory.compactionDue()) {
            compact(true, () -> callModel(answerDue));
            return;
        }
        inject(inbox.takeForCall(answerDue, host.now()));
        run.phase = Phase.MODEL;
        ModelRequest request = model.turnRequest();
        transcript.incrementTurn();
        long id = run.id;
        AiLog.LOG.info("[numen-entity#{}] turn {}: convo={} msgs, tools={}",
                name, transcript.turnCount(), request.messages().size(), request.tools().size());
        emit(new LoopEvent.TurnStarted(id, run.ownerSpoke));
        model.call(request, run.cancel,
                delta -> {
                    if (current(id)) {
                        emit(new LoopEvent.ModelDelta(id, delta.content(), delta.reasoning()));
                    }
                },
                outcome -> onModel(id, request, outcome));
    }

    /**
     * 取出来的条目拼成一条 user 消息:现场块在前,世界的事按时间排进 {@code <events>},主人的话垫底
     * (排版规则在 {@link EventQueue#render})。
     */
    private void inject(List<EventQueue.Entry> batch) {
        List<String> rendered = EventQueue.render(batch, host.now());
        if (rendered.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        String preamble = host.injectionPreamble();
        if (!preamble.isEmpty()) {
            parts.add(preamble);
        }
        parts.addAll(rendered);
        transcript.addUser(String.join("\n", parts));
        transcript.resetTurnCount();   // 新输入开始一条新链:只是日志编号
        if (batch.stream().anyMatch(e -> EventTypes.get(e.type()).ownerWords())) {
            run.ownerSpoke = true;
        }
    }

    private void onModel(long id, ModelRequest request, ModelOutcome outcome) {
        if (!current(id)) {
            return;
        }
        switch (outcome) {
            case ModelOutcome.Failed failed -> end(new RunEnd.Failed(failed.words(), true));
            case ModelOutcome.Answered answered -> {
                emit(new LoopEvent.ModelUsed(answered.usage(), LoopEvent.Purpose.TURN));
                AssistantTurn reply = answered.turn();
                if (!reply.hasToolCalls() && reply.content().isEmpty()) {
                    AiLog.LOG.warn("[numen-entity#{}] LLM returned an empty turn (no content, no tool calls)", name);
                    end(new RunEnd.Failed(EMPTY_REPLY, false));
                    return;
                }
                run.retried = false;
                run.ownerSpoke = false;
                transcript.addAssistant(reply);
                emit(new LoopEvent.AssistantMessage(id, reply));
                if (reply.hasToolCalls()) {
                    run.phase = Phase.TOOLS;
                    tools.run(reply.toolCalls(), toolSink(id));
                } else {
                    transcript.resetTurnCount();
                    turn(false);   // 本来要停了:队里还有要她回应的就接着走,没有才收尾
                }
            }
        }
    }

    private ToolPort.Sink toolSink(long id) {
        return new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
                if (current(id)) {
                    emit(new LoopEvent.ToolStarted(id, call));
                }
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                if (!current(id)) {
                    return;
                }
                transcript.addToolResult(call.id(), resultJson);
                emit(new LoopEvent.ToolFinished(id, call, resultJson));
            }

            @Override
            public void settled() {
                if (current(id)) {
                    turn(true);   // 工具结果等着回应
                }
            }
        };
    }

    private void end(RunEnd end) {
        Run finished = run;
        run = null;
        if (end instanceof RunEnd.Failed failed) {
            // 失败的回应不进历史;记一条切断点,之后的请求里模型知道上一轮没连上
            writeHalt("这一轮没连上(" + failed.words() + ")");
        }
        emit(new LoopEvent.RunEnded(finished.id, end));
        if (end instanceof RunEnd.Failed failed) {
            afterFailure(finished, failed);
        }
        announceHold(null);
        pump();
    }

    /**
     * 失败收尾时决定:调用出错且这条链还没重试过,就重开一次 run;否则让主人看见,进 FAILED——
     * 已经排着急件的话不停牌,它们本身就是解开 FAILED 的理由。
     */
    private void afterFailure(Run failed, RunEnd.Failed end) {
        AiLog.LOG.warn("[numen-entity#{}] turn failed: {}", name, end.words());
        if (end.retryable() && !failed.retried) {
            AiLog.LOG.info("[numen-entity#{}] re-running failed turn once", name);
            startRun(true, failed.ownerSpoke);
            return;
        }
        emit(new LoopEvent.TurnFailed(end.words()));
        stopped = Hold.FAILED;
        stoppedReason = end.words();
        if (inbox.hasUrgent()) {
            release(Hold.Release.URGENT);
        }
    }

    // ---- 控制条目与整理记忆 ----

    private void runControl() {
        // 连着按的几次算一次;批里混着清空就清空说了算——整理要的是腾地方,清空把地方全腾出来了。
        // 分清是哪一条控制命令只能认 id:类型表只说"它是控制命令"。
        boolean clears = inbox.nextControls().stream().anyMatch(e -> EventTypes.CLEAR.equals(e.type()));
        if (!clears) {
            // 整理要发一次请求。端点不可用就进 BLOCKED,条目留在队首——绑定改好了自己接着走,按了就一定会发生。
            String problem = model.unavailable();
            if (problem != null) {
                block(problem);
                return;
            }
        }
        inbox.takeControls();
        if (clears) {
            AiLog.LOG.info("[numen-entity#{}] 清空上下文:排到了", name);
            memory.clear();
            emit(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.CLEAR));
            pump();   // 排在清空后面的话进全新的上下文
            return;
        }
        AiLog.LOG.info("[numen-entity#{}] 整理记忆:排到了,开始", name);
        run = new Run(++lastRunId, true, false, false);
        compact(false, () -> {
            run = null;
            announceHold(null);
            pump();
        });
    }

    /**
     * 发一次整理记忆,落地后接着 {@code then}(调用失败或摘要为空也接着——历史不动,照原样往下走)。
     * 被切断的话结果作废,{@code then} 不再执行。
     */
    private void compact(boolean auto, Runnable then) {
        MemoryPort.Compaction compaction = memory.compaction(auto);
        run.phase = Phase.COMPACT;
        compactChars = 0;
        long id = run.id;
        model.call(compaction.request(), run.cancel,
                delta -> {
                    if (current(id)) {
                        compactChars += delta.content().length();
                    }
                },
                outcome -> {
                    if (!current(id)) {
                        return;
                    }
                    switch (outcome) {
                        case ModelOutcome.Failed failed -> compaction.failed(failed.words());
                        case ModelOutcome.Answered answered -> {
                            emit(new LoopEvent.ModelUsed(answered.usage(), LoopEvent.Purpose.COMPACT));
                            if (compaction.apply(answered.turn(), answered.usage())) {
                                emit(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.COMPACT));
                            } else {
                                compaction.failed("摘要是空的");
                            }
                        }
                    }
                    then.run();
                });
    }

    // ---- 旁路调用 ----

    /**
     * 发一次不属于任何 run 的模型调用(目标评估这类旁路判断):不占内核、不看停牌、不进历史,但用量照样
     * 经 {@link LoopEvent.ModelUsed} 报出去——账只有这一条路。端点不可用时不发,直接交回失败。
     *
     * @param purpose 用量记在哪一项名下
     * @param cancel  调用方自己管的取消令牌;取消之后 {@code onDone} 不再来
     */
    public void consult(LoopEvent.Purpose purpose, ModelRequest request, CancelToken cancel,
                        Consumer<ModelOutcome> onDone) {
        String problem = model.unavailable();
        if (problem != null) {
            onDone.accept(new ModelOutcome.Failed(problem));
            return;
        }
        model.call(request, cancel, delta -> { }, outcome -> {
            if (outcome instanceof ModelOutcome.Answered answered) {
                emit(new LoopEvent.ModelUsed(answered.usage(), purpose));
            }
            onDone.accept(outcome);
        });
    }

    // ---- 切断 ----

    /** 切断,没有细节可附。 */
    public void halt(HaltReason reason) {
        halt(reason, null);
    }

    /**
     * 切断:作废手上的 run,放弃未结算的工具调用,在历史里记下切断点,再按 {@link HaltReason} 的表清队列、
     * 进停牌。闲着的时候同样执行——主人在闲时按停止也要叫停身体、清掉排着的指令。
     *
     * @param detail 附在切断原因后面的细节(死因);没有传 {@code null}
     */
    public void halt(HaltReason reason, String detail) {
        Run cut = run;
        if (cut != null) {
            run = null;
            cut.cancel.cancel();
        }
        List<String> abandoned = tools.cancel(reason.stopsBody());
        if (cut != null) {
            // 切断的是一次模型回复或一批工具往返才记切断点:悬空调用的结果、给模型的说明由下一次请求的
            // ProtocolView 按它现算。整理记忆被切断时对话本身没断,不记。
            if (!cut.control && (cut.phase == Phase.MODEL || cut.phase == Phase.TOOLS)) {
                writeHalt(reason.words(detail));
            }
            transcript.resetTurnCount();
            if (!cut.control) {
                emit(new LoopEvent.RunEnded(cut.id, new RunEnd.Halted(reason)));
            }
        }
        int cleared = reason.clearsInterrupted() ? inbox.clearInterrupted() : 0;
        if (reason.enters() == Hold.DEAD) {
            dead = true;
        } else if (reason.enters() != null) {
            stopped = reason.enters();
            stoppedReason = null;
        }
        AiLog.LOG.info("[numen-entity#{}] halt {}{} (phase={}, abandoned calls={}, cleared queued={}, left={})",
                name, reason, detail == null ? "" : " (" + detail + ")",
                cut == null ? "idle" : cut.phase, abandoned.size(), cleared, inbox.size());
        emit(new LoopEvent.Halted(reason));
        announceHold(null);
    }

    private void writeHalt(String words) {
        transcript.addHalt(words);
        emit(new LoopEvent.TranscriptBoundary(LoopEvent.Boundary.HALT));
    }

    // ---- 读 ----

    /** 合成后的停牌;{@code null} = 没有。 */
    public Hold hold() {
        if (dead) {
            return Hold.DEAD;
        }
        if (host.externallyDriven()) {
            return Hold.EXTERNAL;
        }
        return stopped;
    }

    public LoopStatus status() {
        Phase phase = run == null ? null : run.phase;
        double progress = phase == Phase.COMPACT ? 1.0 - Math.exp(-(compactChars / 4.0) / 1200.0) : 0.0;
        Hold hold = hold();
        return new LoopStatus(phase, hold, hold != null && hold == stopped ? stoppedReason : null,
                host.bodyTaskRunning(), inbox.chatPreview(), progress, host.activity());
    }

    // ---- 内部 ----

    private boolean current(long id) {
        return run != null && run.id == id;
    }

    private void release(Hold.Release release) {
        if (dead && Hold.DEAD.releasedBy(release)) {
            dead = false;
        }
        if (stopped != null && stopped.releasedBy(release)) {
            AiLog.LOG.info("[numen-entity#{}] {} 解开停牌 {}", name, release, stopped);
            stopped = null;
            stoppedReason = null;
        }
    }

    /** 模型端点不可用:进 BLOCKED,把原因随事件交出去。只在刚进去的那一下打日志——停牌期间每 tick 推进也不刷屏。 */
    private void block(String problem) {
        if (stopped != Hold.BLOCKED) {
            AiLog.LOG.warn("[numen-entity#{}] can't reach the model: {}", name, problem);
        }
        stopped = Hold.BLOCKED;
        stoppedReason = problem;
        announceHold(problem);
    }

    private void announceHold(String reason) {
        Hold now = hold();
        if (now == announced) {
            return;
        }
        announced = now;
        emit(new LoopEvent.HoldChanged(now, reason));
    }

    private void emit(LoopEvent event) {
        for (Consumer<? super LoopEvent> listener : List.copyOf(listeners)) {
            listener.accept(event);
        }
    }

    private boolean headIsControl() {
        List<EventQueue.Entry> entries = inbox.entries();
        return !entries.isEmpty() && EventTypes.get(entries.get(0).type()).delivery().control();
    }

    private static String brief(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
