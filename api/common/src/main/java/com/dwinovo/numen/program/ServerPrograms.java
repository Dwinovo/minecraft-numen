package com.dwinovo.numen.sdk;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.tool.ScriptTool;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.script.Modules;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 在服务端以她的身份跑一段 Lua 程序,和她自己写的程序同一个入口({@link ProgramPort}、{@code SerialCalls}):管理员的
 * {@code /numen drive <同伴> <程序>},与重启后接回她手上那件活(记下的那一行 Lua 再跑一遍)。程序用出厂的模块;客户端函数在服务端答不了,
 * 是一条失败。程序等它派的活收尾:这具身体发出的 task_finished 交给它({@link NumenEvents#watch})。
 */
@Internal
public final class ServerPrograms {

    /** 在跑的程序,按同伴:一只同伴同一时刻至多一段。 */
    private static final Map<UUID, SerialCalls> RUNNING = new ConcurrentHashMap<>();

    static {
        NumenEvents.watch((companion, entry) -> {
            SerialCalls calls = RUNNING.get(companion);
            if (calls != null) {
                calls.arrived(entry, false);
            }
        });
    }

    private ServerPrograms() {}

    /**
     * 跑一段程序,整张回执经 {@code receipt} 交回一次。这只同伴已有一段在跑时,这一段当场以那句话结束。
     *
     * @param tag      这段程序的调用 id 前缀,日志里认它
     * @param observer 看着它的每次调用
     */
    public static void run(NumenPlayer her, String tag, String code, ProgramPort.Observer observer,
                           Consumer<String> receipt) {
        UUID uuid = her.getUUID();
        ProgramPort port = new ProgramPort(her, uuid, Modules.factory(), false, observer);
        SerialCalls calls = new SerialCalls(port);
        if (RUNNING.putIfAbsent(uuid, calls) != null) {
            receipt.accept(com.dwinovo.numen.agent.llm.ToolOutcome.failure("another program is already running on "
                    + her.getName().getString() + " on the server; wait for it to end"));
            return;
        }
        String tool = ScriptEngine.IN_USE.toolName();
        LlmToolCall program = new LlmToolCall(tag + "-" + UUID.randomUUID(), tool, ScriptTool.args(code).toString());
        Constants.LOG.info("[numen-program] {} runs on the server: {}", uuid, code);
        calls.run(List.of(program), new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String result) {
                receipt.accept(result);
            }

            @Override
            public void settled() {
                RUNNING.remove(uuid, calls);
            }
        });
    }

    /** 这具身体离开世界:在跑的程序收掉(它等的活已由任务槽收尾)。 */
    public static void stop(NumenPlayer her) {
        SerialCalls calls = RUNNING.remove(her.getUUID());
        if (calls != null) {
            calls.cancel(false);
        }
    }
}
