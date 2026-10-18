package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 一种库此刻对这位玩家的整表(JSON 数组),答他的请求,或别人改了之后推给他。{@code problem} 非空是他的
 * 请求没办成的原因。模型档案对非管理员是脱敏的表(没有 key、地址、代理)。
 */
public record LibraryPayload(String kind, String entries, String problem) implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<LibraryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "library"));

    public static final StreamCodec<ByteBuf, LibraryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Wire.TO_CLIENT.text(), LibraryPayload::kind,
                    Wire.TO_CLIENT.text(), LibraryPayload::entries,
                    Wire.TO_CLIENT.text(), LibraryPayload::problem,
                    LibraryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(LibraryPayload p) {
        ClientPayloadSink.library.accept(p);
    }
}
