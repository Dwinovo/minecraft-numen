package com.dwinovo.numen.bench;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.http.LlmHttpException;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.llm.NumenLlmClient;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.script.ScriptEngine;
import com.dwinovo.numen.api.agent.tool.ScriptTool;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 回话的那一方:真实模型,或者两种基线。就是产品大脑({@code CompanionLoop})的模型口({@link com.dwinovo.numen.api.brain.Mind}),
 * 评测里换上它;请求怎么组装、什么时候调模型都是产品的代码,它只管"谁来答"。评测不分档案,所以档案 id 一概不看。
 * 加上一样评测才有的:最近一次失败的原话,和调模型的次数预算——用完了这次调用不发,运行器随即按超轮数收场。
 */
interface Mind extends com.dwinovo.numen.api.brain.Mind {

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
        private final BooleanSupplier mayCall;
        private volatile Failure lastFailure;

        Live(LlmEndpoint endpoint, BooleanSupplier mayCall) {
            this.endpoint = endpoint;
            this.mayCall = mayCall;
        }

        @Override
        public Cause unavailable(String provider) {
            return endpoint.hasApiKey() ? null : Cause.of(Cause.NO_KEY, "环境变量 " + Settings.KEY_ENV);
        }

        @Override
        public LlmEndpoint endpoint(String provider) {
            return endpoint;
        }

        /** 不要流式增量:评测不落思考,正文等整条回复。 */
        @Override
        public void ask(String provider, UUID companion, ModelRequest request, CancelToken cancel,
                        Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> done) {
            if (!mayCall.getAsBoolean()) {
                return;
            }
            CompletableFuture<NumenLlmClient.ChatResult> result;
            try {
                result = NumenLlmClient.forEndpoint(endpoint).chatStreaming(request.messages(), request.tools(),
                        request.systemPrompt(), null, cancel, null);
            } catch (RuntimeException ex) {
                result = CompletableFuture.failedFuture(ex);
            }
            result.whenComplete((res, err) -> {
                if (err != null) {
                    Failure failure = failure(err);
                    lastFailure = failure;
                    done.accept(new ModelOutcome.Failed(Cause.fromError(err)));
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
     * 标准解是写好的程序,空操作没有程序。程序到第一次被问才现取:场景搭好之后才写得出标准解,而大脑要在搭场景之前就建好。
     * 不花 API,用量记零。
     */
    final class Scripted implements Mind {

        private final LlmEndpoint endpoint;
        private final BooleanSupplier mayCall;
        private final Supplier<String> program;
        private final String words;
        private boolean acted;

        /** @param endpoint 上下文窗口取自它,与真实模型同一个口径 */
        Scripted(LlmEndpoint endpoint, BooleanSupplier mayCall, Supplier<String> program, String words) {
            this.endpoint = endpoint;
            this.mayCall = mayCall;
            this.program = program;
            this.words = words;
        }

        @Override
        public Cause unavailable(String provider) {
            return null;
        }

        @Override
        public LlmEndpoint endpoint(String provider) {
            return endpoint;
        }

        @Override
        public void ask(String provider, UUID companion, ModelRequest request, CancelToken cancel,
                        Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> done) {
            if (!mayCall.getAsBoolean()) {
                return;
            }
            AssistantTurn turn;
            String code = acted ? null : program.get();
            if (code != null) {
                acted = true;
                turn = new AssistantTurn("", List.of(new LlmToolCall("bench-" + UUID.randomUUID(), ScriptEngine.IN_USE.toolName(),
                        ScriptTool.args(code).toString())), null);
            } else {
                turn = new AssistantTurn(words, List.of(), null);
            }
            done.accept(new ModelOutcome.Answered(turn, Usage.ZERO));
        }
    }
}
