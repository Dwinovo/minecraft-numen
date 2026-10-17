package com.dwinovo.numen.api.program;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientCallPayload;
import com.dwinovo.numen.api.network.payload.ClientCallResultPayload;

import java.util.function.Consumer;

/**
 * 没有真客户端的进程里(GameTest、单测、评测)反向请求的传输:请求和答复各经过线上的样子({@link Fragments#crossed}),交给同一个客户端执行体
 * ({@link ClientEndpoint}),和网络上走的是同一条路,只是不出进程。
 */
public final class LoopbackTransport implements ClientTransport {

    private final AnswerTable table = new AnswerTable();
    /** 这个"客户端"不再答复了(卡住了):请求照常到达、函数照常执行,答复到不了服务端。 */
    private volatile boolean silent;

    @Override
    public void request(NumenPlayer her, ClientCallPayload request, Consumer<String> done) {
        table.expect(request.callId(), request.entityUuid(), done);
        ClientEndpoint.handle(Fragments.crossed(Wire.TO_CLIENT, ClientCallPayload.STREAM_CODEC, request), this::uplinked);
    }

    @Override
    public void cancel(String callId) {
        table.cancel(callId);
    }

    /** 从此这个"客户端"的答复都到不了服务端:程序里的客户端函数只能等到时限。 */
    public void silence() {
        silent = true;
    }

    /** 客户端的答复"上行":过一遍线上的字节,交给等的一方。 */
    private void uplinked(ClientCallResultPayload payload) {
        if (silent) {
            return;
        }
        ClientCallResultPayload result = Fragments.crossed(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, payload);
        table.complete(result.callId(), result.entityUuid(), result.replyJson());
    }
}
