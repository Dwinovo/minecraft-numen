package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.bench.report.FunctionUse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 订阅循环内核的事件记账:调了几次模型、几个工具调用、几个失败、同一个失败的调用重复了几次、用量三项,以及她说的话;程序里的每次
 * API 调用按函数记一笔({@link #functions}:调了几次、失败的各是哪种、调用前查过几次帮助、重复失败几次)。
 * 同时把这些写进这次的 {@link Transcript}:她写的程序与回执整段落下(诊断只读这两样),每个失败的程序记下回执里错误值的种类
 * ({@link #errorKind})并按它归一类({@link #errorClass})。流式增量({@code ModelDelta})一概不看——思考流不落。
 */
final class Meter implements Consumer<LoopEvent> {

    private final Transcript transcript;

    int turns;
    int toolCalls;
    int toolErrors;
    int repeatedFailures;
    long tokensMiss;
    long tokensHit;
    long tokensOut;
    /** 她最后一句说出口的话;一句都没说是空串。 */
    String finalWords = "";
    /** 有没有哪一次 API 调用写错了(参数读不成、没有这个函数)。 */
    boolean commandError;
    /** 最近一个失败的工具结果;没有是 null。 */
    String lastFailedResult;
    /** 调模型失败且不再重试、或者端点不可用时的原话;没有是 null。 */
    String apiFailure;

    private final Set<String> failedCalls = new HashSet<>();

    /** 刚结束的那段程序怎么结束的(先于它的结果到);下一个工具结果读过就清掉,不是程序的工具没有。 */
    private LoopEvent.ProgramEnded ending;

    /** 按函数记的账,按第一次调用的先后。 */
    private final Map<String, Use> uses = new LinkedHashMap<>();
    /** {@code numen.api.help} 查过的名字,按先后。 */
    private final List<String> helped = new ArrayList<>();
    /** 失败过的 API 调用的写法。 */
    private final Set<String> failedApiCalls = new HashSet<>();

    /** 一个函数的账。 */
    private static final class Use {
        int calls;
        final Map<String, Integer> failures = new TreeMap<>();
        int helpLookups;
        int repeated;
    }

    Meter(Transcript transcript) {
        this.transcript = transcript;
    }

    @Override
    public void accept(LoopEvent event) {
        switch (event) {
            case LoopEvent.TurnStarted started -> turns++;
            case LoopEvent.ModelUsed used -> add(used.usage());
            case LoopEvent.AssistantMessage message -> {
                String content = message.turn().content();
                if (!content.isBlank()) {
                    finalWords = content.strip();
                    transcript.write("she_says", "text", finalWords);
                }
            }
            case LoopEvent.ToolStarted started -> {
                toolCalls++;
                transcript.write("tool_call", "tool", started.call().name(), "args", started.call().arguments());
            }
            case LoopEvent.ProgramEnded ended -> ending = ended;
            case LoopEvent.ToolFinished finished -> finished(finished.call(), finished.resultJson());
            case LoopEvent.ApiCalled api -> called(api.called());
            case LoopEvent.TurnFailed failed -> {
                apiFailure = failed.words();
                transcript.write("turn_failed", "words", failed.words());
            }
            case LoopEvent.HoldChanged hold -> {
                if (hold.hold() == Hold.BLOCKED) {
                    apiFailure = hold.reason();
                    transcript.write("blocked", "words", String.valueOf(hold.reason()));
                }
            }
            case LoopEvent.RunEnded ended -> transcript.write("run_end", "end", String.valueOf(ended.end()));
            default -> { }
        }
    }

    /** 程序里的一次 API 调用有了结局:记进它那个函数的账。第一次调它时,数一数之前查过几次它(或它的组、名字空间)的帮助。 */
    private void called(ScriptCall.Called called) {
        String fn = called.function();
        Use use = uses.get(fn);
        if (use == null) {
            use = new Use();
            use.helpLookups = (int) helped.stream().filter(name -> fn.equals(name) || fn.startsWith(name + ".")).count();
            uses.put(fn, use);
        }
        use.calls++;
        if (called.kind() != null) {
            use.failures.merge(called.kind(), 1, Integer::sum);
            if (!failedApiCalls.add(called.call())) {
                use.repeated++;
            }
        }
        if (fn.equals(HELP) && called.first() != null) {
            helped.add(called.first());
        }
    }

    /** 查帮助的那个函数。 */
    private static final String HELP = "numen.api.help";

    /** 按函数的账,按函数名。 */
    List<FunctionUse> functions() {
        return uses.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new FunctionUse(e.getKey(),
                e.getValue().calls, e.getValue().failures, e.getValue().helpLookups, e.getValue().repeated)).toList();
    }

    private void add(Usage usage) {
        // 缓存写也是实打实新处理的输入,归在未命中那一栏
        tokensMiss += usage.input() + usage.cacheWrite();
        tokensHit += usage.cacheRead();
        tokensOut += usage.output();
    }

    private void finished(LlmToolCall call, String result) {
        LoopEvent.ProgramEnded program = ending;
        ending = null;
        boolean failed = failed(result);
        String errorKind = failed ? errorKind(program) : "";
        String errorClass = failed ? errorClass(errorKind) : "";
        transcript.write("tool_result", "tool", call.name(), "success", String.valueOf(!failed),
                "error_class", errorClass, "error_kind", errorKind,
                "calls", String.valueOf(program == null ? 0 : program.calls()), "result", result);
        if (!failed) {
            return;
        }
        toolErrors++;
        lastFailedResult = result;
        if (!failedCalls.add(call.name() + " " + call.arguments())) {
            repeatedFailures++;
        }
        if (errorClass.equals("api_args")) {
            commandError = true;
        }
    }

    /**
     * 一个失败的程序停在哪一种错误上:程序结局里错误值的种类({@link ErrorKind#wire});被主人说话、急件停在调用之间或到了上限的是
     * {@code stopped}。不是程序的工具失败是 {@code runtime}。
     */
    static String errorKind(LoopEvent.ProgramEnded program) {
        if (program == null) {
            return ErrorKind.RUNTIME.wire();
        }
        if (program.ending().status() == ScriptCall.Status.STOPPED) {
            return "stopped";
        }
        return program.ending().errorKind() != null ? program.ending().errorKind() : ErrorKind.RUNTIME.wire();
    }
    /**
     * 种类归成五类:{@code syntax} 读不成,{@code api_args} 一次 API 调用写错了(参数、没有这个函数),{@code runtime} 程序自己的
     * 运行错,{@code stopped} 被主人说话、急件或上限停下,其余是 {@code api_failed}——一次 API 调用(或库函数 raise 的)做了但没做成。
     */
    static String errorClass(String kind) {
        if (kind.equals("stopped") || kind.equals(ErrorKind.LIMIT.wire())) {
            return "stopped";
        }
        if (kind.equals(ErrorKind.SYNTAX.wire())) {
            return "syntax";
        }
        if (kind.equals(ErrorKind.RUNTIME.wire())) {
            return "runtime";
        }
        if (kind.equals(ErrorKind.BAD_ARGUMENT.wire()) || kind.equals(ErrorKind.NO_FUNCTION.wire())) {
            return "api_args";
        }
        return "api_failed";
    }
    /** 结果说自己失败了:{@code "success": false}。不带 success 的查询结果、读不成 JSON 的都不算失败。 */
    static boolean failed(String result) {
        try {
            JsonElement json = JsonParser.parseString(result);
            return json.isJsonObject() && json.getAsJsonObject().has("success")
                    && !json.getAsJsonObject().get("success").getAsBoolean();
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException notJson) {
            return false;
        }
    }
}
