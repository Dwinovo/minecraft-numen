package com.dwinovo.numen.pathing.search;

import java.util.Arrays;

/**
 * 按总价排的二叉堆,存的是 {@link Nodes} 的下标,支持原地降键。总价平手时估价小的在前(同总价里 g 更大的,离目标更近的先展开),
 * 平地上一大片同总价的节点不再整片展开:总价低于最优路线的节点怎么排都得展开,顺序只影响总价恰等于最优价的那一层,
 * 所以搜出的总价不变;最优路线有多条等价时,选到哪一条取决于这个顺序。
 *
 * <p>总价差在 {@link #TIE} 以内算平手。平地上同一价钱的路线加的顺序不同,浮点和差在 1e-12 上下:用 f 直接比,平手大半被噪声拆开,
 * 所以堆按取整到这个刻度的总价比({@link Nodes#key}),这一格里再比估价。
 */
final class OpenSet {

    static final double TIE = 1e-6;

    private final Nodes nodes;
    private int[] items = new int[256];
    private int size;

    OpenSet(Nodes nodes) {
        this.nodes = nodes;
    }

    boolean isEmpty() {
        return size == 0;
    }

    /** 放进来(不在堆里的节点)。 */
    void push(int id) {
        if (size == items.length) {
            items = Arrays.copyOf(items, size * 2);
        }
        items[size] = id;
        nodes.heapIndex[id] = size;
        nodes.key[id] = key(nodes.f[id]);
        size++;
        up(nodes.heapIndex[id]);
    }

    /** 在堆里的降键,不在堆里的(没进过、或已出过堆)放进来。 */
    void pushOrUpdate(int id) {
        if (nodes.heapIndex[id] >= 0) {
            nodes.key[id] = key(nodes.f[id]);
            up(nodes.heapIndex[id]);
        } else {
            push(id);
        }
    }

    private static long key(double f) {
        return Math.round(f / TIE);
    }

    private boolean before(int a, int b) {
        long[] key = nodes.key;
        if (key[a] != key[b]) {
            return key[a] < key[b];
        }
        // 同总价先展开离目标近的;估价也平手时先展开已走得远的(沿着走下去,不在整片平台上铺开)
        double[] h = nodes.h;
        return h[a] != h[b] ? h[a] < h[b] : nodes.g[a] > nodes.g[b];
    }

    int pop() {
        int top = items[0];
        size--;
        items[0] = items[size];
        nodes.heapIndex[items[0]] = 0;
        if (size > 0) {
            down(0);
        }
        nodes.heapIndex[top] = -1;
        return top;
    }

    private void up(int i) {
        int n = items[i];
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!before(n, items[parent])) {
                break;
            }
            items[i] = items[parent];
            nodes.heapIndex[items[i]] = i;
            i = parent;
        }
        items[i] = n;
        nodes.heapIndex[n] = i;
    }

    private void down(int i) {
        int n = items[i];
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && before(items[child + 1], items[child])) {
                child++;
            }
            if (!before(items[child], n)) {
                break;
            }
            items[i] = items[child];
            nodes.heapIndex[items[i]] = i;
            i = child;
        }
        items[i] = n;
        nodes.heapIndex[n] = i;
    }
}
