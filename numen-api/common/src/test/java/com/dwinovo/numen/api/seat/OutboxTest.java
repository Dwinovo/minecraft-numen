package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 发件箱:序号递增,留到确认,续传从报上的序号之后补,满了先丢最老的并说出来。 */
@Tag("mc")
class OutboxTest {

    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static SeatEvent event(String text) {
        return SeatEvent.of(HER, 1, new EventQueue.Entry("hungry", text, 1L, false), "", 0);
    }

    private static List<String> texts(List<SeatEvent> events) {
        return events.stream().map(SeatEvent::text).toList();
    }

    @Test
    void eachEventGetsTheNextSeqAndAfterReturnsWhatCameLater() {
        Outbox outbox = new Outbox(10, dropped -> {});
        assertEquals(0, outbox.last());
        assertEquals(1, outbox.first());
        assertEquals(1, outbox.add(event("a")).seq());
        assertEquals(2, outbox.add(event("b")).seq());
        assertEquals(3, outbox.add(event("c")).seq());
        assertEquals(List.of("a", "b", "c"), texts(outbox.after(0)));
        assertEquals(List.of("c"), texts(outbox.after(2)));
        assertEquals(List.of(), texts(outbox.after(3)));
        assertEquals(3, outbox.last());
    }

    @Test
    void anAcknowledgedEventIsNotKeptButTheSeqsKeepCounting() {
        Outbox outbox = new Outbox(10, dropped -> {});
        outbox.add(event("a"));
        outbox.add(event("b"));
        outbox.ack(1);
        assertEquals(List.of("b"), texts(outbox.after(0)));
        assertEquals(2, outbox.first());
        outbox.ack(2);
        assertEquals(List.of(), texts(outbox.after(0)));
        assertEquals(3, outbox.first(), "nothing kept: the next one to come");
        assertEquals(3, outbox.add(event("c")).seq(), "the numbering goes on");
    }

    @Test
    void whenFullTheOldestIsDroppedAndSaidSoNotSilently() {
        List<SeatEvent> dropped = new ArrayList<>();
        Outbox outbox = new Outbox(2, dropped::add);
        outbox.add(event("a"));
        outbox.add(event("b"));
        outbox.add(event("c"));
        assertEquals(List.of("a"), texts(dropped));
        assertEquals(List.of("b", "c"), texts(outbox.after(0)));
        assertEquals(2, outbox.first(), "the one who asks can tell the first events are gone");
    }
}
