package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.http.CancelToken;

import java.util.function.Consumer;

/**
 * 模型那一侧:组装请求、端点检查、发请求。正常一轮、重试、压缩、目标评估都从 {@link #call} 这一处发出。
 */
public interface ModelPort {

    /** 这只同伴现在发不了请求的理由(没绑档案、没填 key),结构化的原因;{@code null} = 能发。 */
    Cause unavailable();

    /**
     * 组装这一轮要发的请求:当前历史、这一刻的运行期状态、系统提示、工具表。
     */
    ModelRequest turnRequest();

    /**
     * 同一个请求,附带这一轮在循环里的坐标:{@code runId} 是这次 run 的编号,{@code n} 是它第几次调模型。
     * 不关心坐标的实现继续只实现上面那个,一个字不用改。
     *
     * <p>{@code n} 与 {@code ConvoState.turnCount()} 不是一个数:后者在主人插话、链结束、切断三处归零,
     * 算的是"当前这条对话链的第几轮",日志里 {@code turn N} 用的是它。
     *
     * <p><b>实现这个方法的</b>那一侧要注意:{@link com.dwinovo.numen.api.agent.request.RuntimeState#xml()}
     * 是现算的,调两次得到的不是同一份。要记下"发出去的是什么",就得只算一次、记那一份。
     */
    default ModelRequest turnRequest(long runId, int n) {
        return turnRequest();
    }

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
