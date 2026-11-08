package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: 这位主人落过盘的会话(成员表与名字),整份——报到时一次,之后成员或名字变了再发。
 * "就他俩"的会话不在里面:客户端按名册自己有。成员表的真源在服务端,客户端只显示。
 */
public record ConversationsPayload(String json) implements NumenPayload {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "conversations");

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
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        Wire.writeText(buf, json);
    }

    public static ConversationsPayload read(FriendlyByteBuf buf) {
        return new ConversationsPayload(Wire.readText(buf));
    }

    /** Client main thread. */
    public static void handle(ConversationsPayload p) {
        ClientPayloadSink.conversations.accept(p);
    }
}
