package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.http.CancelToken;
import com.dwinovo.numen.agent.http.LlmHttpException;
import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.loop.ModelOutcome;
import com.dwinovo.numen.agent.loop.ModelRequest;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.agent.script.ScriptEngine;
import com.dwinovo.numen.agent.tool.ScriptTool;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 回话的那一方:真实模型,或者两种基线。评测大脑的模型口({@link Brain})把请求交给它、把结果切回主线程;请求怎么组装
 * 与产品是同一份代码,它只管"谁来答"。
 */
interface Mind {

    /** 现在发不了请求的理由;能发是 null。 */
    String unavailable();

    /** 答这一次。{@code done} 恰好调一次,可以在任何线程上;取消之后调不调都行,调了也会被丢掉。 */
    void ask(ModelRequest request, CancelToken cancel, Consumer<ModelOutcome> done);

    /** 最近一次失败的 HTTP 状态与服务端原话;没失败过是 null。 */
    default Failure lastFailure() {
        return null;
    }

    /** 一次失败:HTTP 状态码(网络错是 0)与服务端的原话。 */
    record Failure(int status, String body) {

        /** 余额不足:再调也是白调。 */
        boolean outOfBalance() {
            return status == 402;
        }

        /** 服务端说请求超出了上下文窗口。 */
        boolean contextOverflow() {
            String b = body.toLowerCase(java.util.Locale.ROOT);
            return status == 400 && b.contains("context") && (b.contains("length") || b.contains("maximum"));
        }

        String words() {
            return status == 0 ? "network: " + body : "HTTP " + status + ": " + body;
        }
    }

    /** 真实模型:产品同一个客户端({@link NumenLlmClient}),同一种调用。 */
    final class Live implements Mind {

        private final LlmEndpoint endpoint;
        private volatile Failure lastFailure;

        Live(LlmEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public String unavailable() {
            return endpoint.hasApiKey() ? null : "没有 API key(环境变量 " + Settings.KEY_ENV + ")";
        }

        /** 不要流式增量:评测不落思考,正文等整条回复。 */
        @Override
        public void ask(ModelRequest request, CancelToken cancel, Consumer<ModelOutcome> done) {
            CompletableFuture<NumenLlmClient.ChatResult> result;
            try {
                result = NumenLlmClient.forEndpoint(endpoint).chatStreaming(request.messages(), request.tools(),
                        request.systemPrompt(), cancel, null);
            } catch (RuntimeException ex) {
                result = CompletableFuture.failedFuture(ex);
            }
            result.whenComplete((res, err) -> {
                if (err != null) {
                    Failure failure = failure(err);
                    lastFailure = failure;
                    done.accept(new ModelOutcome.Failed(failure.words()));
                    return;
                }
                done.accept(new ModelOutcome.Answered(res.turn(), res.usage()));
            });
        }

        @Override
        public Failure lastFailure() {
            return lastFailure;
        }

        private static Failure failure(Throwable err) {
            Throwable cause = err;
            while (!(cause instanceof LlmHttpException) && cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            if (cause instanceof LlmHttpException http) {
                String body = http.responseBody() == null ? "" : http.responseBody();
                return new Failure(http.statusCode(), body.length() <= 300 ? body : body.substring(0, 300));
            }
            return new Failure(0, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    /**
     * 基线:第一次调用把 {@code program} 作为一次 {@code lua} 调用交出去(没有就直接说话),之后每次都只说 {@code words}。
     * 标准解是写好的程序,空操作没有程序。不花 API,用量记零。
     */
    final class Scripted implements Mind {

        private final String program;
        private final String words;
        private boolean acted;

        Scripted(String program, String words) {
            this.program = program;
            this.words = words;
        }

        @Override
        public String unavailable() {
            return null;
        }

        @Override
        public void ask(ModelRequest request, CancelToken cancel, Consumer<ModelOutcome> done) {
            AssistantTurn turn;
            if (!acted && program != null) {
                acted = true;
                turn = new AssistantTurn("", List.of(new LlmToolCall("bench-" + UUID.randomUUID(), ScriptEngine.IN_USE.toolName(),
                        ScriptTool.args(program).toString())), null);
            } else {
                turn = new AssistantTurn(words, List.of(), null);
            }
            done.accept(new ModelOutcome.Answered(turn, Usage.ZERO));
        }
    }
}
