package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import net.minecraft.core.BlockPos;

/**
 * 一次搜索没搜到要去的地方时身体去哪、交哪一截,只在这里定。输入是这次搜索的边界节点(各带走到它的价钱与估价),输出是交出的一截;
 * 和搜索怎么组织(一次搜到哪、预算多少)无关。
 *
 * <p><b>去哪</b>:LSS-LRTA* 的选点 [Koenig &amp; Sun 2009]——边界上 {@code g + }{@link #TARGET_WEIGHT}{@code ·h} 最小的节点。估价含这一趟抬过的增量
 * ({@link TripMemory}),走进过的死路估价已抬高,不会再被当成最近的去处。
 *
 * <p><b>交哪一截</b>:走到那里的路线里<b>只能停在最后一个回得了头的节点上</b>
 * ({@link com.dwinovo.numen.pathing.plan.Maneuver#reversible}):含着回不了头的一步(落差回不去的坠落、往下挖穿又爬不回来)就往回
 * 截到它之前。半程可能走进死路,只有停在退得回来的地方,走过去不对劲还能原路退回来。憋着气的节点不当终点:身体停在水下等下一次搜索,
 * 接不上就困在那里——憋着气的边界节点不当去处,路线上憋着气的节点不当截点。
 *
 * <p>搜到了要去的地方的路线是完整的,里面的坠落、往下挖穿都允许——落点之后的路已经找到了,不截。
 */
public final class HandOver {

    /** 交出的一截,终点至少要离起点这么多格。 */
    static final int MIN_PARTIAL = 5;
    /** 最多试几个边界节点:走到它的路第一步就回不了头、截不出来,换下一个。 */
    static final int TRIES = 16;

    private HandOver() {}

    /**
     * 边界上的一个节点。
     *
     * @param node     节点
     * @param cost     从起点走到它的价钱({@code g})
     * @param estimate 它的估价({@code h},含这一趟抬过的增量,与搜索用的同一份)
     * @param holding  走到它时憋着气
     */
    record Candidate(int node, double cost, double estimate, boolean holding) {}

    /**
     * 挑去处时估价的系数:按 {@code g + 5·h} 比。搜索按 {@code g + }{@link AStar#WEIGHT}{@code ·h} 排开放集,估价在平台上(凹地口、地下的一大片等价格)几乎不变,
     * 按 {@code g + h} 挑会选中离起点近、总价最小的那批,不朝目标走——规划评测里 mountains-short-027 的首段往反方向走了五十格,
     * 接续又从头泛洪,三次搜索把总预算花完。系数取偏向离目标近的一侧,与 Baritone 对一串系数(1.5 到 10)各记一个最接近目标的节点、
     * 取大系数的做法同向;走进死路的风险由估价抬高({@link TripMemory})与回得了头的截断兜着。
     * 规划评测 dev(668 行)上 5 与 10 一样:B 多到 8 行、丢 3 行,起步与单次最长思考的 p90 不变。
     */
    static final double TARGET_WEIGHT = 5;

    /**
     * 先比 {@code g + }{@link #TARGET_WEIGHT}{@code ·h},取整到堆里同一个刻度;平手的先取离目标近的(沿着走得最远的那个)。
     */
    private static final Comparator<Candidate> PREFERENCE = Comparator
            .comparingLong((Candidate c) -> Math.round((c.cost() + TARGET_WEIGHT * c.estimate()) / OpenSet.TIE))
            .thenComparingDouble(Candidate::estimate);

    /** 边界节点按去处的优先次序排好(憋着气的不当去处,不在里面)。 */
    static List<Candidate> order(List<Candidate> frontier) {
        List<Candidate> sorted = new ArrayList<>(frontier.size());
        for (Candidate c : frontier) {
            if (!c.holding()) {
                sorted.add(c);
            }
        }
        sorted.sort(PREFERENCE);
        return sorted;
    }

    /**
     * 交出的半程:按 {@link #order} 的先后,走到它的路线({@code route})截得出一截的头一个;最多试 {@link #TRIES} 个,都截不出为 null。
     */
    static Route choose(List<Candidate> frontier, IntFunction<Route> route) {
        List<Candidate> order = order(frontier);
        for (int i = 0; i < order.size() && i < TRIES; i++) {
            Route cut = cut(route.apply(order.get(i).node()));
            if (cut != null) {
                return cut;
            }
        }
        return null;
    }

    /** 一条路线交出的一截:前面回得了头的一段里,最后一个没憋着气的节点之前;离起点不够远为 null。 */
    static Route cut(Route route) {
        List<Route.Leg> legs = route.legs();
        int keep = 0;
        for (int i = 0; i < legs.size(); i++) {
            Route.Leg leg = legs.get(i);
            if (!leg.maneuver().reversible()) {
                break;
            }
            if (leg.air().held() <= 0) {
                keep = i + 1;
            }
        }
        if (keep == 0) {
            return null;
        }
        Route cut = keep == legs.size() ? route : new Route(route.start(), route.startStance(), legs.subList(0, keep));
        BlockPos from = cut.start();
        return cut.end().distSqr(from) > (double) MIN_PARTIAL * MIN_PARTIAL ? cut : null;
    }
}
