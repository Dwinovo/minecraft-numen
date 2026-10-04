package com.dwinovo.numen.program;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.script.Modules;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 没有真客户端的进程里(GameTest、单测、评测)反向请求的传输:请求和答复各编码成线上的字节再解出来,交给同一个客户端执行体
 * ({@link ClientEndpoint}),和网络上走的是同一条路,只是不出进程。
 */
public final class LoopbackTransport implements ClientTransport {

    private final AnswerTable table = new AnswerTable();
    private final ClientEndpoint endpoint;

    /**
     * @param sync    这个"客户端"送过哪些模块正文
     * @param modules 她的模块在这个"客户端"的哪里
     */
    public LoopbackTransport(ModuleSync sync, Function<UUID, Modules> modules) {
        this.endpoint = new ClientEndpoint(sync, this::uplinked, modules);
    }

    @Override
    public void request(NumenPlayer her, ClientCallPayload request, Consumer<Answer> done) {
        table.expect(request.callId(), request.entityUuid(), done);
        endpoint.handle(RoundTrip.of(Wire.TO_CLIENT, ClientCallPayload.STREAM_CODEC, request));
    }

    /** 客户端的答复"上行":过一遍线上的字节,交给等的一方。 */
    private void uplinked(CustomPacketPayload payload) {
        ClientCallResultPayload result = RoundTrip.of(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, (ClientCallResultPayload) payload);
        table.complete(result.callId(), result.entityUuid(),
                new Answer(result.replyJson(), result.modules().orElse(null)));
    }
}
