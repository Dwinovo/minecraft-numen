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
 * Server → Client: 玩家在旧版本里存在服务器上的一张皮肤原图的一段。皮肤现在是玩家本机文件夹里的 png,旧版本存在服务器上的"我的皮肤"
 * 在他下一次带着客户端连上时按段下行({@code SkinHandover}:每刻至多一段,和导出的记忆是同一套分段),客户端按序收齐存进本机的
 * 皮肤文件夹,只做一次。
 *
 * @param name  皮肤的名字(落成 {@code <名字>.png})
 * @param index 这是第几段,从 0 起
 * @param total 一共几段
 */
public record SkinHandoverPayload(String name, int index, int total, byte[] bytes) implements CustomPacketPayload {

    public static final Type<SkinHandoverPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "skin_handover"));

    public static final StreamCodec<ByteBuf, SkinHandoverPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Wire.TO_CLIENT.text(), SkinHandoverPayload::name,
                    ByteBufCodecs.VAR_INT, SkinHandoverPayload::index,
                    ByteBufCodecs.VAR_INT, SkinHandoverPayload::total,
                    ByteBufCodecs.byteArray(ExportChunkPayload.SEGMENT_BYTES), SkinHandoverPayload::bytes,
                    SkinHandoverPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(SkinHandoverPayload p) {
        ClientPayloadSink.skinHandover.accept(p);
    }
}
