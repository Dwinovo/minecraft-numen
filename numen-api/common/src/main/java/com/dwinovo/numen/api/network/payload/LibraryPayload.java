package com.dwinovo.numen.api.network.payload;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: 一种库此刻对这位玩家的整表(JSON 数组),答他的请求,或别人改了之后推给他。{@code problem} 非空是他的
 * 请求没办成的原因。模型档案对非管理员是脱敏的表(没有 key、地址、代理)。
 *
 * <p>{@code request} 是这一包答的哪个请求(客户端起的号,原样带回);服务端主动推的(别人改了、命中账涨了、连接状态变了)
 * 不答任何请求,是 0。客户端凭它把答复交给发请求的那一处,推送只刷新表。
 */
public record LibraryPayload(String kind, String entries, String problem, long request) implements CustomPacketPayload, Wire.Fragmentable {

    /** 服务端主动推的一张表。 */
    public LibraryPayload(String kind, String entries, String problem) {
        this(kind, entries, problem, 0);
    }

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "library");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        Wire.writeText(buf, kind);
        Wire.writeText(buf, entries);
        Wire.writeText(buf, problem);
        buf.writeVarLong(request);
    }

    public static LibraryPayload read(FriendlyByteBuf buf) {
        return new LibraryPayload(Wire.readText(buf), Wire.readText(buf), Wire.readText(buf), buf.readVarLong());
    }

    /** Client main thread. */
    public static void handle(LibraryPayload p) {
        ClientPayloadSink.library.accept(p);
    }
}
