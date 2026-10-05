package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.Fragments;
import com.dwinovo.numen.network.NumenPayload;
import com.dwinovo.numen.network.Wire;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.function.Function;

/**
 * 一条超过单包上限的消息({@link Wire.Fragmentable})的一片:消息号、第几片、共几片,第 0 片再带上原包的种类,其余是编码后字节
 * 的一段。两个方向各有自己的通道 id,加载器都按"一个 id 一个方向"登记。怎么切、怎么拼、拼多大见 {@link Fragments}。
 *
 * <p>{@code id} 是这一片所在方向的通道 id({@link #TO_SERVER_ID}、{@link #TO_CLIENT_ID}),跟着片走,所以一个记录类服务两个方向。
 *
 * @param message 这条消息的号:同一条消息的片都是它
 * @param inner   原包的种类,只有第 0 片带
 * @param chunk   原包编码后字节的一段:除最后一片外都正好是 {@link Fragments#chunkBytes}
 */
public record FragmentPayload(ResourceLocation id, int message, int index, int count,
                              Optional<ResourceLocation> inner, byte[] chunk) implements NumenPayload {

    public static final ResourceLocation TO_SERVER_ID = new ResourceLocation(Constants.MOD_ID, "fragment_to_server");
    public static final ResourceLocation TO_CLIENT_ID = new ResourceLocation(Constants.MOD_ID, "fragment_to_client");

    /** 这个方向上的片的通道 id。 */
    public static ResourceLocation idOf(Wire direction) {
        return direction == Wire.TO_SERVER ? TO_SERVER_ID : TO_CLIENT_ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(message);
        buf.writeVarInt(index);
        buf.writeVarInt(count);
        if (index == 0) {
            buf.writeResourceLocation(inner.orElseThrow());
        }
        buf.writeByteArray(chunk);
    }

    /** 这个方向上的片的解码器:一片装不了比这个方向的单包上限更多的字节。 */
    public static Function<FriendlyByteBuf, FragmentPayload> decoderOf(Wire direction) {
        return buf -> {
            int message = buf.readVarInt();
            int index = buf.readVarInt();
            int count = buf.readVarInt();
            Optional<ResourceLocation> inner = index == 0 ? Optional.of(buf.readResourceLocation()) : Optional.empty();
            return new FragmentPayload(idOf(direction), message, index, count, inner,
                    buf.readByteArray(direction.bytes()));
        };
    }
}
