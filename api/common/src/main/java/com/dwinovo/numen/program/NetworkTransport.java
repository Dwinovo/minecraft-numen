package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.ApiReply;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 反向请求走网络:请求作为 {@link ClientCallPayload} 发给她的主人,主人的客户端答 {@link ClientCallResultPayload}。
 * 主人不在线、请求连整条消息的上限都装不下,或主人在等答复时断线,都是这次调用的一条失败,程序按失败往下走。
 */
public final class NetworkTransport implements ClientTransport {

    public static final NetworkTransport INSTANCE = new NetworkTransport();

    private final AnswerTable table = new AnswerTable();

    private NetworkTransport() {}

    @Override
    public void request(NumenPlayer her, ClientCallPayload request, Consumer<Answer> done) {
        ServerPlayer owner = her == null ? null : her.resolveOwnerPlayer();
        if (owner == null) {
            done.accept(failure(request.function(), "its owner is not connected"));
            return;
        }
        int size = request.size();
        if (!Wire.TO_CLIENT.carries(size)) {
            done.accept(new Answer(ApiReply.error(ErrorKind.FAILED, Wire.TO_CLIENT.tooBigMessage("This call", size)
                    + ", so it was not sent. Give it less at a time.", null, null).toString(), null));
            return;
        }
        table.expect(request.callId(), owner.getUUID(), done);
        NumenNetwork.sendToPlayer(owner, request);
    }

    @Override
    public void cancel(String callId) {
        table.cancel(callId);
    }

    /** 主人的客户端答了(服务端主线程)。 */
    public void answered(UUID from, ClientCallResultPayload p) {
        table.complete(p.callId(), from, new Answer(p.replyJson(), p.modules().orElse(null)));
    }

    /** 这位主人断线了:等着他答复的调用都以失败结束。 */
    public void ownerLeft(UUID owner) {
        table.failOwner(owner, callId -> failure("this function", "its owner disconnected"));
    }

    /** 关服。 */
    public void clear() {
        table.clear();
    }

    private static Answer failure(String what, String why) {
        return new Answer(ApiReply.error(ErrorKind.FAILED, what + " runs on your owner's client, and " + why, null,
                null).toString(), null);
    }
}
