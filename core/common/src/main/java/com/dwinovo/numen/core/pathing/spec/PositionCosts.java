package com.dwinovo.numen.core.pathing.spec;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 按坐标的代价叠加表:不看方块看位置。四栏各自独立——踩(站上去)、穿(身体占据)、
 * 挖、放;查不到的格记 0,{@link com.dwinovo.numen.core.pathing.moves.ActionCosts#COST_INF}
 * 即禁止。键是 {@link net.minecraft.core.BlockPos#asLong}。
 *
 * <p>不可变;建表走 {@link #builder()} 或 {@link #protect},合并走 {@link #plus}(同格相加)。
 * 导航自身的目标格用 {@link #protect}:别挖自己要站、要够的那格,也别拿方块把它埋了——
 * 这是规划器的正确性约束,不是权限。
 */
public final class PositionCosts {

    public static final PositionCosts EMPTY = new PositionCosts(
            new Long2DoubleOpenHashMap(0), new Long2DoubleOpenHashMap(0),
            new Long2DoubleOpenHashMap(0), new Long2DoubleOpenHashMap(0));

    private final Long2DoubleMap stand;
    private final Long2DoubleMap pass;
    private final Long2DoubleMap dig;
    private final Long2DoubleMap place;

    private PositionCosts(Long2DoubleMap stand, Long2DoubleMap pass,
                          Long2DoubleMap dig, Long2DoubleMap place) {
        this.stand = stand;
        this.pass = pass;
        this.dig = dig;
        this.place = place;
    }

    /** 禁挖禁放这些格(导航自身目标格、工地格)。 */
    public static PositionCosts protect(LongSet cells) {
        if (cells.isEmpty()) {
            return EMPTY;
        }
        Builder b = builder();
        cells.forEach((long cell) -> b.dig(cell, COST_INF).place(cell, COST_INF));
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public double stand(long cell) {
        return stand.get(cell);
    }

    public double pass(long cell) {
        return pass.get(cell);
    }

    public double dig(long cell) {
        return dig.get(cell);
    }

    public double place(long cell) {
        return place.get(cell);
    }

    public boolean isEmpty() {
        return stand.isEmpty() && pass.isEmpty() && dig.isEmpty() && place.isEmpty();
    }

    /** 两张表叠加:同一格同一栏的代价相加。 */
    public PositionCosts plus(PositionCosts other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return new PositionCosts(sum(stand, other.stand), sum(pass, other.pass),
                sum(dig, other.dig), sum(place, other.place));
    }

    private static Long2DoubleMap sum(Long2DoubleMap a, Long2DoubleMap b) {
        Long2DoubleOpenHashMap out = new Long2DoubleOpenHashMap(a);
        for (Long2DoubleMap.Entry e : b.long2DoubleEntrySet()) {
            out.addTo(e.getLongKey(), e.getDoubleValue());
        }
        return out;
    }

    public static final class Builder {
        private final Long2DoubleOpenHashMap stand = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap pass = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap dig = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap place = new Long2DoubleOpenHashMap();

        private Builder() {}

        public Builder stand(long cell, double cost) {
            stand.addTo(cell, cost);
            return this;
        }

        public Builder pass(long cell, double cost) {
            pass.addTo(cell, cost);
            return this;
        }

        public Builder dig(long cell, double cost) {
            dig.addTo(cell, cost);
            return this;
        }

        public Builder place(long cell, double cost) {
            place.addTo(cell, cost);
            return this;
        }

        public PositionCosts build() {
            return new PositionCosts(stand, pass, dig, place);
        }
    }
}
