package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → Client: 主人要求导出的一只同伴的记忆(zip)的一段。整包按段依次下行({@code Exports} 每刻至多一段),每段都装得进一个包;
 * 客户端按序收齐再存成文件({@code MemoryFiles}),总大小不设上限。
 *
 * @param index 这是第几段,从 0 起
 * @param total 一共几段
 */
public record ExportChunkPayload(UUID companion, String companionName, int index, int total, byte[] bytes)
        implements CustomPacketPayload {

    /** 一段最多多少字节:远小于一个下行包的上限,留足包头。 */
    public static final int SEGMENT_BYTES = 64 * 1024;

    public static final Type<ExportChunkPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "export_chunk"));

    public static final StreamCodec<ByteBuf, ExportChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ExportChunkPayload::companion,
                    Wire.TO_CLIENT.text(), ExportChunkPayload::companionName,
                    ByteBufCodecs.VAR_INT, ExportChunkPayload::index,
                    ByteBufCodecs.VAR_INT, ExportChunkPayload::total,
                    ByteBufCodecs.byteArray(SEGMENT_BYTES), ExportChunkPayload::bytes,
                    ExportChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(ExportChunkPayload p) {
        ClientPayloadSink.export.accept(p);
    }
}
