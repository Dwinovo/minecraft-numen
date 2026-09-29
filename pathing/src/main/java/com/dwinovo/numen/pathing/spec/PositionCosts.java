package com.dwinovo.numen.pathing.spec;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleMaps;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

/**
 * 按坐标的代价与禁令:不看方块看位置。四种用法({@link Use})各自一栏,每栏三样——禁止的格、额外加价的格,以及"只许这些格"
 * (设了就是这一栏别的格一律禁止);查不到的格不禁止、不加价。禁止是一件事实,不是一个很大的数,所以单独成集合,不与加价混在一起。
 * 键是 {@link net.minecraft.core.BlockPos#asLong}。
 *
 * <p>禁止的格有两种给法:逐格({@link Builder#forbid(Use, long)},一格一个键)与整片({@link Builder#forbid(Use, Region)}):
 * 宿主的一片地方可能有几百万格,逐格展开进集合既慢又占地方,所以整片只交一个"这一格在不在"的判定,模块逐格问它,不认识它怎么存。
 *
 * <p>不可变;建表走 {@link #builder()} 或 {@link #protect},合并走 {@link #plus}(禁令取并集,同格加价相加,"只许"取交集)。
 * 导航自己的目标格用 {@link #protect}:别挖自己要站、要够的那格,也别拿方块把它埋了——这是规划的正确性约束,不是权限。
 * "只许"给宿主写一趟路的承诺:只许挖、只许放计划里的那几格,重搜时自然只在承诺里找({@link Builder#confine})。
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

    /**
     * 一片格子,只回答一格在不在里面(键是 {@link net.minecraft.core.BlockPos#asLong})。宿主用自己的表示实现它——比如按 16³ 小节
     * 存的位图,一次查表加一次取位——模块只问这一句。搜索线程逐格问,所以实现要不可变(或线程安全),而且问一格要快。
     */
    @FunctionalInterface
    public interface Region {
        boolean contains(long cell);
    }

    public static final PositionCosts EMPTY = new PositionCosts(new EnumMap<>(Use.class), new EnumMap<>(Use.class),
            new EnumMap<>(Use.class), new EnumMap<>(Use.class));

    private final Map<Use, LongSet> forbidden;
    /** 整片禁止的:这一栏在其中任何一片里的格都禁止。 */
    private final Map<Use, List<Region>> forbiddenRegions;
    private final Map<Use, Long2DoubleMap> extra;
    /** 设了"只许"的那几栏:这一栏只许这些格。 */
    private final Map<Use, LongSet> confined;

    private PositionCosts(Map<Use, LongSet> forbidden, Map<Use, List<Region>> forbiddenRegions,
                          Map<Use, Long2DoubleMap> extra, Map<Use, LongSet> confined) {
        this.forbidden = forbidden;
        this.forbiddenRegions = forbiddenRegions;
        this.extra = extra;
        this.confined = confined;
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

    /** 这一格禁不禁止这样用:在禁止的格里、在禁止的某一片里,或这一栏设了"只许"而它不在其中。 */
    public boolean forbids(Use use, long cell) {
        LongSet cells = forbidden.get(use);
        if (cells != null && cells.contains(cell)) {
            return true;
        }
        List<Region> regions = forbiddenRegions.get(use);
        if (regions != null) {
            for (Region region : regions) {
                if (region.contains(cell)) {
                    return true;
                }
            }
        }
        LongSet only = confined.get(use);
        return only != null && !only.contains(cell);
    }

    /** 这样用这一格额外加的价钱;没登记是 0。 */
    public double extra(Use use, long cell) {
        Long2DoubleMap costs = extra.get(use);
        return costs == null ? 0 : costs.get(cell);
    }

    public boolean isEmpty() {
        return forbidden.isEmpty() && forbiddenRegions.isEmpty() && extra.isEmpty() && confined.isEmpty();
    }

    /** 两张表叠加:禁令取并集(整片的接在一起),同一格同一栏的加价相加,同一栏的"只许"取交集。 */
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
        private final EnumMap<Use, List<Region>> forbiddenRegions = new EnumMap<>(Use.class);
        private final EnumMap<Use, Long2DoubleOpenHashMap> extra = new EnumMap<>(Use.class);
        private final EnumMap<Use, LongOpenHashSet> confined = new EnumMap<>(Use.class);

        private Builder() {}

        public Builder forbid(Use use, long cell) {
            forbidden.computeIfAbsent(use, u -> new LongOpenHashSet()).add(cell);
            return this;
        }

        /** 这样用 {@code region} 里的每一格都禁止:整片交进来,不逐格展开。 */
        public Builder forbid(Use use, Region region) {
            forbiddenRegions.computeIfAbsent(use, u -> new ArrayList<>()).add(region);
            return this;
        }

        /**
         * 这样用只许在 {@code cells} 里:别的格一律禁止。同一栏再设一次取交集——两份承诺都要守。给空集就是这一栏一格都不许。
         */
        public Builder confine(Use use, LongSet cells) {
            LongOpenHashSet already = confined.get(use);
            if (already == null) {
                confined.put(use, new LongOpenHashSet(cells));
            } else {
                already.retainAll(cells);
            }
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
            table.forbiddenRegions.forEach((use, regions) -> regions.forEach(r -> forbid(use, r)));
            table.extra.forEach((use, costs) -> costs.long2DoubleEntrySet()
                    .forEach(e -> add(use, e.getLongKey(), e.getDoubleValue())));
            table.confined.forEach(this::confine);
        }

        public PositionCosts build() {
            EnumMap<Use, LongSet> f = new EnumMap<>(Use.class);
            forbidden.forEach((use, cells) -> f.put(use, LongSets.unmodifiable(new LongOpenHashSet(cells))));
            EnumMap<Use, List<Region>> r = new EnumMap<>(Use.class);
            forbiddenRegions.forEach((use, regions) -> r.put(use, List.copyOf(regions)));
            EnumMap<Use, Long2DoubleMap> e = new EnumMap<>(Use.class);
            extra.forEach((use, costs) -> e.put(use, Long2DoubleMaps.unmodifiable(new Long2DoubleOpenHashMap(costs))));
            EnumMap<Use, LongSet> c = new EnumMap<>(Use.class);
            confined.forEach((use, cells) -> c.put(use, LongSets.unmodifiable(new LongOpenHashSet(cells))));
            return f.isEmpty() && r.isEmpty() && e.isEmpty() && c.isEmpty() ? EMPTY : new PositionCosts(f, r, e, c);
        }
    }
}
