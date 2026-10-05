package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.ApiFunction;
import com.dwinovo.numen.sdk.ApiRegistry;
import com.dwinovo.numen.sdk.Dispatcher;

import java.util.function.Consumer;

/**
 * 服务端函数在进程内执行:排进这段程序的车道({@link MainQueue.Lane}),服务端主线程轮到它时在身体旁边执行。程序在自己的线程上
 * 等结果,主线程不等程序。
 */
final class ServerCalls implements ProgramCalls {

    private final NumenPlayer her;
    private final MainQueue.Lane lane;
    /** 受理了一件占身体的活,收到它的编号:程序要等的这件活的收尾归程序({@link ServerPrograms})。 */
    private final Consumer<String> accepted;

    ServerCalls(NumenPlayer her, MainQueue.Lane lane, Consumer<String> accepted) {
        this.her = her;
        this.lane = lane;
        this.accepted = accepted;
    }

    @Override
    public void execute(String callId, Invocation invocation, Consumer<String> done, Runnable sent) {
        ApiFunction fn = ApiRegistry.function(invocation.function());
        boolean job = fn != null && fn.kind() == ScriptCatalog.Kind.JOB;
        Consumer<String> landed = !job ? done : reply -> {
            ApiReply.Parsed parsed = ApiReply.parse(reply);
            if (parsed.ok() && parsed.job() != null) {
                accepted.accept(parsed.job());
            }
            done.accept(reply);
        };
        lane.post(() -> {
            Dispatcher.serve(invocation.function(), invocation.args(), her, callId, landed);
            sent.run();
        });
    }
}
