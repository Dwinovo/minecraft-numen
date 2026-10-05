package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.sdk.ApiRegistry;
import com.dwinovo.numen.sdk.Dispatcher;

import java.util.function.Consumer;

/**
 * 一段在服务端跑的程序要的几样东西({@link Program.Port}),各交给负责那件事的部件:函数目录来自这段程序的模块({@link RunModules})、
 * 读参数是 {@link Dispatcher#invocation}、执行调用是 {@link ProgramCalls}、认收尾是 {@link NumenEvents#finishOf}。
 */
final class ProgramPort implements Program.Port {

    private final NumenPlayer her;
    private final RunModules modules;
    private final ProgramCalls calls;

    /** @param her 她的身体;没有世界的单测是 null */
    ProgramPort(NumenPlayer her, RunModules modules, ProgramCalls calls) {
        this.her = her;
        this.modules = modules;
        this.calls = calls;
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
    public void dispatch(String callId, Invocation invocation, Consumer<String> done) {
        calls.execute(callId, invocation, done, () -> { });
    }

    @Override
    public ScriptCall.Finish finish(EventQueue.Entry entry) {
        return NumenEvents.finishOf(entry);
    }

    /** 程序已经结束,它等的那件活的收尾才到:送给主人的大脑,在服务端主线程上。 */
    @Override
    public void forward(EventQueue.Entry entry) {
        her.getServer().execute(() -> NumenEvents.deliver(her, entry));
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }
}
