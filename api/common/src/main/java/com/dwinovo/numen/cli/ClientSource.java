package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ToolCall;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 主人客户端上的一次调用:她的 UUID、回信口。客户端动作的处理函数拿到的就是它,当场执行、经它回结果。
 *
 * <p>要她的循环、界面这类只活在客户端的东西,拿 UUID 去客户端那一侧的登记处取——公共代码摸不到客户端类。
 */
public final class ClientSource implements CommandSource {

    private final UUID companion;
    private final Consumer<String> reply;

    ClientSource(UUID companion, Consumer<String> reply) {
        this.companion = companion;
        this.reply = reply;
    }

    /** 脚本里的一次调用:结果经这次调用交回。 */
    static ClientSource of(ToolCall call) {
        return new ClientSource(call.ctx().entityUuid(), call::complete);
    }

    /** 她是谁。 */
    public UUID companion() {
        return companion;
    }

    @Override
    public void reply(String resultJson) {
        reply.accept(resultJson);
    }
}
