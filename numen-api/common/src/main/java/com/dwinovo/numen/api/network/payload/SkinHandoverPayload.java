package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 玩家在旧版本里存在服务器上的一张皮肤原图的一段。皮肤现在是玩家本机文件夹里的 png,旧版本存在服务器上的"我的皮肤"
 * 在他下一次带着客户端连上时按段下行({@code SkinHandover}:每刻至多一段,和导出的记忆是同一套分段),客户端按序收齐存进本机的
 * 皮肤文件夹,只做一次。
 *
 * @param name  皮肤的名字(落成 {@code <名字>.png})
 * @param index 这是第几段,从 0 起
 * @param total 一共几段
 */
public record SkinHandoverPayload(String name, int index, int total, byte[] bytes) implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "skin_handover");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        Wire.writeText(buf, name);
        buf.writeVarInt(index);
        buf.writeVarInt(total);
        buf.writeByteArray(bytes);
    }

    public static SkinHandoverPayload read(FriendlyByteBuf buf) {
        return new SkinHandoverPayload(Wire.readText(buf), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(ExportChunkPayload.SEGMENT_BYTES));
    }

    /** Client main thread. */
    public static void handle(SkinHandoverPayload p) {
        ClientPayloadSink.skinHandover.accept(p);
    }
}
