package com.dwinovo.numen.api.program;

import com.dwinovo.numen.script.ApiReply;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientCallPayload;
import com.dwinovo.numen.api.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.api.sdk.Dispatcher;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/**
 * 主人客户端这一侧:替服务端上跑着的程序执行它调的客户端函数({@code ClientCall}),把结果送回去。
 *
 * <p>送回去的出口是一个挂点({@code uplink}):主人客户端上是网络,回环传输({@link LoopbackTransport})里是它自己的收件口。
 */
public final class ClientEndpoint {

    private ClientEndpoint() {}

    /** 执行一个反向请求,答复经 {@code uplink} 送出。 */
    public static void handle(ClientCallPayload request, Consumer<ClientCallResultPayload> uplink) {
        JsonObject args = JsonParser.parseString(request.argumentsJson()).getAsJsonObject();
        Dispatcher.client(request.function(), args, request.entityUuid(), reply -> answer(request, reply, uplink));
    }

    private static void answer(ClientCallPayload request, String reply, Consumer<ClientCallResultPayload> uplink) {
        ClientCallResultPayload result = new ClientCallResultPayload(request.entityUuid(), request.callId(), reply);
        int size = result.size();
        if (!Wire.TO_SERVER.carries(size)) {
            result = new ClientCallResultPayload(request.entityUuid(), request.callId(),
                    ApiReply.error(ErrorKind.FAILED, Wire.TO_SERVER.tooBigMessage("The result of this call", size)
                            + ", so it was not sent. Ask for less of it at a time.", null, null).toString());
        }
        uplink.accept(result);
    }
}
