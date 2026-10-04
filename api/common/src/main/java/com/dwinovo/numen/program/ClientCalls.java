package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.payload.ClientCallPayload;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 客户端函数向主人客户端发反向请求:程序线程挂着等答复(同 MCP 的 sampling、LSP 的 workspace/configuration),客户端用
 * {@code Dispatcher.client} 执行完答回来。答复里带着她的新模块时,先换进这段程序的模块({@link RunModules#learn}),再让程序往下走。
 */
final class ClientCalls implements ProgramCalls {

    private final NumenPlayer her;
    private final UUID companion;
    private final MainQueue.Lane lane;
    private final ClientTransport transport;
    private final RunModules modules;

    ClientCalls(NumenPlayer her, UUID companion, MainQueue.Lane lane, ClientTransport transport, RunModules modules) {
        this.her = her;
        this.companion = companion;
        this.lane = lane;
        this.transport = transport;
        this.modules = modules;
    }

    @Override
    public void execute(String callId, Invocation invocation, Consumer<String> done, Runnable sent) {
        ClientCallPayload request = new ClientCallPayload(companion, callId, invocation.function(),
                invocation.args().toString());
        lane.post(() -> {
            transport.request(her, request, answer -> done.accept(learned(answer)));
            sent.run();
        });
    }

    private String learned(ClientTransport.Answer answer) {
        if (answer.modules() != null) {
            try {
                modules.learn(answer.modules());
            } catch (IllegalArgumentException wrong) {
                return ApiReply.error(ErrorKind.FAILED, "your client sent a module that does not match its "
                        + "fingerprint: " + wrong.getMessage(), null, null).toString();
            }
        }
        return answer.reply();
    }
}
