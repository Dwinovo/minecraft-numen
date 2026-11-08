package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.network.Wire;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 读写服务器上的一种库(人设、模型档案、服务器配置)。{@code op} 是 list / put / delete,{@code json} 是
 * 存和删带的内容。{@code request} 是客户端起的请求号(要答复的才起,从 1 数),服务端原样带回在答复里;0 是不等答复。谁能做什么、能看到什么由服务端判(见 {@code Libraries}),答复是 {@link LibraryPayload}。
 */
public record LibraryRequestPayload(String kind, String op, String json, long request) implements NumenPayload, Wire.Fragmentable {

    /** 不等答复的请求。 */
    public LibraryRequestPayload(String kind, String op, String json) {
        this(kind, op, json, 0);
    }

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "library_request");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        Wire.writeText(buf, kind);
        Wire.writeText(buf, op);
        Wire.writeText(buf, json);
        buf.writeVarLong(request);
    }

    public static LibraryRequestPayload read(FriendlyByteBuf buf) {
        return new LibraryRequestPayload(Wire.readText(buf), Wire.readText(buf), Wire.readText(buf), buf.readVarLong());
    }

    /** Handler invoked on the server main thread. */
    public static void handle(LibraryRequestPayload p, ServerPlayer player) {
        Replies.library(player, p.kind(), p.op(), p.json(), p.request());
    }
}
