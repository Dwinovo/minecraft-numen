package com.dwinovo.numen.api.seat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 一只同伴的座位上发出去的事件,留到客户端确认收到({@link #ack})。每条盖一个递增的序号;客户端断线重连时报上它收到的最后一号,
 * 从下一号补({@link #after})。满了先丢最老的并记账({@code onEvicted}):客户端很久不来确认,不设上限就会涨下去,但丢弃不能无声。
 *
 * <p>线程:任何线程,各方法自己串行。
 */
final class Outbox {

    private final int cap;
    private final Consumer<SeatEvent> onEvicted;
    private final List<SeatEvent> kept = new ArrayList<>();
    private long last;

    /**
     * @param cap       最多留几条
     * @param onEvicted 满了被丢掉的那条
     */
    Outbox(int cap, Consumer<SeatEvent> onEvicted) {
        this.cap = Math.max(1, cap);
        this.onEvicted = onEvicted;
    }

    /** 收一条,盖上下一个序号并返回盖好的。 */
    synchronized SeatEvent add(SeatEvent event) {
        SeatEvent stamped = event.withSeq(++last);
        kept.add(stamped);
        while (kept.size() > cap) {
            onEvicted.accept(kept.removeFirst());
        }
        return stamped;
    }

    /** 序号大于 {@code seq} 的,按先后。 */
    synchronized List<SeatEvent> after(long seq) {
        return kept.stream().filter(e -> e.seq() > seq).toList();
    }

    /** 客户端收到了 {@code seq} 号及以前的:这些不再留。 */
    synchronized void ack(long seq) {
        kept.removeIf(e -> e.seq() <= seq);
    }

    /** 发过的最后一个序号;一条都没发过是 0。 */
    synchronized long last() {
        return last;
    }

    /** 还留着的最早一个序号;一条都没留是 {@code last() + 1}。 */
    synchronized long first() {
        return kept.isEmpty() ? last + 1 : kept.getFirst().seq();
    }
}
