package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 服务端大脑怎么跟模型说话:一条同伴绑的档案 id 进来,端点、可不可用、发请求都在这一侧。产品里是 {@link LlmMind}
 * (服主的档案与 key);GameTest 给脚本化的一个。
 *
 * <p>回调可以在任何线程上交回——循环自己把它切回服务端主线程。
 */
public interface Mind {

    /** 绑的档案现在发不了请求的理由(没绑、没填 key、没地址);{@code null} = 能发。 */
    Cause unavailable(String provider);

    /** 这份档案此刻解析出的端点:窗口、模型、思考档位都从它取。 */
    LlmEndpoint endpoint(String provider);

    /**
     * 发一次请求。结果没被取消就恰好交回一次,失败也是一个值({@link ModelOutcome.Failed}),不抛;
     * {@code cancel} 取消之后一个回调都不再来。
     *
     * @param companion 日志里认这次调用是谁的
     */
    void ask(String provider, UUID companion, ModelRequest request, CancelToken cancel,
             Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> onDone);
}
