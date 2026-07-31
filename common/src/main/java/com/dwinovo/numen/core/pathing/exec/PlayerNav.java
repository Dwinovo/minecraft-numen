package com.dwinovo.numen.core.pathing.exec;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.bridge.PoolSearchDispatcher;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.execute.PathingCore;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.NavProfiler;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

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
 * <p>sacred(自身目标格,不可挖不可埋)/ deniedPlace(执行层证明放不上
 * 的格)两个语义开关穿透本导航建的每一个
 * {@link CalculationContext}:搜索用冻结快照、执行期复核用活世界,同一
 * 套开关同一把尺。
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

    private final NumenPlayer player;
    private final Supplier<GoalCompiler.Compiled> compiledSupplier;
    private final BooleanSupplier reached;
    private final ContextProvider contextProvider;
    private final boolean revalidateGoalEachTick;
    /**
     * speed 参数保留在签名上;新执行体系不支持变速(移动全走原版输入
     * 物理),这里把它路由成疾跑门:speed &lt; 1.0 表示"慢速",本导航
     * 的每个 tick 里禁疾跑(见 {@link #tick} 的 allowSprint 包夹)。
     */
    private final boolean sprintAllowed;

    /** 段规划状态机:搜索派发、段执行、无缝接段、失败自动重搜全在其内。 */
    private final PathingCore core;

    /**
     * 最近一次拉取的目标的 sacred 格({@code BlockPos.asLong()} 键)。
     * 每次拉目标同步刷新——goal 与 sacred 是同一份契约,不允许分开读。
     */
    private LongSet sacred = LongSets.emptySet();
    /**
     * 执行层证明"无支撑放不上"(NO_SUPPORT)的格,本次导航内累积、
     * 穿进此后每次搜索,确定性重搜才不会反复规划同一个不可能的脚手架。
     *
     * <p>回填点说明:新执行体系里放置失败表现为移动原语 FAILED → 整段
     * 取消重规划,途中不携带"哪一格无支撑"的结构化信息;能拿到该信息
     * 时在 {@link #tick} 的执行失败分支里 {@code deniedPlace.add(...)}。
     * 机制与上下文穿透保留,当前无生产者。
     */
    private final LongOpenHashSet deniedPlace = new LongOpenHashSet();

    /**
     * 搜索目标在脚下即满足、而 caller 的 reached 仍不满足:钉稳 ARRIVED
     * 结论供任务层判 STANCE_DUD(换站位、拉黑该成员),绝不衰变成撒谎
     * 的一跳 target lost。目标真移动时照常重根清位。
     */
    private boolean searchSatisfied;
    /** 最近一次下发给状态机的目标中心(重根判定的基准)。 */
    private BlockPos plannedCenter;

    private int replans;
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

    /** 调试覆盖层钩子。可视化尚未接回,仅存引用(与旧行为一致的空壳)。 */
    @SuppressWarnings("unused")
    private Supplier<List<BlockPos>> highlights;

    /** 单格目标:按意图编译(可走格=站上去,占用格=贴脸即到,不吞噬目标)。 */
    public PlayerNav(NumenPlayer player, BlockPos goal, double speed, BooleanSupplier reached) {
        this(player, speed, reached, () -> GoalCompiler.block(player.level(), goal));
    }

    /** 可移动的单格目标:每次拉取重新按意图编译(格位腾空后收紧为站上去)。 */
    public PlayerNav(NumenPlayer player, Supplier<BlockPos> goalSupplier, double speed,
                     BooleanSupplier reached) {
        this(player, speed, reached, () -> {
            BlockPos g = goalSupplier.get();
            return g == null ? null : GoalCompiler.block(player.level(), g);
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

    /** 持续跟随一个实时实体,每 tick 用实体当前脚位重新校验目标。 */
    public static PlayerNav followEntity(NumenPlayer player, Supplier<? extends Entity> entitySupplier,
                                         double followRadius, double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached,
                () -> followEntityContract(player, entitySupplier, followRadius), true);
    }

    /** 裸自定义目标(runAway、column 等)。不带 sacred——有方块目标的意图
     *  应走 {@link #to} / {@link GoalCompiler},让目标受保护。 */
    public static PlayerNav toGoal(NumenPlayer player, Supplier<NavGoal> goalSupplier,
                                   double speed, BooleanSupplier reached) {
        return new PlayerNav(player, speed, reached, bare(goalSupplier));
    }

    /** 把裸目标包成无 sacred 的编译契约(engineGoal 经词表映射同步派生)。 */
    private static Supplier<GoalCompiler.Compiled> bare(Supplier<NavGoal> goals) {
        return () -> {
            NavGoal g = goals.get();
            return g == null ? null
                    : new GoalCompiler.Compiled(g, LongSets.emptySet(), null, false);
        };
    }

    private static GoalCompiler.Compiled followEntityContract(NumenPlayer player,
                                                             Supplier<? extends Entity> entitySupplier,
                                                             double followRadius) {
        Entity entity = entitySupplier.get();
        if (entity == null || entity == player || entity.isRemoved() || !entity.isAlive()) {
            return null;
        }
        NavGoal goal = followEntityGoal(entity.blockPosition(), followRadius);
        return new GoalCompiler.Compiled(goal, LongSets.emptySet(), null, false);
    }

    static NavGoal followEntityGoal(BlockPos entityFeet, double followRadius) {
        return NavGoal.near(entityFeet, Math.max(0.0, followRadius));
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
        this.sprintAllowed = speed >= 1.0;
        this.reached = reached;
        this.contextProvider = contextProvider == null ? ContextProvider.DEFAULT : contextProvider;
        this.revalidateGoalEachTick = revalidateGoalEachTick;
        this.core = new PathingCore(player, PoolSearchDispatcher.INSTANCE,
                this::searchContext, this::executionContext);
    }

    /** 搜索用冻结上下文:快照世界 + 快照背包,穿透三个语义开关。 */
    private CalculationContext searchContext() {
        CalculationContext ctx = contextProvider.forSearch(player, sacred, deniedPlace);
        lastSearchContext = ctx;
        return ctx;
    }

    /** 执行期复核用实时上下文:活世界 + 当下背包,同一套语义开关。 */
    private CalculationContext executionContext() {
        return contextProvider.forExecution(player, sacred, deniedPlace);
    }

    public interface ContextProvider {
        ContextProvider DEFAULT = new ContextProvider() {
            @Override
            public CalculationContext forSearch(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
                return ContextFactory.forSearch(player, sacred, deniedPlace);
            }

            @Override
            public CalculationContext forExecution(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
                return ContextFactory.forExecution(player, sacred, deniedPlace);
            }
        };

        CalculationContext forSearch(NumenPlayer player, LongSet sacred, LongSet deniedPlace);
        CalculationContext forExecution(NumenPlayer player, LongSet sacred, LongSet deniedPlace);
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

        // goal 与 sacred 一体拉取——两者是同一份契约
        long tCompile = NavProfiler.begin();
        GoalCompiler.Compiled compiled = compiledSupplier.get();
        NavProfiler.end("goal.compile", tCompile);
        if (compiled == null) {
            return fail(FailureType.TARGET_LOST, "target lost");
        }
        sacred = compiled.sacred();
        NavGoal navGoal = compiled.goal();

        // 目标中心移动 >2 格:重根(进度量尺随之复位,状态机自会软取消旧段)
        if (plannedCenter != null && navGoal.center().distSqr(plannedCenter) > GOAL_MOVED_SQR) {
            searchSatisfied = false;
            bestGoalH = Double.MAX_VALUE;
            plannedCenter = navGoal.center();
            withSprintGate(() -> core.setGoalAndPath(compiled.engineGoal()));
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
            if (compiled.engineGoal().isInGoal(feet.getX(), feet.getY(), feet.getZ())) {
                return Status.ARRIVED;
            }
            searchSatisfied = false;
        }

        PathExecutor before = core.getCurrent();
        long tExec = NavProfiler.begin();
        withSprintGate(() -> {
            // 状态机空闲(初次、或结果被判孤儿丢弃)时(重新)下发目标;
            // setGoalAndPath 已在目标内/已有段/已有在飞搜索时自会不派发
            if (revalidateGoalEachTick || core.getGoal() == null
                    || (core.getCurrent() == null && !core.hasInProgressSearch())) {
                core.setGoalAndPath(compiled.engineGoal());
            }
            core.tick();
        });
        NavProfiler.end("core.tick", tExec);

        // 首段搜索失败:验尸并终局。裁定前再问一次 caller 谓词——本 tick
        // 身体可能已挪进满足位,ARRIVED 优先于失败结论
        if (core.calcFailedLastTick()) {
            Status verdict = fail(FailureType.NO_PATH, noPathAutopsy(navGoal));
            return reached.getAsBoolean() ? Status.ARRIVED : verdict;
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
     * speed 参数的落点:本导航的每一段驱动都包在 allowSprint 门里,
     * slow(speed&lt;1.0)时全局禁疾跑——搜索上下文的 canSprint 快照与
     * 执行器的逐 tick 疾跑决策读的都是这一个开关,包夹后原值复原,
     * 不影响别的同伴。
     */
    private void withSprintGate(Runnable body) {
        NavSettings settings = NavSettings.get();
        boolean saved = settings.allowSprint;
        settings.allowSprint = saved && sprintAllowed;
        try {
            body.run();
        } finally {
            settings.allowSprint = saved;
        }
    }

    /**
     * 一次失败重规划的记账。进度按目标自己的启发函数在脚下的取值度量
     * (yLevel 只看竖直、column 只看水平、composite 看最近成员、runAway
     * 负值随逃离下降,各自天然正确);有真实改善清零连击,否则连击到
     * {@link #MAX_STALLED_REPLANS} 判 BOXED_IN。返回 null 表示继续跑。
     */
    private Status accountReplan(NavGoal liveGoal) {
        BlockPos feet = PathExecutor.playerFeet(player);
        double h = liveGoal.heuristic(feet);
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
     * 空搜索结果的教学式验尸——直接喂给模型的人话:离目标多远、有无
     * 搭路耗材、还有什么可解锁的手段。搜索器统计面未随异步句柄暴露,
     * 此处按可得素材给结构化结论。
     */
    private String noPathAutopsy(NavGoal goal) {
        BlockPos feet = PathExecutor.playerFeet(player);
        BlockPos center = goal.center();
        double dist = Math.sqrt(feet.distSqr(center));
        StringBuilder r = new StringBuilder("no path to target");
        r.append(String.format(" (from %s toward %s, about %.0f blocks away;"
                        + " the search burned its whole budget without finding a route",
                feet.toShortString(), center.toShortString(), dist));
        if (lastSearchContext != null && !lastSearchContext.hasThrowaway) {
            r.append("; carrying no scaffolding blocks to bridge or pillar with");
        }
        if (!deniedPlace.isEmpty()) {
            r.append("; ").append(deniedPlace.size())
                    .append(" scaffold spot(s) already proven unplaceable this navigation");
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

    /**
     * 规划器在飞且当前无路段在执行——身体站着等异步搜索返回。任务层用它
     * 冻结任务 deadline:deadline 度量的是身体干活的刻,搜索的墙钟延迟不该
     * 折算成任务超时(tick 越快于真实时间,这笔折算越离谱,无上限 tick 的
     * 测试服上足以在首次搜索返回前烧光整个预算)。
     */
    /** 搜索结论分布摘要,转发自内核(排障日志用)。 */
    public String outcomeSummary() {
        return core.outcomeSummary();
    }

    public boolean planningInFlight() {
        return core.hasInProgressSearch() && core.getCurrent() == null;
    }

    /** 当前执行路段计划挖开的格子集(无路段时为空集)。任务层的世界修复
     *  逻辑对这些格子让行:同一具身体不能一边为走路挖它、一边把它填回去。 */
    public java.util.Set<BlockPos> plannedBreaks() {
        PathExecutor current = core.getCurrent();
        return current == null ? java.util.Set.of() : current.toBreak();
    }

    /** 调试覆盖层格集(如整片矿区)。可视化未接回,暂为空壳。 */
    public void setHighlights(Supplier<List<BlockPos>> highlights) {
        this.highlights = highlights;
    }

    /** 停止导航:取消在飞搜索、丢段、清键停挖,并把身体停稳、松潜行。 */
    public void stop() {
        stopped = true;
        searchSatisfied = false;
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


