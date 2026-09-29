package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * 一条路线最近一次的计划:从哪儿规划的、何时,每段多长、多少刻、要挖哪几格、要放哪几格、要问主人哪几格,到不了的段为什么,
 * 哪一段之后还是未知。计划是粗的——每段一次搜索,看得清的只有那一次快照里的地方。
 *
 * <p>计划是承诺:她看过的这一份就是 {@code move go} 许改的全部格子({@link #bind})。从别处出发重新规划的结果拿来和它比
 * ({@link #beyond}),要改的、要问的格没超出它才走。一步步的路不存:路线是推导出来的,能交接的是目标加规格。
 *
 * @param from 从哪一格规划的(她当时脚下)
 * @param at   何时(主世界游戏刻)
 * @param legs 每段一份,与路线的路段一一对应
 */
public record Plan(BlockPos from, long at, List<Leg> legs) {

    /** 一段走不走得通。 */
    public enum Reach {
        /** 整段看清了,走得通。 */
        WALKABLE,
        /** 看清了开头那一截({@link Leg#end} 为止),之后是什么这次没看到:搜索预算用完、伸出了看得见的地方。 */
        PARTIAL,
        /** 按这一段的规格走不通({@link Leg#why} 说为什么)。 */
        UNREACHABLE,
        /** 前面有一段走不通或还没看清,这一段没有规划。 */
        UNPLANNED
    }

    /** 一格与那一格的方块:要挖的是规划时那里的方块,要放的是打算放下的方块。 */
    public record Cell(BlockPos pos, Block block) {

        static final Codec<Cell> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(c -> c.pos().asLong()),
                BuiltInRegistries.BLOCK.byNameCodec().fieldOf("block").forGetter(Cell::block)
        ).apply(i, (pos, block) -> new Cell(BlockPos.of(pos), block)));

        public Cell {
            pos = pos.immutable();
        }
    }

    /** 要问主人的一格,与许可给的为什么要问(照权限层的原话)。 */
    public record Ask(BlockPos pos, String cause) {

        static final Codec<Ask> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.LONG.fieldOf("pos").forGetter(a -> a.pos().asLong()),
                Codec.STRING.fieldOf("cause").forGetter(Ask::cause)
        ).apply(i, (pos, cause) -> new Ask(BlockPos.of(pos), cause)));

        public Ask {
            pos = pos.immutable();
        }
    }

    /**
     * 一段的计划。
     *
     * @param steps  几步(看清的那一截)
     * @param ticks  规划器估的刻数(含这一段规格的罚分)
     * @param end    看清的那一截停在哪一格;没有看清任何一截为 null
     * @param digs   要挖的格
     * @param places 要放方块的格(路上留下的;倒水接坠落当步收回,不算)
     * @param asks   其中要问主人的格
     * @param why    走不通、或只看清一截时为什么(寻路结局的原话);走得通、没规划为空串
     */
    public record Leg(Reach reach, int steps, int ticks, BlockPos end, List<Cell> digs, List<Cell> places,
                      List<Ask> asks, String why) {

        static final Codec<Leg> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("reach").forGetter(l -> l.reach().name().toLowerCase(Locale.ROOT)),
                Codec.INT.fieldOf("steps").forGetter(Leg::steps),
                Codec.INT.fieldOf("ticks").forGetter(Leg::ticks),
                Codec.LONG.optionalFieldOf("end").forGetter(l -> Optional.ofNullable(l.end()).map(BlockPos::asLong)),
                Cell.CODEC.listOf().fieldOf("digs").forGetter(Leg::digs),
                Cell.CODEC.listOf().fieldOf("places").forGetter(Leg::places),
                Ask.CODEC.listOf().fieldOf("asks").forGetter(Leg::asks),
                Codec.STRING.fieldOf("why").forGetter(Leg::why)
        ).apply(i, (reach, steps, ticks, end, digs, places, asks, why) -> new Leg(
                Reach.valueOf(reach.toUpperCase(Locale.ROOT)), steps, ticks, end.map(BlockPos::of).orElse(null), digs,
                places, asks, why)));

        public Leg {
            digs = List.copyOf(digs);
            places = List.copyOf(places);
            asks = List.copyOf(asks);
        }

        /** 前面走不通或没看清而没有规划的一段。 */
        public static Leg unplanned() {
            return new Leg(Reach.UNPLANNED, 0, 0, null, List.of(), List.of(), List.of(), "");
        }

        /** 按这一段的规格走不通。 */
        public static Leg unreachable(String why) {
            return new Leg(Reach.UNREACHABLE, 0, 0, null, List.of(), List.of(), List.of(), why);
        }
    }

    static final Codec<Plan> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("from").forGetter(p -> p.from().asLong()),
            Codec.LONG.fieldOf("at").forGetter(Plan::at),
            Leg.CODEC.listOf().fieldOf("legs").forGetter(Plan::legs)
    ).apply(i, (from, at, legs) -> new Plan(BlockPos.of(from), at, legs)));

    public Plan {
        from = from.immutable();
        legs = List.copyOf(legs);
    }

    /** 要挖的全部格。 */
    public LongSet digs() {
        LongSet out = new LongOpenHashSet();
        legs.forEach(l -> l.digs().forEach(c -> out.add(c.pos().asLong())));
        return out;
    }

    /** 要放方块的全部格。 */
    public LongSet places() {
        LongSet out = new LongOpenHashSet();
        legs.forEach(l -> l.places().forEach(c -> out.add(c.pos().asLong())));
        return out;
    }

    /** 要问主人的全部格。 */
    public LongSet asks() {
        LongSet out = new LongOpenHashSet();
        legs.forEach(l -> l.asks().forEach(a -> out.add(a.pos().asLong())));
        return out;
    }

    /** 第一段走不通的段(从 0 数);都走得通或只是没看清为 -1。 */
    public int unreachable() {
        for (int i = 0; i < legs.size(); i++) {
            if (legs.get(i).reach() == Reach.UNREACHABLE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 这份计划超出 {@code committed} 的地方:它要挖而那份没挖的格、要放而那份没放的格、要问而那份没问的格。三样都空就是没超出。
     */
    public Difference beyond(Plan committed) {
        LongSet digs = committed.digs();
        LongSet places = committed.places();
        LongSet asks = committed.asks();
        List<Cell> moreDigs = new ArrayList<>();
        List<Cell> morePlaces = new ArrayList<>();
        List<Ask> moreAsks = new ArrayList<>();
        for (Leg leg : legs) {
            leg.digs().stream().filter(c -> !digs.contains(c.pos().asLong())).forEach(moreDigs::add);
            leg.places().stream().filter(c -> !places.contains(c.pos().asLong())).forEach(morePlaces::add);
            leg.asks().stream().filter(a -> !asks.contains(a.pos().asLong())).forEach(moreAsks::add);
        }
        return new Difference(moreDigs, morePlaces, moreAsks);
    }

    /** 一份计划超出承诺的格。 */
    public record Difference(List<Cell> digs, List<Cell> places, List<Ask> asks) {

        public Difference {
            digs = List.copyOf(digs);
            places = List.copyOf(places);
            asks = List.copyOf(asks);
        }

        public boolean isEmpty() {
            return digs.isEmpty() && places.isEmpty() && asks.isEmpty();
        }
    }

    /**
     * 这份计划当承诺写进一趟路的规格:只许挖它要挖的格、只许放它要放的格(位置代价的"只许",见 {@link PositionCosts}),
     * 路上世界变了、执行层重搜时自然只在这些格里找,找不到就停下。一格都不改的计划,承诺就是一格都不许改。
     */
    public RouteSpec bind(RouteSpec spec) {
        PositionCosts promise = PositionCosts.builder().confine(PositionCosts.Use.DIG, digs())
                .confine(PositionCosts.Use.PLACE, places()).build();
        return spec.edit().positions(spec.positions().plus(promise)).build();
    }
}
