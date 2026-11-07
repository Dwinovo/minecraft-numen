package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: 这位主人落过盘的会话(成员表与名字),整份——报到时一次,之后成员或名字变了再发。
 * "就他俩"的会话不在里面:客户端按名册自己有。成员表的真源在服务端,客户端只显示。
 */
public record ConversationsPayload(String json) implements CustomPacketPayload {

    public static final Type<ConversationsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "conversations"));

    public static final StreamCodec<ByteBuf, ConversationsPayload> STREAM_CODEC =
            StreamCodec.composite(Wire.TO_CLIENT.text(), ConversationsPayload::json, ConversationsPayload::new);

    public List<Conversation> conversations() {
        List<Conversation> out = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
            Conversation conv = Conversation.fromJson(e.getAsJsonObject());
            if (conv != null) {
                out.add(conv);
            }
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(ConversationsPayload p) {
        ClientPayloadSink.conversations.accept(p);
    }
}
