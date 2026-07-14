package com.dwinovo.numen.core.pathing.engine;

import java.util.Arrays;

/**
 * A binary min-heap open set ordered by {@link Node#f}, with an O(log n)
 * decrease-key (each node caches its heap index),
 * plus {@link #peekLowest()} for the learning
 * write-back's frontier read.
 *
 * <p>Hand-rolled rather than {@link java.util.PriorityQueue} because A* relaxes
 * the same node many times, each time potentially lowering its key.
 * {@code PriorityQueue} has no decrease-key — you'd either re-insert duplicates
 * (and lazily skip stale pops) or pay an O(n) {@code remove}. Here each node
 * caches its {@link Node#heapIndex}, so lowering a key is a pure O(log n)
 * sift-up with no search. 1-indexed so parent/child math is shifts.
 */
final class OpenSet<E> {

    private static final int INITIAL_CAPACITY = 1024;

    private Node<E>[] array;
    private int size = 0;

    @SuppressWarnings("unchecked")
    OpenSet() {
        array = (Node<E>[]) new Node[INITIAL_CAPACITY];
    }

    boolean isEmpty() {
        return size == 0;
    }

    int size() {
        return size;
    }

    /** Add a node not currently in the set, then sift it up. */
    void insert(Node<E> node) {
        if (size + 1 >= array.length) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        array[size] = node;
        node.heapIndex = size;
        siftUp(size);
    }

    /** Decrease-key: a node already in the set got a lower {@link Node#f}. */
    void update(Node<E> node) {
        siftUp(node.heapIndex);
    }

    /** The lowest-f node WITHOUT removing it. Caller must check {@link #isEmpty()} first. */
    Node<E> peekLowest() {
        return array[1];
    }

    /** Remove and return the lowest-f node. */
    Node<E> removeLowest() {
        Node<E> result = array[1];
        result.heapIndex = -1;
        size--;
        if (size > 0) {
            Node<E> moved = array[size + 1];
            array[size + 1] = null;
            array[1] = moved;
            moved.heapIndex = 1;
            siftDown(1);
        } else {
            array[1] = null;
        }
        return result;
    }

    private void siftUp(int index) {
        Node<E> node = array[index];
        while (index > 1) {
            int parent = index >>> 1;
            Node<E> p = array[parent];
            if (node.f >= p.f) {
                break;
            }
            array[index] = p;
            p.heapIndex = index;
            index = parent;
        }
        array[index] = node;
        node.heapIndex = index;
    }

    private void siftDown(int index) {
        Node<E> node = array[index];
        while (true) {
            int child = index << 1;
            if (child > size) {
                break;
            }
            if (child + 1 <= size && array[child + 1].f < array[child].f) {
                child++;
            }
            Node<E> c = array[child];
            if (node.f <= c.f) {
                break;
            }
            array[index] = c;
            c.heapIndex = index;
            index = child;
        }
        array[index] = node;
        node.heapIndex = index;
    }
}
