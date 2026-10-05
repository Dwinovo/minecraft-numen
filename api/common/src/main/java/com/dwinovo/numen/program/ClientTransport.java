package com.dwinovo.numen.program;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.ClientCallPayload;

import java.util.function.Consumer;

/**
 * 反向请求的传输:服务端上的程序调了只有主人客户端答得了的函数({@code ClientCall}),请求怎么到客户端、答复怎么回来。
 * 产品里是网络({@link NetworkTransport});没有真客户端的地方(GameTest、单测)是同样经过包编解码的回环
 * ({@link LoopbackTransport})。派发的逻辑({@link ClientCalls})不知道是哪一个。
 */
public interface ClientTransport {

    /**
     * 客户端的答复。
     *
     * @param reply   这次调用的结果({@code ApiReply} 写的那一份)
     * @param modules 这次调用改了她的模块时,客户端此刻的清单与新正文;没改是 null
     */
    record Answer(String reply, ModuleSet modules) {}

    /**
     * 把请求交给她的主人的客户端。答复(或客户端回不来的失败)经 {@code done} 恰好交回一次,在服务端主线程上。
     *
     * @param her 她的身体;没有世界的单测是 null
     */
    void request(NumenPlayer her, ClientCallPayload request, Consumer<Answer> done);

    /**
     * 不再要 {@code callId} 的答复(等的一方超时了、程序结束了):传输里记着的这笔撤掉,之后才到的答复被忽略,{@code done} 不会再被调。
     * 没在等的(已经答了、从没发出去)不理。
     */
    void cancel(String callId);
}
