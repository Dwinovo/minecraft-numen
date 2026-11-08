package com.dwinovo.numen.api.network.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 服务端对一个连接说的第一句话,只有它说的协议版本。玩家在世界里、他的连接登记了这个频道,服务端就发这一包,
 * 别的 Numen 包都要等他用 {@link HelloPayload} 报上同一版协议之后才发。客户端版本对得上才报到。
 *
 * <p><b>格式永远只是一个协议号。</b>协议怎么改,这一包和 {@link HelloPayload} 都不改:两端版本对不上时,能读懂的只剩这两包,
 * 它们一变,对不上的一端连"对不上"都读不出来,解包失败就被断开。
 */
public record ServerHelloPayload(int protocol) implements CustomPacketPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "server_hello");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(protocol);
    }

    public static ServerHelloPayload read(FriendlyByteBuf buf) {
        return new ServerHelloPayload(buf.readVarInt());
    }

    /** Client main thread. */
    public static void handle(ServerHelloPayload p) {
        ClientPayloadSink.serverHello.accept(p);
    }
}
