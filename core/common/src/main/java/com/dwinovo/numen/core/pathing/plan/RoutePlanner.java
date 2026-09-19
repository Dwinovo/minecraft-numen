package com.dwinovo.numen.core.pathing.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.bridge.SearchDispatcher;
import com.dwinovo.numen.core.pathing.bridge.SearchHandle;
import com.dwinovo.numen.core.pathing.execute.TerrainBill;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.spec.PositionCosts;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Gate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BlockGetter;

/**
 * 只搜不走的规划查询:收目标({@link GoalCompiler.Compiled})与规格,交给搜索派发器,
 * 结论是路径加预算账({@link TerrainBill#planned})。它是全仓唯一的"只查路线"派发口——
 * 导航的无路探针与规划查询工具都是它的一次查询。
 *
 * <p><b>备选靠惩罚法</b>:要第二条时,把前面每条路踩过的格子写进
 * {@link PositionCosts} 的 stand 栏加价(倍率 {@link NavSettings#routeAlternativePenaltyFactor}),
 * 在同一目标下再搜一次;与已有候选重叠率高于 {@link NavSettings#routeAlternativeMaxOverlap}
 * 的丢弃。不引入随机种子——路线可复现是排障的前提。最多 {@link #MAX_ALTERNATIVES} 条。
 *
 * <p>候选带的是调用方给的规格(不含目标的 sacred 保护与惩罚项):sacred 是这次目标的正确性
 * 约束,搜索时由本类并进去;惩罚只为了出备选,走这条路时不该带着。
 *
 * <p>查询在 tick 线程轮询({@link Query#poll});谁发起谁轮询——导航自己每刻轮,工具面的
 * 查询交给 {@link #deliver},由服务端 tick({@link #serverTick})代为轮询并回调。
 */
public final class RoutePlanner {

    /** 一次查询最多出几条候选。 */
    public static final int MAX_ALTERNATIVES = 3;

    /**
     * 一条候选路线:直达目标的整条路。
     *
     * @param spec 走这条路该用的规格(调用方给的那份)
     * @param path 搜出来的路径(已装配移动原语)
     * @param bill 预算账
     */
    public record Candidate(RouteSpec spec, NavPath path, TerrainBill bill) {}

    private final SearchDispatcher dispatcher;
    /** 主线程建冻结搜索上下文:每次搜索一份,规格随惩罚项变。 */
    private final Function<RouteSpec, CalculationContext> contexts;
    /** 预算账读的世界(主线程)。 */
    private final BlockGetter level;
    /** 预算账问"为什么需要同意"用的裁决快照,出账时现取(主线程)。 */
    private final Supplier<Gate> gates;

    public RoutePlanner(SearchDispatcher dispatcher, Function<RouteSpec, CalculationContext> contexts,
                        BlockGetter level, Supplier<Gate> gates) {
        this.dispatcher = dispatcher;
        this.contexts = contexts;
        this.level = level;
        this.gates = gates;
    }

    /**
     * 发起一次查询(必须在 tick 线程)。
     *
     * @param realStart    身体真实脚位
     * @param start        A* 展开起点(脚下不可站时的假起点)
     * @param goal         目标契约;它的 sacred 格在搜索时禁挖禁放
     * @param spec         这次查询的规格
     * @param alternatives 想要几条候选(1..{@link #MAX_ALTERNATIVES})
     */
    public Query plan(BlockPos realStart, BlockPos start, GoalCompiler.Compiled goal, RouteSpec spec,
                      int alternatives) {
        return new Query(realStart, start, goal, spec, Math.clamp(alternatives, 1, MAX_ALTERNATIVES));
    }

    /** 一次查询:逐 tick {@link #poll},出结论前可 {@link #cancel}。 */
    public final class Query {

        private final BlockPos realStart;
        private final BlockPos start;
        private final GoalCompiler.Compiled goal;
        private final RouteSpec spec;
        private final int wanted;

        private final List<Candidate> found = new ArrayList<>();
        /** 已有候选经过的全部格子:重叠率的分母一侧,也是下一次搜索要加价的格。 */
        /** 每次搜索交出的路,含超预算作废的:惩罚按它们算,备选才会去别处找。 */
        private final List<NavPath> searched = new ArrayList<>();
        private final Set<BlockPos> covered = new HashSet<>();
        private int searches;
        private SearchHandle inFlight;
        private List<Candidate> result;
        /** 账单超出规格改动预算而作废的候选数,以及其中改动最少的那条改了几格。 */
        private int overBudget;
        private int cheapestChange = Integer.MAX_VALUE;

