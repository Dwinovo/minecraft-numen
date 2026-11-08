package com.dwinovo.numen.pathing.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 按方块状态查的表:一种状态算一次、之后只读。状态是驻留对象,表键就是状态本身,几个搜索线程同时查是安全的。
 *
 * <p>规划里每展开一个节点要查几百次(碰撞箱、语义种类),并发哈希表每次查都要算哈希、过一遍桶,前面再放一张直接映射的小表:
 * 状态按它自己的哈希落进一个槽,槽里放着"状态加结论",对上了就是结论,对不上才去并发哈希表里取、再顶掉槽里的。槽里的
 * 条目只有 final 字段,线程间不加锁地读写也是安全的(读到的要么是旧条目、要么是写完的新条目)。
 */
final class StateTable<T> {

    private static final int SLOTS = 1 << 12;

    private record Entry<T>(BlockState state, T value) {}

    private final Function<BlockState, T> compute;
    private final ConcurrentHashMap<BlockState, T> all = new ConcurrentHashMap<>();
    private final Entry<?>[] slots = new Entry<?>[SLOTS];

    StateTable(Function<BlockState, T> compute) {
        this.compute = compute;
    }

    @SuppressWarnings("unchecked")
    T of(BlockState state) {
        int slot = System.identityHashCode(state) & (SLOTS - 1);
        Entry<?> entry = slots[slot];
        if (entry != null && entry.state == state) {
            return (T) entry.value;
        }
        T value = all.computeIfAbsent(state, compute);
        slots[slot] = new Entry<>(state, value);
        return value;
    }
}
