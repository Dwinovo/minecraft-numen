package com.dwinovo.numen.api.network.payload;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.Wire;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 一只同伴一刻里发生的、界面要画要听的事({@link Feed}),按发生的先后。一行日志里的工具结果可以很长,
 * 一刻的事件可能装不下一个包,所以可分片。
 */
public record FeedPayload(UUID companion, String eventsJson) implements CustomPacketPayload, Wire.Fragmentable {

    public static final Type<FeedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "feed"));

    public static final StreamCodec<ByteBuf, FeedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, FeedPayload::companion,
                    Wire.TO_CLIENT.text(), FeedPayload::eventsJson,
                    FeedPayload::new);

    public static FeedPayload of(UUID companion, List<Feed> events) {
        return new FeedPayload(companion, Feed.toJson(events).toString());
    }

    /** 读出事件;读不懂的种类(新版本加的)跳过。 */
    public List<Feed> events() {
        return Feed.listFromJson(JsonParser.parseString(eventsJson).getAsJsonArray());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(FeedPayload p) {
        ClientPayloadSink.feed.accept(p);
    }
}
