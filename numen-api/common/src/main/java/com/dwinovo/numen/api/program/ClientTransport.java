package com.dwinovo.numen.api.program;

import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 反向请求的传输:服务端上的程序调了只有主人客户端答得了的函数({@code ClientCall}),请求怎么到客户端、答复怎么回来。
 * 产品里是座位上的 {@code client/call}({@code Seats#transport});没有真客户端的地方(GameTest、单测、评测)是同一套座位代码经进程内的
 * 管道({@code SeatPipe})连着一个客户端。派发的逻辑({@link ClientCalls})不知道是哪一个。
 */
public interface ClientTransport {

    /**
     * 一次反向请求。
     *
     * @param companion 程序属于哪只同伴:请求去她的座位
     * @param callId    这次调用的编号,{@link #cancel} 按它撤
     * @param function  函数的全名
     * @param arguments 程序的调用读成的参数;客户端用同样的编解码器再读一遍
     */
    record Request(UUID companion, String callId, String function, JsonObject arguments) {}

    /**
     * 把请求交给她的主人的客户端。答复(这次调用的结果,{@code ApiReply} 写的那一份;或客户端回不来的失败)经 {@code done}
     * 恰好交回一次,在服务端主线程上。
     */
    void request(Request request, Consumer<String> done);

    /**
     * 不再要 {@code callId} 的答复(等的一方超时了、程序结束了):传输里记着的这笔撤掉,之后才到的答复被忽略,{@code done} 不会再被调。
     * 没在等的(已经答了、从没发出去)不理。
     */
    void cancel(String callId);
}
