package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.script.Modules;
import com.dwinovo.numen.sdk.Dispatcher;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 主人客户端这一侧:替服务端上跑着的程序执行它调的客户端函数,把结果送回去。函数改了她的模块(程序里的
 * {@code numen.module.save})时,答复里带上客户端此刻的清单与新正文({@link ModuleSet}),真源始终只有客户端的文件。
 *
 * <p>送回去的出口是一个挂点({@code uplink}):主人客户端上是网络,回环传输({@link LoopbackTransport})里是它自己的收件口。
 */
public final class ClientEndpoint {

    /** 主人客户端上这个连接的那一份:和 {@link ProgramUplink#CONNECTION} 共用同一份"送过哪些正文"。 */
    public static final ClientEndpoint CONNECTION = new ClientEndpoint(ProgramUplink.CONNECTION.sync(),
            payload -> ProgramUplink.wire.accept(payload), Modules::of);

    private final ModuleSync sync;
    private final Consumer<CustomPacketPayload> uplink;
    /** 她的模块在哪:主人客户端上是 {@link Modules#of}。 */
    private final Function<UUID, Modules> modules;

    public ClientEndpoint(ModuleSync sync, Consumer<CustomPacketPayload> uplink, Function<UUID, Modules> modules) {
        this.sync = sync;
        this.uplink = uplink;
        this.modules = modules;
    }

    /** 执行一个反向请求,答复经 {@code uplink} 送出。 */
    public void handle(ClientCallPayload request) {
        UUID companion = request.entityUuid();
        long revision = Modules.revision();
        JsonObject args = JsonParser.parseString(request.argumentsJson()).getAsJsonObject();
        Dispatcher.client(request.function(), args, companion, reply -> answer(request, revision, reply));
    }

    private void answer(ClientCallPayload request, long revision, String reply) {
        Optional<ModuleSet> changed = Modules.revision() == revision ? Optional.empty()
                : Optional.of(sync.pack(modules.apply(request.entityUuid()).sources()));
        ClientCallResultPayload result = new ClientCallResultPayload(request.entityUuid(), request.callId(), reply,
                changed);
        int size = result.size();
        if (!Wire.TO_SERVER.carries(size)) {
            // 模块这一刻没送出去:下次重新带
            changed.ifPresent(m -> sync.lost(m.bodies().keySet()));
            result = new ClientCallResultPayload(request.entityUuid(), request.callId(),
                    ApiReply.error(ErrorKind.FAILED, Wire.TO_SERVER.tooBigMessage("The result of this call", size)
                            + ", so it was not sent. Ask for less of it at a time.", null, null).toString(),
                    Optional.empty());
        }
        uplink.accept(result);
    }
}
