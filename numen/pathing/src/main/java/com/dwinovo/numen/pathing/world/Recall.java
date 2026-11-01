package com.dwinovo.numen.pathing.world;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;

/**
 * 一次搜索的记事本:同一格的结论(脚落在多高、身体在这个节点上怎么待着、旁边有没有伤身的格)在展开相邻的几个节点时
 * 要被反复问到,每格算一次记下来。
 *
 * <ul>
 *   <li><b>叠了改动的世界</b>:记事本挂在搜索读的快照上({@link Source}),记的是快照的结论。叠了改动的视图也交得出它,但每种结论
 *       说明自己读哪几格({@link Reach}):改动落在那一列那一段之内的,结论变了,照常现算、不记;落在之外的,结论与快照上的一模一样,
 *       照用记事本——挖开一格、垫下一块只影响它自己那一列;</li>
 *   <li><b>读到了没加载的列</b>:搜索要知道一步的前提读没读到快照之外的列({@link #unloaded}),算一个结论时读到了,
 *       这一位随结论记下,取回时照样记上,与现算一模一样;</li>
 *   <li><b>一具身体</b>:结论随身体尺寸而变,记事本只答建它时那一具身体的问;</li>
 *   <li><b>大小封顶的直接映射表</b>(同原版 {@code PathTypeCache}):每种结论一张,每格按坐标的哈希落进一个槽,撞了就顶掉
 *       旧的。不逐格建对象、大小封顶 {@value #SLOTS} 槽,从小起步、顶掉的次数多了才翻倍——逐格建表的缓存会把垃圾回收撑爆。
 *       表只是缓存,丢了就现算,槽数多少不改变任何结论。</li>
 * </ul>
 *
 * <p>一次搜索一本,只在跑这次搜索的线程上用。
 */
public final class Recall {

    /** 每张表最多的槽数,同原版 {@code PathTypeCache}:展开一个节点问到的格都挨着它,再多四倍的槽也不见更快。 */
    private static final int SLOTS = 1 << 12;
    /** 每张表起手的槽数:几十个节点就到的搜索用不满,不为它整张分配出去;被顶掉的次数多过槽数就翻倍。 */
    private static final int INITIAL_SLOTS = 1 << 8;
    private static final byte FILLED = 1;
    private static final byte UNLOADED = 2;
    private static final AtomicInteger FACTS = new AtomicInteger();

    /** 带着这次搜索的记事本的视图;没有记事本(活世界)为 null。 */
    public interface Source {
        Recall recall();

        /** 这份视图相对记事本记的快照,在 {@code (x, z)} 这一列 {@code yMin} 到 {@code yMax} 之间改过格子。 */
        boolean changed(int x, int yMin, int yMax, int z);
    }

    /**
     * 一种结论读哪几格:{@code (x, y, z)} 的结论只由 {@code (x, z)} 这一列、从 {@code y - below} 到 {@code y + above} 之间的格子决定。
     * 说少了,叠了改动的视图上会用到过时的结论;说多了只是少用几次记事本。
     */
    public record Reach(int below, int above) {}

    /** 一种按格的结论怎么算。 */
    @FunctionalInterface
    public interface Measure<T> {
        T at(BlockGetter level, BodyStats body, int x, int y, int z);
    }

    /** 一种按格、结果是一个高度的结论怎么算。 */
    @FunctionalInterface
    public interface Height {
        double at(BlockGetter level, BodyStats body, int x, int y, int z);
    }

    /** 一种按格的结论:算法,加上它在每本记事本里的那张表。 */
    public static final class Fact<T> {
        private final int index = FACTS.getAndIncrement();
        private final Measure<T> measure;
        private final Reach reach;

        public Fact(Measure<T> measure, Reach reach) {
            this.measure = measure;
            this.reach = reach;
        }

        /** 这一格的结论:{@code level} 带着记事本、改动又没落在这一结论读的格子里时每格只算一次,否则现算。 */
        @SuppressWarnings("unchecked")
        public T at(BlockGetter level, BodyStats body, int x, int y, int z) {
            Recall recall = in(level, body, x, y, z, reach);
            if (recall == null) {
                return measure.at(level, body, x, y, z);
            }
            Table table = recall.table(index, false);
            long cell = BlockPos.asLong(x, y, z);
            int slot = table.slot(cell);
            if (table.holds(slot, cell)) {
                recall.recall(table, slot);
                return (T) table.objects[slot];
            }
            boolean outer = recall.begin();
            T value = measure.at(level, body, x, y, z);
            table.objects[slot] = value;
            recall.store(table, slot, cell, outer);
            return value;
        }
    }

