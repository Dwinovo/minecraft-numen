package com.dwinovo.numen.pathing.spec;

import java.util.EnumMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMaps;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

/**
 * 按坐标的代价与禁令:不看方块看位置。四种用法({@link Use})各自一栏,每栏两样——禁止的格,与额外加价的格;
 * 查不到的格不禁止、不加价。禁止是一件事实,不是一个很大的数,所以单独成集合,不与加价混在一起。
 * 键是 {@link net.minecraft.core.BlockPos#asLong}。
 *
 * <p>不可变;建表走 {@link #builder()} 或 {@link #protect},合并走 {@link #plus}(禁令取并集,同格加价相加)。
 * 导航自己的目标格用 {@link #protect}:别挖自己要站、要够的那格,也别拿方块把它埋了——这是规划的正确性约束,不是权限。
 */
public final class PositionCosts {

    /** 一格被用到的方式。 */
    public enum Use {
        /** 站上去:这一格是脚下踩着的那一格。 */
        STAND,
        /** 身体占着这一格经过。 */
        PASS,
        /** 挖掉这一格。 */
        DIG,
        /** 往这一格里放下一块留在世界上的方块。倒水接坠落不算:水在同一步里就收回。 */
        PLACE
    }

    public static final PositionCosts EMPTY = new PositionCosts(new EnumMap<>(Use.class), new EnumMap<>(Use.class));

    private final Map<Use, LongSet> forbidden;
    private final Map<Use, Long2DoubleMap> extra;

    private PositionCosts(Map<Use, LongSet> forbidden, Map<Use, Long2DoubleMap> extra) {
        this.forbidden = forbidden;
        this.extra = extra;
    }

    /** 禁挖禁放这些格。 */
    public static PositionCosts protect(LongSet cells) {
        Builder b = builder();
        cells.forEach((long cell) -> b.forbid(Use.DIG, cell).forbid(Use.PLACE, cell));
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 这一格禁不禁止这样用。 */
    public boolean forbids(Use use, long cell) {
        LongSet cells = forbidden.get(use);
        return cells != null && cells.contains(cell);
    }

    /** 这样用这一格额外加的价钱;没登记是 0。 */
    public double extra(Use use, long cell) {
        Long2DoubleMap costs = extra.get(use);
        return costs == null ? 0 : costs.get(cell);
    }

    public boolean isEmpty() {
        return forbidden.isEmpty() && extra.isEmpty();
    }

    /** 两张表叠加:禁令取并集,同一格同一栏的加价相加。 */
    public PositionCosts plus(PositionCosts other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        Builder b = builder();
        b.addAll(this);
        b.addAll(other);
        return b.build();
    }

    public static final class Builder {
        private final EnumMap<Use, LongOpenHashSet> forbidden = new EnumMap<>(Use.class);
        private final EnumMap<Use, Long2DoubleOpenHashMap> extra = new EnumMap<>(Use.class);

        private Builder() {}

        public Builder forbid(Use use, long cell) {
            forbidden.computeIfAbsent(use, u -> new LongOpenHashSet()).add(cell);
            return this;
        }

        /**
         * 这样用这一格额外加价。
         *
         * @throws IllegalArgumentException 加价为负或不是有限数:禁止用 {@link #forbid},不用无穷大表示
         */
        public Builder add(Use use, long cell, double cost) {
            if (!(cost >= 0) || Double.isInfinite(cost)) {
                throw new IllegalArgumentException("加价要是非负的有限数,禁止用 forbid:" + cost);
            }
            extra.computeIfAbsent(use, u -> new Long2DoubleOpenHashMap()).addTo(cell, cost);
            return this;
        }

        private void addAll(PositionCosts table) {
            table.forbidden.forEach((use, cells) -> cells.forEach((long cell) -> forbid(use, cell)));
            table.extra.forEach((use, costs) -> costs.long2DoubleEntrySet()
                    .forEach(e -> add(use, e.getLongKey(), e.getDoubleValue())));
        }

        public PositionCosts build() {
            EnumMap<Use, LongSet> f = new EnumMap<>(Use.class);
            forbidden.forEach((use, cells) -> f.put(use, LongSets.unmodifiable(new LongOpenHashSet(cells))));
            EnumMap<Use, Long2DoubleMap> e = new EnumMap<>(Use.class);
            extra.forEach((use, costs) -> e.put(use, Long2DoubleMaps.unmodifiable(new Long2DoubleOpenHashMap(costs))));
            return f.isEmpty() && e.isEmpty() ? EMPTY : new PositionCosts(f, e);
        }
    }
}
