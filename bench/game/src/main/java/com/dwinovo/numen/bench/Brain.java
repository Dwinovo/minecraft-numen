package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.loop.AgentLoop;
import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.HostPort;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelPort;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.memory.Compactor;
import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.request.AgentRequestContext;
import com.dwinovo.numen.agent.request.BodySnapshot;
import com.dwinovo.numen.agent.request.MemoryPreamble;
import com.dwinovo.numen.agent.request.RuntimeState;
import com.dwinovo.numen.agent.tool.CompanionToolPort;
import com.dwinovo.numen.agent.tool.ToolAnchor;

import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 评测里她的大脑:产品的循环内核 {@link AgentLoop} 原样,四个端口在这里接到服务端进程里。和主人客户端上的
 * {@code EntityAgentLoop} 同一套零件:请求由 {@link AgentRequestContext} 组装(系统提示、运行期状态、工具表只有那一份)、
 * 札记索引由 {@link MemoryPreamble} 注入、整理记忆是 {@link Compactor}、派工具是 {@link CompanionToolPort}。不同的只有回话的一方
 * ({@link Mind})与两件客户端才有的事:人设用内置默认人设,主动性用默认档位。
 *
 * <p>全部在服务端主线程上:模型的回调、下行的包都先进 {@code mail},由运行器每刻取出来执行。
 */
final class Brain {

    final UUID her;
    final EventQueue queue = new EventQueue(EventQueue.Journal.NONE);
    final RuntimeState runtime;
    final CompanionToolPort tools;
    final AgentLoop loop;
    private final MemoryPreamble memory;
    private volatile BodySnapshot body;
    private final java.util.function.LongSupplier dayTime;

    /**
     * @param chat    会话日志落在哪(整理记忆要它);收场后连同目录删掉,评测不读它
     * @param window  上下文窗口,与产品同一个口径(模型表)
     * @param mail    主线程的信箱
     * @param mayCall 还能不能再调一次模型(轮数预算);不能时这次调用不发,运行器随即按超轮数收场
     */
    Brain(UUID her, Mind mind, Path chat, int window, Queue<Runnable> mail, BooleanSupplier mayCall,
          java.util.function.LongSupplier dayTime) {
        this.her = her;
        ConvoLog log = ConvoLog.atFile(chat);
        ConvoState convo = new ConvoState(msg -> log.append(msg, null));
        this.runtime = new RuntimeState(her, () -> body);
        this.memory = new MemoryPreamble(NoteBook.of(her));
        ToolAnchor anchor = () -> her;
        this.dayTime = dayTime;
        this.tools = new CompanionToolPort(her, () -> anchor, this::receiptAfterCut);
        Compactor compactor = new Compactor(her.toString(), convo, log, () -> window);
        ModelPort model = new ModelPort() {
            @Override
            public String unavailable() {
                return mind.unavailable();
            }

            @Override
            public ModelRequest turnRequest() {
                return AgentRequestContext.turn(convo.snapshot(), runtime.xml(), NumenPrompts.DEFAULT_PERSONA,
                        com.dwinovo.numen.script.Modules.of(her));
            }

            @Override
            public void call(ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta,
                             Consumer<ModelOutcome> onDone) {
                if (!mayCall.getAsBoolean()) {
                    return;
                }
                mind.ask(request, cancel, outcome -> mail.add(() -> {
                    if (!cancel.isCancelled()) {
                        onDone.accept(outcome);
                    }
                }));
            }
        };
        HostPort host = new HostPort() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }

            @Override
            public int initiativeLevel() {
                return EventQueue.DEFAULT_LEVEL;
            }

            @Override
            public boolean externallyDriven() {
                return false;
            }

            @Override
            public String injectionPreamble() {
                return memory.next();
            }

            @Override
            public boolean bodyTaskRunning() {
                return runtime.bodyTaskRunning();
            }

            @Override
            public String activity() {
                String task = runtime.activity();
                return task != null ? task : tools.currentToolName();
            }
        };
        this.loop = new AgentLoop(her.toString(), model, tools, convo, queue, compactor, host);
        loop.subscribe(compactor::on);
        loop.subscribe(runtime::on);
        loop.subscribe(memory::on);
    }

    void subscribe(Consumer<? super LoopEvent> listener) {
        loop.subscribe(listener);
    }

    /** 主人说一句,和他在聊天框里说的一样进她的收件箱。 */
    void ownerSays(String words) {
        loop.push(List.of(new EventQueue.Entry(EventTypes.QUERY, EventQueue.query(words),
                System.currentTimeMillis(), false)));
    }

    /** 服务端推来的身体状态。 */
    void body(BodySnapshot snapshot) {
        body = snapshot;
    }

    boolean hasBody() {
        return body != null;
    }

    /**
     * 她闲下来了:没在跑的对话、没停牌、身体没有后台活、队里没有会叫醒她的条目。
     */
    boolean idle() {
        return loop.status().phase() == null && loop.hold() == null && !runtime.bodyTaskRunning()
                && !queue.hasWaking();
    }

    /** 收场:作废在飞的回合、放弃未结算的调用,不叫停身体(身体随后整个离场)。 */
    void dispose() {
        loop.halt(HaltReason.DISPOSE);
    }

    /** 切断后服务端交出的程序回执:这一批已经作废,她仍必须知道切断前做了什么,所以作为一条事件进收件箱(和产品里同一个做法)。 */
    private void receiptAfterCut(String program, String receipt) {
        loop.push(List.of(com.dwinovo.numen.event.NumenEvents.programStopped(dayTime.getAsLong(), program,
                com.dwinovo.numen.program.RunResult.messageOf(receipt), System.currentTimeMillis())));
    }
}
