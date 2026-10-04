package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.tool.ScriptTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.script.Modules;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 在同一个进程里跑她的程序的执行口:脚本里的每次调用读成函数与参数({@link Dispatcher#invocation}),服务端函数交 {@link Dispatcher#serve}
 * (不经网络),客户端函数当场执行(这个进程替主人客户端答的时候)或如实失败;程序等的活的收尾按 {@link NumenEvents#finishOf} 认。管理员
 * 写的程序({@code /numen drive})、重启后再跑的那一行、GameTest 与单测都经它,和产品里同样那几处。
 */
public final class ProgramPort implements SerialCalls.Port {

    /** 看着每次调用派出去、回来;不看就用 {@link #NONE}。 */
    public interface Observer {

        /** 派出去了一次。 */
        default void dispatched(LlmToolCall line, Invocation invocation) {
        }

        /** 交出去了(服务端的已经交给派发,它当场受理的活此刻在她的槽里);回来之前。 */
        default void sent(LlmToolCall line) {
        }

        /** 回来了。 */
        default void replied(LlmToolCall line, String reply) {
        }
    }

    /** 什么都不看。 */
    public static final Observer NONE = new Observer() {
    };

    private final NumenPlayer her;
    private final UUID companion;
    private final Modules modules;
    private final boolean clientHere;
    private final Observer observer;

    /**
     * @param her        她的身体;没有世界的单测是 null
     * @param modules    程序能用的模块
     * @param clientHere 客户端函数在这个进程里答(GameTest、单测替主人客户端答);否则它们是一条失败
     */
    public ProgramPort(NumenPlayer her, UUID companion, Modules modules, boolean clientHere, Observer observer) {
        this.her = her;
        this.companion = companion;
        this.modules = modules;
        this.clientHere = clientHere;
        this.observer = observer;
    }

    /** 只跑程序:别的工具在这里没有。 */
    @Override
    public void invoke(LlmToolCall call, Consumer<String> done) {
        done.accept(ToolOutcome.failure("only programs run here, not " + call.name()));
    }

    @Override
    public String scriptOf(LlmToolCall call) {
        return ScriptTool.code(call.arguments());
    }

    @Override
    public void dispatch(LlmToolCall line, Invocation invocation, Consumer<String> done) {
        observer.dispatched(line, invocation);
        Consumer<String> landed = reply -> {
            observer.replied(line, reply);
            done.accept(reply);
        };
        if (Dispatcher.runsOnServer(invocation)) {
            Dispatcher.serve(invocation.function(), invocation.args(), her, line.id(), landed);
        } else if (clientHere) {
            Dispatcher.client(invocation, companion, landed);
        } else {
            landed.accept(ApiReply.error(ErrorKind.NO_FUNCTION, invocation.function() + " runs on your owner's "
                    + "client, and this program runs on the server", null, null).toString());
        }
        observer.sent(line);
    }

    @Override
    public ScriptCall.Finish finish(EventQueue.Entry entry) {
        return NumenEvents.finishOf(entry);
    }

    @Override
    public ScriptCatalog catalog() {
        return ApiRegistry.catalog(modules);
    }

    @Override
    public Invocation invocation(ScriptRun.Call call) {
        return Dispatcher.invocation(call);
    }

    @Override
    public void tally(String module, ScriptCall.Tally tally) {
        modules.tally(module, tally.ok(), tally.line(), tally.error(), now());
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
