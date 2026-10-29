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
 * 一次朝真终点的加权 A*:从起点节点逐个展开 {@link Moves#ALL} 的每种走法、每个方向,前提成立的步子按它的代价(乘上旧路折扣)松弛,
 * 开放集按"走到的代价加估价的 {@link #WEIGHT} 倍"排。
 *
 * <ul>
 *   <li><b>节点不建对象</b>({@link Nodes}):每个节点是一个下标,位置、代价、父节点、走法放在平行数组里,堆里存下标
 *       ({@link OpenSet});</li>
 *   <li><b>惰性评估</b>(Lazy Weighted A*,Cohen、Phillips、Likhachev 2014):每种走法只问快判断({@link Move#quick}),只读节点四周的
 *       标志字({@link com.dwinovo.numen.pathing.world.CellFlags}),不建草稿、不发射线、不问许可。必然不成立的当场丢掉;必然成立的
 *       直接得到落点与代价;其余(要挖要放、四周有半砖、水、梯子……)按乐观的代价放进开放集当未核的候选,取出来时才在父节点面对的
 *       世界上精确判({@link Move#premise}):不成立就丢,真价更贵就按真价排回去,成立且不贵才落成节点。一次搜索精确判断的次数是
 *       取出的候选数,不是生成的步数;交出的路线上每一步都是判过的。快判断答"成立"的节点不带 {@link Maneuver},交路线时才补出来
 *       ({@link #maneuver});许可在这次搜索里按格缓存({@link CostModel#searching});</li>
 *   <li><b>身体面对的世界</b>:展开一个节点时,前提读的是快照加上走到这个节点的最近 {@value #HISTORY} 步做过的改动——刚挖开的格是空的,
 *       刚垫下的块托着脚,斜着一级一级往山上凿台阶时,下一级脚下的格正是前几步自己挖开的。更早的步子已经离得远了,不叠;</li>
 *   <li><b>终止</b>只有几种:到了目标;到了这一段的终点——一个相对目标的估价比起点低 {@link Search#advance} 的节点(没憋着气),
 *       远路一段一段地交、身体边走边接着搜;这次的时限到了({@link Search#deadline},表是宿主给的 {@link SearchClock});展开节点的上限
 *       用完({@link Search#budget});能走到的都搜过了。估价按真终点算,段终点只是"推进够了"的停止判据,开放集的排法不因它变。
 *       加权 A*(Pohl 1970)交出的路线至多比最优贵 {@link #WEIGHT} 倍;不先贪心拿一个解;</li>
 *   <li><b>没到目标</b>(时间到、上限用完、走得到的都搜过了、有路伸进没加载的区块):LSS-LRTA* [Koenig &amp; Sun 2009]。展开过的节点的
 *       估价抬高({@link Learning},交给宿主记进 {@link TripMemory}),交出的半程路线去哪、截到哪由 {@link HandOver} 定
 *       (边界上总价最小的节点,截到回得了头的地方);另记离目标最近的节点上朝目标的步子为什么被拒({@link SearchResult.Blocking}),收场时说缺什么;</li>
 *   <li><b>停下的原因</b>随结论交出({@link SearchResult.Stop}):到了、到了这一段的终点、时间到、上限用完、搜完无路、有路伸进快照外没加载的区块。
 *       一步的前提读到了没加载的列,它成不成立就不知道,这一步不走、结论记成"未加载";前提没读到那些列就定了的(规格没开这种
 *       走法、紧挨着的一列就挡住了),与那边是什么无关,不算;快判断只在节点四周读全了才断定成立或不成立,读不全的答"可能",取出时由精确判断去断;</li>
 *   <li><b>接着一条路线往下搜</b>:起点是那条路线的终点({@link Search#arrival}),身体到那里时怎么待着按那一步的落点算,
 *       展开起点时叠上那一步的改动——快照里还没有它垫下的块;</li>
 *   <li><b>这一趟里走不通的步</b>({@link TripMemory}):展开时跳过,当它不存在;</li>
 *   <li><b>估价</b>:疾跑底价加额外代价下界({@link Estimate}):水平距离乘疾跑一格,加上路上绕不开要挖掉的格至少多少钱、
 *       要升降几个节点每个至少多花多少,再加上这一趟记忆里抬高过的增量。每一项都是下界;
 *       前两样在开搜时按世界算一次,每个节点只查表加乘法;</li>
 *   <li><b>目标格保护</b>:目标的 {@link Goal#protection()} 并进路线规格的按位置禁令,规划不挖自己要站、要够的格;</li>
 *   <li><b>改动预算</b>(规格的 {@code alterBudget})在展开时就生效:设了预算时节点按"位置加已改几格"区分,超出预算的步子
 *       不展开,所以搜出来的路一定在预算内;</li>
 *   <li><b>憋气</b>:每个节点带着走到那里时身体憋气的样子,每一步按 {@link Breath#after} 往下推(这一步眼睛换不换得了气、
 *       身体要几刻),走过去憋不住的步子不走({@link Breath#lasts})——判据只在 {@link Breath}。节点再按憋气的档
 *       ({@link Breath#band})区分:同一格,憋着气刚到的与换过气到的是两个节点,贵一点却换过气的那条不会被便宜的挤掉;</li>
 *   <li><b>到达价</b>:进了目标的节点出堆时补上它的到达价放回堆里,按总价再次出堆才收,同时照常往外展开;到达价无穷的
 *       节点停下也办不成,不算到。</li>
 * </ul>
 *
 * <p>只读 {@link Search#view()} 这份快照,单线程跑完;表按做了多少活折算({@link SearchClock#STEADY})时结论逐字节可复现,不引入随机。
 */
public final class AStar {

    /**
     * 加权的倍数:交出的路线至多比最优贵这么多倍。靶场(10-29,27 条可达路线)上扫过 1.2、1.5、2.0、2.5:到达 25/24/25/25 条,
     * 每节点 25.6/27.0/23.4/17.9 微秒,路线价合计相差不到 2%;2.5 时埋在岩石里的空腔一次搜索几百个节点就挖到,湖面、破碎地形一次搜到。
     * 分段(一段 64 格)之后又扫过 4 与 8:到达不变,中距离路线价合计高 21% 与 32%。
     */
    static final double WEIGHT = 2.5;
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
    private final Nodes nodes = new Nodes();
    private final OpenSet open = new OpenSet(nodes);
    /** {@link #recentEdits} 找到的步子,每次调用都从头写;一次搜索在一个线程上跑完,共用这一块。 */
    private final Maneuver[] steps = new Maneuver[HISTORY];
    /** 有前提成立的步子因为憋不住气没走。 */
    private boolean breathless;
    /** 有一步读到了没加载的列、没能判断。 */
    private boolean skippedUnloaded;
    private Estimate estimate;
    private Probe probe;
    private Quick quick;
    private int start;
    private int expanded;
    /** 没到目标时为什么停:时间到、上限用完、被叫停;搜空为 null(由有没有读到没加载的列分)。 */
    private SearchResult.Stop stop;
    /** 节点里出现过的最高一档憋气({@link #dominated} 往上找到这里为止)。 */
    private int topBand;
    /** 离目标最近的展开过的节点。 */
    private int closest = -1;
    /** 取出候选时精确判断的账。 */
    private final SearchResult.Checks checks = SearchResult.Checks.none();
    /**
     * 这一段的终点在估价上的位置:相对目标的估价({@link Estimate#base},不含这一趟记忆里抬高的增量)不高于它的节点(不是目标的成员也)
     * 算到了这一段的终点;只认目标时是负无穷。量的是离目标近了多少:抬高的增量只说明那片地方走不通,把它算进来,学习抬高了起点之后,
     * 没探过的远处(增量为零)会被当成"近了一段",一段段往外走。
     */
    private double segmentEnd;
    /** 搜到的终点在目标里(不是这一段的终点)。 */
    private boolean reachedGoal;
    /** 取出候选时一共做了几次精确判断:表按它与展开数折算这次搜索想了多久。 */
    private int judged;
    // 边:从展开过的节点出去的每一步成立的走法,抬高估价时用
    private int[] edgeFrom = new int[256];
    private int[] edgeTo = new int[256];
    private double[] edgeCost = new double[256];
    private int edges;

    private AStar(Search search) {
        this.search = search;
        RouteSpec spec = search.model().spec();
        this.goal = search.goal();
        this.model = Goal.guarded(goal, search.model()).searching();
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
            return new SearchResult(SearchResult.Stop.STRANDED, null, 0, false, Double.NaN, null, null, checks);
        }
        estimate = Estimate.of(view, model, goal, startPos, search.budget(), memory, search.favoring().floor());
        double startEstimate = estimate.at(startPos);
        if (!Double.isFinite(startEstimate)) {
            // 这一趟的记忆说从这里到不了(展开过的都是死路)
            return new SearchResult(SearchResult.Stop.EXHAUSTED, null, 0, false, startEstimate, null, null, checks);
        }
        probe = new Probe(view, new Recall(body));
        quick = new Quick(model, view);
        segmentEnd = estimate.base(startPos.getX(), startPos.getY(), startPos.getZ()) - search.advance();
        // 表在真开跑时才开始走:排队的时间不算这次搜索想了多久
        int terminal = search(startStance, search.deadline().start(), cancelled);
        if (terminal >= 0) {
            return new SearchResult(reachedGoal ? SearchResult.Stop.ARRIVED : SearchResult.Stop.SEGMENT, route(terminal), expanded,
                    breathless, startEstimate, null, null, checks);
        }
        if (stop == SearchResult.Stop.CANCELLED) {
            return new SearchResult(stop, null, expanded, breathless, startEstimate, null, null, checks);
        }
        SearchResult.Stop why = stop != null ? stop : skippedUnloaded ? SearchResult.Stop.UNLOADED : SearchResult.Stop.EXHAUSTED;
        return new SearchResult(why, advance(), expanded, breathless, startEstimate, learned(), blocking(), checks);
    }

    /** 开放集的排序键:走到的代价加估价的 {@link #WEIGHT} 倍。 */
    private static double key(double g, double h) {
        return g + WEIGHT * h;
    }

    /** 跑这一遍:到了目标或这一段的终点交出终点节点;没到为 -1,为什么停记在 {@link #stop}。 */
    private int search(Stance startStance, SearchClock.Timer timer, BooleanSupplier cancelled) {
        BlockPos startPos = search.start();
        start = nodes.add(startPos.asLong(), 0, breath.band(search.air()), estimate.at(startPos));
        topBand = nodes.band[start];
        nodes.g[start] = 0;
        nodes.stance[start] = startStance;
        nodes.via[start] = search.arrival();
        nodes.air[start] = search.air();
        nodes.f[start] = key(0, nodes.h[start]);
        open.push(start);

        double closestH = Double.POSITIVE_INFINITY;
        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                stop = SearchResult.Stop.CANCELLED;
                return -1;
            }
            int current = open.pop();
            if (nodes.pending[current]) {
                verify(current);
                continue;
            }
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
            Stance stance = nodes.stance[current];
            // 身体此刻面对的世界:快照,加上走到这个节点的最近几步做过的改动
            WorldView here = done.isEmpty() ? probe : EditedView.after(probe, done);
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
                    reachedGoal = true;
                    return current;
                }
            } else if (current != start && nodes.air[current].held() <= 0 && estimate.base(cx, cy, cz) <= segmentEnd) {
                // 到了这一段的终点:相对目标的估价比起点低一段,没憋着气(憋着气的节点不当终点:身体停在水下等下一段,接不上就困在那里)
                return current;
            }
            if (expanded >= search.budget()) {
                stop = SearchResult.Stop.BUDGET;
                return -1;
            }
            if (timer.up(expanded, judged)) {
                stop = SearchResult.Stop.TIME;
                return -1;
            }
            expanded++;
            nodes.expanded[current] = true;
            if (nodes.h[current] < closestH) {
                closestH = nodes.h[current];
                closest = current;
            }
            expand(current, cx, cy, cz, stance, here, done);
        }
        return -1;
    }

    /**
     * 展开一个节点:每种走法每个方向问一次快判断。答"成立"的当场松弛落点;答"可能"的按乐观的代价放进开放集当未核的候选
     * ({@link Nodes#candidate}),取出来时才精确判({@link #verify})。落点那一格已经有不比这条候选贵的节点(憋气最宽裕的那一档,
     * 已改格数不多于它)就不放:精确的代价只会更贵,它到不了更好的地方;落点是猜的({@link Quick#guessed})不在此列。
     */
    private void expand(int current, int cx, int cy, int cz, Stance stance, WorldView here, List<Edit> done) {
        SearchView view = search.view();
        long at = nodes.pos[current];
        quick.begin(cx, cy, cz, stance, here, done);
        List<Move> moves = Moves.ALL;
        for (int mi = 0; mi < moves.size(); mi++) {
            Move move = moves.get(mi);
            List<Heading> headings = move.headings();
            for (int hi = 0; hi < headings.size(); hi++) {
                int verdict = move.quick(quick, headings.get(hi));
                if (verdict == Quick.REJECT) {
                    continue;
                }
                int tx = quick.toX();
                int ty = quick.toY();
                int tz = quick.toZ();
                if (verdict == Quick.HOLDS) {
                    relax(current, mi, hi, tx, ty, tz, 0, false, quick.cost(), quick.ticks(), quick.ground(ty), null);
                    continue;
                }
                if (!Bounds.holdsBody(view.border(), body, tx, tz)) {
                    continue;
                }
                long to = BlockPos.asLong(tx, ty, tz);
                if (memory.blocks(move.kind(), at, to)) {
                    continue;
                }
                int used = budgeted ? nodes.used[current] : 0;
                double g = nodes.g[current] + quick.cost() * search.favoring().factor(to);
                boolean guessed = quick.guessed();
                int real = guessed ? -1 : settledNode(to, used, g);
                if (real >= 0) {
                    // 不放进开放集:那一格已经有不比它贵的节点。从这儿走到那个节点的路仍在,乐观价是它的下界,学习要这条边
                    edge(current, real, g - nodes.g[current]);
                    continue;
                }
                int candidate = nodes.candidate(to, guessed, used, estimate.at(tx, ty, tz), current, mi, hi, g);
                nodes.viaCost[candidate] = quick.cost();
                nodes.f[candidate] = key(g, nodes.h[candidate]);
                open.push(candidate);
            }
        }
    }

    /** 格 {@code to}(已改 {@code used} 格)上憋气最宽裕的那一档已经有走到那儿不比 {@code g} 贵的节点:是它的下标,没有为 -1。 */
    private int settledNode(long to, int used, double g) {
        int real = nodes.find(to, used, 0);
        return real >= 0 && nodes.g[real] - g <= MIN_IMPROVEMENT ? real : -1;
    }

    /**
     * 取出一条未核的候选:在父节点此刻面对的世界上精确判它那一步(Lazy Weighted A*)。不成立就丢掉;读到没加载的列,父节点记成往外
     * 没看全;成立就按真价松弛它真正的落点——真价不比估价贵,落点的排序键就是这条候选的,下一个出堆的就是它;贵了就按真价排回去。
     * 取出时落点那一格已经有不比它贵的节点,不判。
     */
    private void verify(int candidate) {
        int parent = nodes.parent[candidate];
        int real = nodes.guessed[candidate] ? -1 : settledNode(nodes.pos[candidate], nodes.used[candidate], nodes.g[candidate]);
        if (real >= 0) {
            edge(parent, real, candidateStep(candidate));
            return;
        }
        int mi = nodes.move[candidate];
        int hi = nodes.heading[candidate];
        Move move = Moves.ALL.get(mi);
        int kind = move.kind().ordinal();
        List<Edit> done = recentEdits(parent);
        WorldView here = done.isEmpty() ? probe : EditedView.after(probe, done);
        probe.recall.clearUnloaded();
        Premise premise = move.premise(model, here, BlockPos.of(nodes.pos[parent]), nodes.stance[parent],
                move.headings().get(hi));
        if (probe.recall.unloaded()) {
            skippedUnloaded = true;
            nodes.holed[parent] = true;
            return;
        }
        checks.checked()[kind]++;
        judged++;
        if (!(premise instanceof Premise.Holds holds)) {
            checks.refused()[kind]++;
            return;
        }
        Maneuver m = holds.maneuver();
        BlockPos to = m.to();
        double cost = move.cost(model, m);
        if (nodes.g[parent] + cost * search.favoring().factor(to.asLong()) > nodes.g[candidate] + MIN_IMPROVEMENT) {
            checks.dearer()[kind]++;
        }
        relax(parent, mi, hi, to.getX(), to.getY(), to.getZ(), m.alterations(), m.submerged(), cost, move.ticks(model, m),
                m.landing(), m);
    }

    /**
     * 从节点 {@code from} 按第 {@code mi} 种走法第 {@code hi} 个方向走到 {@code (tx, ty, tz)} 的一步已经判定成立:世界边界、这一趟走不通
     * 的步、改动预算、憋气都过了,就按它的代价(乘上旧路折扣)松弛落点。快判断答"成立"的没有 {@link Maneuver}({@code m} 为 null),
     * 交路线时才补出来({@link #maneuver})。
     */
    private void relax(int from, int mi, int hi, int tx, int ty, int tz, int alterations, boolean submerged, double cost,
                       double ticks, Stance landing, Maneuver m) {
        Move move = Moves.ALL.get(mi);
        if (!Bounds.holdsBody(search.view().border(), body, tx, tz)) {
            return;
        }
        long to = BlockPos.asLong(tx, ty, tz);
        if (memory.blocks(move.kind(), nodes.pos[from], to)) {
            return;
        }
        int used = nodes.used[from] + alterations;
        if (budgeted && used > alterBudget) {
            return;
        }
        if (!(cost > 0) || Double.isInfinite(cost)) {
            throw new IllegalStateException(move.kind() + " 从 " + BlockPos.of(nodes.pos[from]) + " 算出了非法的代价 " + cost);
        }
        Breath.Air air = nodes.air[from];
        if (submerged || !breath.rested(air)) {
            air = breath.after(air, submerged, ticks);
            if (!breath.lasts(air)) {
                breathless = true;
                return;
            }
        }
        int band = breath.band(air);
        double step = cost * search.favoring().factor(to);
        double g = nodes.g[from] + step;
        int next = nodes.find(to, budgeted ? used : 0, band);
        if (next < 0) {
            next = nodes.add(to, budgeted ? used : 0, band, estimate.at(tx, ty, tz));
            topBand = Math.max(topBand, band);
        }
        edge(from, next, step);
        if (nodes.g[next] - g <= MIN_IMPROVEMENT) {
            return;
        }
        nodes.g[next] = g;
        nodes.f[next] = key(g, nodes.h[next]);
        nodes.parent[next] = from;
        nodes.via[next] = m;
        nodes.viaCost[next] = cost;
        nodes.stance[next] = landing;
        nodes.air[next] = air;
        nodes.move[next] = (byte) mi;
        nodes.heading[next] = (byte) hi;
        open.pushOrUpdate(next);
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

    /** 未核的候选那一步在搜索里记的乐观价(含旧路折扣):真价不会比它低。 */
    private double candidateStep(int candidate) {
        return nodes.viaCost[candidate] * search.favoring().factor(nodes.pos[candidate]);
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

    // ==================== 没到目标:往边界推进、抬高估价、说缺什么 ====================

    /**
     * 边界(可以交出去走的去处):没展开过的节点,和展开时有一步没看全(读到没加载的列)的节点;未核的候选还没确认走得通,不算。
     * 学习另当别论({@link #learned}):那里没取出的候选算边界。
     */
    private boolean boundary(int n) {
        return !nodes.pending[n] && (!nodes.expanded[n] || nodes.holed[n]);
    }

    /** 交出的半程路线:边界节点交给 {@link HandOver#choose} 挑去处、截到回得了头的地方;没有可交的为 null。 */
    private Route advance() {
        List<HandOver.Candidate> frontier = new ArrayList<>();
        for (int n = 0; n < nodes.size; n++) {
            if (n != start && boundary(n) && Double.isFinite(nodes.g[n])) {
                long at = nodes.pos[n];
                frontier.add(new HandOver.Candidate(n, nodes.g[n],
                        estimate.at(BlockPos.getX(at), BlockPos.getY(at), BlockPos.getZ(at)), nodes.air[n].held() > 0));
            }
        }
        return HandOver.choose(frontier, this::route);
    }

    /**
     * 展开过的格子的估价该抬高多少({@link Learning},边界含没取出的未核候选):格 → 这一趟记忆里该有的增量(含搜索开始时已有的)。同一格有几个节点
     * (已改格数、憋气的档不同)时取其中最小的增量——对哪一个都不高估;只有一格没展开过的节点也算,它的增量是 0。
     */
    private Long2DoubleMap learned() {
        int size = nodes.size;
        // 没取出的未核候选(还在开放集里)是出路:从展开过的父节点出去的一步,乐观价是真价的下界,落点的估价是落点的下界。
        // 不算进来,可挖可放时出口全是候选的节点会被抬成无穷,违反"抬过的仍是下界"
        for (int n = 0; n < size; n++) {
            if (nodes.pending[n] && nodes.heapIndex[n] >= 0) {
                edge(nodes.parent[n], n, candidateStep(n));
            }
        }
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
            if (nodes.pending[n]) {
                continue;
            }
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
    private SearchResult.Blocking blocking() {
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

    private Route route(int end) {
        List<Route.Leg> legs = new ArrayList<>();
        for (int n = end; nodes.parent[n] >= 0; n = nodes.parent[n]) {
            Maneuver m = maneuver(n);
            legs.add(new Route.Leg(m, nodes.viaCost[n], Moves.of(m.kind()).ticks(model, m), nodes.air[n]));
        }
        Collections.reverse(legs);
        return new Route(BlockPos.of(nodes.pos[start]), nodes.stance[start], legs);
    }

    /**
     * 走到节点 {@code n} 的那一步的全套事实。精确判断算出来的节点带着;快判断答"成立"得出的,按同一个走法、方向,在上一个节点
     * 此刻面对的世界上用精确的前提判一遍补出来,补出来的留在节点上。快判断答"成立"的步子精确判断一定成立(单测在随机世界上
     * 逐节点逐走法逐方向核对),这里不成立就是快判断的推导错了,当场报出来。
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
