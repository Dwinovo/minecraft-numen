package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

/**
 * 座位上的一条 JSON-RPC 消息(请求、答复或通知),两个方向各一种种类、同一个记录类。主人的客户端上的大脑与服务端上的身体之间说的
 * 话全装在这一种包里;消息可以很长(程序的回执、客户端函数的答复),所以是 {@link Wire.Fragmentable},超过单包上限的由
 * {@code Fragments} 切片、对端拼回。
 *
 * <p>{@code type} 是这一条所在方向的种类({@link #TO_SERVER}、{@link #TO_CLIENT}),跟着包走。
 *
 * @param json 一条 JSON-RPC 消息的一行文本({@link RpcMessage#encode})
 */
public record SeatPayload(Type<SeatPayload> type, String json) implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<SeatPayload> TO_SERVER = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "seat_to_server"));
    public static final Type<SeatPayload> TO_CLIENT = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "seat_to_client"));

    public static final StreamCodec<ByteBuf, SeatPayload> TO_SERVER_CODEC = codec(TO_SERVER, Wire.TO_SERVER);
    public static final StreamCodec<ByteBuf, SeatPayload> TO_CLIENT_CODEC = codec(TO_CLIENT, Wire.TO_CLIENT);

    private static StreamCodec<ByteBuf, SeatPayload> codec(Type<SeatPayload> type, Wire direction) {
        return direction.text().map(json -> new SeatPayload(type, json), SeatPayload::json);
    }

    /** 这个方向上的种类。 */
    public static Type<SeatPayload> typeOf(Wire direction) {
        return direction == Wire.TO_SERVER ? TO_SERVER : TO_CLIENT;
    }

    /** 这个方向上的编解码器。 */
    public static StreamCodec<ByteBuf, SeatPayload> codecOf(Wire direction) {
        return direction == Wire.TO_SERVER ? TO_SERVER_CODEC : TO_CLIENT_CODEC;
    }

    /**
     * 一条消息装成这个方向上的包,发送之前量过:装不下一整条消息的上限({@link Wire#MESSAGE_BYTES})的不装。
     *
     * @throws com.dwinovo.numen.mcp.rpc.MessageTooBig 消息太长,送不出去;说明写给收到失败的那一方看
     */
    public static SeatPayload frame(Wire direction, RpcMessage message) throws IOException {
        SeatPayload payload = new SeatPayload(typeOf(direction), message.encode());
        int size = Wire.size(codecOf(direction), payload, Unpooled::buffer);
        if (!direction.carries(size)) {
            throw new com.dwinovo.numen.mcp.rpc.MessageTooBig(direction.tooBigMessage("This message", size)
                    + ", so it was not sent. Ask for less of it at a time.");
        }
        return payload;
    }

    /**
     * 包里的那条消息。
     *
     * @throws RpcException 不是 JSON,或不是 JSON-RPC 的一条消息
     */
    public RpcMessage message() throws RpcException {
        return RpcMessage.decode(json);
    }

    /** Client main thread. */
    public static void handle(SeatPayload p) {
        ClientPayloadSink.seat.accept(p);
    }
}
