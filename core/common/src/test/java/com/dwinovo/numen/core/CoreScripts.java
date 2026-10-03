package com.dwinovo.numen.core;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.loop.SerialCalls;
import com.dwinovo.numen.agent.loop.ToolPort;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.ScriptCatalog;
import com.dwinovo.numen.agent.script.ScriptRun;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.ScriptTool;
import com.dwinovo.numen.task.TaskDispatch;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * core 的单测从她的入口调:一段程序交跑脚本的那个工具,产品里同一个派发器({@link SerialCalls})、同一个脚本层与同一个前端
 * ({@link NumenCli#invocation})。客户端动作经 {@link NumenCli#call} 当场执行;服务端动作直接交服务端的入口
 * ({@link NumenCli#serve}),没有身体——单测里的处理函数不碰身体,活体给 null。
 */
public final class CoreScripts {

    private CoreScripts() {}

    /** 一次运行的回执与它每次 API 调用的回执(按先后)。 */
    public record Run(JsonObject receipt, List<String> replies) {

        /** 最后一次 API 调用的回执。 */
        public JsonObject lastReply() {
            return JsonParser.parseString(replies.get(replies.size() - 1)).getAsJsonObject();
        }

        /** 程序的回执正文。 */
        public String message() {
            return receipt.get("message").getAsString();
        }

        /** 跑到了最后。 */
        public boolean ok() {
            return receipt.get("success").getAsBoolean();
        }
    }

    /** {@code her} 跑一段程序;程序里的调用都当场回执(单测里没有后台活)。 */
    public static Run run(UUID her, String code) {
        List<String> replies = new ArrayList<>();
        List<String> receipts = new ArrayList<>();
        SerialCalls calls = new SerialCalls(new Port(her, replies));
        calls.run(List.of(new LlmToolCall("test-lua", toolName(), ScriptTool.args(code).toString())),
                new ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                receipts.add(resultJson);
            }

            @Override
            public void settled() {
            }
        });
        if (receipts.size() != 1) {
            throw new AssertionError("the program did not end at once: " + receipts + " / " + replies);
        }
        return new Run(JsonParser.parseString(receipts.get(0)).getAsJsonObject(), replies);
    }

    private static String toolName() {
        return com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.toolName();
    }

    /** {@link #run} 的派发口:没有身体、没有网络,其余都是产品里那几处。 */
    private record Port(UUID her, List<String> replies) implements SerialCalls.Port {

        @Override
        public void invoke(LlmToolCall call, Consumer<String> done) {
            throw new AssertionError("only programs run here: " + call.name());
        }

        @Override
        public String scriptOf(LlmToolCall call) {
            return ScriptTool.code(call.arguments());
        }

        @Override
        public void dispatch(LlmToolCall call, Invocation invocation, Consumer<String> done) {
            Consumer<String> kept = json -> {
                replies.add(json);
                done.accept(json);
            };
            if (NumenCli.runsOnServer(invocation)) {
                NumenCli.serve(NumenCli.pathOf(invocation), invocation.args(), null, call.id(), kept);
            } else {
                NumenCli.call(invocation, new ToolCall(call.id(), NumenCli.pathOf(invocation),
                        invocation.args().toString(), () -> her, kept));
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
