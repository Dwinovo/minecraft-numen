package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.plan.MoveKind;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;

/**
 * 这一趟的记忆:只活这一趟,走完或换了目标就丢。记两样,搜索与执行共用这一份:
 * <ul>
 *   <li><b>抬高过的估价</b>:一段搜索没搜到这段的终点,它展开过的节点比估价更远({@link Learning}),每一格的增量记在这里,
 *       以后所有搜索的估价都是"原估价 + 这里的增量"({@link Estimate}),同一处死路不会撞第二次。LRTA* 一族在有限的世界里保证到达
 *       就靠这个 [Korf 1990;Koenig &amp; Sun 2009]。增量是对着这一趟的目标算的,换了目标就失效({@link #forgetRaised});</li>
 *   <li><b>走不通的步</b>:执行里一步走不下去或卡住,这一步(走法、起点、落点)记为不通,以后的搜索不再走它(D* 一族发现与世界
 *       不符就把那一处改掉再规划 [Stentz 1994])。这是世界的事实,换了目标仍然有效。</li>
 * </ul>
 *
 * <p>主线程写;搜索在工作线程上读的是派发那一刻冻下来的一份({@link #frozen}),写不影响在飞的搜索。
 */
public final class TripMemory {

    private final Long2DoubleOpenHashMap raised = new Long2DoubleOpenHashMap();
    /** 走不通的步:起点 → 依次是走法的序号与落点,两个一组。 */
    private final Long2ObjectOpenHashMap<LongArrayList> blocked = new Long2ObjectOpenHashMap<>();
    private Frozen frozen = Frozen.NONE;
    private boolean changed;

    /** 估价在 {@code cell} 这一格抬高到比原估价多 {@code by}(只升不降);真的升高了为 true。 */
    public boolean raise(long cell, double by) {
        if (by > raised.get(cell)) {
            raised.put(cell, by);
            changed = true;
            return true;
        }
        return false;
    }

    /** 一批格的增量;有哪一格真的升高了为 true。 */
    public boolean raise(Long2DoubleMap increments) {
        boolean raised = false;
        for (Long2DoubleMap.Entry entry : increments.long2DoubleEntrySet()) {
            raised |= raise(entry.getLongKey(), entry.getDoubleValue());
        }
        return raised;
    }

    /** 这一步在这一趟里走不通。 */
    public void block(MoveKind kind, BlockPos from, BlockPos to) {
        LongArrayList steps = blocked.computeIfAbsent(from.asLong(), k -> new LongArrayList());
        for (int i = 0; i < steps.size(); i += 2) {
            if (steps.getLong(i) == kind.ordinal() && steps.getLong(i + 1) == to.asLong()) {
                return;
            }
        }
        steps.add(kind.ordinal());
        steps.add(to.asLong());
        changed = true;
    }

    /** 目标换了:抬高过的估价是对着旧目标算的,不再作数;走不通的步是世界的事实,留着。 */
    public void forgetRaised() {
        if (!raised.isEmpty()) {
            raised.clear();
            changed = true;
        }
    }

    /** 抬高过的格数。 */
    public int raisedCells() {
        return raised.size();
    }

    /** 走不通的步数。 */
    public int blockedSteps() {
        int n = 0;
        for (LongArrayList steps : blocked.values()) {
            n += steps.size() / 2;
        }
        return n;
    }

    /** 此刻记着的东西冻下来的一份,交给在工作线程上跑的搜索;没变过就还是上一份。 */
    public Frozen frozen() {
        if (changed) {
            Long2ObjectOpenHashMap<long[]> steps = new Long2ObjectOpenHashMap<>(blocked.size());
            blocked.forEach((from, list) -> steps.put(from, list.toLongArray()));
            frozen = new Frozen(new Long2DoubleOpenHashMap(raised), steps);
            changed = false;
        }
        return frozen;
    }

    /** 冻下来的一份:只读,多个线程同时读是安全的。 */
    public static final class Frozen {

        /** 什么都没记。 */
        public static final Frozen NONE = new Frozen(new Long2DoubleOpenHashMap(), new Long2ObjectOpenHashMap<>());

        private final Long2DoubleOpenHashMap raised;
        private final Long2ObjectOpenHashMap<long[]> blocked;

        private Frozen(Long2DoubleOpenHashMap raised, Long2ObjectOpenHashMap<long[]> blocked) {
            this.raised = raised;
            this.blocked = blocked;
        }

        /** {@code cell}({@link BlockPos#asLong} 编码)的估价比原来多多少。 */
        public double raised(long cell) {
            return raised.isEmpty() ? 0 : raised.get(cell);
        }

        /** 从 {@code from} 用 {@code kind} 走到 {@code to} 这一步记为走不通了。 */
        public boolean blocks(MoveKind kind, long from, long to) {
            if (blocked.isEmpty()) {
                return false;
            }
            long[] steps = blocked.get(from);
            if (steps == null) {
                return false;
            }
            for (int i = 0; i < steps.length; i += 2) {
                if (steps[i] == kind.ordinal() && steps[i + 1] == to) {
                    return true;
                }
            }
            return false;
        }

        public boolean isEmpty() {
            return raised.isEmpty() && blocked.isEmpty();
        }
    }
}
