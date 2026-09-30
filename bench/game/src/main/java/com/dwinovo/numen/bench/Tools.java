package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.entity.CompanionEvents;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 评测大脑的工具口:顺序与等待是产品的 {@link SerialCalls},一个调用怎么执行与主人客户端的派发器相同——按名字取工具,
 * 交给它一个绑着她的 {@link ToolCall},由工具自己决定当场答还是送去服务端({@link ServerToolTransport},上行出口在
 * 评测里直通服务端入口)。
 */
final class Tools implements ToolPort {

    private final UUID her;
    private final SerialCalls calls;

    Tools(UUID her) {
        this.her = her;
        this.calls = new SerialCalls(this::invoke, TaskDispatch::runningTaskOf, NumenEvents::finishedTaskOf);
    }

    @Override
    public void run(List<LlmToolCall> batch, Sink sink) {
        calls.run(batch, sink);
    }

    @Override
    public void arrived(EventQueue.Entry entry, boolean urgent) {
        calls.arrived(entry, urgent);
    }

    @Override
    public List<String> cancel(boolean stopBody) {
        List<String> ids = calls.cancel();
        ServerToolTransport.forget(ids);
        if (stopBody) {
            CompanionEvents.fire(CompanionEvent.ABORT, her);
        }
        return ids;
    }

    /** 手上这一件的工具名;空闲是 null。 */
    String currentToolName() {
        LlmToolCall call = calls.current();
        return call == null ? null : call.name();
    }

    private void invoke(LlmToolCall call, Consumer<String> done) {
        NumenTool tool = ToolRegistry.resolve(call.name());
        if (tool == null) {
            done.accept(TaskResult.fail("unknown tool: " + call.name()).toJson());
            return;
        }
        ToolCall handle = new ToolCall(call.id(), tool.name(), call.arguments(), () -> her, done);
        try {
            tool.invoke(handle);
        } catch (RuntimeException ex) {
            done.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
