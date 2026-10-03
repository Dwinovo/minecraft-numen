package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 订阅循环内核的事件记账:调了几次模型、几个工具调用、几个失败、同一个失败的调用重复了几次、用量三项,以及她说的话。
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
            case LoopEvent.ToolFinished finished -> finished(finished.call(), finished.resultJson());
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

    private void add(Usage usage) {
        // 缓存写也是实打实新处理的输入,归在未命中那一栏
        tokensMiss += usage.input() + usage.cacheWrite();
        tokensHit += usage.cacheRead();
        tokensOut += usage.output();
    }

    private void finished(LlmToolCall call, String result) {
        boolean failed = failed(result);
        String errorKind = failed ? errorKind(result) : "";
        String errorClass = failed ? errorClass(errorKind) : "";
        transcript.write("tool_result", "tool", call.name(), "success", String.valueOf(!failed),
                "error_class", errorClass, "error_kind", errorKind, "calls", String.valueOf(calls(result)),
                "result", result);
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
     * 一个失败的程序停在哪一种错误上:回执数据里错误值的 {@code kind}({@link ErrorKind#wire});被主人说话、急件停在调用之间的
     * 是 {@code stopped}。读不出(不是程序的回执)是 {@code runtime}。
     */
    static String errorKind(String result) {
        JsonElement json;
        try {
            json = JsonParser.parseString(result);
        } catch (JsonParseException notJson) {
            return ErrorKind.RUNTIME.wire();
        }
        JsonObject receipt = json.isJsonObject() ? json.getAsJsonObject() : new JsonObject();
        JsonObject data = receipt.has("data") && receipt.get("data").isJsonObject()
                ? receipt.getAsJsonObject("data") : new JsonObject();
        if (data.has("status") && data.get("status").getAsString().equals("stopped")) {
            return "stopped";
        }
        JsonObject error = data.has("error") && data.get("error").isJsonObject() ? data.getAsJsonObject("error") : null;
        return error != null && error.has("kind") ? error.get("kind").getAsString() : ErrorKind.RUNTIME.wire();
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

    /** 这段程序做了几次 API 调用(回执的 {@code data.calls});读不出是 0。 */
    static int calls(String result) {
        try {
            JsonElement json = JsonParser.parseString(result);
            JsonObject data = json.isJsonObject() && json.getAsJsonObject().has("data")
                    && json.getAsJsonObject().get("data").isJsonObject()
                    ? json.getAsJsonObject().getAsJsonObject("data") : null;
            return data != null && data.has("calls") ? data.get("calls").getAsInt() : 0;
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            return 0;
        }
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
