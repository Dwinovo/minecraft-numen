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
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.keys.ServerKeyQuota;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 服务端大脑用<b>绑档案的那个人</b>看到的模型档案({@link ProviderLibrary},服务器提供的加他的"我的")调模型:她的主人不止一位时,
 * 档案是谁绑的,就在谁的库里解析、用谁的 key、记在谁的账上({@link Bound#by})。档案每次发请求前现解析:改了档案、删了档案、
 * 换了 key 下一次请求就生效。key 先找他自己的、再找服主的({@link Keys});用的是服主的 key,就受每人每天的 token 限额
 * ({@link ServerKeyQuota}),每次请求烧掉的量记在他名下;他自己的 key 不受限。
 */
public final class LlmMind implements Mind {

    private final Function<UUID, ProviderLibrary> providers;
    private final ModelCatalog catalog;
    private final ServerKeyQuota quota;

    /**
     * @param providers 一位玩家看到的模型档案库(连同他找 key 的顺序)
     * @param catalog   型号目录:没绑档案时的端点从它取
     */
    public LlmMind(Function<UUID, ProviderLibrary> providers, ModelCatalog catalog, ServerKeyQuota quota) {
        this.providers = providers;
        this.catalog = catalog;
        this.quota = quota;
    }

    @Override
    public Cause unavailable(Bound provider) {
        if (provider == null) {
            return Cause.of(Cause.UNBOUND);
        }
        ProviderLibrary library = providers.apply(provider.by());
        String id = provider.id().toString();
        if (library.get(id) == null) {
            return Cause.of(Cause.UNBOUND);
        }
        Keys.Found key = library.key(id);
        if (key == null) {
            return Cause.of(Cause.NO_KEY, library.get(id).name());
        }
        if (!library.resolve(id).hasUrl()) {
            return Cause.of(Cause.NO_URL, library.get(id).name());
        }
        if (key.source() == Keys.Source.SERVER && quota.exceeded(provider.by())) {
            return Cause.of(Cause.QUOTA);
        }
        return null;
    }

    @Override
    public LlmEndpoint endpoint(Bound provider) {
        return provider == null ? catalog.unresolved()
                : providers.apply(provider.by()).resolve(provider.id().toString());
    }

    /**
     * 连请求都没组装出来就出的错——服务商配置对不上、历史转不成线格式——也是一次失败的调用,走同一个出口:
     * 同步抛出去的话,内核会永远等在 MODEL。
     */
    @Override
    public void ask(Bound provider, UUID companion, ModelRequest request, CancelToken cancel,
                    Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> onDone) {
        CompletableFuture<NumenLlmClient.ChatResult> result;
        Keys.Found key = provider == null ? null : providers.apply(provider.by()).key(provider.id().toString());
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
                Constants.LOG.warn("[numen-entity#{}] LLM call failed: {} ({})", companion, cause.forModel(), unwrap(err));
                onDone.accept(new ModelOutcome.Failed(cause));
                return;
            }
            if (key != null && key.source() == Keys.Source.SERVER && res.usage() != null) {
                quota.charge(provider.by(), res.usage().fresh());
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
