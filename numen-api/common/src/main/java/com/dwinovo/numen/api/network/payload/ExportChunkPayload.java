package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
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
        implements NumenPayload {

    /** 一段最多多少字节:远小于一个下行包的上限,留足包头。 */
    public static final int SEGMENT_BYTES = 64 * 1024;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "export_chunk");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(companion);
        Wire.writeText(buf, companionName);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(bytes);
    }

    public static ExportChunkPayload read(FriendlyByteBuf buf) {
        return new ExportChunkPayload(buf.readUUID(), Wire.readText(buf), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(SEGMENT_BYTES));
    }

    /** Client main thread. */
    public static void handle(ExportChunkPayload p) {
        ClientPayloadSink.export.accept(p);
    }
}
