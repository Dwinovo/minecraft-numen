package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonPrimitive;

import java.io.IOException;

/**
 * 客户端到一台服务器的传输:Streamable HTTP({@link HttpClientTransport})或 stdio 子进程({@link StdioClientTransport})。
 * 只管把消息送过去、把对方的消息交回来;协议(握手、编号、撤回)在 {@link McpClient} 与 {@link RpcPeer}。
 */
public interface ClientTransport extends RpcPeer.Outbound, AutoCloseable {

    /** 传输交回来的东西。 */
    interface Inbound {

        /** 对方发来一条消息。 */
        void message(RpcMessage message);

        /** 这个请求的答复不会来了:没送到,或它的流断了、接不上。 */
        void lost(JsonPrimitive requestId, IOException why);

        /** 整条连接断了(子进程退出):还在等的都不会有答复了。 */
        void closed(IOException why);
    }

    /**
     * 开始一个会话的收发。再调一次是从头开一个新会话(Streamable HTTP 的会话被服务端结束、答 404 之后,规范要求重新握手)。
     */
    void open(Inbound inbound) throws IOException;

    /**
     * 发一条消息。请求交出去就返回,它的答复经 {@link Inbound#message} 回来,送不到经 {@link Inbound#lost};通知与答复送到才返回。
     *
     * @throws SessionExpired Streamable HTTP 的会话已被服务端结束
     */
    @Override
    void send(RpcMessage message) throws IOException;

    /** 握手做完、版本谈定:Streamable HTTP 此后每个请求带版本头,并开 GET 流听服务端与请求无关的消息(返回时流已开好或已被拒)。 */
    void initialized(ProtocolVersion version) throws IOException;

    /** 关掉:HTTP 告诉服务端结束会话,stdio 收掉子进程。关了就是关了。 */
    @Override
    void close();
}
