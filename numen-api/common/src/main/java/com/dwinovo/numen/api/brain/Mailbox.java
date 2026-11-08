package com.dwinovo.numen.api.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 每位玩家一个有界信箱:上行的东西在网络线程上读懂、验过形状就投进来,服务端主线程每刻从每个信箱最多取几条处理。
 * 一个客户端狂发,只会把自己的信箱塞满、满了的丢弃,不会把主线程的队排长。
 *
 * <p>线程:{@link #offer} 任何线程;{@link #drain} 主线程。
 */
public final class Mailbox<T> {

    private static final class Box<T> {
        final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
    }

    private final int capacity;
    private final Map<UUID, Box<T>> boxes = new ConcurrentHashMap<>();

    public Mailbox(int capacity) {
        this.capacity = capacity;
    }

    /** 投进这位玩家的信箱;满了不收,返回 false。 */
    public boolean offer(UUID player, T item) {
        Box<T> box = boxes.computeIfAbsent(player, id -> new Box<>());
        if (box.size.incrementAndGet() > capacity) {
            box.size.decrementAndGet();
            return false;
        }
        box.queue.add(item);
        return true;
    }

    /** 每位玩家的信箱里最多取 {@code perPlayer} 条,按投进来的先后交给 {@code handler}。 */
    public void drain(int perPlayer, BiConsumer<UUID, T> handler) {
        for (Map.Entry<UUID, Box<T>> e : boxes.entrySet()) {
            List<T> taken = new ArrayList<>(perPlayer);
            Box<T> box = e.getValue();
            for (int i = 0; i < perPlayer; i++) {
                T item = box.queue.poll();
                if (item == null) {
                    break;
                }
                box.size.decrementAndGet();
                taken.add(item);
            }
            taken.forEach(item -> handler.accept(e.getKey(), item));
        }
    }

    /** 这位玩家下线了:他信箱里没处理的丢掉。 */
    public void drop(UUID player) {
        boxes.remove(player);
    }

    public void clear() {
        boxes.clear();
    }
}