    /** 一种按格、结果是一个高度的结论。 */
    public static final class HeightFact {
        private final int index = FACTS.getAndIncrement();
        private final Height measure;
        private final Reach reach;

        public HeightFact(Height measure, Reach reach) {
            this.measure = measure;
            this.reach = reach;
        }

        /** 同 {@link Fact#at}。 */
        public double at(BlockGetter level, BodyStats body, int x, int y, int z) {
            Recall recall = in(level, body, x, y, z, reach);
            if (recall == null) {
                return measure.at(level, body, x, y, z);
            }
            Table table = recall.table(index, true);
            long cell = BlockPos.asLong(x, y, z);
            int slot = table.slot(cell);
            if (table.holds(slot, cell)) {
                recall.recall(table, slot);
                return table.heights[slot];
            }
            boolean outer = recall.begin();
            double value = measure.at(level, body, x, y, z);
            table.heights[slot] = value;
            recall.store(table, slot, cell, outer);
            return value;
        }
    }

    private final BodyStats body;
    private Table[] tables = new Table[0];
    /** 这一次前提判断读到了没加载的列。 */
    private boolean unloaded;

    /** 给一次搜索、这一具身体开一本。 */
    public Recall(BodyStats body) {
        this.body = body;
    }

    /** 读世界的那一层读到了没加载的列时记一笔。 */
    public void touchUnloaded() {
        unloaded = true;
    }

    /** 从上次 {@link #clearUnloaded} 以来读到过没加载的列(连同从记事本里取回的结论当初读到的)。 */
    public boolean unloaded() {
        return unloaded;
    }

    public void clearUnloaded() {
        unloaded = false;
    }

    private static Recall in(BlockGetter level, BodyStats body, int x, int y, int z, Reach reach) {
        if (level instanceof Source source) {
            Recall recall = source.recall();
            return recall != null && recall.body == body && !source.changed(x, y - reach.below, y + reach.above, z) ? recall : null;
        }
        return null;
    }

    private Table table(int index, boolean heights) {
        if (index >= tables.length) {
            tables = Arrays.copyOf(tables, index + 1);
        }
        Table table = tables[index];
        if (table == null) {
            table = new Table(heights);
            tables[index] = table;
        }
        return table;
    }

    /** 取回一个记下的结论:它当初读到了没加载的列,这一次也算读到了。 */
    private void recall(Table table, int slot) {
        if ((table.meta[slot] & UNLOADED) != 0) {
            unloaded = true;
        }
    }

    /** 开始现算一个结论:单独看它读没读到没加载的列。交回之前的那一位。 */
    private boolean begin() {
        boolean outer = unloaded;
        unloaded = false;
        return outer;
    }

    /** 记下刚算完的结论,连同它读没读到没加载的列;再并回之前的那一位。 */
    private void store(Table table, int slot, long cell, boolean outer) {
        table.put(slot, cell, (byte) (FILLED | (unloaded ? UNLOADED : 0)));
        unloaded |= outer;
    }

    /** 一种结论的一张表。结果是对象的放 {@link #objects},是高度的放 {@link #heights},只建用得上的那一份。 */
    private static final class Table {
        private final boolean heightsTable;
        long[] keys;
        byte[] meta;
        Object[] objects;
        double[] heights;
        private int mask;
        /** 顶掉了别的格的次数,到了槽数就翻倍。 */
        private int evictions;

        Table(boolean heights) {
            this.heightsTable = heights;
            allocate(INITIAL_SLOTS);
        }

        private void allocate(int slots) {
            keys = new long[slots];
            meta = new byte[slots];
            objects = heightsTable ? null : new Object[slots];
            heights = heightsTable ? new double[slots] : null;
            mask = slots - 1;
            evictions = 0;
        }

        int slot(long cell) {
            return (int) HashCommon.mix(cell) & mask;
        }

        boolean holds(int slot, long cell) {
            return meta[slot] != 0 && keys[slot] == cell;
        }

        /** 把 {@code cell} 的结论记进 {@code slot}(值已经放进 {@link #objects} 或 {@link #heights}):顶掉别的格太多就换一张大一倍的空表。 */
        void put(int slot, long cell, byte flags) {
            boolean evicts = meta[slot] != 0 && keys[slot] != cell;
            keys[slot] = cell;
            meta[slot] = flags;
            if (evicts && ++evictions >= keys.length && keys.length < SLOTS) {
                allocate(keys.length * 2);
            }
        }
    }
}
