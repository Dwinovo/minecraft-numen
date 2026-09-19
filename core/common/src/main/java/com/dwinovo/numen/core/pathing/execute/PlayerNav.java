package com.dwinovo.numen.core.pathing.execute;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import java.util.LinkedHashMap;
import java.util.Map;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.bridge.PoolSearchDispatcher;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.plan.RouteBook;
import com.dwinovo.numen.core.pathing.plan.RoutePlanner;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.core.pathing.util.NavProfiler;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.Permission;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 任务层的导航正门:把一份编译好的导航契约({@link GoalCompiler.Compiled})
 * 交给段规划状态机 {@link PathingCore} 驱动,对外维持三态合同
 * ({@link Status}):
 * <ul>
 *   <li>caller 的 {@code reached} 谓词永远最先判、判真即 ARRIVED;</li>
 *   <li>搜索目标在脚下即满足(无路可走)时稳定持续返回 ARRIVED——任务层
 *       据此判"到位却不满足"(STANCE_DUD)并自行处置,绝不衰变成一跳
 *       而过的 target lost;</li>
 *   <li>目标中心移动超过 2 格重根;执行失败由状态机自动重搜,本层只做
 *       放弃判定的记账(见 {@link #accountReplan})。</li>
 * </ul>
 *
 * <p>引擎自身无限重试;"何时放弃"是任务过程层的语义,住在这里:连续
 * {@link #MAX_STALLED_REPLANS} 次失败重规划目标启发值无 ≥{@link #REPLAN_PROGRESS_EPS_H}
 * 改善 → FAILED(BOXED_IN);{@link #MAX_REPLANS} 次硬保险丝兜底。
 *
 * <p>这次导航的路线规格({@link RouteSpec})在构造时定死:提供者给的规格,加上
 * speed 折成的疾跑门;每次拉目标再把 sacred(自身目标格,不可挖不可埋)作为按位置
 * 的代价并进去。本导航建的每一个 {@link CalculationContext}——搜索用冻结快照、
 * 执行期复核用活世界——带的都是同一份规格,同一把尺。
 *
 * <p><b>不动地形的路找不到时</b>(规格不改地形的首段 NO_PATH),不立刻终局:
 * 用可自然改动的规格向 {@link RoutePlanner} 只搜不走地查一次候选路线,自然改动也没路再用
 * {@link RouteSpec.Alter#ANY} 查一次(候选的账单标出哪些格要主人同意);每条带预算账
 * ({@link TerrainBill}),记进这具身体的 {@link RouteBook},清单交给任务层——裁决是
 * {@link FailureType#TERRAIN_BLOCKED},模型读清单挑一条({@code goto route:<id>})或换目的地。
 * 引擎不替它猜"这是不是玩家的房子":木板玻璃在地表是房子,石头泥土在地下不是,这个判断归模型。
 *
 * <p><b>开走之前问主人</b>:规格是 {@link RouteSpec.Alter#ANY} 时,每一段路被采纳之前出一张预算账,
 * 里面有要问主人的格就扣住不走({@link #consentNeeded}),由任务发起征询;主人答应之后
 * ({@link #consentGranted})按新的授权重看,都覆盖了才开走。别的规格下这些格的代价是 INF,
 * 规划不出穿过它们的路,不需要这道口。
 */
public final class PlayerNav {

    public enum Status { RUNNING, ARRIVED, FAILED }

    /** 重规划次数硬保险丝(理智上限,不是真正的放弃条件)。 */
    private static final int MAX_REPLANS = 200;
    /** 真正的放弃条件:连续这么多次失败重规划都没有真实接近目标。 */
    private static final int MAX_STALLED_REPLANS = 6;
    /**
     * 算作"真实接近"的启发值降幅——约等于按目标自己的尺度前进一格
     * (平走约 3.56 加权、爬升约 3.16、下降约 3.89)。进度以目标启发
     * 函数在脚下的取值度量,不用到 center() 的欧氏距离:yLevel 目标的
     * center 是 (0, level, 0),欧氏距离被无意义的水平偏移淹没。
     */
    private static final double REPLAN_PROGRESS_EPS_H = 4.0;
    /** 目标中心移动超过该距离平方(2 格)即重根。 */
    private static final double GOAL_MOVED_SQR = 4.0;

    /**
     * 活目标({@link #trackGoal})的<b>重规划节拍</b>(刻)。
     *
     * <p>只按 {@code center()} 位移触发是不够的:危险场那种目标,{@code center()} 只是她要打的
     * 那一只,<b>别的怪走动完全不改变它</b>。而威胁坐标是开路那一刻的快照,于是她躲的是几秒前
     * 那些怪站过的地方——场是对的,读数是旧的。
     *
     * <p>五刻约合怪走一格,短程 A* 一次很快,这个节拍买回来的是"滚动时域真的在滚"。
     */
    private static final int LIVE_GOAL_REPLAN_TICKS = 5;

    private final NumenPlayer player;
    private final Supplier<GoalCompiler.Compiled> compiledSupplier;
    private final BooleanSupplier reached;
    private final ContextProvider contextProvider;
    private final boolean revalidateGoalEachTick;
    /**
     * 这次导航的路线规格(不含按目标变化的位置代价)。speed 参数保留在签名上;
     * 执行体系不支持变速(移动全走原版输入物理),speed &lt; 1.0 表示"慢速",
     * 折成规格里的疾跑关——搜索上下文的 canSprint 与执行器的逐 tick 疾跑决策
     * 读的都是这一份。
     */
    private final RouteSpec spec;

    /** 段规划状态机:搜索派发、段执行、无缝接段、失败自动重搜全在其内。 */
    private final PathingCore core;
    /** 只搜不走的查询口(无路探针走它),与状态机同一派发器、同一上下文来源。 */
    private final RoutePlanner planner;

    /**
     * 最近一次拉取的目标契约(goal 与 sacred 一体)。每次拉目标同步刷新——规格里的
     * 位置保护从它派生,不允许 goal 与 sacred 分开读。
     */
    private GoalCompiler.Compiled contract;

    /**
     * 搜索目标在脚下即满足、而 caller 的 reached 仍不满足:钉稳 ARRIVED
     * 结论供任务层判 STANCE_DUD(换站位、拉黑该成员),绝不衰变成撒谎
     * 的一跳 target lost。目标真移动时照常重根清位。
     */
    private boolean searchSatisfied;
    /** 钉稳时的目标。活目标每刻重取,按它定下的"停在脚下"不再算数({@link Goal#keepsStop})就作废、重新搜。 */
    private Goal settledGoal;
    /** 最近一次下发给状态机的目标中心(重根判定的基准)。 */
    private BlockPos plannedCenter;

    private int replans;
    private int ticksSincePlan;
    private int stalledReplans;
    /** 脚下到过的最优(最低)目标启发值——停滞重规划的量尺。 */
    private double bestGoalH = Double.MAX_VALUE;
    private String failReason = "target unreachable";
    private FailureType failType = FailureType.NO_PATH;
    /** 最近一次执行失败的原因(放弃报告里点名反复失败的动作)。 */
    private String lastExecFailure;
    /** 终局闩:FAILED 一经裁定即稳定持续(failReason 不再被覆写)。 */
    private boolean failedTerminal;
    private boolean stopped;

    /** 最近一次搜索上下文(验尸文案的素材:有无脚手架耗材等)。 */
    private CalculationContext lastSearchContext;

    /**
     * 无路时要不要查"若许改地形有哪几条路"。默认不查:查询要站着等几秒,追怪/跟随这类
     * 活目标的导航等不起,也没人会为了够一只僵尸去拆墙。goto 与接近类交互任务开它——
     * 那里的失败回执是模型下一步决策的依据,候选清单值这几秒。
     */
    private boolean terrainProbe;
    /** 在飞的候选路线查询;非空时本导航原地等它出结论,不再驱动状态机。 */
    private RoutePlanner.Query probe;
    /** 在飞查询用的改动档:先 NATURAL,没路再 ANY。 */
    private RouteSpec.Alter probeAlter;
    /** 扣着的那段路要问主人的清单(采纳时出账算好)。 */
    private List<ConsentItem> heldConsent = List.of();
    /** 查询所针对的目标契约(候选记入路线簿时带上)。 */
    private GoalCompiler.Compiled probeGoal;
    /**
     * 有限改动预算下的整路规划;非空时原地等它出结论。预算是规划时的约束——直接派搜索
     * 会绕过它,所以设了预算的导航先按规格规划整条路,预算内才采纳。
     */
    private RoutePlanner.Query budgetPlan;
    /** 预算已核过(采纳了预算内的整路,或走的是规划好的路线)。 */
    private boolean budgetPlanned;

    /** 单格目标:按意图编译(可走格=站上去,占用格=贴脸即到,不吞噬目标)。 */
    public PlayerNav(NumenPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, speed, reached,
                () -> GoalCompiler.block(player.level(), goal, ContextProvider.DEFAULT.spec()));
    }

    /** 可移动的单格目标:每次拉取重新按意图编译(格位腾空后收紧为站上去)。 */
    public PlayerNav(NumenPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, speed, reached, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null
                    : GoalCompiler.block(player.level(), g, ContextProvider.DEFAULT.spec());
        });
    }

    /** 编译契约正门:goal + sacred + 到达原料一体下发。 */
    public static PlayerNav to(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, compiled);
    }

    /** 编译契约正门,带任务专用的搜索/执行成本上下文。 */
    public static PlayerNav to(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                               double speed, BooleanSupplier reached,
                               ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, compiled, false, contextProvider);
    }

    /** 编译契约正门,并在每 tick 重新读取目标与保护格。 */
    public static PlayerNav toRevalidating(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                           double speed, BooleanSupplier reached,
                                           ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, compiled, true, contextProvider);
    }

    /** 同上,用缺省搜索/执行上下文。 */
    public static PlayerNav toRevalidating(NumenPlayer player, Supplier<GoalCompiler.Compiled> compiled,
                                           double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, compiled, true);
    }

    /** 裸自定义目标(avoid、column 等)。不带 sacred——有方块目标的意图
     *  应走 {@link #to} / {@link GoalCompiler},让目标受保护。 */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier));
    }

    /** 同上,带任务专用的搜索/执行成本上下文(挖矿等要改地形的意图从这儿进)。 */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached,
                                   ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier), false, contextProvider);
    }

    /**
     * 目标<b>会动</b>的裸目标导航。两件事:每 tick 重新校验她还在不在目标里,走出去了就接着走;
     * 每 {@link #LIVE_GOAL_REPLAN_TICKS} 刻重取一次目标,让威胁快照跟上。
     *
     * <p>{@link #toGoal} 一旦搜索满足就永久返回 {@code ARRIVED},不再驱动移动 —— 那对固定
     * 坐标是对的,对"跟着一只怪保持站位"就成了站死。追击与近身走位都要这一个。
     */
    public static PlayerNav trackGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                      double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier), true);
    }

    /** 同上,带任务专用的搜索/执行成本上下文。 */
    public static PlayerNav trackGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                      double speed, BooleanSupplier reached,
                                      ContextProvider contextProvider) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier), true, contextProvider);
    }

    /**
     * 开启无路探针:只走不改找不到路时,查可改地形的候选路线并把清单列给任务层
     * (见类文档)。建好导航、首次 tick 前调用。
     */
    public PlayerNav withTerrainProbe() {
        this.terrainProbe = true;
        return this;
    }

    /** 身体离路线起点最多这么远(格,平方)还算"还在起点上"。 */
    private static final double ROUTE_START_SQR = 4.0;

    /**
     * 沿路线簿里的一条路走:目标与规格都是那条路的;缓存的路径还有效——身体没离开起点、
     * 首段此刻仍可走——就直接走它,否则在同一规格下重算。中途被堵也在同一规格下重算,
     * 不会偷偷换成另一种走法。
     */
    public static PlayerNav alongRoute(NumenPlayer player, RouteBook.Route route, BooleanSupplier reached) {
        PlayerNav nav = new PlayerNav(player, 1.0, reached, route::goal, false,
                ContextProvider.of(route.spec()));
        nav.contract = route.goal();
        nav.plannedCenter = route.goal().goal().center();
        nav.seed(route);
        return nav.withTerrainProbe();
    }

    /** 缓存路径的有效性只在这里判:起点两格内、首段用活世界复核仍可行。 */
    private void seed(RouteBook.Route route) {
        if (route.path().movements().isEmpty()) {
            return;   // 起点即目标:状态机自己会判脚下满足
        }
        if (PathExecutor.playerFeet(player).distSqr(route.start()) > ROUTE_START_SQR) {
            Constants.LOG.info("[numen-path] 路线 {} 的起点 {} 离身体太远,同一规格下重算",
                    route.id(), route.start().toShortString());
            return;
        }
        Movement first = route.path().movements().get(0);
        if (first.calculateCost(executionContext(), new MutableMoveResult()) >= COST_INF) {
            Constants.LOG.info("[numen-path] 路线 {} 的首段此刻走不了,同一规格下重算", route.id());
            return;
        }
        core.seed(route.goal().engineGoal(), route.path());
        budgetPlanned = true;
    }

    /** 把裸目标包成无 sacred 的编译契约(engineGoal 经词表映射同步派生)。 */
    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        return () -> {
            NavGoal g = goals.get();
            return g == null ? null
                    : new GoalCompiler.Compiled(g, LongSets.emptySet());
        };
    }

    private PlayerNav(NumenPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier) {
        this(player, speed, reached, compiledSupplier, false, ContextProvider.DEFAULT);
    }

    private PlayerNav(NumenPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier,
                      boolean revalidateGoalEachTick) {
        this(player, speed, reached, compiledSupplier, revalidateGoalEachTick,
                ContextProvider.DEFAULT);
    }

    private PlayerNav(NumenPlayer player, double speed, BooleanSupplier reached,
                      Supplier<GoalCompiler.Compiled> compiledSupplier,
                      boolean revalidateGoalEachTick, ContextProvider contextProvider) {
        this.player = player;
        this.compiledSupplier = compiledSupplier;
        this.reached = reached;
        this.contextProvider = contextProvider == null ? ContextProvider.DEFAULT : contextProvider;
        this.revalidateGoalEachTick = revalidateGoalEachTick;
        RouteSpec provided = this.contextProvider.spec();
        this.spec = speed >= 1.0 ? provided : provided.withSprint(false);
        this.core = new PathingCore(player, PoolSearchDispatcher.INSTANCE,
                this::searchContext, this::executionContext, spec, this::admits);
        this.planner = new RoutePlanner(PoolSearchDispatcher.INSTANCE,
                s -> this.contextProvider.forSearch(player, s), player.level(),
                () -> Permission.gateFor(player));
    }

    /**
     * 一段路能不能开走:{@link RouteSpec.Alter#ANY} 下出一张预算账,没有要问主人的格才放行;
     * 要问的清单记下来({@link #consentNeeded})。判据就是账单里权限层填的那一条,这里不另判。
     */
    private boolean admits(NavPath path) {
        if (spec.alter() != RouteSpec.Alter.ANY) {
            return true;
        }
        heldConsent = TerrainBill.planned(path,
                com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(player.level()),
                Permission.gateFor(player)).consentItems();
        return heldConsent.isEmpty();
    }

    /** 扣着等主人点头的那段路要问的清单;没有扣着的路是空表。 */
    public List<ConsentItem> consentNeeded() {
        return core.heldPath() == null ? List.of() : heldConsent;
    }

    /** 主人答应了:按新的授权重看扣着的那段路,都覆盖了就开走;还有没覆盖的就继续扣着。 */
    public void consentGranted() {
        NavPath held = core.heldPath();
        if (held != null && admits(held)) {
            core.releaseHeld();
        }
    }

    /** 这次搜索/复核用的规格:导航规格加上当前目标的 sacred 格(禁挖禁放)。 */
    private RouteSpec routeSpec() {
        return contract == null ? spec : contract.protecting(spec);
    }

    /** 搜索用冻结上下文:快照世界 + 快照背包 + 本次规格。 */
    private CalculationContext searchContext() {
        CalculationContext ctx = contextProvider.forSearch(player, routeSpec());
        lastSearchContext = ctx;
        return ctx;
    }

    /** 执行期复核用实时上下文:活世界 + 当下背包,同一份规格。 */
    private CalculationContext executionContext() {
        return contextProvider.forExecution(player, routeSpec());
    }

    /**
     * 一次导航的成本上下文来源:搜索用冻结快照、执行用活世界,外加这次导航的路线规格。
     * 规格只在这里定一次——上下文按它折成本,移动原语按它决定能不能顺手放一块。
     */
    public interface ContextProvider {
        /** 缺省:只走不改。接近类动作全部用它,忘了指定也只会更保守。 */
        ContextProvider DEFAULT = of(RouteSpec.defaults());
        /** 可自然改动:挖矿这类天然要动地形的意图。 */
        ContextProvider NATURAL = of(RouteSpec.defaults().withAlter(RouteSpec.Alter.NATURAL));

        static ContextProvider of(RouteSpec spec) {
            return new ContextProvider() {
                @Override
                public RouteSpec spec() {
                    return spec;
                }

                @Override
                public CalculationContext forSearch(NumenPlayer player, RouteSpec spec) {
                    return ContextFactory.forSearch(player, spec);
                }

                @Override
                public CalculationContext forExecution(NumenPlayer player, RouteSpec spec) {
                    return ContextFactory.forExecution(player, spec);
                }
            };
        }

        /** 这次导航的路线规格(不含按目标变化的位置代价,那由导航自己并进去)。 */
        RouteSpec spec();

        CalculationContext forSearch(NumenPlayer player, RouteSpec spec);

        CalculationContext forExecution(NumenPlayer player, RouteSpec spec);
    }

    /** ARRIVED-IN-PLACE 已打点(边沿去重)。 */
    private boolean arrivedInPlaceLogged;

    public Status tick() {
        NavProfiler.tickFrame();
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }
        if (failedTerminal) {
            return Status.FAILED;
        }
        if (stopped) {
            failReason = "target lost";
            failType = FailureType.TARGET_LOST;
            return Status.FAILED;
        }
        if (probe != null) {
            return pollProbe();
        }
        // 步行导航驱动的是脚下的身体:坐着任何载具都先下来——乘客的行走输入对载具
        // 无效,不在这儿下就坐着"走"到失速。全仓步行任务共用这一处,别在任务层各判各的。
        // 放在 reached 之后:已经到位就不惊动座驾(跟主人同船漂着的 follow 不该把她甩下去)。
        if (player.isPassenger()) {
            player.stopRiding();
        }

        // goal 与 sacred 一体拉取——两者是同一份契约
        long tCompile = NavProfiler.begin();
        GoalCompiler.Compiled compiled = compiledSupplier.get();
        NavProfiler.end("goal.compile", tCompile);
        if (compiled == null) {
            return fail(FailureType.TARGET_LOST, "target lost");
        }
        contract = compiled;
        NavGoal navGoal = compiled.goal();

        // 目标中心移动 >2 格:重根(进度量尺随之复位,状态机自会软取消旧段)
        if (plannedCenter != null && navGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            searchSatisfied = false;
            bestGoalH = Double.MAX_VALUE;
            ticksSincePlan = 0;
            plannedCenter = navGoal.center();
            core.setGoalAndPath(compiled.engineGoal());
            return Status.RUNNING;
        }
        // 活目标:到点就重取一次。进度量尺不复位——那是给"卡住了"用的,不该被节拍抹平。
        if (revalidateGoalEachTick && ++ticksSincePlan >= LIVE_GOAL_REPLAN_TICKS) {
            ticksSincePlan = 0;
            searchSatisfied = false;
            plannedCenter = navGoal.center();
            core.setGoalAndPath(compiled.engineGoal());
            return Status.RUNNING;
        }
        if (plannedCenter == null) {
            plannedCenter = navGoal.center();
        }

        if (searchSatisfied) {
            if (!revalidateGoalEachTick) {
                return Status.ARRIVED;
            }
            BlockPos feet = PathExecutor.playerFeet(player);
            if (Goal.keepsStop(settledGoal, compiled.engineGoal(), feet.getX(), feet.getY(), feet.getZ())) {
                return Status.ARRIVED;
            }
            searchSatisfied = false;
        }

        if (spec.budgeted() && spec.alter().mayAlter() && !budgetPlanned) {
            Status planning = planWithinBudget(compiled);
            if (planning != null) {
                return planning;
            }
        }

        PathExecutor before = core.getCurrent();
        long tExec = NavProfiler.begin();
        // 状态机空闲(初次、或结果被判孤儿丢弃)时(重新)下发目标;
        // setGoalAndPath 已在目标内/已有段/已有在飞搜索时自会不派发
        if (revalidateGoalEachTick || core.getGoal() == null
                || (core.getCurrent() == null && !core.hasInProgressSearch())) {
            core.setGoalAndPath(compiled.engineGoal());
        }
        core.tick();
        NavProfiler.end("core.tick", tExec);

        // 首段搜索失败:验尸并终局。裁定前再问一次 caller 谓词——本 tick
        // 身体可能已挪进满足位,ARRIVED 优先于失败结论
        if (core.calcFailedLastTick()) {
            if (reached.getAsBoolean()) {
                return Status.ARRIVED;
            }
            // 找不到路:先查放宽一档的候选路线,把每条会动什么列出来再裁决——模型要的是带价签的
            // 选项,不是一句 no path。只走不改的先查自然改动;自然改动的直接查连要主人同意的格也算的
            boolean preserving = !spec.alter().mayAlter();
            if (terrainProbe && spec.alter() != RouteSpec.Alter.ANY
                    && submitProbe(compiled, preserving ? RouteSpec.Alter.NATURAL : RouteSpec.Alter.ANY)) {
                InputDriver.halt(player);
                return Status.RUNNING;
            }
            return fail(FailureType.NO_PATH, noPathAutopsy(navGoal,
                    preserving ? " without altering terrain" : ""));
        }

        // 执行失败(段被取消,状态机已自动重搜):做放弃判定的记账
        PathExecutor after = core.getCurrent();
        if (before != null && after != before && before.failed()) {
            lastExecFailure = before.failureCause();
            Status verdict = accountReplan(navGoal);
            if (verdict != null) {
                return verdict;
            }
        }

        // 到达判定:状态机归于空闲且脚下满足搜索目标 → 稳定 ARRIVED。
        // 覆盖两种情形:路径走完进入目标;以及"原地即满足"(setGoalAndPath
        // 因脚下已在目标内根本不派发搜索)。
        Goal engineGoal = core.getGoal();
        if (engineGoal != null && core.getCurrent() == null && !core.hasInProgressSearch()) {
            BlockPos feet = PathExecutor.playerFeet(player);
            if (engineGoal.isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                searchSatisfied = true;
                settledGoal = engineGoal;
                if (!arrivedInPlaceLogged) {
                    // 只在进入边沿打一次:任务层反复重建导航时,同一驻留会逐 tick 重进
                    // 这个分支,连续打点是日志洪水
                    arrivedInPlaceLogged = true;
                    Constants.LOG.info(
                            "[numen-path] ARRIVED-IN-PLACE feet={} goal-center={} —— 搜索目标在脚下"
                                    + "即满足,钉稳结论交任务层裁决",
                            feet.toShortString(), plannedCenter.toShortString());
                }
                return Status.ARRIVED;
            }
        }
        return Status.RUNNING;
    }

    /**
     * 一次失败重规划的记账。进度按目标自己的启发函数在脚下的取值度量
     * (yLevel 只看竖直、column 只看水平、composite 看最近成员,各自天然正确);有真实改善清零连击,否则连击到
     * {@link #MAX_STALLED_REPLANS} 判 BOXED_IN。返回 null 表示继续跑。
     */
    private Status accountReplan(NavGoal liveGoal) {
        BlockPos feet = PathExecutor.playerFeet(player);
        double h = liveGoal.progressHeuristic(feet);
        if (bestGoalH - h >= REPLAN_PROGRESS_EPS_H) {
            bestGoalH = h;
            stalledReplans = 0;
        } else if (++stalledReplans >= MAX_STALLED_REPLANS) {
            Status verdict = fail(FailureType.BOXED_IN,
                    "gave up: no real progress toward the target over "
                            + MAX_STALLED_REPLANS + " consecutive attempts"
                            + (lastExecFailure != null
                                    ? "; the recurring failure: " + lastExecFailure : ""));
            return reached.getAsBoolean() ? Status.ARRIVED : verdict;
        }
        if (replans++ >= MAX_REPLANS) {
            Status verdict = fail(FailureType.BOXED_IN,
                    "gave up after " + MAX_REPLANS + " replans");
            return reached.getAsBoolean() ? Status.ARRIVED : verdict;
        }
        return null;
    }

    /** 终局裁定:停下身体、钉住原因,此后 tick 稳定返回 FAILED。 */
    private Status fail(FailureType type, String reason) {
        failReason = reason;
        failType = type;
        failedTerminal = true;
        core.forceCancel();
        return Status.FAILED;
    }

    /**
     * 查一次"若许这一档改动有哪几条路":与状态机同一派发器、同一目标、同一起点,只把规格的改动档
     * 换成 {@code alter},要 {@link RoutePlanner#MAX_ALTERNATIVES} 条;只搜不走。
     *
     * @return 是否真的派出去了(派不出去时直接按 NO_PATH 裁决)
     */
    private boolean submitProbe(GoalCompiler.Compiled compiled, RouteSpec.Alter alter) {
        BlockPos start = core.pathStart();
        if (start == null) {
            return false;
        }
        probe = planner.plan(PathExecutor.playerFeet(player), start, compiled,
                spec.withAlter(alter), RoutePlanner.MAX_ALTERNATIVES);
        probeGoal = compiled;
        probeAlter = alter;
        Constants.LOG.info("[numen-path] 无路,查 alter={} 的候选路线 start={} goal={}",
                alter, start.toShortString(), compiled.goal().center().toShortString());
        return true;
    }

    /**
     * 查询出结论:有候选 → 记进路线簿、列清单,TERRAIN_BLOCKED;自然改动无候选 → 再查一次连要主人
     * 同意的格也算进去的路;那也无候选 → 连挖都到不了,NO_PATH。等结论期间身体原地站住。
     * 裁决前仍让 caller 的 reached 谓词先说话。
     */
    private Status pollProbe() {
        java.util.List<RoutePlanner.Candidate> candidates = probe.poll();
        if (candidates == null) {
            InputDriver.halt(player);
            return Status.RUNNING;
        }
        boolean overBudget = probe.exceededBudget();
        int cheapestChange = probe.cheapestChange();
        probe = null;
        GoalCompiler.Compiled goal = probeGoal;
        probeGoal = null;
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }
        if (candidates.isEmpty() && probeAlter == RouteSpec.Alter.NATURAL
                && submitProbe(goal, RouteSpec.Alter.ANY)) {
            InputDriver.halt(player);
            return Status.RUNNING;
        }
        if (candidates.isEmpty()) {
            return fail(FailureType.NO_PATH, noPathAutopsy(goal.goal(), overBudget
                    ? ", " + TerrainBill.overBudget(spec.alterBudget(), cheapestChange)
                    : ", not even by digging or bridging"));
        }
        boolean relaxed = probeAlter == RouteSpec.Alter.ANY
                ? candidates.stream().anyMatch(c -> !c.bill().consentItems().isEmpty())
                : candidates.stream().anyMatch(c -> !c.bill().isEmpty());
        if (!relaxed) {
            // 放宽一档搜出的路根本用不着放宽的那一档(不动地形、不碰要同意的格)——那是原规格
            // 那次搜索自己的问题(预算、执行器不认账):如实说没路,别把放宽当万能解
            return fail(FailureType.NO_PATH, noPathAutopsy(goal.goal(), ""));
        }
        RouteBook book = RouteBook.of(player);
        long now = player.level().getGameTime();
        Map<String, TerrainBill> byId = new LinkedHashMap<>();
        for (RoutePlanner.Candidate c : candidates) {
            RouteBook.Route route = book.add(goal, c.spec(), c.path(), c.bill(), now);
            byId.put(route.id(), route.bill());
        }
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos center = goal.goal().center();
        String reason = TerrainBill.noCleanRoute(feet, center, spec.alter().mayAlter(), byId);
        Constants.LOG.info("[numen-path] TERRAIN-BLOCKED start={} goal={} | {}",
                feet.toShortString(), center.toShortString(), reason);
        return fail(FailureType.TERRAIN_BLOCKED, reason);
    }

    /**
     * 设了改动预算的导航先规划整条路:在飞就等,出结论后预算内的采纳为首段、超预算按无路终局。
     * 返回 null 表示不需要等(已采纳,或起点算不出、交给正常搜索——那时预算按不限处理并记日志)。
     */
    private Status planWithinBudget(GoalCompiler.Compiled compiled) {
        if (budgetPlan == null) {
            BlockPos start = core.pathStart();
            if (start == null) {
                budgetPlanned = true;
                Constants.LOG.info("[numen-path] 起点算不出来,改动预算 {} 这次不核", spec.alterBudget());
                return null;
            }
            budgetPlan = planner.plan(PathExecutor.playerFeet(player), start, compiled, spec, 1);
        }
        java.util.List<RoutePlanner.Candidate> planned = budgetPlan.poll();
        if (planned == null) {
            InputDriver.halt(player);
            return Status.RUNNING;
        }
        boolean overBudget = budgetPlan.exceededBudget();
        int cheapestChange = budgetPlan.cheapestChange();
        budgetPlan = null;
        budgetPlanned = true;
        if (reached.getAsBoolean()) {
            return Status.ARRIVED;
        }
        if (planned.isEmpty()) {
            return fail(FailureType.NO_PATH, noPathAutopsy(compiled.goal(), overBudget
                    ? ", " + TerrainBill.overBudget(spec.alterBudget(), cheapestChange) : ""));
        }
        if (!planned.get(0).path().movements().isEmpty()) {
            core.seed(compiled.engineGoal(), planned.get(0).path());
        }
        return null;
    }

    /**
     * 空搜索结果的教学式验尸——直接喂给模型的人话:离目标多远、有无
     * 搭路耗材、还有什么可解锁的手段。搜索器统计面未随异步句柄暴露,
     * 此处按可得素材给结构化结论。
     */
    /** @param qualifier 紧跟 "no path to target" 之后的限定语(地形许可的说明),可为空串 */
    private String noPathAutopsy(NavGoal goal, String qualifier) {
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos center = goal.center();
        double dist = Math.sqrt(feet.distSqr(center));
        StringBuilder r = new StringBuilder("no path to target").append(qualifier);
        r.append(String.format(" (from %s toward %s, about %.0f blocks away;"
                        + " the search burned its whole budget without finding a route",
                feet.toShortString(), center.toShortString(), dist));
        if (lastSearchContext != null && !lastSearchContext.hasThrowaway) {
            r.append("; carrying no scaffolding blocks to bridge or pillar with");
        }
        r.append(')');
        String reason = r.toString();
        Constants.LOG.info("[numen-path] NO-PATH start={} goal={} | {}",
                feet.toShortString(), center.toShortString(), reason);
        return reason;
    }

    public boolean isSafeToCancel() {
        return core.isSafeToCancel();
    }

    public BlockPos pathStart() {
        return core.pathStart();
    }

    /**
     * 这次导航真挖了什么、真放了什么。任务层在 stopNav 时并进旅程账,回执末尾如实相告。
     * 不改地形的导航账本通常为空(规划不出动地形的路,执行器也不会顺手动),但窒息自救
     * 的挖出算在内——那是反射,不是寻路决定,也该如实报。
     */
    public TerrainBill ledger() {
        return core.ledger();
    }

    /** FAILED 后的人话验尸(直接喂 LLM)。 */
    public String failReason() {
        return failReason;
    }

    /** FAILED 的结构化归因,任务层恢复梯按枚举分支。 */
    public FailureType failType() {
        return failType;
    }

    /**
     * 距上次真实推进(移动完成/重定位/活跃挖掘)的 tick 数——进度租约
     * 型任务 deadline 的 liveness 信号。规划间隙(无执行段)读 0:预算内
     * 的搜索本身就是进度,只是不是走路那种。
     */
    public int stallTicks() {
        PathExecutor current = core.getCurrent();
        return current == null ? 0 : current.ticksSinceProgress();
    }

    /** 搜索结论分布摘要,转发自内核(排障日志用)。 */
    public String outcomeSummary() {
        return core.outcomeSummary();
    }

    /**
     * 规划器在飞且当前无路段在执行——身体站着等异步搜索返回。任务层用它
     * 冻结任务 deadline:deadline 度量的是身体干活的刻,搜索的墙钟延迟不该
     * 折算成任务超时(tick 越快于真实时间,这笔折算越离谱,无上限 tick 的
     * 测试服上足以在首次搜索返回前烧光整个预算)。
     */
    public boolean planningInFlight() {
        return core.hasInProgressSearch() && core.getCurrent() == null;
    }

    /** 停止导航:取消在飞搜索、丢段、清键停挖,并把身体停稳、松潜行。 */
    public void stop() {
        stopped = true;
        searchSatisfied = false;
        if (probe != null) {
            probe.cancel();
            probe = null;
        }
        core.forceCancel();
        InputDriver.halt(player);
        // 垫柱逐 tick 按着潜行,路径终止时没有别人替它松——这里兜底
        player.setShiftKeyDown(false);
    }

    /**
     * 本 tick 原地站住:清移动输入、松潜行,但目标、当前路径段与在飞搜索全部保留,
     * 下一次 {@link #tick()} 从当前状态续跑——不产生任何冷启动搜索。身体必须静止的
     * 就地作业(如站桩挖掘)期间逐 tick 调用;与 {@link #stop()}(终局释放)互不替代。
     */
    public void pause() {
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }
}


