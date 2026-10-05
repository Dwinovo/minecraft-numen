package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 进同伴输入队列的那种包:实时的一条、离线补发的整批,是同一种形状。
 *
 * <p>四个字段每一个都有人要:类型查表、正文给模型、事发时刻标年龄、急件决定开不开轮。
 * 编解码丢了哪一个,她都会做错事而且不报错。
 */
class NumenEventPayloadTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static NumenEventPayload roundTrip(NumenEventPayload p) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        NumenEventPayload.STREAM_CODEC.encode(buf, p);
        return NumenEventPayload.STREAM_CODEC.decode(buf);
    }

    @Test
    void aWholeBatchRoundTripsInOrderWithEveryField() {
        NumenEventPayload sent = new NumenEventPayload(A, List.of(
                new EventQueue.Entry(EventTypes.TASK_FINISHED, "<event kind=\"task_finished\">矿挖完了</event>", 1_000L, true),
                new EventQueue.Entry(EventTypes.REFLEX, "<event kind=\"reflex\" reflex=\"mlg\">broke a fall with a water bucket</event>", 2_000L, false),
                new EventQueue.Entry("第三方模组的类型", "外面来的一条", 3_000L, false)));

        assertEquals(sent, roundTrip(sent));
    }

    @Test
    void aLiveEventIsTheSamePacketWithOneEntry() {
        NumenEventPayload sent = new NumenEventPayload(A, List.of(
                new EventQueue.Entry(EventTypes.OWNER_HURT, "<event kind=\"owner_hurt\"/>", 42L, true)));

        assertEquals(sent, roundTrip(sent));
    }
}
