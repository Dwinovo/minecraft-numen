package com.dwinovo.numen.pathing.search;

import java.util.Arrays;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.world.Clearance;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 埋深:从一个节点走到目标,路上至少还要挖掉多少钱。估价({@link Estimate})把它加在水平底价与升降的额外代价上。
 *
 * <p>为什么要它:估价的其余两样只按身体怎么挪算(水平每格按疾跑、竖直按跳与落),挖一格的真实价钱——挖掘耗时加规格的挖掘罚分
 * ({@link CostModel#digCost})——是估价里走一格的十几倍。目标埋在石头里时,真实代价远高过估价,A* 要把地面上、连同每一列
 * 往下挖开的几格,凡是比挖过去便宜的节点全展开一遍,才敢认挖下去的那条路:挖正下方十几格的矿,四万个节点也搜不到头。
 *
 * <p>怎么算:一个放宽了的问题的精确解。从目标的每个终点({@link Goal#endCells})往外做一次 Dijkstra,身体一格一格挪,不管
 * 站不站得住、跳不跳得上,只数挪进去时要清开几格整块实心的格({@link Clearance#solid}),每格按 {@link CostModel#digFloor}
 * 计价:横着挪进一列,身体在那一列占着的几格都要清开;往上挪清开头顶新占的一格;往下挪清开脚下新占的一格;空气、流体、
 * 半砖这些格与没加载的列不要钱。横着只算四个正方向——斜走不改地形,切角时两侧那两列都得让得开,斜着钻进石头至少要先
 * 挖开一侧,和先直走一格再拐一样多。真实的每一步至少要清开这些格、每格至少要这个价,所以它是下界:加到估价上,A* 仍然
 * 搜出最便宜的路,只是不必再展开那些挖掘绕不开、却按走路估得很便宜的节点。
 *
 * <p>算到哪里停:按价钱从终点往外一格格定下,定到起点那一格就停,还没定下的格至少是停下时那一档;同一档里先定离起点近的
 * ——空地里挪不要钱,整片空地都在同一档,按远近排才不必铺满它,终点露在外面的目标只定下起点跟前几格就停。最多定下
 * {@code cap} 格(这次搜索的展开预算,定一格只读两三格方块,比展开一个节点便宜得多),到了也停,没定下的格同样取停下时
 * 那一档,仍是下界。规格不许改地形时路上不会挖,不算。
 */
final class Burial {

    /** 不加。 */
    static final Burial NONE = new Burial(new Long2DoubleOpenHashMap(), 0);

    /** 横着挪的四个方向。 */
    private static final int[][] SIDEWAYS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final Long2DoubleOpenHashMap settled;
    /** 没定下的格至少要挖掉这么多钱。 */
    private final double rest;

    private Burial(Long2DoubleOpenHashMap settled, double rest) {
        this.settled = settled;
        this.rest = rest;
    }

    /** 节点 {@code (x, y, z)} 走进目标至少还要挖掉多少钱。 */
    double floor(int x, int y, int z) {
        return settled.getOrDefault(BlockPos.asLong(x, y, z), rest);
    }

    /** 这次搜索的埋深:在 {@code view} 上从 {@code goal} 的终点往外算,到 {@code start} 为止,最多定下 {@code cap} 格。 */
    static Burial of(SearchView view, CostModel model, Goal goal, BlockPos start, int cap) {
        if (!model.spec().dig()) {
            return NONE;
        }
        LongSet ends = goal.endCells();
        return ends == null ? NONE : new Field(view, model, start).run(ends, cap);
    }

    /** 一次 Dijkstra 的工作区。 */
    private static final class Field {

        private final SearchView view;
        private final CostModel model;
        private final BlockPos start;
        private final long startKey;
        /** 身体站着时占几格高。 */
        private final int tall;
        private final int minY;
        private final int maxY;
        /** 每格清开它要多少钱(不是整块实心为 0,挖不动为无穷大),算过就记下。 */
        private final Long2DoubleOpenHashMap prices = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        private final Long2DoubleOpenHashMap settled = new Long2DoubleOpenHashMap();
        private final CostQueue queue = new CostQueue();

        Field(SearchView view, CostModel model, BlockPos start) {
            this.view = view;
            this.model = model;
            this.start = start;
            this.startKey = start.asLong();
            this.tall = Clearance.topCell(model.body().stats(), 0) + 1;
            this.minY = view.getMinBuildHeight();
            this.maxY = view.getMaxBuildHeight();
            best.defaultReturnValue(Double.POSITIVE_INFINITY);
        }

        Burial run(LongSet ends, int cap) {
            for (long end : ends) {
                best.put(end, 0);
                queue.push(0, distance(end), end);
            }
            double reached = 0;
            while (!queue.isEmpty()) {
                double cost = queue.peekCost();
                long cell = queue.pop();
                if (cost > best.get(cell) || settled.containsKey(cell)) {
                    continue;
                }
                reached = cost;
                if (cell == startKey || settled.size() >= cap) {
                    break;
                }
                settled.put(cell, cost);
                relax(cell, cost);
            }
            return new Burial(settled, reached);
        }

        /** 从刚定下的 {@code cell} 往回松弛:能一步挪进它的每一格,到它的价钱加上挪进去要清开的格。 */
        private void relax(long cell, double cost) {
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            // 横着挪进 cell:身体在这一列占着的几格都要清开
            double across = 0;
            for (int i = 0; i < tall; i++) {
                across += price(x, y + i, z);
            }
            for (int[] d : SIDEWAYS) {
                offer(x - d[0], y, z - d[1], cost + across);
            }
            // 从下面往上挪进 cell:清开头顶新占的那一格;从上面往下挪进 cell:清开脚下新占的那一格
            offer(x, y - 1, z, cost + price(x, y + tall - 1, z));
            offer(x, y + 1, z, cost + price(x, y, z));
        }

        private void offer(int x, int y, int z, double cost) {
            if (Double.isInfinite(cost) || y < minY || y + tall > maxY) {
                return;
            }
            long cell = BlockPos.asLong(x, y, z);
            if (cost < best.get(cell) && !settled.containsKey(cell)) {
                best.put(cell, cost);
                queue.push(cost, distance(cell), cell);
            }
        }

        /** 清开这一格要多少钱。 */
        private double price(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            double known = prices.getOrDefault(key, -1);
            if (known >= 0) {
                return known;
            }
            double price = 0;
            if (view.isLoaded(x, z)) {
                BlockState state = view.getBlockState(new BlockPos(x, y, z));
                if (Clearance.solid(state)) {
                    price = model.digFloor(state);
                }
            }
            prices.put(key, price);
            return price;
        }

        /** 同一档里的先后:离起点的曼哈顿距离。 */
        private int distance(long cell) {
            return Math.abs(BlockPos.getX(cell) - start.getX()) + Math.abs(BlockPos.getY(cell) - start.getY())
                    + Math.abs(BlockPos.getZ(cell) - start.getZ());
        }
    }
}