        private Query(BlockPos realStart, BlockPos start, GoalCompiler.Compiled goal, RouteSpec spec,
                      int wanted) {
            this.realStart = realStart;
            this.start = start;
            this.goal = goal;
            this.spec = spec;
            this.wanted = wanted;
            submit(PositionCosts.EMPTY);
        }

        private void submit(PositionCosts penalty) {
            NavSettings settings = NavSettings.get();
            RouteSpec searchSpec = goal.protecting(spec.withPositions(spec.positions().plus(penalty)));
            inFlight = dispatcher.submit(realStart, start, goal.engineGoal(), contexts.apply(searchSpec),
                    Favoring.empty(), settings.primaryTimeoutMS, settings.failureTimeoutMS);
            searches++;
        }

        /**
         * 未完成返回 null;完成后返回候选(按找到的先后;可能为空),此后每次返回同一个列表。
         * 一次搜索到不了目标即收工——朝目标推进的半截路不是路线,再加惩罚也搜不出来。
         */
        public List<Candidate> poll() {
            if (result != null) {
                return result;
            }
            PathCalcResult calc = inFlight.poll();
            if (calc == null) {
                return null;
            }
            inFlight = null;
            NavPath path = calc.getType() == PathCalcResult.Type.SUCCESS_TO_GOAL
                    ? calc.getPath().orElse(null) : null;
            if (path != null && !overlapsTooMuch(path)) {
                TerrainBill bill = TerrainBill.planned(path, level, gates.get());
                int changed = bill.breakCount() + bill.placeCount();
                if (changed > spec.alterBudget()) {
                    // 预算是规划时的约束:超了的路不算候选,但它的格子照样计入惩罚,
                    // 下一次搜索才会去别处找改动更少的路
                    overBudget++;
                    cheapestChange = Math.min(cheapestChange, changed);
                } else {
                    found.add(new Candidate(spec, path, bill));
                }
                searched.add(path);
                covered.addAll(path.positions());
            }
            if (path == null || searches >= wanted) {
                result = List.copyOf(found);
                return result;
            }
            submit(penalty());
            return null;
        }

        public boolean isDone() {
            return result != null;
        }

        /** 一条候选都没留下,而且至少有一条是因为超出改动预算才作废的。 */
        public boolean exceededBudget() {
            return found.isEmpty() && overBudget > 0;
        }

        /** 作废的候选里改动最少的那条改了几格;没有作废的候选时无意义。 */
        public int cheapestChange() {
            return cheapestChange;
        }

        public void cancel() {
            if (inFlight != null) {
                inFlight.cancel();
                inFlight = null;
            }
            result = List.copyOf(found);
        }

        /** 新路的格子落在已有候选上的比例高于阈值,就不算一条新路。 */
        private boolean overlapsTooMuch(NavPath path) {
            if (covered.isEmpty()) {
                return false;
            }
            List<BlockPos> cells = path.positions();
            int shared = 0;
            for (BlockPos p : cells) {
                if (covered.contains(p)) {
                    shared++;
                }
            }
            return (double) shared / cells.size() > NavSettings.get().routeAlternativeMaxOverlap;
        }

        /**
         * 惩罚表:已有候选踩过的每一格,踩价附加 (倍率-1)×单格步行成本——平走一格的价按倍率算,
         * 别的动作只是相对没那么贵。同一格被两条路踩过就加两次。
         */
        private PositionCosts penalty() {
            double extra = (NavSettings.get().routeAlternativePenaltyFactor - 1.0)
                    * ActionCosts.WALK_ONE_BLOCK_COST;
            PositionCosts.Builder b = PositionCosts.builder();
            for (NavPath path : searched) {
                for (BlockPos p : path.positions()) {
                    b.stand(p.asLong(), extra);
                }
            }
            return b.build();
        }
    }

    // ==================== 工具面的代轮询 ====================

    private record Pending(Query query, Consumer<List<Candidate>> onDone) {}

    private static final List<Pending> PENDING = new ArrayList<>();

    static {
        // 查询属于世界:退出存档时在飞的取消、回调作废
        com.dwinovo.numen.platform.ServerLifecycle.onStopped(RoutePlanner::dropAll);
    }

    /** 让服务端 tick 替调用方轮询这次查询,出结论时回调(主线程)。 */
    public static void deliver(Query query, Consumer<List<Candidate>> onDone) {
        PENDING.add(new Pending(query, onDone));
    }

    /** 每服务端 tick 一次:把出了结论的查询回调出去。 */
    public static void serverTick(MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            List<Candidate> candidates = p.query().poll();
            if (candidates == null) {
                continue;
            }
            it.remove();
            p.onDone().accept(candidates);
        }
    }

    private static void dropAll() {
        for (Pending p : PENDING) {
            p.query().cancel();
        }
        PENDING.clear();
    }
}
