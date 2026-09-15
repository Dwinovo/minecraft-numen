package com.dwinovo.numen.entity;

import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.inbox.EventTypes;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人登录时离线补发的打包:一只同伴攒下的全部条目装进一个包。
 *
 * <p>逐条发包的话,客户端第一条急件一到就开轮,只带走已经到的那几条——"我帮你把矿挖完了"
 * 跟后面几件事被拆进两轮说。一个包一次送达,客户端一次入队再问熟度。
 */
class OutboxReplayTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID C = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final long T0 = 1_000_000L;

    @Test
    void eachCompanionGetsOnePacketWithEverythingSheSaved() {
        EventOutbox box = new EventOutbox();
        box.put(A, EventTypes.EVENT, "<event>任务完成</event>", T0, true);
        box.put(A, EventTypes.EVENT, "<event>吃了个面包</event>", T0 + 5, false);
        box.put(A, EventTypes.EVENT, "<event>跨了维度</event>", T0 + 9, false);
        box.put(B, EventTypes.EVENT, "<event>被怪打了</event>", T0 + 1, true);

        List<NumenEventPayload> packets = Companions.outboxPayloads(box, List.of(A, B, C), T0 + 10);

        assertEquals(2, packets.size(), "一只一个包;什么都没攒的 C 不发");
        assertEquals(A, packets.get(0).entityUuid());
        assertEquals(List.of("<event>任务完成</event>", "<event>吃了个面包</event>", "<event>跨了维度</event>"),
                packets.get(0).entries().stream().map(EventQueue.Entry::text).toList(), "一条不少,按发生时间");
        assertEquals(T0 + 5, packets.get(0).entries().get(1).ts(), "事发时刻原样带过去");
        assertEquals(B, packets.get(1).entityUuid());
        assertEquals(1, packets.get(1).entries().size());
        assertTrue(box.peek(A).isEmpty() && box.peek(B).isEmpty(), "补发过就不能再发一次");
    }

    @Test
    void droppedEntriesAreReportedInsideThePacket() {
        EventOutbox box = new EventOutbox();
        for (int i = 0; i < EventQueue.DEFAULT_CAP + 3; i++) {
            box.put(A, EventTypes.EVENT, "<event>第" + i + "件</event>", T0 + i, false);
        }

        List<EventQueue.Entry> entries = Companions.outboxPayloads(box, List.of(A), T0 + 1_000).get(0).entries();

        assertEquals(EventQueue.DEFAULT_CAP + 1, entries.size(), "攒满的上限加一条丢弃说明");
        assertEquals(EventQueue.droppedNote(3), entries.get(entries.size() - 1).text());
    }
}
