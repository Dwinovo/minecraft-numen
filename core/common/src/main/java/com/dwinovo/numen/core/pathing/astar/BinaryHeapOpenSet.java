package com.dwinovo.numen.core.pathing.astar;

import java.util.Arrays;

/**
 * open set 的数组二叉小顶堆实现:按 combinedCost 排序,
 * 借节点上缓存的 heapPosition 支持 decrease-key(update)。
 * 下标从 1 开始,父子关系用移位运算。
 */
public final class BinaryHeapOpenSet {

    /** 初始容量(2^10)。 */
    private static final int INITIAL_CAPACITY = 1024;

    private PathNode[] array;
    private int size;

    public BinaryHeapOpenSet() {
        this(INITIAL_CAPACITY);
    }

    public BinaryHeapOpenSet(int size) {
        this.size = 0;
        this.array = new PathNode[size];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** 插入:挂到末尾后上浮。 */
    public void insert(PathNode value) {
        if (size >= array.length - 1) {
            array = Arrays.copyOf(array, array.length << 1);
        }
        size++;
        value.heapPosition = size;
        array[size] = value;
        update(value);
    }

    /** decrease-key:combinedCost 变小后从当前位置上浮。 */
    public void update(PathNode val) {
        int index = val.heapPosition;
        int parentInd = index >>> 1;
        double cost = val.combinedCost;
        PathNode parentNode = array[parentInd];
        while (index > 1 && parentNode.combinedCost > cost) {
            array[index] = parentNode;
            array[parentInd] = val;
            val.heapPosition = parentInd;
            parentNode.heapPosition = index;
            index = parentInd;
            parentInd = index >>> 1;
            parentNode = array[parentInd];
        }
    }

    /** 弹出 combinedCost 最小的节点:末尾补根后下沉。 */
    public PathNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException("空堆不可弹出");
        }
        PathNode result = array[1];
        PathNode val = array[size];
        array[1] = val;
        val.heapPosition = 1;
        array[size] = null;
        size--;
        result.heapPosition = -1;
        if (size < 2) {
            return result;
        }
        int index = 1;
        int smallerChild = 2;
        double cost = val.combinedCost;
        do {
            PathNode smallerChildNode = array[smallerChild];
            double smallerChildCost = smallerChildNode.combinedCost;
            if (smallerChild < size) {
                PathNode rightChildNode = array[smallerChild + 1];
                double rightChildCost = rightChildNode.combinedCost;
                if (smallerChildCost > rightChildCost) {
                    smallerChild++;
                    smallerChildCost = rightChildCost;
                    smallerChildNode = rightChildNode;
                }
            }
            if (cost <= smallerChildCost) {
                break;
            }
            array[index] = smallerChildNode;
            array[smallerChild] = val;
            val.heapPosition = smallerChild;
            smallerChildNode.heapPosition = index;
            index = smallerChild;
        } while ((smallerChild <<= 1) <= size);
        return result;
    }
}
