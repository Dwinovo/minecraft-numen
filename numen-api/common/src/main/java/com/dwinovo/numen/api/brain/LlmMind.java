package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.NumenLlmClient;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 主人的客户端上的大脑调模型:用她绑的模型档案({@link ProviderLibrary},主人电脑上的库与钥匙串)。档案每次发请求前现解析:改了档案、
 * 删了档案、换了 key 下一次请求就生效。key 只在这台电脑上,不上服务器、不进座位。
 */
public final class LlmMind implements Mind {

    private final Supplier<ProviderLibrary> providers;
    private final ModelCatalog catalog;

    /**
     * @param providers 主人的模型档案库
     * @param catalog   型号目录:没绑档案时的端点从它取
     */
    public LlmMind(Supplier<ProviderLibrary> providers, ModelCatalog catalog) {
        this.providers = providers;
        this.catalog = catalog;
    }

    @Override
    public Cause unavailable(Bound provider) {
        if (provider == null) {
            return Cause.of(Cause.UNBOUND);
        }
        ProviderLibrary library = providers.get();
        String id = provider.id().toString();
        if (library.get(id) == null) {
            return Cause.of(Cause.UNBOUND);
        }
        if (library.key(id) == null) {
            return Cause.of(Cause.NO_KEY, library.get(id).name());
        }
        if (!library.resolve(id).hasUrl()) {
            return Cause.of(Cause.NO_URL, library.get(id).name());
        }
        return null;
    }

    @Override
    public LlmEndpoint endpoint(Bound provider) {
        return provider == null ? catalog.unresolved() : providers.get().resolve(provider.id().toString());
    }

    /**
     * 连请求都没组装出来就出的错——服务商配置对不上、历史转不成线格式——也是一次失败的调用,走同一个出口:
     * 同步抛出去的话,内核会永远等在 MODEL。
     */
    @Override
    public void ask(Bound provider, UUID companion, ModelRequest request, CancelToken cancel,
                    Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> onDone) {
        CompletableFuture<NumenLlmClient.ChatResult> result;
        try {
            result = NumenLlmClient.forEndpoint(endpoint(provider)).chatStreaming(request.messages(),
                    request.tools(), request.systemPrompt(), companion.toString(), cancel, delta -> {
                        if (!cancel.isCancelled()) {
                            onDelta.accept(new ModelPort.Delta(delta.content(), delta.reasoning()));
                        }
                    });
        } catch (RuntimeException ex) {
            result = CompletableFuture.failedFuture(ex);
        }
        result.whenComplete((res, err) -> {
            if (cancel.isCancelled()) {
                return;   // 取消之后不再回调:发起这次调用的一方已经不要它了
            }
            if (err != null) {
                // 面向主人的是分类人话;技术细节进日志(传输层还有全量)。
                Cause cause = Cause.fromError(err);
                Constants.LOG.warn("[numen-mind#{}] LLM call failed: {} ({})", companion, cause.forModel(), unwrap(err));
                onDone.accept(new ModelOutcome.Failed(cause));
                return;
            }
            onDone.accept(new ModelOutcome.Answered(res.turn(), res.usage()));
        });
    }

    private static String unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }
}
