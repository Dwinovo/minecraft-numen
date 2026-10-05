package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.nav.BoatNav;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * 一趟路的计划:{@code numen.route.plan} 照一份描述({@link Description})从她脚下只搜不走算出来的——每一段去哪、走不走得通、看清的那一截
 * 一步步怎么走、要挖要放哪几格、哪几格要问主人、要潜哪几段水。计划只在算出它的那一段程序里有效({@link Plans}):服务端把算好的路
 * 暂留着,{@code numen.move.go} 照它走;程序结束就作废,世界不会等她。
 *
 * <p>计划是承诺:她看过的这一份就是 {@code numen.move.go} 许改的全部格子({@link #bind})。从别处出发重新规划的结果拿来和它比
 * ({@link #beyond}),要改的、要问的格没超出它才走。
 *
 * @param id   这一段程序里点名它的编号
 * @param from 从哪一格规划的(她当时脚下)
 * @param legs 每一站一段,与描述的途经点一一对应
 */
public record Plan(String id, Description description, BlockPos from, List<Leg> legs) {

    /** 一段走不走得通。 */
    public enum Reach {
        /** 整段看清了,走得到。 */
        REACHED,
        /** 看清了开头那一截({@link Leg#finish} 为止),之后是什么这次没看到:搜索预算用完、伸出了看得见的地方、水路到了岸边。 */
        PARTIAL,
        /** 按这一段的描述走不通({@link Leg#why} 说为什么)。 */
        UNREACHABLE,
        /** 前面有一段走不通或还没看清,这一段从哪儿起还不知道,没有规划;走的时候边走边算。 */
        UNPLANNED
    }

    /**
     * 一段。
     *
     * @param to    编好的去处;这一站此刻就编不成目标(去处写不通)为 null
     * @param spec  这一段走的寻路规格
     * @param reach 走不走得通
     * @param route   走路的一段:走得到时是整段,只看清一截时是那一截;没有为 null
     * @param chart   驾船的一段:航线;走路的一段为 null
     * @param changes 看清的那一截要改的格:挖的、放的(连同要问主人的为什么问)
     * @param why     走不通、或只看清一截时为什么(寻路结局的原话);走得到、没规划为空串
     */
    public record Leg(Stop stop, Destination to, RouteSpec spec, Reach reach, Route route, BoatNav.Chart chart,
                      NavText.Changes changes, String why) {

        /** 一段看清了的路(或那一截):要改的格从路上的改动读。 */
        static Leg walked(Stop stop, Destination to, RouteSpec spec, Reach reach, Route route, String why) {
            return new Leg(stop, to, spec, reach, route, null,
                    NavText.Changes.of(route == null ? List.of() : route.edits()), why);
        }

        /** 没有路的一段(走不通、没规划、驾船):一格不改。 */
        static Leg bare(Stop stop, Destination to, RouteSpec spec, Reach reach, BoatNav.Chart chart, String why) {
            return new Leg(stop, to, spec, reach, null, chart, NavText.Changes.of(List.of()), why);
        }

        /** 看清的那一截停在哪一格;没有看清任何一截为 null。 */
        public BlockPos finish() {
            if (chart != null) {
                return chart.end();
            }
            return route == null ? null : route.end();
        }

        /** 几步(看清的那一截)。 */
        public int steps() {
            return chart != null ? chart.path().size() : route == null ? 0 : route.legs().size();
        }

        /** 规划器估的刻数(含这一段规格的罚分);驾船按一格一秒的三分之一估。 */
        public double ticks() {
            if (chart != null) {
                return chart.path().size() * BOAT_TICKS_PER_BLOCK;
            }
            return route == null ? 0 : route.cost();
        }

    }

    /** 船在水上一格约几刻:原版小船直行约每秒八格。 */
    static final double BOAT_TICKS_PER_BLOCK = 2.5;

    public Plan {
        from = from.immutable();
        legs = List.copyOf(legs);
    }

    /** 每一段都走得到或看得清开头(没有走不通的段)。 */
    public boolean ok() {
        return unreachable() < 0;
    }

    /** 第一段走不通的段(从 0 数);没有为 -1。 */
    public int unreachable() {
        for (int i = 0; i < legs.size(); i++) {
            if (legs.get(i).reach() == Reach.UNREACHABLE) {
                return i;
            }
        }
        return -1;
    }

    /** 走不通的那一段的为什么;走得通为空串。 */
    public String why() {
        int bad = unreachable();
        return bad < 0 ? "" : legs.get(bad).why();
    }

    /** 看清的部分共几步。 */
    public int steps() {
        return legs.stream().mapToInt(Leg::steps).sum();
    }

    /** 看清的部分约几秒(规划器的估价,含罚分)。 */
    public double seconds() {
        return Math.round(legs.stream().mapToDouble(Leg::ticks).sum() / 2.0) / 10.0;
    }

    /** 要挖的全部格。 */
    public LongSet digs() {
        LongSet out = new LongOpenHashSet();
        legs.forEach(l -> l.changes().digs().keySet().forEach(p -> out.add(p.asLong())));
        return out;
    }

    /** 要放方块的全部格。 */
    public LongSet places() {
        LongSet out = new LongOpenHashSet();
        legs.forEach(l -> l.changes().places().keySet().forEach(p -> out.add(p.asLong())));
        return out;
    }

    /** 要问主人的全部格。 */
    public LongSet asks() {
        LongSet out = new LongOpenHashSet();
        legs.forEach(l -> l.changes().asks().keySet().forEach(p -> out.add(p.asLong())));
        return out;
    }

    /** 一格与那一格的方块:要挖的是规划时那里的方块,要放的是打算放下的方块。 */
    public record Cell(BlockPos pos, Block block) {}

    /** 要问主人的一格,与许可给的为什么要问(照权限层的原话)。 */
    public record Ask(BlockPos pos, String cause) {}

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
            NavText.Changes changes = leg.changes();
            changes.digs().forEach((pos, block) -> {
                if (!digs.contains(pos.asLong())) {
                    moreDigs.add(new Cell(pos, block));
                }
            });
            changes.places().forEach((pos, block) -> {
                if (!places.contains(pos.asLong())) {
                    morePlaces.add(new Cell(pos, block));
                }
            });
            changes.asks().forEach((pos, cause) -> {
                if (!asks.contains(pos.asLong())) {
                    moreAsks.add(new Ask(pos, cause));
                }
            });
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
