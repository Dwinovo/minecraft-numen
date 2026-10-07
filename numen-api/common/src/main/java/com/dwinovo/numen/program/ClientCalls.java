package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.sdk.Dispatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 客户端函数向主人客户端发反向请求:程序线程挂着等答复(同 MCP 的 sampling、LSP 的 workspace/configuration),客户端用
 * {@code Dispatcher.client} 执行完答回来。答复里带着她的新模块时,先换进这段程序的模块({@link RunModules#learn}),再让程序往下走。
 *
 * <p>一段程序在等的反向请求都记在这里,到结局只有三种:客户端答了;主人的客户端在 {@link ProgramLimits#CLIENT_ANSWER_TICKS} 刻内没答
 * ({@link #expire},服务器每刻在主线程上查一次),这次调用以 {@link ErrorKind#TIMEOUT} 失败,程序拿到失败接着往下走;程序结束了
 * ({@link #close}),不等了也不再发。后两种都同时叫传输撤掉它那头记的这笔({@link ClientTransport#cancel}),之后才到的答复没有人认。
 */
final class ClientCalls implements ProgramCalls {

    /** 一个在等的反向请求:什么时候算超时、是哪次调用、结果交给谁。 */
    private record Pending(long deadline, Invocation invocation, Consumer<String> done) {}

    private final NumenPlayer her;
    private final UUID companion;
    private final MainQueue.Lane lane;
    private final ClientTransport transport;
    private final RunModules modules;
    /** 服务器刻数:期限按它量。 */
    private final LongSupplier ticks;

    /** 在等的反向请求,按调用编号;受 {@code this} 保护(程序结束在程序的线程上,别的在服务端主线程上)。 */
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private boolean closed;

    ClientCalls(NumenPlayer her, UUID companion, MainQueue.Lane lane, ClientTransport transport, RunModules modules,
                LongSupplier ticks) {
        this.her = her;
        this.companion = companion;
        this.lane = lane;
        this.transport = transport;
        this.modules = modules;
        this.ticks = ticks;
    }

    @Override
    public void execute(String callId, Invocation invocation, Consumer<String> done, Runnable sent) {
        ClientCallPayload request = new ClientCallPayload(companion, callId, invocation.function(),
                invocation.args().toString());
        lane.post(() -> {
            synchronized (this) {
                if (closed) {
                    return;   // 程序在这个请求排到之前就结束了:不发
                }
                // 先记下再发:传输当场回答(主人不在线)时,答复找得到它
                pending.put(callId, new Pending(ticks.getAsLong() + ProgramLimits.CLIENT_ANSWER_TICKS, invocation,
                        done));
            }
            transport.request(her, request, answer -> answered(callId, answer));
            sent.run();
        });
    }

    /** 每个服务器刻在主线程上调一次:到了期限还没答的,以一次失败交回程序。 */
    void expire() {
        long now = ticks.getAsLong();
        Map<String, Pending> due = new LinkedHashMap<>();
        synchronized (this) {
            pending.entrySet().removeIf(entry -> {
                boolean over = entry.getValue().deadline() <= now;
                if (over) {
                    due.put(entry.getKey(), entry.getValue());
                }
                return over;
            });
        }
        due.forEach((callId, waiting) -> {
            transport.cancel(callId);
            waiting.done().accept(timedOut(waiting.invocation()));
        });
    }

    /** 程序结束了(不论怎么结束):在等的全部撤掉,不再交回,排着还没发的也不发。 */
    void close() {
        List<String> ids;
        synchronized (this) {
            closed = true;
            ids = new ArrayList<>(pending.keySet());
            pending.clear();
        }
        ids.forEach(transport::cancel);
    }

    private void answered(String callId, ClientTransport.Answer answer) {
        Pending waiting;
        synchronized (this) {
            waiting = pending.remove(callId);
        }
        if (waiting != null) {
            waiting.done().accept(learned(answer));
        }
    }

    private String learned(ClientTransport.Answer answer) {
        if (answer.modules() != null) {
            try {
                modules.learn(answer.modules());
            } catch (IllegalArgumentException wrong) {
                return ApiReply.error(ErrorKind.FAILED, "your client sent a module that does not match its "
                        + "fingerprint: " + wrong.getMessage(), null, null).toString();
            }
        }
        return answer.reply();
    }

    private static String timedOut(Invocation invocation) {
        return ApiReply.error(ErrorKind.TIMEOUT, "your owner's client did not answer " + invocation.function()
                + " within " + ProgramLimits.CLIENT_ANSWER_TICKS / 20 + " seconds. A client function only reads, "
                + "so the same call is safe to run again.", Dispatcher.lua(invocation), null).toString();
    }
}
