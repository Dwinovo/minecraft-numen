package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.loop.Hold;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 订阅循环内核的事件记账:调了几次模型、几个工具调用、几个失败、同一个失败的调用重复了几次、用量三项,以及她说的话。
 * 同时把这些写进这次的 {@link Transcript}。流式增量({@code ModelDelta})一概不看——思考流不落。
 */
final class Meter implements Consumer<LoopEvent> {

    /** 结果里出现这些字样,是一行命令写错了(命令树读不通、参数不合、把工具名写进了命令)。 */
    private static final List<String> COMMAND_ERRORS = List.of("Unknown command", "Unknown or incomplete command",
            "Incorrect argument", "invalid arguments", "unknown tool", "is a tool name, not a command");

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
    /** 有没有哪一行命令写错了。 */
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
                transcript.write("tool_call", "tool", started.call().name(), "args",
                        Transcript.clip(started.call().arguments()));
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
        transcript.write("tool_result", "tool", call.name(), "success", String.valueOf(!failed),
                "result", Transcript.clip(result));
        if (!failed) {
            return;
        }
        toolErrors++;
        lastFailedResult = result;
        if (!failedCalls.add(call.name() + " " + call.arguments())) {
            repeatedFailures++;
        }
        if (COMMAND_ERRORS.stream().anyMatch(result::contains)) {
            commandError = true;
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
