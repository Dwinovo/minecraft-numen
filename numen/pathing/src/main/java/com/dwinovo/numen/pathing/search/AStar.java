package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.plan.Heading;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Move;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Quick;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.WorldView;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Bounds;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Recall;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;

/**
 * A*:从起点节点逐个展开 {@link Moves#ALL} 的每种走法、每个方向,前提成立的步子按它的代价(乘上旧路折扣)松弛。
 *
 * <ul>
 *   <li><b>节点不建对象</b>({@link Nodes}):每个节点是一个下标,位置、代价、父节点、走法放在平行数组里,堆里存下标
 *       ({@link OpenSet});</li>
 *   <li><b>先快后精</b>:每种走法先问快判断({@link Move#quick}),只读节点四周的标志字({@link com.dwinovo.numen.pathing.world.CellFlags}),
 *       前提必然不成立的当场丢掉,必然成立的直接得到落点与代价,什么也不建;四周有半砖、水、梯子这类标志字说不清的格,
 *       或要挖要放的步子,才走精确的 {@link Move#premise}。快判断得出的节点不带 {@link Maneuver}:只有交出的路线,才把路线上
 *       每一步按精确的前提重判一遍补出来({@link Pass#maneuver});</li>
 *   <li><b>身体面对的世界</b>:展开一个节点时,前提读的是快照加上走到这个节点的最近 {@value #HISTORY} 步做过的改动——刚挖开的格是空的,
 *       刚垫下的块托着脚,斜着一级一级往山上凿台阶时,下一级脚下的格正是前几步自己挖开的。更早的步子已经离得远了,不叠;</li>
 *   <li><b>一段的终点</b>({@link Search#advance}):到了目标,或到了一个估价比起点低一段的节点(停的节点照现有规矩:站得住、没憋着气)。
 *       终止条件只在这里一处;估价是每种目标统一的"还差多少",不另造"离终点多远"。搜索用的估价是对着这个终点的:
 *       {@code max(0, h − (起点的 h − 一段))},仍是下界也仍一致;</li>
 *   <li><b>先贪心拿一个解,再加权搜到证明够好为止</b>:同一个搜索跑两遍。第一遍只按估价排(权重无穷大,贪心),预算
 *       {@link #GREEDY_NODES},拿到的第一个解的价钱是上限 UB;第二遍按总价加估价的 {@link #WEIGHT} 倍排,总价加估价不低于 UB 的节点
 *       剪掉(估价是下界,剪掉的分支里不可能有比 UB 便宜的路),找到更便宜的就换上。交出手里最好的。
 *       <b>什么时候停</b>:开放集里总价加估价的最小值是这一段最优价的下界,UB 不高过它的 {@link #WEIGHT} 倍时,手里的解已证明
 *       不差于最优的这么多倍,立刻停——贪心解已经够好的不再搜,贪心解很差(绕不开的挖)证不出界的,一直搜到预算上限找别的路。
 *       证到的界(解的价钱 ÷ 下界)随结论交出({@link SearchResult#gap})。依据是 LAMA 规划器用的
 *       "先贪心、再加权、以已有解的价钱剪枝"(Restarting Weighted A*,Richter、Thayer &amp; Ruml 2010),属于随时可交的启发式搜索
 *       [Hansen &amp; Zhou 2007;ARA*,Likhachev、Gordon &amp; Thrun 2003,同样以解的价钱与下界的比停]。贪心那一遍在实心岩石里就是朝终点直挖,
 *       在地表就是朝终点直走、挡了就挖或垫——走法、前提、许可全是同一套;</li>
 *   <li><b>没搜到这一段的终点</b>(预算用完、走得到的都搜过了、有路伸进没加载的区块):LSS-LRTA* [Koenig &amp; Sun 2009]。展开过的节点的
 *       估价抬高({@link Learning},交给宿主记进 {@link TripMemory}),交出的半程路线去哪、截到哪由 {@link HandOver} 定
 *       (边界上总价最小的节点,截到回得了头的地方);另记离目标最近的节点上朝目标的步子为什么被拒({@link SearchResult.Blocking}),收场时说缺什么;</li>
 *   <li><b>预算</b>按展开的节点数计,两遍合起来;结论不随机器快慢漂移;</li>
 *   <li><b>停下的原因</b>随结论交出({@link SearchResult.Stop}):到了、到了这一段的终点、搜完无路、预算用完、有路伸进快照外没加载的区块。
 *       一步的前提读到了没加载的列,它成不成立就不知道,这一步不走、结论记成"未加载";前提没读到那些列就定了的(规格没开这种
 *       走法、紧挨着的一列就挡住了),与那边是什么无关,不算;快判断只在节点四周读全了才开口,读不全的交给精确判断去断;</li>
 *   <li><b>接着一条路线往下搜</b>:起点是那条路线的终点({@link Search#arrival}),身体到那里时怎么待着按那一步的落点算,
 *       展开起点时叠上那一步的改动——快照里还没有它垫下的块;</li>
 *   <li><b>这一趟里走不通的步</b>({@link TripMemory}):展开时跳过,当它不存在;</li>
 *   <li><b>估价</b>:疾跑底价加额外代价下界({@link Estimate}):水平距离乘疾跑一格,加上路上绕不开要挖掉的格至少多少钱、
 *       要升降几个节点每个至少多花多少,再加上这一趟记忆里抬高过的增量。每一项都是下界,搜出来的仍是最便宜的路;
 *       前两样在开搜时按世界算一次,每个节点只查表加乘法;</li>
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

    /** 贪心那一遍最多展开几个节点。 */
    static final int GREEDY_NODES = 1_000;
    /** 加权那一遍的权重:交出的路线至多比这一段的最优贵这么多倍。 */
    static final double WEIGHT = 1.5;
    /** 加权那一遍每展开几个节点重算一次开放集的下界(扫一遍堆,比展开一个节点便宜得多)。 */
    private static final int PROOF_EVERY = 32;
    /** 小于这个改进不松弛:平地上斜走与直走组合出的浮点差不值得重排堆。 */
    private static final double MIN_IMPROVEMENT = 0.01;
    /** 一个节点面对的世界叠几步之前的改动:走到它的最近这么多步。 */
    static final int HISTORY = 8;

    private final Search search;
    private final CostModel model;
    private final BodyStats body;
    private final Goal goal;
    private final boolean budgeted;
    private final int alterBudget;
    private final Breath breath;
    private final TripMemory.Frozen memory;
    /** 有前提成立的步子因为憋不住气没走。 */
    private boolean breathless;
    /** 有一步读到了没加载的列、没能判断。 */
    private boolean skippedUnloaded;
    private Estimate estimate;
    private Probe probe;
    private Quick quick;
    /** 这一段的终点在估价上的位置:估价不高过它的节点(不是目标的成员也)算到了;0 是只认目标。 */
    private double shift;

    private AStar(Search search) {
        this.search = search;
        RouteSpec spec = search.model().spec();
        this.goal = search.goal();
        this.model = Goal.guarded(goal, search.model());
        this.body = model.body().stats();
        this.budgeted = spec.budgeted();
        this.alterBudget = spec.alterBudget();
        this.breath = model.body().breath();
        this.memory = search.memory();
    }

    /** 在当前线程上跑完一次搜索;{@code cancelled} 在每展开一个节点前问一次。 */
    public static SearchResult run(Search search, BooleanSupplier cancelled) {
        return new AStar(search).run(cancelled);
    }

    private SearchResult run(BooleanSupplier cancelled) {
        SearchView view = search.view();
        BlockPos startPos = search.start();
        Maneuver arrival = search.arrival();
        Stance startStance = arrival != null ? arrival.landing() : Stance.at(view, body, startPos);
        if (startStance == null) {
            return failed(SearchResult.Stop.STRANDED, Double.NaN, 0, null);
        }
        estimate = Estimate.of(view, model, goal, startPos, search.budget(), memory, search.favoring().floor());
        double startEstimate = estimate.at(startPos);
        if (!Double.isFinite(startEstimate)) {
            // 这一趟的记忆说从这里到不了(展开过的都是死路)
            return failed(SearchResult.Stop.EXHAUSTED, startEstimate, 0, null);
        }
        shift = startEstimate - search.advance() > 0 ? startEstimate - search.advance() : 0;
        probe = new Probe(view, new Recall(body));
        quick = new Quick(model, view);

        // 这一段最优价的下界:先是起点的估价,加权搜索里随开放集的下界往上抬
        double lower = toSegment(startEstimate);
        Pass greedy = new Pass(Double.POSITIVE_INFINITY, Math.min(GREEDY_NODES, search.budget()), Double.POSITIVE_INFINITY, lower);
        greedy.run(startStance, cancelled);
        Pass found = greedy.terminal >= 0 ? greedy : null;
        Pass last = greedy;
        int expanded = greedy.expanded;
        // 贪心那一遍把能走到的地方都搜空了还没有解,加权再搜也是同一张图:不再搜
        boolean emptied = found == null && greedy.stop == null;
        boolean proven = found != null && found.total <= WEIGHT * lower;
        if (greedy.stop != SearchResult.Stop.CANCELLED && !emptied && !proven && search.budget() > expanded) {
            Pass weighted = new Pass(WEIGHT, search.budget() - expanded,
                    found != null ? greedy.total : Double.POSITIVE_INFINITY, lower);
            weighted.run(startStance, cancelled);
            expanded += weighted.expanded;
            last = weighted;
            lower = weighted.lower;
            if (weighted.terminal >= 0) {
                found = weighted;
            }
        }
        if (last.stop == SearchResult.Stop.CANCELLED) {
            return failed(SearchResult.Stop.CANCELLED, startEstimate, expanded, null);
        }
        if (found != null) {
            SearchResult.Stop stop = found.reachedGoal ? SearchResult.Stop.ARRIVED : SearchResult.Stop.SEGMENT;
            return new SearchResult(stop, found.route(found.terminal), expanded, breathless, startEstimate, null, null,
                    lower > 0 ? Math.max(1, found.total / lower) : Double.POSITIVE_INFINITY);
        }
        SearchResult.Stop stop = last.stop != null ? last.stop
                : skippedUnloaded ? SearchResult.Stop.UNLOADED : SearchResult.Stop.EXHAUSTED;
        return failed(stop, startEstimate, expanded, last);
    }

    /** 没搜到的结论:交出的半程路线、展开过的格子该抬高多少估价、离目标最近的节点上朝目标的步子为什么被拒。 */
    private SearchResult failed(SearchResult.Stop stop, double startEstimate, int expanded, Pass pass) {
        if (pass == null || stop == SearchResult.Stop.CANCELLED) {
            return new SearchResult(stop, null, expanded, breathless, startEstimate, null, null, Double.NaN);
        }
        return new SearchResult(stop, pass.advance(), expanded, breathless, startEstimate, pass.learned(), pass.blocking(),
                Double.NaN);
    }

    /** 搜索的估价到这一段的终点:不高过终点的位置时是 0。 */
    private double toSegment(double estimated) {
        return estimated > shift ? estimated - shift : 0;
    }

    /** 一遍搜索:自己的节点表与堆,共用这次搜索的快照、估价、记事本。 */
    private final class Pass {

        final double weight;
        final int budget;
        final double ub;
        /** 这一段最优价的下界:进来时的,加权搜索里随开放集往上抬(解的价钱不低于它也不高过 UB)。 */
        double lower;
        final Nodes nodes = new Nodes();
        final OpenSet open = new OpenSet(nodes);
        int start;
        int expanded;
        /** 节点里出现过的最高一档憋气({@link #dominated} 往上找到这里为止)。 */
        int topBand;
        /** 搜到的终点节点;没搜到为 -1。 */
        int terminal = -1;
        /** 搜到的终点的总价(走到加停下);没搜到为无穷大。 */
        double total = Double.POSITIVE_INFINITY;
        /** 搜到的终点在目标里(不是这一段的终点)。 */
        boolean reachedGoal;
        /** 没搜到时这一遍为什么停:预算用完、被叫停,搜空为 null(由宿主按有没有读到没加载的列分)。 */
        SearchResult.Stop stop;
        /** 离目标最近的展开过的节点(按这一段的估价)。 */
        int closest = -1;
        /** {@link #recentEdits} 找到的步子,每次调用都从头写;一遍搜索在一个线程上跑完,共用这一块。 */
        private final Maneuver[] steps = new Maneuver[HISTORY];
        // 边:从展开过的节点出去的每一步成立的走法,抬高估价时用
        int[] edgeFrom = new int[256];
        int[] edgeTo = new int[256];
        double[] edgeCost = new double[256];
        int edges;

        Pass(double weight, int budget, double ub, double lower) {
            this.weight = weight;
            this.budget = budget;
            this.ub = ub;
            this.lower = lower;
        }

        /** 总价的排序键:贪心只看估价,加权看总价加估价的 {@link #WEIGHT} 倍。 */
        private double key(double g, double toSegment) {
            return weight == Double.POSITIVE_INFINITY ? toSegment : g + weight * toSegment;
        }

        /**
         * 剪枝:总价加估价不低于已有的解的价钱 UB。估价是下界,这样的节点下面不可能有比 UB 便宜的路。
         * 剪枝不乘权重:乘了会把真正更便宜、但估价大的绕路分支先剪掉;{@link #WEIGHT} 倍的保证靠"证明够好就停"拿。
         */
        private boolean pruned(double g, double toSegment) {
            return ub != Double.POSITIVE_INFINITY && g + toSegment >= ub;
        }

        void run(Stance startStance, BooleanSupplier cancelled) {
            SearchView view = search.view();
            BlockPos startPos = search.start();
            Maneuver arrival = search.arrival();
            start = nodes.add(startPos.asLong(), 0, breath.band(search.air()), toSegment(estimate.at(startPos)));
            topBand = nodes.band[start];
            nodes.g[start] = 0;
            nodes.stance[start] = startStance;
            nodes.via[start] = arrival;
            nodes.air[start] = search.air();
            nodes.f[start] = key(0, nodes.h[start]);
            open.push(start);

            List<Move> moves = Moves.ALL;
            double closestH = Double.POSITIVE_INFINITY;
            while (!open.isEmpty()) {
                if (cancelled.getAsBoolean()) {
                    stop = SearchResult.Stop.CANCELLED;
                    return;
                }
                if (ub != Double.POSITIVE_INFINITY && expanded > 0 && expanded % PROOF_EVERY == 0) {
                    // 开放集的下界抬了:手里的解(价钱 UB)已不差于最优的 WEIGHT 倍就停,留着贪心那一遍的解
                    lower = Math.max(lower, Math.min(open.lowerBound(), ub));
                    if (ub <= WEIGHT * lower) {
                        return;
                    }
                }
                int current = open.pop();
                if (dominated(current)) {
                    // 同一格有憋气更宽裕、总价又不高的节点:它走得到的这一个都走得到,不展开;记作展开过,不当边界
                    nodes.expanded[current] = true;
                    continue;
                }
                long at = nodes.pos[current];
                int cx = BlockPos.getX(at);
                int cy = BlockPos.getY(at);
                int cz = BlockPos.getZ(at);
                List<Edit> done = recentEdits(current);
                boolean edited = !done.isEmpty();
                Stance stance = nodes.stance[current];
                // 身体此刻面对的世界:快照,加上走到这个节点的最近几步做过的改动
                WorldView here = edited ? EditedView.after(probe, done) : probe;
                // 到达价无穷的节点停下也办不成:不算到,照常往外展开
                double stopping = goal.contains(cx, cy, cz, stance)
                        ? goal.arrival(here, cx, cy, cz, stance) : Double.POSITIVE_INFINITY;
                if (Double.isFinite(stopping)) {
                    if (stopping > MIN_IMPROVEMENT && !nodes.paid[current]) {
                        // 到达价还没付:按总价放回堆里,同时照常往外展开——起点在贵的成员里时要走得出去
                        nodes.paid[current] = true;
                        nodes.f[current] = nodes.g[current] + stopping;
                        open.push(current);
                    } else {
                        terminal = current;
                        total = nodes.g[current] + stopping;
                        reachedGoal = true;
                        bound();
                        return;
                    }
                } else if (nodes.h[current] == 0 && shift > 0 && current != start && nodes.air[current].held() <= 0) {
                    // 到了这一段的终点:估价比起点低一段,站得住、没憋着气(憋着气的节点不当终点:身体停在水下等下一段,
                    // 下一段接不上就困在那里)
                    terminal = current;
                    total = nodes.g[current];
                    bound();
                    return;
                }
                if (expanded >= budget) {
                    stop = SearchResult.Stop.BUDGET;
                    return;
                }
                expanded++;
                nodes.expanded[current] = true;
                if (nodes.h[current] < closestH) {
                    closestH = nodes.h[current];
                    closest = current;
                }
                quick.begin(cx, cy, cz, stance, here, done);
                BlockPos from = null;
                for (int mi = 0; mi < moves.size(); mi++) {
                    Move move = moves.get(mi);
                    List<Heading> headings = move.headings();
                    for (int hi = 0; hi < headings.size(); hi++) {
                        Heading heading = headings.get(hi);
                        Maneuver m = null;
                        int tx;
                        int ty;
                        int tz;
                        int alterations;
                        boolean submerged;
                        double cost;
                        double ticks = Double.NaN;
                        Stance landing;
                        int verdict = move.quick(quick, heading);
                        if (verdict == Quick.REJECT) {
                            continue;
                        }
                        if (verdict == Quick.HOLDS) {
                            tx = quick.toX();
                            ty = quick.toY();
                            tz = quick.toZ();
                            alterations = 0;
                            submerged = false;
                            cost = quick.cost();
                            ticks = quick.ticks();
                            landing = quick.ground(ty);
                        } else {
                            if (from == null) {
                                from = new BlockPos(cx, cy, cz);
                            }
                            probe.recall.clearUnloaded();
                            Premise premise = move.premise(model, here, from, stance, heading);
                            if (probe.recall.unloaded()) {
                                skippedUnloaded = true;
                                nodes.holed[current] = true;
                                continue;
                            }
                            if (!(premise instanceof Premise.Holds holds)) {
                                continue;
                            }
                            m = holds.maneuver();
                            BlockPos to = m.to();
                            tx = to.getX();
                            ty = to.getY();
                            tz = to.getZ();
                            alterations = m.alterations();
                            submerged = m.submerged();
                            cost = Double.NaN;
                            landing = m.landing();
                        }
                        if (!Bounds.holdsBody(view.border(), body, tx, tz)) {
                            continue;
                        }
                        long to = BlockPos.asLong(tx, ty, tz);
                        if (memory.blocks(move.kind(), at, to)) {
                            continue;
                        }
                        int used = nodes.used[current] + alterations;
                        if (budgeted && used > alterBudget) {
                            continue;
                        }
                        if (m != null) {
                            cost = move.cost(model, m);
                        }
                        if (!(cost > 0) || Double.isInfinite(cost)) {
                            throw new IllegalStateException(move.kind() + " 从 " + new BlockPos(cx, cy, cz) + " 算出了非法的代价 " + cost);
                        }
                        Breath.Air air = nodes.air[current];
                        if (submerged || !breath.rested(air)) {
                            air = breath.after(air, submerged, m != null ? move.ticks(model, m) : ticks);
                            if (!breath.lasts(air)) {
                                breathless = true;
                                continue;
                            }
                        }
                        int band = breath.band(air);
                        double step = cost * search.favoring().factor(to);
                        double g = nodes.g[current] + step;
                        int next = nodes.find(to, budgeted ? used : 0, band);
                        if (next < 0) {
                            double h = toSegment(estimate.at(tx, ty, tz));
                            if (pruned(g, h)) {
                                continue;
                            }
                            next = nodes.add(to, budgeted ? used : 0, band, h);
                            topBand = Math.max(topBand, band);
                        } else if (pruned(g, nodes.h[next])) {
                            continue;
                        }
                        edge(current, next, step);
                        if (nodes.g[next] - g <= MIN_IMPROVEMENT) {
                            continue;
                        }
                        nodes.g[next] = g;
                        nodes.f[next] = key(g, nodes.h[next]);
                        nodes.parent[next] = current;
                        nodes.via[next] = m;
                        nodes.viaCost[next] = cost;
                        nodes.stance[next] = landing;
                        nodes.air[next] = air;
                        nodes.move[next] = (byte) mi;
                        nodes.heading[next] = (byte) hi;
                        open.pushOrUpdate(next);
                    }
                }
            }
            if (ub != Double.POSITIVE_INFINITY) {
                // 开放集搜空了:总价加估价低于 UB 的节点都展开过,没有比 UB 更便宜的路
                lower = ub;
            }
        }

        /**
         * 加权搜索按总价加权估价的顺序弹出了终点:开放集里别的节点的"总价加 {@link #WEIGHT} 倍估价"都不低于它的总价,
         * 所以最优价至少是它的总价的 1/{@link #WEIGHT}。
         */
        private void bound() {
            if (weight != Double.POSITIVE_INFINITY) {
                lower = Math.max(lower, total / weight);
            }
        }

        /**
         * 同一格(已改格数相同)里,有憋气更宽裕的一档({@link Breath#band} 0 档最宽裕,其余档越高越宽裕)、总价又不高于它的节点:
         * 那一个走得到的这一个都走得到,多余的。水里来回浮沉几下就是一个新档的节点,不去重,搜索会在浮沉的各档上铺满预算。
         */
        private boolean dominated(int n) {
            int band = nodes.band[n];
            if (band == 0) {
                return false;
            }
            long at = nodes.pos[n];
            int used = nodes.used[n];
            double g = nodes.g[n];
            int better = nodes.find(at, used, 0);
            if (better >= 0 && nodes.g[better] <= g) {
                return true;
            }
            for (int b = band + 1; b <= topBand; b++) {
                better = nodes.find(at, used, b);
                if (better >= 0 && nodes.g[better] <= g) {
                    return true;
                }
            }
            return false;
        }

        private void edge(int from, int to, double cost) {
            if (edges == edgeFrom.length) {
                edgeFrom = Arrays.copyOf(edgeFrom, edges * 2);
                edgeTo = Arrays.copyOf(edgeTo, edges * 2);
                edgeCost = Arrays.copyOf(edgeCost, edges * 2);
            }
            edgeFrom[edges] = from;
            edgeTo[edges] = to;
            edgeCost[edges] = cost;
            edges++;
        }

        // ==================== 没搜到:往边界推进、抬高估价、说缺什么 ====================

        /** 边界:没展开过的节点,和展开时有一步没看全(读到没加载的列)的节点。 */
        private boolean boundary(int n) {
            return !nodes.expanded[n] || nodes.holed[n];
        }

        /** 交出的半程路线:边界节点交给 {@link HandOver#choose} 挑去处、截到回得了头的地方;没有可交的为 null。 */
        Route advance() {
            List<HandOver.Candidate> frontier = new ArrayList<>();
            for (int n = 0; n < nodes.size; n++) {
                if (n != start && boundary(n) && Double.isFinite(nodes.g[n])) {
                    long at = nodes.pos[n];
                    int x = BlockPos.getX(at);
                    int y = BlockPos.getY(at);
                    int z = BlockPos.getZ(at);
                    frontier.add(new HandOver.Candidate(n, nodes.g[n], estimate.at(x, y, z), nodes.air[n].held() > 0));
                }
            }
            return HandOver.choose(frontier, this::route);
        }

        /**
         * 展开过的格子的估价该抬高多少({@link Learning}):格 → 这一趟记忆里该有的增量(含搜索开始时已有的)。同一格有几个节点
         * (已改格数、憋气的档不同)时取其中最小的增量——对哪一个都不高估;只有一格没展开过的节点也算,它的增量是 0。
         */
        Long2DoubleMap learned() {
            int size = nodes.size;
            boolean[] settled = new boolean[size];
            double[] h = new double[size];
            for (int n = 0; n < size; n++) {
                settled[n] = nodes.expanded[n] && !nodes.holed[n];
                BlockPos at = BlockPos.of(nodes.pos[n]);
                h[n] = estimate.at(at.getX(), at.getY(), at.getZ());
            }
            double[] raised = Learning.raise(settled, h, size, edgeFrom, edgeTo, edgeCost, edges);
            Long2DoubleOpenHashMap by = new Long2DoubleOpenHashMap();
            for (int n = 0; n < size; n++) {
                long cell = nodes.pos[n];
                double more = settled[n] ? raised[n] - h[n] : 0;
                if (!by.containsKey(cell) || more < by.get(cell)) {
                    by.put(cell, more);
                }
            }
            Long2DoubleOpenHashMap out = new Long2DoubleOpenHashMap();
            for (Long2DoubleMap.Entry e : by.long2DoubleEntrySet()) {
                if (e.getDoubleValue() > MIN_IMPROVEMENT) {
                    out.put(e.getLongKey(), memory.raised(e.getLongKey()) + e.getDoubleValue());
                }
            }
            return out;
        }

        /**
         * 离目标最近的展开过的节点上,朝目标的步子里被拒得最说明问题的一条(没开挖、没有料、挖不动、权限、基岩、岩浆……,
         * {@link com.dwinovo.numen.pathing.plan.Reason#informative}):每种走法每个方向按精确的前提再判一遍,只看水平与竖直朝目标的方向。
         * 没有为 null。
         */
        SearchResult.Blocking blocking() {
            if (closest < 0) {
                return null;
            }
            BlockPos from = BlockPos.of(nodes.pos[closest]);
            List<Edit> done = recentEdits(closest);
            WorldView here = done.isEmpty() ? probe : EditedView.after(probe, done);
            double now = estimate.base(from.getX(), from.getY(), from.getZ());
            SearchResult.Blocking best = null;
            double bestEstimate = Double.POSITIVE_INFINITY;
            for (Move move : Moves.ALL) {
                for (Heading heading : move.headings()) {
                    double toward = estimate.base(from.getX() + heading.dx(), from.getY() + heading.dy(), from.getZ() + heading.dz());
                    if (!(toward < now) || toward >= bestEstimate) {
                        continue;
                    }
                    probe.recall.clearUnloaded();
                    Premise premise = move.premise(model, here, from, nodes.stance[closest], heading);
                    if (premise instanceof Premise.Fails fails && fails.reason().informative()) {
                        best = new SearchResult.Blocking(move.kind(), from, fails);
                        bestEstimate = toward;
                    }
                }
            }
            return best;
        }

        // ==================== 路线 ====================

        /**
         * 走到节点 {@code n} 的最近 {@link AStar#HISTORY} 步做过的改动,按先后。前提读的只是身边几格,可它们可能是几步之前自己挖开的:
         * 斜着一级一级往山上凿台阶,下一级的脚下正是上一级刚挖开的头顶。只叠最后一步的话,前提会当那一格还是实心的。
         * 更早的步子离得远了(沿路折回来最多隔着几步),不叠。
         */
        private List<Edit> recentEdits(int n) {
            int count = 0;
            int total = 0;
            for (int i = 0, node = n; i < HISTORY && node >= 0; i++, node = nodes.parent[node]) {
                Maneuver m = nodes.via[node];
                if (m != null && !m.edits().isEmpty()) {
                    steps[count++] = m;
                    total += m.edits().size();
                }
            }
            if (count == 0) {
                return List.of();
            }
            if (count == 1) {
                return steps[0].edits();
            }
            List<Edit> out = new ArrayList<>(total);
            for (int i = count - 1; i >= 0; i--) {
                out.addAll(steps[i].edits());
            }
            return out;
        }

        Route route(int end) {
            List<Route.Leg> legs = new ArrayList<>();
            for (int n = end; nodes.parent[n] >= 0; n = nodes.parent[n]) {
                Maneuver m = maneuver(n);
                legs.add(new Route.Leg(m, nodes.viaCost[n], Moves.of(m.kind()).ticks(model, m), nodes.air[n]));
            }
            Collections.reverse(legs);
            return new Route(BlockPos.of(nodes.pos[start]), nodes.stance[start], legs);
        }

        /**
         * 走到节点 {@code n} 的那一步的全套事实。精确判断算出来的节点带着;快判断算出来的,按同一个走法、方向,在上一个节点
         * 此刻面对的世界上用精确的前提判一遍补出来(快判断答"成立"的步子,精确判断一定成立),补出来的留在节点上。
         */
        private Maneuver maneuver(int n) {
            Maneuver m = nodes.via[n];
            if (m != null) {
                return m;
            }
            int parent = nodes.parent[n];
            Move move = Moves.ALL.get(nodes.move[n]);
            Heading heading = move.headings().get(nodes.heading[n]);
            List<Edit> done = recentEdits(parent);
            WorldView here = done.isEmpty() ? probe : EditedView.after(probe, done);
            Premise premise = move.premise(model, here, BlockPos.of(nodes.pos[parent]), nodes.stance[parent], heading);
            if (!(premise instanceof Premise.Holds holds)) {
                throw new IllegalStateException(move.kind() + " " + heading + " 从 " + BlockPos.of(nodes.pos[parent]) + " 快判断成立,精确判断不成立:" + premise);
            }
            m = holds.maneuver();
            nodes.via[n] = m;
            return m;
        }
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
        public boolean changed(int x, int yMin, int yMax, int z) {
            return false;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = view.loadedBlockState(pos);
            if (state == null) {
                recall.touchUnloaded();
                return view.getBlockState(pos);
            }
            return state;
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
}
