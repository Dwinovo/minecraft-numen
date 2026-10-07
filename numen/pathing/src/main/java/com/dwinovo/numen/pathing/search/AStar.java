package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Move;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Bounds;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Recall;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * A*:从起点节点逐个展开 {@link Moves#ALL} 的每种走法、每个方向,前提成立的步子按它的代价(乘上旧路折扣)松弛。
 *
 * <ul>
 *   <li><b>身体面对的世界</b>:展开一个节点时,前提读的是快照加上"走到这个节点的那一步"做过的改动——刚挖开的格是空的,
 *       刚垫下的块托着脚。更早的步子做过的改动不叠:身体已经离开了那里;</li>
 *   <li><b>预算</b>按展开的节点数计,结论不随机器快慢漂移;</li>
 *   <li><b>停下的原因</b>随结论交出({@link SearchResult.Stop}):到了、搜完无路、预算用完、有路伸进快照外没加载的区块。
 *       一步的前提读到了没加载的列,它成不成立就不知道,这一步不走、结论记成"未加载";前提没读到那些列就定了的(规格没开这种
 *       走法、紧挨着的一列就挡住了),与那边是什么无关,不算;</li>
 *   <li><b>半程路线</b>:没到目标时,按几档"估价加已走代价的折算"各取最好的节点,取第一个离起点超过 {@value #MIN_PARTIAL}
 *       格的交出;都不够远就不交——原地打转的半截路不是路。最后一档只看估价(离目标多近):估价按疾跑算,挖隧道、搭桥时
 *       每一步的真实代价比它贵几十倍,前几档的折算都压不住已走的代价,最好的节点总在起点跟前。憋着气的节点不当终点:
 *       身体停在水下等下一段,下一段接不上就困在那里;</li>
 *   <li><b>先交半程</b>:展开到 {@link Search#handOver} 个节点还没到目标,只要已经有够远的半程路线就先交出它(停因同预算用完),
 *       执行层先走这一段、同时从它的终点接着搜——许放块时搜索在空中四面铺开,一次搜到底要几秒,身体一直站着等。
 *       在封顶之前到了目标的,路线与搜到底一样;封顶时还没有够远的半程,就接着搜,直到有了或预算用完;</li>
 *   <li><b>接着一条路线往下搜</b>:起点是那条路线的终点({@link Search#arrival}),身体到那里时怎么待着按那一步的落点算,
 *       展开起点时叠上那一步的改动——快照里还没有它垫下的块;</li>
 *   <li><b>估价</b>:目标的估价({@link Goal#estimate})加上埋深({@link Burial}):路上绕不开要挖掉的格至少多少钱。两样都是
 *       下界,搜出来的仍是最便宜的路;埋深在开搜时从目标往外算一次;</li>
 *   <li><b>目标格保护</b>:目标的 {@link Goal#protection()} 并进路线规格的按位置禁令,规划不挖自己要站、要够的格;</li>
 *   <li><b>改动预算</b>(规格的 {@code alterBudget})在展开时就生效:设了预算时节点按"位置加已改几格"区分,超出预算的步子
 *       不展开,所以搜出来的路一定在预算内,而且是预算内最便宜的;</li>
 *   <li><b>憋气</b>:每个节点带着走到那里时身体憋气的样子,每一步按 {@link Breath#after} 往下推(这一步眼睛换不换得了气、
 *       身体要几刻),走过去憋不住的步子不走({@link Breath#lasts})——判据只在 {@link Breath}。节点再按憋气的档
 *       ({@link Breath#band})区分:同一格,憋着气刚到的与换过气到的是两个节点,贵一点却换过气的那条不会被便宜的挤掉;</li>
 *   <li><b>到达价</b>:进了目标的节点出堆时补上它的到达价放回堆里,按总价再次出堆才收,同时照常往外展开;到达价无穷的
 *       节点停下也办不成,不算到。</li>
 * </ul>
 *
 * <p>只读 {@link Search#view()} 这份快照,单线程跑完;结论可复现,不引入随机。
 */
public final class AStar {

    /** 半程路线的几档折算系数:越小越接近真正的最优,越大越激进地朝目标扑;最后一档不计已走的代价。 */
    private static final double[] COEFFICIENTS = {1.5, 2, 2.5, 3, 4, 5, 10, Double.POSITIVE_INFINITY};
    /** 半程路线的终点至少要离起点这么多格才交出。 */
    static final int MIN_PARTIAL = 5;
    /** 小于这个改进不松弛:平地上斜走与直走组合出的浮点差不值得重排堆。 */
    private static final double MIN_IMPROVEMENT = 0.01;

    private final Search search;
    private final CostModel model;
    private final BodyStats body;
    private final boolean budgeted;
    private final int alterBudget;
    private final Breath breath;
    /** 有前提成立的步子因为憋不住气没走。 */
    private boolean breathless;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final Heap open = new Heap();
    private Burial burial = Burial.NONE;

    private AStar(Search search) {
        this.search = search;
        RouteSpec spec = search.model().spec();
        this.model = Goal.guarded(search.goal(), search.model());
        this.body = model.body().stats();
        this.budgeted = spec.budgeted();
        this.alterBudget = spec.alterBudget();
        this.breath = model.body().breath();
    }

    /** 在当前线程上跑完一次搜索;{@code cancelled} 在每展开一个节点前问一次。 */
    public static SearchResult run(Search search, BooleanSupplier cancelled) {
        return new AStar(search).run(cancelled);
    }

    private SearchResult run(BooleanSupplier cancelled) {
        SearchView view = search.view();
        Goal goal = search.goal();
        BlockPos startPos = search.start();
        Maneuver arrival = search.arrival();
        Stance startStance = arrival != null ? arrival.landing() : Stance.at(view, body, startPos);
        if (startStance == null) {
            return new SearchResult(SearchResult.Stop.STRANDED, null, 0, false);
        }
        burial = Burial.of(view, model, goal, startPos, search.budget());
        Node start = node(startPos.getX(), startPos.getY(), startPos.getZ(), 0, breath.band(search.air()));
        start.g = 0;
        start.stance = startStance;
        start.via = arrival;
        start.air = search.air();
        start.f = start.h;
        open.push(start);
        Node[] best = new Node[COEFFICIENTS.length];
        double[] bestScore = new double[COEFFICIENTS.length];
        Arrays.fill(best, start);
        Arrays.fill(bestScore, start.h);

        int expanded = 0;
        boolean skippedUnloaded = false;
        Probe probe = new Probe(view, new Recall(body));
        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return new SearchResult(SearchResult.Stop.CANCELLED, null, expanded, breathless);
            }
            Node current = open.pop();
            // 身体此刻面对的世界:快照,加上走到这个节点的那一步做过的改动
            WorldView here = current.via == null ? probe : EditedView.after(probe, current.via.edits());
            // 到达价无穷的节点停下也办不成:不算到,照常往外展开
            double stopping = goal.contains(current.x, current.y, current.z, current.stance)
                    ? goal.arrival(here, current.x, current.y, current.z, current.stance) : Double.POSITIVE_INFINITY;
            if (Double.isFinite(stopping)) {
                double total = current.g + stopping;
                if (total > current.f + MIN_IMPROVEMENT) {
                    // 到达价还没付:按总价放回堆里,同时照常往外展开——起点在贵的成员里时要走得出去
                    current.f = total;
                    open.push(current);
                } else {
                    return new SearchResult(SearchResult.Stop.ARRIVED, route(start, current), expanded, breathless);
                }
            }
            if (expanded >= search.handOver()) {
                Route early = partial(start, best);
                if (early != null) {
                    return new SearchResult(SearchResult.Stop.BUDGET, early, expanded, breathless);
                }
            }
            if (expanded >= search.budget()) {
                return new SearchResult(SearchResult.Stop.BUDGET, partial(start, best), expanded, breathless);
            }
            expanded++;
            BlockPos from = new BlockPos(current.x, current.y, current.z);
            for (Move move : Moves.ALL) {
                for (Heading heading : move.headings()) {
                    probe.recall.clearUnloaded();
                    Premise premise = move.premise(model, here, from, current.stance, heading);
                    if (probe.recall.unloaded()) {
                        skippedUnloaded = true;
                        continue;
                    }
                    if (!(premise instanceof Premise.Holds holds)) {
                        continue;
                    }
                    Maneuver m = holds.maneuver();
                    BlockPos to = m.to();
                    if (!Bounds.holdsBody(view.border(), body, to.getX(), to.getZ())) {
                        continue;
                    }
                    int used = current.used + m.alterations();
                    if (budgeted && used > alterBudget) {
                        continue;
                    }
                    double cost = move.cost(model, m);
                    if (!(cost > 0) || Double.isInfinite(cost)) {
                        throw new IllegalStateException(move.kind() + " 从 " + from + " 算出了非法的代价 " + cost);
                    }
                    Breath.Air air = current.air;
                    if (m.submerged() || !breath.rested(air)) {
                        air = breath.after(air, m.submerged(), move.ticks(model, m));
                        if (!breath.lasts(air)) {
                            breathless = true;
                            continue;
                        }
                    }
                    Node next = node(to.getX(), to.getY(), to.getZ(), budgeted ? used : 0, breath.band(air));
                    double g = current.g + cost * search.favoring().factor(to);
                    if (next.g - g <= MIN_IMPROVEMENT) {
                        continue;
                    }
                    next.g = g;
                    next.f = g + next.h;
                    next.parent = current;
                    next.via = m;
                    next.viaCost = cost;
                    next.stance = m.landing();
                    next.air = air;
                    open.pushOrUpdate(next);
                    if (air.held() > 0) {
                        // 憋着气的节点不当半程路线的终点:身体停在那里等下一段,下一段要是接不上,她就困在水下
                        continue;
                    }
                    for (int i = 0; i < COEFFICIENTS.length; i++) {
                        double score = next.h + next.g / COEFFICIENTS[i];
                        if (bestScore[i] - score > MIN_IMPROVEMENT) {
                            bestScore[i] = score;
                            best[i] = next;
                        }
                    }
                }
            }
        }
        SearchResult.Stop stop = skippedUnloaded ? SearchResult.Stop.UNLOADED : SearchResult.Stop.EXHAUSTED;
        return new SearchResult(stop, partial(start, best), expanded, breathless);
    }

    /**
     * 前提读世界经过的这一层:照读快照,读到没加载的列就在记事本上记一笔。快照之外读出来的是空气,那不是测量——一步的前提
     * 读到了它,结论就不作数。这次搜索的记事本({@link Recall})也挂在这里:快照不变,按格的结论算一次就记下。
     */
    private static final class Probe implements WorldView, Recall.Source {

        private final SearchView view;
        final Recall recall;

        Probe(SearchView view, Recall recall) {
            this.view = view;
            this.recall = recall;
        }

        @Override
        public Recall recall() {
            return recall;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (!view.isLoaded(pos.getX(), pos.getZ())) {
                recall.touchUnloaded();
            }
            return view.getBlockState(pos);
        }

        @Override
        public boolean airSection(int x, int y, int z) {
            if (!view.isLoaded(x, z)) {
                recall.touchUnloaded();
            }
            return view.airSection(x, y, z);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return view.getBlockEntity(pos);
        }

        @Override
        public int getHeight() {
            return view.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return view.getMinBuildHeight();
        }

        @Override
        public WorldBorder border() {
            return view.border();
        }

        @Override
        public boolean ultraWarm() {
            return view.ultraWarm();
        }
    }

    private Node node(int x, int y, int z, int used, int band) {
        long key = BlockPos.asLong(x, y, z);
        Node head = nodes.get(key);
        for (Node n = head; n != null; n = n.sibling) {
            if (n.used == used && n.band == band) {
                return n;
            }
        }
        Node n = new Node(x, y, z, used, band, search.goal().estimate(x, y, z) + burial.floor(x, y, z));
        n.sibling = head;
        nodes.put(key, n);
        return n;
    }

    /** 按系数从紧到松,取第一个离起点足够远的最好节点;都不够远就没有半程路线。 */
    private Route partial(Node start, Node[] best) {
        for (Node n : best) {
            double dx = n.x - start.x;
            double dy = n.y - start.y;
            double dz = n.z - start.z;
            if (dx * dx + dy * dy + dz * dz > MIN_PARTIAL * MIN_PARTIAL) {
                return route(start, n);
            }
        }
        return null;
    }

    private static Route route(Node start, Node end) {
        List<Route.Leg> legs = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            legs.add(new Route.Leg(n.via, n.viaCost, n.air));
        }
        Collections.reverse(legs);
        return new Route(new BlockPos(start.x, start.y, start.z), start.stance, legs);
    }

    /** 一个搜索节点:位置,加上设了改动预算时到这儿已经改了几格、憋气的档。 */
    private static final class Node {
        final int x;
        final int y;
        final int z;
        final int used;
        final int band;
        final double h;
        double g = Double.POSITIVE_INFINITY;
        double f;
        Stance stance;
        Node parent;
        Maneuver via;
        double viaCost;
        /** 走到这里时身体憋气的样子。 */
        Breath.Air air;
        /** 同一位置、已改格数或憋气的档不同的另一个节点。 */
        Node sibling;
        int heapIndex = -1;

        Node(int x, int y, int z, int used, int band, double h) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.used = used;
            this.band = band;
            this.h = h;
        }
    }

    /** 按 f 排的二叉堆,支持原地降键。 */
    private static final class Heap {
        private Node[] items = new Node[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void push(Node n) {
            if (size == items.length) {
                items = Arrays.copyOf(items, size * 2);
            }
            items[size] = n;
            n.heapIndex = size;
            size++;
            up(n.heapIndex);
        }

        void pushOrUpdate(Node n) {
            if (n.heapIndex >= 0) {
                up(n.heapIndex);
            } else {
                push(n);
            }
        }

        Node pop() {
            Node top = items[0];
            size--;
            items[0] = items[size];
            items[0].heapIndex = 0;
            items[size] = null;
            if (size > 0) {
                down(0);
            }
            top.heapIndex = -1;
            return top;
        }

        private void up(int i) {
            Node n = items[i];
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (items[parent].f <= n.f) {
                    break;
                }
                items[i] = items[parent];
                items[i].heapIndex = i;
                i = parent;
            }
            items[i] = n;
            n.heapIndex = i;
        }

        private void down(int i) {
            Node n = items[i];
            while (true) {
                int child = 2 * i + 1;
                if (child >= size) {
                    break;
                }
                if (child + 1 < size && items[child + 1].f < items[child].f) {
                    child++;
                }
                if (items[child].f >= n.f) {
                    break;
                }
                items[i] = items[child];
                items[i].heapIndex = i;
                i = child;
            }
            items[i] = n;
            n.heapIndex = i;
        }
    }
}
