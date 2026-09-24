package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 主人客户端的命令源:她的 UUID、回信口。客户端动作当场执行;解析到的是服务端动作时,调用照身体工具的
 * 路子整个送去服务端({@code ServerToolTransport}),在那边再解析、执行,结果走原来的回执。
 *
 * <p>要她的循环、界面这类只活在客户端的东西,拿 UUID 去客户端那一侧的登记处取——公共代码摸不到客户端类。
 */
public final class ClientSource implements CommandSource {

    private final UUID companion;
    private final Consumer<String> reply;
    private final Runnable toServer;

    /**
     * @param toServer 把这次调用原样送去服务端;结果回来时由传输层交给 {@code reply} 的同一个去处
     */
    ClientSource(UUID companion, Consumer<String> reply, Runnable toServer) {
        this.companion = companion;
        this.reply = reply;
        this.toServer = toServer;
    }

    /** 模型的一次工具调用:结果经这次调用交回,要去服务端就把这次调用原样送过去。 */
    static ClientSource of(ToolCall call) {
        return new ClientSource(call.ctx().entityUuid(), call::complete, () -> ServerToolTransport.ship(call));
    }

    /** 她是谁。 */
    public UUID companion() {
        return companion;
    }

    @Override
    public void reply(String resultJson) {
        reply.accept(resultJson);
    }

    /** 服务端动作:这一侧只负责把调用送过去。 */
    void forwardToServer() {
        toServer.run();
    }
}
