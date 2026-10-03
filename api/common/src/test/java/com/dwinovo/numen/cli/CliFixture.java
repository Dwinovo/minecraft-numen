package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.TaskDispatch;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * API 单测共用的几样:经插件那扇门登记、从两个前端调一次(她的脚本、人写的一行命令)、读回执。登记处是进程级的静态表,各个
 * 测试类登记各自名字的组,互不相撞。
 */
final class CliFixture {

    static {
        // API 自己的两组(帮助 api.help、原版指令 mc.run)产品里由引擎在初始化时登记;单测没有那一步,在这里登记一次
        if (!NumenCli.isTopLevel(HelpCommands.GROUP)) {
            HelpCommands.install();
            McCommands.install();
        }
    }

    private CliFixture() {}

    /** 插件拿到的那扇门——测试和插件走同一条路登记。 */
    static NumenApi door() {
        AtomicReference<NumenApi> api = new AtomicReference<>();
        NumenPlugins.register(api::set);
        return api.get();
    }

    /** 一次调用的回执。 */
    static final class Outcome {
        final List<String> replies = new ArrayList<>();

        boolean success() {
            return json().get("success").getAsBoolean();
        }

        String message() {
            return json().get("message").getAsString();
        }

        JsonObject json() {
            if (replies.size() != 1) {
                throw new AssertionError("expected exactly one reply, got " + replies);
            }
            return JsonParser.parseString(replies.get(0)).getAsJsonObject();
        }
    }

    /**
     * 人写的一行命令(服务端那一侧的前端):经执行入口,在服务端的树上解析、执行,处理函数拿到的是这次调用的源。和真服务器差的
     * 只有身体:测试的处理函数不碰身体,活体给 null。原版指令(行首 {@code /})要真服务器,在 GameTest 里验。
     */
    static Outcome onServer(String line) {
        Outcome out = new Outcome();
        CommandRunner.line(new ServerSource(null, "test-call", out.replies::add), line);
        return out;
    }

    /** 服务端收到脚本里一次调用的那一刻:按路径找到动作,把 JSON 读成值交给它({@link NumenCli#serve})。 */
    static Outcome serveJson(String path, String json) {
        Outcome out = new Outcome();
        NumenCli.serve(path, JsonParser.parseString(json).getAsJsonObject(), null, "test-call", out.replies::add);
        return out;
    }

    /** 一个函数或一组的全部帮助,经 {@code api.help} 取。 */
    static String help(String name) {
        Outcome run = lua("return api.help(\"" + name + "\")");
        if (!run.success()) {
            throw new AssertionError(run.message());
        }
        return run.json().getAsJsonObject("data").get("returned").getAsString();
    }

    /**
     * 她的一段脚本,从跑脚本的那个工具进:产品里同一个派发器({@link SerialCalls})、同一个脚本层与同一个前端
     * ({@link NumenCli#invocation})。客户端动作经 {@link NumenCli#call} 当场执行;服务端动作直接交给服务端的入口
     * ({@link NumenCli#serve(String, JsonObject, com.dwinovo.numen.entity.NumenPlayer, String, Consumer)}),活体给 null,
     * 和网络送过去的是同一份调用。回执就是那次工具调用的结果。
     */
    static Outcome lua(String code) {
        Outcome out = new Outcome();
        UUID her = UUID.randomUUID();
        SerialCalls calls = new SerialCalls(new Port(her));
        calls.run(List.of(new LlmToolCall("test-lua", "lua", ScriptTool.args(code).toString())), new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                out.replies.add(resultJson);
            }

            @Override
            public void settled() {
            }
        });
        return out;
    }

    /** {@link #lua} 的派发口:没有身体、没有网络,其余都是产品里那几处。 */
    private record Port(UUID her) implements SerialCalls.Port {

        @Override
        public void invoke(LlmToolCall call, Consumer<String> done) {
            throw new AssertionError("only the lua tool is called here: " + call.name());
        }

        @Override
        public String scriptOf(LlmToolCall call) {
            return ScriptTool.code(call.arguments());
        }

        @Override
        public void dispatch(LlmToolCall call, Invocation invocation, Consumer<String> done) {
            if (NumenCli.runsOnServer(invocation)) {
                NumenCli.serve(NumenCli.pathOf(invocation), invocation.args(), null, call.id(), done);
            } else {
                NumenCli.call(invocation, new ToolCall(call.id(), NumenCli.pathOf(invocation),
                        invocation.args().toString(), () -> her, done));
            }
        }

        @Override
        public String leftRunning(String resultJson) {
            return TaskDispatch.runningTaskOf(resultJson);
        }

        @Override
        public ScriptCall.Finish finish(EventQueue.Entry entry) {
            return null;
        }

        @Override
        public ScriptCatalog catalog() {
            return NumenCli.scriptCatalog();
        }

        @Override
        public Invocation invocation(ScriptRun.Call call) {
            return NumenCli.invocation(call);
        }

        @Override
        public void tally(String script, ScriptCall.Tally tally) {
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    }
}
