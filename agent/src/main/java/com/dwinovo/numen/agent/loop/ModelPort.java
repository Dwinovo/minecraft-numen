package com.dwinovo.numen.agent.loop;

import com.dwinovo.numen.agent.http.CancelToken;

import java.util.function.Consumer;

/**
 * 模型那一侧:组装请求、端点检查、发请求。正常一轮、重试、压缩、目标评估都从 {@link #call} 这一处发出。
 */
public interface ModelPort {

    /** 这只同伴现在发不了请求的理由(没绑档案、没填 key),给主人看的话;{@code null} = 能发。 */
    String unavailable();

    /**
     * 组装这一轮要发的请求:当前历史、这一刻的运行期状态、系统提示、常驻工具表;
     * {@link ModelRequest#callable} 用同一份快照算。
     */
    ModelRequest turnRequest();

    /**
     * 发一次请求。两个回调都在内核的线程(主线程)上执行;{@code cancel} 取消之后一个都不再来。
     *
     * @param onDelta 流式回复的增量
     * @param onDone  结果,没被取消就恰好一次
     */
    void call(ModelRequest request, CancelToken cancel, Consumer<Delta> onDelta, Consumer<ModelOutcome> onDone);

    /**
     * 流式回复的一小段,已按这次调用的服务商方言解开(解码要看是哪家服务商,只有发请求的这一侧知道)。
     *
     * @param content   正文增量;没有为空串
     * @param reasoning 思考增量;没有为空串
     */
    record Delta(String content, String reasoning) {}
}
