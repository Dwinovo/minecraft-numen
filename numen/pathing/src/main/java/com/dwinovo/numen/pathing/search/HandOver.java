package com.dwinovo.numen.pathing.search;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * 交出策略:什么时候先交一截半程路线、交哪一截,只在这里定。
 *
 * <ul>
 *   <li><b>什么时候</b>:每一段搜索展开到 {@link #NODES} 个节点还没到目标、又已经有可交的半程,就先交出它({@link #due});
 *       预算用完、搜完无路、搜到了没加载的区块时没到目标,交的也是这一截;</li>
 *   <li><b>交哪一截</b>({@link #cut}):搜索按几档"估价加已走代价的折算"各挑出一个离目标近的节点,走到那里的路线里
 *       <b>只能停在最后一个回得了头的节点上</b>({@link com.dwinovo.numen.pathing.plan.Maneuver#reversible}):含着回不了头的一步
 *       (落差回不去的坠落、往下挖穿又爬不回来)就往回截到它之前;截完离起点不到 {@value #MIN_PARTIAL} 格的不交——原地打转的
 *       半截路不是路,第一步就回不了头的也没有可交的,搜索接着搜到目标或搜完。身体走到截的终点才发现下面接不上,
 *       又回不去,就是困在半腰;截在回得了头的节点上,走过去不对劲还能原路退回来。憋着气的节点也不当终点:身体停在水下等
 *       下一段,下一段接不上就困在那里。</li>
 * </ul>
 */
public final class HandOver {

    /**
     * 每一段搜索展开到这么多个节点还没到目标、又已经有可交的半程路线,就先交出它:身体先走这一段,快走完时从它的终点接着搜。
     * 依据是等多久,不是地形:许改地形的搜索每个节点 15 到 76 微秒(实测见 docs/pathing.md 第十三节),一万个节点在
     * 0.15 到 0.76 秒之间,与 Baritone 先交路线的 primaryTimeoutMS(500 毫秒)同一量级;它是默认预算
     * {@link com.dwinovo.numen.pathing.api.NavRequest#DEFAULT_BUDGET} 的四分之一,Baritone 的 primary 与 failure 两个时限也是一比四。
     * 靶场十八条固定路线共搜了 36 次(估价是运动学下界,搜到头的就是最优路线):一半自己搜到头,
     * 中位约两千个节点、最多 9965 个,另一半在这里交出半程;下到盆地、绕山、爬坡这类要绕的路一次搜不完。
     * 按节点数计,结论不随机器快慢变。
     */
    public static final int NODES = 10_000;

    /** 交出的一截,终点至少要离起点这么多格。 */
    static final int MIN_PARTIAL = 5;

    private HandOver() {}

    /** 该先交半程了:已经展开到阈值 {@code nodes}。 */
    static boolean due(int expanded, int nodes) {
        return expanded >= nodes;
    }

    /**
     * 按先后给出的几条候选路线(从紧到松的档位各一条)里,第一条截完够远的:截到最后一个回得了头、也没憋着气的节点。
     * 都不够远为 null。
     */
    static Route pick(List<Route> candidates) {
        for (Route route : candidates) {
            Route cut = cut(route);
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
