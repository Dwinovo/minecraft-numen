package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.llm.NumenLlmClient;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.keys.ServerKeyQuota;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 服务端大脑用主人看到的模型档案({@link ProviderLibrary},内置加我的)调模型。档案每次发请求前现解析:改了档案、删了档案、
 * 换了 key 下一次请求就生效。key 先找主人自己的、再找服主的({@link Keys});用的是服主的 key,就受每人每天的 token 限额
 * ({@link ServerKeyQuota}),每次请求烧掉的量记在这位主人名下;主人自己的 key 不受限。
 */
public final class LlmMind implements Mind {

    private final ProviderLibrary library;
    private final UUID owner;
    private final ServerKeyQuota quota;

    public LlmMind(ProviderLibrary library, UUID owner, ServerKeyQuota quota) {
        this.library = library;
        this.owner = owner;
        this.quota = quota;
    }

    @Override
    public Cause unavailable(String provider) {
        if (provider == null || library.get(provider) == null) {
            return Cause.of(Cause.UNBOUND);
        }
        Keys.Found key = library.key(provider);
        if (key == null) {
            return Cause.of(Cause.NO_KEY, library.get(provider).name());
        }
        if (!library.resolve(provider).hasUrl()) {
            return Cause.of(Cause.NO_URL, library.get(provider).name());
        }
        if (key.source() == Keys.Source.SERVER && quota.exceeded(owner)) {
            return Cause.of(Cause.QUOTA);
        }
        return null;
    }

    @Override
    public LlmEndpoint endpoint(String provider) {
        return library.resolve(provider);
    }

    /**
     * 连请求都没组装出来就出的错——服务商配置对不上、历史转不成线格式——也是一次失败的调用,走同一个出口:
     * 同步抛出去的话,内核会永远等在 MODEL。
     */
    @Override
    public void ask(String provider, UUID companion, ModelRequest request, CancelToken cancel,
                    Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> onDone) {
        CompletableFuture<NumenLlmClient.ChatResult> result;
        Keys.Found key = library.key(provider);
        try {
            result = NumenLlmClient.forEndpoint(library.resolve(provider)).chatStreaming(request.messages(),
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
                Constants.LOG.warn("[numen-entity#{}] LLM call failed: {} ({})", companion, cause.forModel(), unwrap(err));
                onDone.accept(new ModelOutcome.Failed(cause));
                return;
            }
            if (key != null && key.source() == Keys.Source.SERVER && res.usage() != null) {
                quota.charge(owner, res.usage().fresh());
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
