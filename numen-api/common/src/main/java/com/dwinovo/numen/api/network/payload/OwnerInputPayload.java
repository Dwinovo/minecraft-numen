package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.OwnerInputs;
import com.dwinovo.numen.api.network.Wire;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Client → Server: 主人做的一件事({@link OwnerInput}),JSON 一条。在网络线程上读懂、验过形状就投进这位玩家的信箱,
 * 主线程每刻取几条处理({@link OwnerInputs});读不懂的丢弃——上行的东西不可信。
 */
public record OwnerInputPayload(String json) implements CustomPacketPayload {

    public static final Type<OwnerInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "owner_input"));

    public static final StreamCodec<ByteBuf, OwnerInputPayload> STREAM_CODEC =
            StreamCodec.composite(Wire.TO_SERVER.text(), OwnerInputPayload::json, OwnerInputPayload::new);

    public static OwnerInputPayload of(OwnerInput input) {
        return new OwnerInputPayload(input.toJson().toString());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the network thread (or the server thread where the loader has no other). */
    public static void handle(OwnerInputPayload p, ServerPlayer player) {
        OwnerInput input = read(p.json());
        if (input == null) {
            Constants.LOG.warn("[numen-net] dropped an unreadable owner input from {}", player.getUUID());
            return;
        }
        OwnerInputs.offer(player.getUUID(), input);
    }

    static OwnerInput read(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            return el instanceof JsonObject o ? OwnerInput.fromJson(o) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
