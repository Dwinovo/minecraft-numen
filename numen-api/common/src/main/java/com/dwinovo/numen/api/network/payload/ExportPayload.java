package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 主人要求导出的一只同伴的记忆(对话日志与札记打成的 zip),交到他的客户端存成文件。大小随她聊过多少而长,
 * 所以可分片;拼回之后至多一条消息的上限,更大的服务端不送、直接告诉他。
 */
public record ExportPayload(String companionName, byte[] zip) implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<ExportPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export"));

    public static final StreamCodec<ByteBuf, ExportPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Wire.TO_CLIENT.text(), ExportPayload::companionName,
                    ByteBufCodecs.byteArray(Wire.MESSAGE_BYTES), ExportPayload::zip,
                    ExportPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(ExportPayload p) {
        ClientPayloadSink.export.accept(p);
    }
}
