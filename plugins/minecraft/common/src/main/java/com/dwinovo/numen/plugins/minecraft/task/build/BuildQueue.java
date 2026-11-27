package com.dwinovo.numen.plugins.minecraft.task.build;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 这一次要放的格的队列:按给定的顺序排好,从队头一格一格取;取出来站不住的排到队尾,只重试这一次。
 *
 * <p>只管先后与"重试过了"这个记号,不认识方块也不碰世界:一格站不站得住由调用方去问方块,问到站不住时来这里
 * {@link #later} 一下——还没重试过就排到队尾、等别的格先放完,重试过了就放弃,交回 {@code false}。
 */
final class BuildQueue<T> {

    private final ArrayDeque<T> waiting;
    private final Set<T> retried = new HashSet<>();

    BuildQueue(Collection<T> cells, Comparator<? super T> order) {
        List<T> sorted = cells.stream().sorted(order).toList();
        this.waiting = new ArrayDeque<>(sorted);
    }

    /** 取队头那一格;队列空了是 null。 */
    T next() {
        return waiting.pollFirst();
    }

    boolean isEmpty() {
        return waiting.isEmpty();
    }

    /**
     * 这一格此刻站不住:没重试过就排到队尾并记下,交回 {@code true};已经重试过了不再排,交回 {@code false},调用方放弃它。
     */
    boolean later(T cell) {
        if (!retried.add(cell)) {
            return false;
        }
        waiting.addLast(cell);
        return true;
    }
}
