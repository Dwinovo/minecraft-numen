package com.dwinovo.numen.pathing.search;

import java.util.Arrays;

/** 按价钱、同价按离起点远近排的二叉堆(估价从目标往外做 Dijkstra 用:{@link Burial});同一格可以进来几次,出来时旧的那次由调用方丢掉。 */
final class CostQueue {
    private double[] costs = new double[256];
    private int[] ties = new int[256];
    private long[] cells = new long[256];
    private int size;

    boolean isEmpty() {
        return size == 0;
    }

    double peekCost() {
        return costs[0];
    }

    void push(double cost, int tie, long cell) {
        if (size == costs.length) {
            costs = Arrays.copyOf(costs, size * 2);
            ties = Arrays.copyOf(ties, size * 2);
            cells = Arrays.copyOf(cells, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (!before(cost, tie, costs[parent], ties[parent])) {
                break;
            }
            move(parent, i);
            i = parent;
        }
        costs[i] = cost;
        ties[i] = tie;
        cells[i] = cell;
    }

    long pop() {
        long top = cells[0];
        size--;
        double cost = costs[size];
        int tie = ties[size];
        long cell = cells[size];
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && before(costs[child + 1], ties[child + 1], costs[child], ties[child])) {
                child++;
            }
            if (!before(costs[child], ties[child], cost, tie)) {
                break;
            }
            move(child, i);
            i = child;
        }
        costs[i] = cost;
        ties[i] = tie;
        cells[i] = cell;
        return top;
    }

    private void move(int from, int to) {
        costs[to] = costs[from];
        ties[to] = ties[from];
        cells[to] = cells[from];
    }

    private static boolean before(double cost, int tie, double otherCost, int otherTie) {
        return cost < otherCost || (cost == otherCost && tie < otherTie);
    }
}
