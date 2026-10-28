package com.dwinovo.numen.pathing.search;

import java.util.Arrays;

/**
 * 抬高估价:LSS-LRTA* 的学习一步 [Koenig &amp; Sun 2009]。一段搜索没搜到这段的终点,展开过的节点({@code expanded})都不是终点,
 * 目标要经过它们没展开的边界才到得了,所以它们的估价至少是"沿着搜到的边走到边界上某个节点,再加上那个节点自己的估价"里最小的:
 * <pre>h'(s) = min over 边界上的 b ( 沿展开过的节点从 s 走到 b 的最短价钱 + h(b) )</pre>
 * 从边界往里做一次 Dijkstra。每一步的走法都只会让真实代价更高,所以 {@code h'} 仍是下界(不会比真实代价高),又不低于原来的 {@code h}。
 * 边界一个都没有(展开过的节点就是一切可达的地方)时,展开过的节点都得到无穷大:这一趟的规格下从这里到不了。
 *
 * <p>边只收"从展开过的节点出去的":每展开一个节点,它的每一步成立的走法记一条(落点、价钱)。
 */
final class Learning {

    private Learning() {}

    /**
     * @param expanded 节点展开过没有;没展开的就是边界
     * @param h        每个节点现在的估价
     * @param nodes    节点数
     * @param from     边的起点,必须是展开过的节点
     * @param to       边的落点
     * @param cost     边的价钱
     * @param edges    边数
     * @return 每个节点抬高之后的估价(边界不变,展开过的不低于原来的)
     */
    static double[] raise(boolean[] expanded, double[] h, int nodes, int[] from, int[] to, double[] cost, int edges) {
        // 按落点分组:谁能一步走到它
        int[] start = new int[nodes + 1];
        for (int e = 0; e < edges; e++) {
            start[to[e] + 1]++;
        }
        for (int n = 0; n < nodes; n++) {
            start[n + 1] += start[n];
        }
        int[] into = new int[edges];
        int[] fill = Arrays.copyOf(start, nodes);
        for (int e = 0; e < edges; e++) {
            into[fill[to[e]]++] = e;
        }

        double[] raised = new double[nodes];
        Queue queue = new Queue(raised, nodes);
        for (int n = 0; n < nodes; n++) {
            if (expanded[n]) {
                raised[n] = Double.POSITIVE_INFINITY;
            } else {
                raised[n] = h[n];
                if (Double.isFinite(h[n])) {
                    queue.lower(n);
                }
            }
        }
        while (!queue.isEmpty()) {
            int top = queue.pop();
            for (int i = start[top]; i < start[top + 1]; i++) {
                int e = into[i];
                int s = from[e];
                double through = raised[top] + cost[e];
                if (through < raised[s]) {
                    raised[s] = through;
                    queue.lower(s);
                }
            }
        }
        for (int n = 0; n < nodes; n++) {
            if (expanded[n]) {
                // 估价不一致时(旧路折扣、憋气的档)走出来的价钱可能低于原估价:只升不降
                raised[n] = Math.max(raised[n], h[n]);
            }
        }
        return raised;
    }

    /**
     * 按 {@code raised} 排的二叉堆,存节点下标,支持原地降键:一个节点在堆里至多一份,不为每次降键装一个对象
     * (一次学习有几十万条边,不为每条边装一个对象、也不在堆里留过期的副本)。
     */
    private static final class Queue {

        private final double[] value;
        private final int[] items;
        /** 节点在堆里的位置;不在堆里为 -1。 */
        private final int[] at;
        private int size;

        Queue(double[] value, int nodes) {
            this.value = value;
            this.items = new int[nodes];
            this.at = new int[nodes];
            Arrays.fill(at, -1);
        }

        boolean isEmpty() {
            return size == 0;
        }

        /** {@code node} 的值刚降过(或第一次给):不在堆里就放进来,在就上浮。 */
        void lower(int node) {
            if (at[node] < 0) {
                items[size] = node;
                at[node] = size;
                size++;
            }
            up(at[node]);
        }

        int pop() {
            int top = items[0];
            at[top] = -1;
            size--;
            if (size > 0) {
                items[0] = items[size];
                at[items[0]] = 0;
                down(0);
            }
            return top;
        }

        private void up(int i) {
            int n = items[i];
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (value[items[parent]] <= value[n]) {
                    break;
                }
                items[i] = items[parent];
                at[items[i]] = i;
                i = parent;
            }
            items[i] = n;
            at[n] = i;
        }

        private void down(int i) {
            int n = items[i];
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && value[items[child + 1]] < value[items[child]]) {
                    child++;
                }
                if (value[items[child]] >= value[n]) {
                    break;
                }
                items[i] = items[child];
                at[items[i]] = i;
                i = child;
            }
            items[i] = n;
            at[n] = i;
        }
    }
}
