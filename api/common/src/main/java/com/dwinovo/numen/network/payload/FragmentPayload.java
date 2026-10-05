package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.Fragments;
import com.dwinovo.numen.network.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 一条超过单包上限的消息({@link Wire.Fragmentable})的一片:消息 id、第几片、共几片,第 0 片再带上原包的种类,其余是编码后字节
 * 的一段。两个方向各有自己的种类 id,NeoForge 与 Fabric 都按"一个 id 一个方向"登记。怎么切、怎么拼、拼多大见 {@link Fragments}。
 *
 * <p>{@code type} 是这一片所在方向的种类({@link #TO_SERVER}、{@link #TO_CLIENT}),跟着片走,所以一个记录类服务两个方向。
 *
 * @param inner 原包的种类,只有第 0 片带
 * @param chunk 原包编码后字节的一段:除最后一片外都正好是 {@link Fragments#chunkBytes}
 */
public record FragmentPayload(Type<FragmentPayload> type, int id, int index, int count,
                              Optional<ResourceLocation> inner, byte[] chunk) implements CustomPacketPayload {

    public static final Type<FragmentPayload> TO_SERVER = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "fragment_to_server"));
    public static final Type<FragmentPayload> TO_CLIENT = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "fragment_to_client"));

    public static final StreamCodec<ByteBuf, FragmentPayload> TO_SERVER_CODEC =
            codec(TO_SERVER, Wire.TO_SERVER_PACKET);
    public static final StreamCodec<ByteBuf, FragmentPayload> TO_CLIENT_CODEC =
            codec(TO_CLIENT, Wire.TO_CLIENT_PACKET);

    /** 这个方向上的片的种类。 */
    public static Type<FragmentPayload> typeOf(Wire direction) {
        return direction == Wire.TO_SERVER ? TO_SERVER : TO_CLIENT;
    }

    /** 这个方向上的片的编解码器。 */
    public static StreamCodec<ByteBuf, FragmentPayload> codecOf(Wire direction) {
        return direction == Wire.TO_SERVER ? TO_SERVER_CODEC : TO_CLIENT_CODEC;
    }

    private static StreamCodec<ByteBuf, FragmentPayload> codec(Type<FragmentPayload> type, int maxChunk) {
        StreamCodec<ByteBuf, byte[]> bytes = ByteBufCodecs.byteArray(maxChunk);
        return StreamCodec.of((buf, p) -> {
            VarInt.write(buf, p.id);
            VarInt.write(buf, p.index);
            VarInt.write(buf, p.count);
            if (p.index == 0) {
                ResourceLocation.STREAM_CODEC.encode(buf, p.inner.orElseThrow());
            }
            bytes.encode(buf, p.chunk);
        }, buf -> {
            int id = VarInt.read(buf);
            int index = VarInt.read(buf);
            int count = VarInt.read(buf);
            Optional<ResourceLocation> inner = index == 0 ? Optional.of(ResourceLocation.STREAM_CODEC.decode(buf))
                    : Optional.empty();
            return new FragmentPayload(type, id, index, count, inner, bytes.decode(buf));
        });
    }
}
