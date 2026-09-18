package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.inbox.EventQueue;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: 一只同伴的一批输入条目,进她的输入队列。
 *
 * <p>实时发生的事是一批里一条,主人登录时离线补发的是攒下的整批——<b>同一种包</b>。整批一次
 * 送达,客户端先全部入队再问一次熟没熟;逐条发的话第一条急件一到就开轮,只带走已经到的那几条。
 *
 * <p>每一条就是 {@code EventQueue.Entry} 的线上形状——四个字段一个不少:
 *
 * <ul>
 *   <li>{@code type} —— 查类型表(拼给模型的样子、进不进聊天流、打断时清不清)。
 *       第三方内容包注册了新类型,这条通道不用改;</li>
 *   <li>{@code ts} —— <b>事发的真实时刻</b>,不是收到的时刻。主人离线期间攒下的事
 *       补发时,少了它她会把三小时前的事当成刚发生的;</li>
 *   <li>{@code urgent} —— 她不知道就会做错事。到了队列会立刻带走攒的一切开一轮
 *       (除非循环停着牌,比如她死着)。</li>
 * </ul>
 *
 * <p>消费时机<b>不由发送方决定</b>:队列按自己的规则(急件 / 攒够条数 / 攒够时长)
 * 说了算。
 */
public record NumenEventPayload(UUID entityUuid, List<EventQueue.Entry> entries)
        implements CustomPacketPayload {

    public static final Type<NumenEventPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_event"));

    private static final StreamCodec<RegistryFriendlyByteBuf, EventQueue.Entry> ENTRY_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), EventQueue.Entry::type,
                    ByteBufCodecs.STRING_UTF8, EventQueue.Entry::text,
                    ByteBufCodecs.VAR_LONG, EventQueue.Entry::ts,
                    ByteBufCodecs.BOOL, EventQueue.Entry::urgent,
                    EventQueue.Entry::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, NumenEventPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NumenEventPayload::entityUuid,
                    ENTRY_CODEC.apply(ByteBufCodecs.list()), NumenEventPayload::entries,
                    NumenEventPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenEventPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.event.accept(p);
    }
}
