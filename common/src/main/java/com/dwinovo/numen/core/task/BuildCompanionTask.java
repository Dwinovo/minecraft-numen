package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.cache.LoadedOnlyView;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.AimProcessor;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.movements.BuildPlacementRegistry;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多格建造任务:赴工地、在工地里施工、逐批落位。
 *
 * <p><b>施工模型</b>——同伴走到工地,然后在工地范围内一批一批地把方块落进世界,
 * 伴随朝向、挥手、粒子与音效。她不逐格走到每个方块旁边,也不需要"够得着"。
 *
 * <p>这是刻意的产品选择,不是偷懒。逐格走位是<b>客户端自动化模组</b>的生存约束
 * ——它必须让服务端看起来像有人在按键。我们是服务端模组,从来不需要骗谁;为那条
 * 约束付出的代价(站位求解、落脚点重试、视线射线、臂展判定、脚手架自救)全是
 * 为不存在的问题写的,并且把"高层够不着"变成了盖不完房子的硬天花板。
 *
 * <p>保留下来的是真正属于我们的东西:生存模式逐格扣料、清障掉落、期望状态精确
 * 落位、以及"支撑还没长出来就先放着,下一遍再来"的分遍推进。
 */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {

    private static final AimProcessor AIM = new AimProcessor();
    private static final double WALK_SPEED = 1.0;

    /** 赴工地的时限:走不到就地开工,绝不因为路不通而不干活。 */
    private static final int TRAVEL_BUDGET_TICKS = 30 * 20;
    /**
     * 施工节奏:由<b>目标总时长</b>反推速率,再夹在上下限之间。
     *
     * <p>固定速率两头不讨好:定成"每秒两格"的手感,小屋四分钟正好,五千多格的
     * 大屋要盖四十九分钟;定快了小屋一眨眼就没了。改成先给一个总时长目标,速率
     * 由格数除出来——不管盖多大,时长都可预期,而且都有戏看。
     */
    private static final double SURVIVAL_MIN_RATE = 2.0 / 20.0;    // 每秒 2 格,慢的那一头
    private static final double SURVIVAL_TARGET_TICKS = 12 * 60 * 20;   // 再大也不超过 12 分钟
    private static final double FREE_MAX_RATE = 100.0 / 20.0;      // 创造快,但不瞬移
    private static final double FREE_TARGET_TICKS = 25 * 20;       // 再小也演满 25 秒
    /** 单刻落位硬上限:再快也不能一刻塞几百格,那是卡顿不是建造。 */
    private static final int MAX_CELLS_PER_TICK = 8;
    /** 挥臂间隔:与原版挥臂动画一轮的长度对齐。 */
    private static final int SWING_PERIOD_TICKS = 6;
    /** 手到落点那道粒子的采样点数。 */
    private static final int CONJURE_TRAIL_SAMPLES = 6;
    /** 一层砌完后停下端详的刻数——匀速是机器,有起伏才是人。 */
    private static final int LAYER_PAUSE_TICKS = 18;
    /** 巡视路线离工地包围盒的外扩格数。 */
    private static final int SITE_MARGIN = 2;
    /** 挑落脚点时往前看多少格,决定她该站到哪一侧去。 */
    private static final int WANDER_LOOKAHEAD_CELLS = 120;
    /** 换个地方站:让她绕着工地动起来,而不是钉在原地。 */
    private static final int WANDER_INTERVAL_TICKS = 4 * 20;
    /** 单次挪窝的步行时限。 */
    private static final int WANDER_WALK_TICKS = 40;
    /** 连续几遍零进展才升级处置。 */
    private static final int MAX_BARREN_PASSES = 3;
    /** 每批最多冒几处粒子——整栋房子逐格发粒子会把客户端打垮。 */
    private static final int PARTICLE_BUDGET_PER_BATCH = 3;

    /**
     * 写入标志:{@code UPDATE_CLIENTS}(同步给客户端)+ {@code UPDATE_KNOWN_SHAPE}
     * (跳过形状重算),<b>不含</b> {@code UPDATE_NEIGHBORS}。
     *
     * <p>这是整个施工能不能照图落地的分水岭。默认的 {@code 3} 会通知邻块并触发
     * 形状重算,于是原版立刻拿它自己的规则复核我们刚写下的每一格:靠在非泥土
     * 方块上的粉红花瓣被判无效弹掉、楼梯与栅栏的连接态被按邻居重写、悬空的贴附
     * 方块整批消失。图纸里本来就有原版放不出来的格(社区图纸尤其常见——保存时
     * 的世界和落位时的世界不是一回事),按 {@code 3} 写就是逐格送去被否决。
     *
     * <p>所以这里不走通知链路:<b>图纸怎么画就怎么落</b>,不让世界中途改我们的
     * 稿。光照仍由区块自己维护,不会盖出一栋黑房子。
     *
     * <p>代价是建成后邻块不联动(红石不自动初始化)。对一栋房子来说这是划算的:
     * 少了它房子盖不完整,有了它只是红石要玩家碰一下。
     */
    private static final int PLACE_FLAGS =
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                    | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;

    private enum Phase { TRAVEL, WORK }

    private final Map<Long, BuildTaskRecord.Target> targetByPos = new LinkedHashMap<>();
    /** 施工期寻路垫出来的非目标方块,收工时一并撤掉。 */
    private final LinkedHashSet<BlockPos> scaffold = new LinkedHashSet<>();
    /** 本遍缺料统计(遍末报告用)。 */
    private final Map<Item, Integer> passMissing = new LinkedHashMap<>();

    private LongOpenHashSet observedCompleted;
    private boolean providerRegistered;
    private Phase phase = Phase.TRAVEL;
    private int travelTicks;

    /** 工地包围盒(全体目标格的最小/最大角)。 */
    private BlockPos siteMin;
    private BlockPos siteMax;

    /** 本遍施工顺序:低层先、层内清障→骨架→贴附、蛇形走位。 */
    private List<BuildTaskRecord.Target> order = List.of();
    /** 当前层在 order 里的区间,以及本层已经轮到过的格。 */
    private int layerStart;
    private int layerEnd;
    private int layerCursor;
    private final LongOpenHashSet placedThisLayer = new LongOpenHashSet();
    /** 每刻该落几格(可以是小数),以及攒下来的落位信用。 */
    private double cellsPerTick;
    private double placeCredit;
    private int swingCooldown;
    /** 已砌好又被外力弄没的格数(收工时用来解释"为什么磨了这么久")。 */
    private int damagedCells;
    private int passStartCompleted;
    private int barrenPasses;
    /**
     * 暂停落位的刻数,两处用它:
     *
     * <p>其一是零进展遍后的冷却。一遍全是"放不下去"时会在同一 tick 内跑完(每格
     * 都不耗预算),三遍连着翻完只要三刻——她根本来不及从压着的格子上挪开,就被
     * 判成推不动了。裁决必须等身体真的动过。
     *
     * <p>其二是砌完一层后的停顿。恒定输出像打印机;停一拍、把刚砌好的那层扫一眼
     * 再继续,才有人在干活的样子。
     */
    private int workPause;
    /** 上一批落位所在的层,用来发现"翻过一层了"。 */
    private int lastLayerY = Integer.MIN_VALUE;
    /** 当前该不该蹲(由落位批次的高度决定,跨 tick 保持)。 */
    private boolean crouching;

    /** 挪窝状态。 */
    private List<Vec3> wanderPoints = List.of();
    private int wanderIndex;
    private int wanderTicks;
    private Vec3 wanderTarget;

    private String note = "done";

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        for (BuildTaskRecord.Target target : record.targets) {
            targetByPos.put(target.pos().asLong(), target);
        }
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(this::checkExistingBlocks, this::checkMaterials);
    }

    /**
     * 开工盘料:料不齐<b>整批拒绝,一格不动</b>。
     *
     * <p>试过放行"能盖多少盖多少",撤了。盖一半停下来的后果比拒绝严重得多:
     * 半栋房子杵在原地,而续建要靠模型重发一模一样的指令——它多半发不一样,
     * 于是新旧两版叠在同一片地基上。<b>拒绝是原子的,半成品不是。</b>
     *
     * <p>真正该改的不在这里:她把一栋房子拆成五次调用,盘料只盘到当前这一批,
     * 于是墙砌完了才发现屋顶的料不够。整栋一次规划,这道门就只会响一次。
     */
    private Precondition.Failure checkMaterials() {
        if (!r.consumeMaterials) {
            return null;
        }
        Map<Item, Integer> need = remainingNeed();
        Map<Item, Integer> shortfall = shortfallAgainstInventory(need);
        if (shortfall.isEmpty()) {
            return null;
        }
        if (r.allowPartial) {
            // 整幢图纸一趟本来就运不完:能开工就开工,补给跑几趟是常态而不是错误。
            // 只有一格都买不起时才拦——那才是真的开不了工。
            for (Item item : need.keySet()) {
                if (hasItem(item, true)) {
                    return null;
                }
            }
        }
        return new Precondition.Failure(
                "not enough materials yet — " + summarizeShortfall(shortfall)
                        + ". Nothing was placed. Survival mode consumes 1 item per cell; gather these, "
                        + "then send the SAME call again — anything already standing is skipped, so a "
                        + "restocked repeat picks up exactly where this left off.",
                FailureType.NO_MATERIAL);
    }

    /**
     * 此刻还需要的材料:按物品汇总所有<b>尚未达标且要花料</b>的格。
     *
     * <p>开工前置与中途报缺共用这一个统计口径,所以玩家听到的永远是同一个
     * 问题的答案——"从现在起还要凑什么"。以前中途报的是"本遍缺了什么",
     * 那是过程量,玩家拿它没法决定去采多少。
     */
    private Map<Item, Integer> remainingNeed() {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (!costsMaterial(target)) continue;
            if (target.matches(player.level().getBlockState(target.pos()))) continue;
            need.merge(target.item(), target.materialCount(), Integer::sum);
        }
        // 摆设实体也要料:它们不是目标格,但同样是玩家得掏出来的东西
        for (BuildTaskRecord.EntitySpawn spawn : r.entities) {
            if (spawn.item() != Items.AIR && !fixtureAlreadyThere(spawn)) {
                need.merge(spawn.item(), 1, Integer::sum);
            }
        }
        return need;
    }

    /** 计费判据在 {@link BuildTaskRecord.Target#costsMaterial()}——盘点工具与这里共用。 */
    private boolean costsMaterial(BuildTaskRecord.Target target) {
        return !isAirTarget(target) && target.costsMaterial();
    }

    /** 需求减去背包现货 = 还差多少。 */
    private Map<Item, Integer> shortfallAgainstInventory(Map<Item, Integer> need) {
        Map<Item, Integer> shortfall = new LinkedHashMap<>();
        for (var e : need.entrySet()) {
            int have = PlayerInv.count(player.getInventory(), e.getKey());
            if (have < e.getValue()) {
                shortfall.put(e.getKey(), e.getValue() - have);
            }
        }
        return shortfall;
    }

    /** 报缺料时最多点名几种。 */
    private static final int MISSING_ITEMS_LISTED = 8;

    /**
     * 缺料清单:按缺口从大到小点名前几种,其余只报种类数与总件数。
     *
     * <p>一栋社区图纸能缺一百五十多种材料,全列出来是几千字符——玩家读不完,
     * 模型的上下文也白烧掉一大块,而真正决定"先去干什么"的永远是排头那几样。
     */
    private static String summarizeShortfall(Map<Item, Integer> shortfall) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(shortfall.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        StringBuilder out = new StringBuilder();
        int listed = Math.min(MISSING_ITEMS_LISTED, sorted.size());
        for (int i = 0; i < listed; i++) {
            if (i > 0) out.append(", ");
            out.append(label(sorted.get(i).getKey())).append(" x").append(sorted.get(i).getValue());
        }
        if (sorted.size() > listed) {
            int restKinds = sorted.size() - listed;
            int restCount = 0;
            for (int i = listed; i < sorted.size(); i++) {
                restCount += sorted.get(i).getValue();
            }
            out.append(", and ").append(restKinds).append(" more kinds (")
                    .append(restCount).append(" items)");
        }
        int total = 0;
        for (int v : shortfall.values()) {
            total += v;
        }
        out.append("; ").append(total).append(" items short in total");
        return out.toString();
    }

    private Precondition.Failure checkExistingBlocks() {
        if (r.replaceExisting) {
            return null;
        }
        for (BuildTaskRecord.Target target : r.targets) {
            BlockState state = player.level().getBlockState(target.pos());
            if (!target.matches(state) && (isAirTarget(target) || !isReplaceable(target.pos(), state))) {
                return new Precondition.Failure("target " + target.shortPos()
                        + " is occupied; enable replacement or clear it first", FailureType.TARGET_LOST);
            }
        }
        return null;
    }

    @Override
    protected void onStart() {
        observedCompleted = new LongOpenHashSet();
        computeSite();
        computeWanderPoints();
        registerProvider();
        updateCompleted();
        rebuildOrder();
        computePace();
        passStartCompleted = r.completed();
        phase = Phase.TRAVEL;
    }

    @Override
    protected TaskState onTick() {
        // 蹲姿由施工分支决定并保持:落位只在批次刻发生,若每 tick 复位成站立,
        // 中间那些刻就会把她弹起来,看起来是在抖而不是在蹲着贴边放。
        player.setShiftKeyDown(phase == Phase.WORK && crouching);
        registerProvider();
        updateCompleted();
        drainScaffold();

        if (r.completed() >= r.targets.size()) {
            return finish();
        }
        return switch (phase) {
            case TRAVEL -> tickTravel();
            case WORK -> tickWork();
        };
    }

    // ------------------------------------------------------------------
    // 一、赴工地
    // ------------------------------------------------------------------

    /**
     * 走到工地跟前。整个任务里唯一一次寻路——之后落位不再依赖走位,所以这一段
     * 走成什么样都不影响能不能盖完:到了就开工,没到、走不通、超时,一样开工。
     */
    private TaskState tickTravel() {
        if (nav == null) {
            NavGoal goal = siteApproachGoal();
            nav = PlayerNav.to(player,
                    () -> new GoalCompiler.Compiled(goal, protectedCells(), null, false),
                    WALK_SPEED, () -> false, this);
            nav.setHighlights(this::pendingPositions);
        }
        return switch (nav.tick()) {
            case ARRIVED, FAILED -> {
                beginWork();
                yield TaskState.RUNNING;
            }
            case RUNNING -> {
                // 预算只计"正在往那儿走"的刻;搜索在飞的刻是规划器的墙钟延迟,
                // 在高 tps 的无头测试里折算尤其离谱,不计入。
                if (!nav.planningInFlight() && ++travelTicks > TRAVEL_BUDGET_TICKS) {
                    com.dwinovo.numen.core.Constants.LOG.debug(
                            "[numen-build] 赴工地超时,就地开工 feet={} 工地={}",
                            player.blockPosition().toShortString(), siteMin.toShortString());
                    beginWork();
                }
                yield TaskState.RUNNING;
            }
        };
    }

    private void beginWork() {
        stopNav();
        InputDriver.halt(player);
        phase = Phase.WORK;
        placeCredit = 0;
        wanderTicks = 0;
        wanderTarget = null;
    }

    /**
     * 赴工地 = 走到巡视路线的<b>起点</b>,而不是"离工地中心多远以内"。后者的
     * 半径圈把工地内部整个包含在内,她很可能在场内就算到位,一开工就压住格子。
     */
    private NavGoal siteApproachGoal() {
        Vec3 first = wanderPoints.isEmpty()
                ? Vec3.atBottomCenterOf(siteMin.offset(-SITE_MARGIN, 0, -SITE_MARGIN))
                : wanderPoints.get(0);
        return NavGoal.nearGround(BlockPos.containing(first), 2.0);
    }

    // ------------------------------------------------------------------
    // 二、施工
    // ------------------------------------------------------------------

    private TaskState tickWork() {
        tickWander();
        if (workPause > 0) {
            workPause--;
            return TaskState.RUNNING;
        }
        // 速率可以小于每刻一格,所以用信用累积而不是"每 N 刻放一批":
        // 生存慢到每十刻一格时,每一格都自成一批,节奏自然就散开了。
        placeCredit += cellsPerTick;
        int budget = (int) Math.min(placeCredit, MAX_CELLS_PER_TICK);
        if (budget <= 0) {
            return TaskState.RUNNING;
        }
        return runBatch(budget);
    }

    /**
     * 落一批:在<b>当前最低的未完成层</b>里,挑离她最近的几格。
     *
     * <p>顺序仍然低层优先(上面的东西得有底下的东西撑着),但层内不再是固定的
     * 蛇形——而是跟着她的位置走。以前落位顺序和她在哪毫无关系,于是观感是"她在
     * 那边溜达,方块在这边冒出来":两条互不相干的动画叠在一起,一眼就假。绑上
     * 之后,她走到东墙东墙就长,绕到南边南边接着长。<b>因果对上,比加多少粒子
     * 都管用。</b>
     */
    private TaskState runBatch(int budget) {
        List<BlockPos> touched = new ArrayList<>();
        BlockState sample = null;
        while (budget > 0) {
            BuildTaskRecord.Target target = nearestPendingInLayer();
            if (target == null) {
                break;
            }
            BlockState placed = processCell(target);
            layerCursor++;   // 无论成败都推进,免得同一格被反复挑中空转
            if (placed != null) {
                budget--;
                placeCredit -= 1.0;
                touched.add(target.pos());
                sample = placed;
            }
        }
        if (!touched.isEmpty()) {
            performWork(touched, sample);
        }
        if (layerCursor >= layerEnd) {
            return advanceLayer();
        }
        return TaskState.RUNNING;
    }

    /**
     * 当前层里离她最近的一格待建。
     *
     * <p>{@code layerCursor} 只用来保证"这一层每格都轮到过一次",不决定顺序;
     * 真正的顺序由距离决定,所以她走到哪里哪里就长。
     */
    private BuildTaskRecord.Target nearestPendingInLayer() {
        BuildTaskRecord.Target best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3 me = player.position();
        for (int i = layerStart; i < layerEnd; i++) {
            BuildTaskRecord.Target t = order.get(i);
            if (placedThisLayer.contains(t.pos().asLong())) {
                continue;
            }
            double dx = t.pos().getX() + 0.5 - me.x;
            double dz = t.pos().getZ() + 0.5 - me.z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = t;
            }
        }
        if (best != null) {
            placedThisLayer.add(best.pos().asLong());
        }
        return best;
    }

    /** 这一层扫完了:翻到下一层,或者本遍到顶收遍。 */
    private TaskState advanceLayer() {
        // 翻层停一拍,把刚砌好的这层扫一眼。恒定输出是打印机,有起伏才像人在干活。
        workPause = LAYER_PAUSE_TICKS;
        placedThisLayer.clear();
        layerStart = layerEnd;
        layerCursor = layerStart;
        if (layerStart >= order.size()) {
            return endPass();
        }
        int y = order.get(layerStart).pos().getY();
        int end = layerStart;
        while (end < order.size() && order.get(end).pos().getY() == y) {
            end++;
        }
        layerEnd = end;
        return TaskState.RUNNING;
    }

    /**
     * 处理一格。
     *
     * @return 落位后的期望状态(有产出);null = 本遍先放下(已达标/缺料/她自己
     *         正站在这格里)
     */
    private BlockState processCell(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState current = player.level().getBlockState(pos);
        if (target.matches(current)) {
            markObserved(target, true);
            return null;
        }
        BlockState desired = target.desiredState();
        if (desired != null && desired.getBlock() instanceof LiquidBlock) {
            return null;   // 流体不承接:布水与排水都不做
        }
        if (!player.level().isLoaded(pos)) {
            return null;   // 区块这一刻没加载:临时状况,下一遍再来(不算注定动不了)
        }

        boolean occupied = !current.isAir() && !(current.getBlock() instanceof LiquidBlock);
        if (occupied) {
            clear(pos);
            if (isAirTarget(target)) {
                markObserved(target, true);
                return desired;
            }
            current = player.level().getBlockState(pos);
        }
        if (isAirTarget(target)) {
            markObserved(target, current.isAir() || current.getBlock() instanceof LiquidBlock);
            return null;
        }

        // 谁都不豁免——包括她自己:身体占着的格子这遍先放下,下一遍她已经挪开了。
        // 这是唯一保留的放置前置条件,防的是把方块塞进活物身体里这类真事故。
        if (blockedByEntity(pos, desired)) {
            return null;
        }
        // 一格不一定只花一件(双层砖两件、雪层按层数),盘点与实扣共用同一个件数
        int cost = r.consumeMaterials && costsMaterial(target) ? target.materialCount() : 0;
        if (cost > 0 && !hasItems(target.item(), cost, true)) {
            // 【事件挂点】本遍第一次缺料 —— {@code passMissing.isEmpty()} 恰好就是
            // 那一次边沿,不必另记状态去重。要推给她时在此 GameEvents.emit,
            // 前提是先在 GameEvents.Kind 里登记一个词(那是 numen-api 的改动)。
            passMissing.merge(target.item(), cost, Integer::sum);
            return null;
        }

        player.level().setBlock(pos, desired, PLACE_FLAGS);
        applyBlockEntityData(pos, desired);
        // 放完给方块一次"我被放下了"的回调:命名牌、告示牌、部分方块实体靠它初始化。
        // 兜住异常——这条回调本是给"玩家手持物品放置"设计的,我们没有那个上下文,
        // 个别方块会在里面自己炸掉,而那不该让整栋楼停工。
        try {
            desired.getBlock().setPlacedBy(player.level(), pos, desired, player,
                    new ItemStack(target.item()));
        } catch (RuntimeException ignored) {
            // 放置本身已经成功,回调失败只影响那一格的附加数据
        }
        for (int k = 0; k < cost; k++) {
            consumeOne(target.item());
        }
        r.placedOne();
        markObserved(target, true);
        return desired;
    }

    /**
     * 清掉挡路的方块:生存掉落物品(她清出来的木头该归玩家),免耗材不掉。
     * 破坏特效走原版 levelEvent,音效与碎屑与玩家自己挖一模一样。
     */
    private void clear(BlockPos pos) {
        var level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (r.consumeMaterials) {
            net.minecraft.world.level.block.Block.dropResources(
                    state, level, pos, level.getBlockEntity(pos), player, player.getMainHandItem());
        }
        level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(state));
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        r.brokeOne();
    }

    /**
     * 演出:朝这一批的中心转头、举起对应方块、挥手,方块碎屑与落位声。
     * 粒子按批限量——整栋房子逐格发粒子会把客户端打垮。
     */
    private void performWork(List<BlockPos> touched, BlockState sample) {
        Vec3 centre = Vec3.ZERO;
        for (BlockPos pos : touched) {
            centre = centre.add(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        centre = centre.scale(1.0 / touched.size());
        applySteppedAim(centre);
        // 低处蹲下、高处站直:所有人都知道贴边放方块要蹲,这是玩家最熟的建造姿势。
        crouching = centre.y < player.getY() + 0.6;
        // 挥手按动画节拍走,不按落位节拍。原版一轮挥臂约 6 刻,而落位每 2 刻一批
        // ——每批都触发就是每秒十下,手臂永远画不完一个来回,看起来是抽搐不是干活。
        boolean swung = --swingCooldown <= 0;
        if (swung) {
            player.swing(InteractionHand.MAIN_HAND);
            swingCooldown = SWING_PERIOD_TICKS;
        }
        if (sample != null) {
            int slot = findSlot(sample.getBlock().asItem(), true);
            if (slot >= 0) {
                player.holdInHand(slot);
            }
        }
        if (!(player.level() instanceof ServerLevel level) || sample == null) {
            return;
        }
        if (swung) {
            emitConjureTrail(level, centre);
        }
        int spouts = Math.min(PARTICLE_BUDGET_PER_BATCH, touched.size());
        int step = Math.max(1, touched.size() / spouts);
        for (int i = 0; i < touched.size(); i += step) {
            BlockPos pos = touched.get(i);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, sample),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    4, 0.28, 0.28, 0.28, 0.0);
        }
        var sound = sample.getSoundType().getPlaceSound();
        BlockPos at = touched.get(touched.size() / 2);
        level.playSound(null, at, sound, SoundSource.BLOCKS, 0.7f, 0.9f + player.getRandom().nextFloat() * 0.2f);
    }

    /**
     * 从她手上飞向落点的一道粒子。
     *
     * <p>方块凭空出现、她在旁边挥手——这两件事之间原本没有任何可见的联系,看着
     * 就像作弊。把因果画出来之后,隔空落位才读得成手艺而不是开挂。一次挥臂一道,
     * 跟着挥臂节拍走,不会刷屏。
     */
    private void emitConjureTrail(ServerLevel level, Vec3 to) {
        Vec3 from = player.getEyePosition().add(player.getLookAngle().scale(0.6)).add(0, -0.3, 0);
        Vec3 step = to.subtract(from).scale(1.0 / (CONJURE_TRAIL_SAMPLES + 1));
        for (int i = 1; i <= CONJURE_TRAIL_SAMPLES; i++) {
            Vec3 p = from.add(step.scale(i));
            level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * 一遍扫完的裁决。有进展就开下一遍(补漏);零进展先挪窝重试(剩下的多半
     * 正压在她自己脚下),连着几遍颗粒无收才认账——缺料是邀请,不是错误。
     */
    private TaskState endPass() {
        updateCompleted();
        if (r.completed() >= r.targets.size()) {
            return finish();
        }
        boolean progressed = r.completed() > passStartCompleted;
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-build] 收遍 {}/{} 本遍+{} 缺料{} 零进展遍{}",
                r.completed(), r.targets.size(), r.completed() - passStartCompleted,
                passMissing.size(), barrenPasses);
        if (progressed) {
            barrenPasses = 0;
        } else if (++barrenPasses >= MAX_BARREN_PASSES) {
            if (!passMissing.isEmpty()) {
                // 先报干了多少,再报还差什么——玩家要的是"还要凑多少才能收工",
                // 不是一句材料不足。已经砌好的部分留在世界里,不回滚。
                fail("built " + r.completed() + "/" + r.targets.size()
                        + " and ran out — " + missingReason(), FailureType.NO_MATERIAL);
                return TaskState.FAILED;
            }
            // 挪了窝也补不上:留案再交代。盖不完就是盖不完,不粉饰成成功。
            dumpOutstanding();
            fail(diagnoseOutstanding() + "; built " + r.completed() + "/" + r.targets.size(),
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        // 零进展往往是她自己站在剩下的格子里:立刻挪窝,并且把下一遍压后到
        // 她走完这一程之后——不给身体挪开的时间就连翻三遍,判的是假的死局。
        if (!progressed) {
            wanderTarget = null;
            wanderTicks = WANDER_INTERVAL_TICKS;
            workPause = WANDER_WALK_TICKS + 20;
        }
        rebuildOrder();
        passStartCompleted = r.completed();
        passMissing.clear();
        return TaskState.RUNNING;
    }

    /**
     * 收不了尾时的留案:缺格按层分布 + 逐条实例(期望/实际/立得住吗/被谁占着)。
     * 盖不完的病因只有两类——世界拒收,或者她自己压着——两者的修法完全不同,
     * 不该靠单个失败样本去猜。
     */
    private void dumpOutstanding() {
        Map<Integer, Integer> byLayer = new java.util.TreeMap<>();
        List<BuildTaskRecord.Target> examples = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (target.matches(player.level().getBlockState(target.pos()))) continue;
            byLayer.merge(target.pos().getY(), 1, Integer::sum);
            if (examples.size() < 12) {
                examples.add(target);
            }
        }
        com.dwinovo.numen.Constants.LOG.info(
                "[numen-build] 收不了尾 {}/{} feet={} 缺格分层={}",
                r.completed(), r.targets.size(), player.blockPosition().toShortString(), byLayer);
        for (BuildTaskRecord.Target target : examples) {
            BlockPos pos = target.pos();
            BlockState desired = target.desiredState();
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-build] 缺 {} 期望={} 实际={} 立得住={} 占身={} 有料={}",
                    pos.toShortString(), desired, player.level().getBlockState(pos),
                    desired.canSurvive(player.level(), pos), blockedByEntity(pos, desired),
                    !r.consumeMaterials || hasItem(target.item(), true));
        }
    }

    /**
     * 收不了尾时的病因分类。
     *
     * <p>剩下的格子放不下去只有四种可能,而玩家的应对完全不同:让占着的人挪开、
     * 让拆的人住手、去补材料、或者认下图纸里原版不允许的那几格。以前四种都归成
     * 一句 "could not be placed" —— 判据其实早就算出来了,只是没送到人面前。
     * 裁决做了却不交代理由,和没裁决一样难用。
     */
    private String diagnoseOutstanding() {
        int occupied = 0;
        int unsupported = 0;
        int broke = 0;
        BlockPos sample = null;
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos pos = target.pos();
            if (target.matches(player.level().getBlockState(pos))) {
                continue;
            }
            if (sample == null) {
                sample = pos;
            }
            BlockState desired = target.desiredState();
            if (blockedByEntity(pos, desired)) {
                occupied++;
            } else if (!desired.canSurvive(player.level(), pos)) {
                unsupported++;
            } else {
                broke++;
            }
        }
        int missing = r.targets.size() - r.completed();
        List<String> parts = new ArrayList<>();
        if (occupied > 0) {
            parts.add(occupied + " blocked by someone standing there — ask them to step aside");
        }
        if (unsupported > 0) {
            parts.add(unsupported + " that vanilla physics will not hold at that spot"
                    + " (the blueprint asks for something impossible there)");
        }
        if (broke > 0) {
            parts.add(broke + " that would not stay put"
                    + (damagedCells > 0 ? " — something kept breaking the finished work" : ""));
        }
        return missing + " cell(s) unbuilt at " + (sample == null ? "?" : sample.toShortString())
                + (parts.isEmpty() ? "" : ": " + String.join("; ", parts));
    }

    /** 还差什么才能收工——按此刻的剩余需求算,不是本遍的过程量。 */
    private String missingReason() {
        Map<Item, Integer> shortfall = shortfallAgainstInventory(remainingNeed());
        if (shortfall.isEmpty()) {
            shortfall = passMissing;   // 期间被人补过料的边缘情形,退回本遍统计
        }
        return "still needs " + summarizeShortfall(shortfall)
                + ". Everything already standing stays; restock and send the SAME call again to carry "
                + "on from exactly here — finished cells are skipped automatically.";
    }

    /** 收工:撤掉自己垫的脚手架,放一把庆祝的粒子。 */
    /**
     * 收工时把图纸里的摆设实体生成出来:展示框、盔甲架、画。
     *
     * <p>放在最后一步,因为它们要挂在墙上、立在地上——墙和地得先有。躯壳照生,身上
     * 的东西已经在加载时剥掉了(见 {@code BuildStates#safeEntityData}),所以这里
     * 不涉及任何物品。
     */
    private void spawnFixtures() {
        if (r.entities.isEmpty() || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        for (BuildTaskRecord.EntitySpawn spawn : r.entities) {
            try {
                if (fixtureAlreadyThere(spawn)) {
                    continue;   // 重发同一个调用是续建,不该再生成一份
                }
                boolean pays = r.consumeMaterials && spawn.item() != Items.AIR;
                if (pays && !hasItems(spawn.item(), 1, true)) {
                    passMissing.merge(spawn.item(), 1, Integer::sum);
                    continue;
                }
                var created = net.minecraft.world.entity.EntityType.create(spawn.nbt(), level);
                if (created.isEmpty()) {
                    continue;
                }
                var entity = created.get();
                // 挂画与展示框的朝向存在自己的 NBT 里(facing / TileX,Y,Z),这里只把
                // 朝向按图纸旋转转过去;旋转过的图纸里挂件可能仍需人工校一下朝向
                entity.moveTo(spawn.x(), spawn.y(), spawn.z(),
                        entity.rotate(spawn.rotation()), entity.getXRot());
                entity.setUUID(java.util.UUID.randomUUID());   // 同一张图纸建两遍不能撞 UUID
                level.addFreshEntity(entity);
                if (pays) {
                    consumeOne(spawn.item());
                }
            } catch (RuntimeException ignored) {
                // 一只摆设生成失败不该让整栋楼算失败
            }
        }
    }

    /**
     * 这只摆设已经在那儿了吗——<b>实体的幂等靠这一步</b>。
     *
     * <p>方块能安全重发,是因为我们逐格拿世界当进度对照;而"重发同一个调用就是续建"
     * 正是我们写进工具描述、教给模型的做法。实体没有这一步的话,建完再发一次就多出
     * 一份摆设——同一面墙上两个展示框叠在一起。
     */
    private boolean fixtureAlreadyThere(BuildTaskRecord.EntitySpawn spawn) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        var type = net.minecraft.world.entity.EntityType.by(spawn.nbt());
        if (type.isEmpty()) {
            return false;
        }
        AABB box = new AABB(spawn.x() - 0.5, spawn.y() - 0.5, spawn.z() - 0.5,
                spawn.x() + 0.5, spawn.y() + 0.5, spawn.z() + 0.5);
        return !level.getEntities(type.get(), box, e -> true).isEmpty();
    }

    /**
     * 收工时给紧贴工地外围一圈的水重排一次流体 tick。
     *
     * <p>我们落位不带邻居更新——那是为了不让原版中途改写图纸(见 {@code PLACE_FLAGS})
     * ,但代价是<b>周围的水不知道世界变了</b>:在湖里砌一道墙,两侧的水停在过期状态;
     * 把水下的一块石头清掉,那个洞不会自己被水填上。踢一脚只需要在外壳上做,内部
     * 全是刚放好的方块。
     *
     * <p>只在"连带清空"那档做:低档位不清场,挖不出会漏水的空腔。
     */
    private void nudgeSurroundingWater() {
        if (r.replaceMode != ReplaceMode.REPLACE_EMPTY
                || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        BlockPos.betweenClosedStream(siteMin.offset(-1, -1, -1), siteMax.offset(1, 1, 1))
                .filter(pos -> pos.getX() < siteMin.getX() || pos.getX() > siteMax.getX()
                        || pos.getY() < siteMin.getY() || pos.getY() > siteMax.getY()
                        || pos.getZ() < siteMin.getZ() || pos.getZ() > siteMax.getZ())
                .filter(pos -> level.isLoaded(pos) && level.getFluidState(pos).is(Fluids.WATER))
                .forEach(pos -> level.scheduleTick(pos.immutable(), Fluids.WATER,
                        Fluids.WATER.getTickDelay(level)));
    }

    private TaskState finish() {
        for (BlockPos pos : scaffold) {
            if (targetByPos.containsKey(pos.asLong())) continue;
            BlockState state = player.level().getBlockState(pos);
            if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
                player.level().destroyBlock(pos, r.consumeMaterials);
            }
        }
        scaffold.clear();
        spawnFixtures();
        nudgeSurroundingWater();
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    (siteMin.getX() + siteMax.getX()) / 2.0 + 0.5,
                    siteMax.getY() + 1.0,
                    (siteMin.getZ() + siteMax.getZ()) / 2.0 + 0.5,
                    24, (siteMax.getX() - siteMin.getX()) / 3.0 + 1.0, 1.0,
                    (siteMax.getZ() - siteMin.getZ()) / 3.0 + 1.0, 0.0);
        }
        InputDriver.halt(player);
        if (r.completed() >= r.targets.size()) {
            note = "all requested cells match";
        }
        return TaskState.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 三、在工地里动起来
    // ------------------------------------------------------------------

    /**
     * 挪窝。用直接步进而不是寻路——施工期间起一次 A* 既慢又可能顺手挖填,
     * 而"她在工地里走动"这件事根本不需要精确到达:走得到就走到,走不到换一个。
     */
    private void tickWander() {
        if (wanderPoints.isEmpty()) {
            return;
        }
        if (wanderTarget == null) {
            if (++wanderTicks < WANDER_INTERVAL_TICKS) {
                return;
            }
            wanderTicks = 0;
            wanderTarget = nextWanderPoint();
            return;
        }
        // 走位交给寻路,不是朝目标推移动输入。直线步进遇到墙、树、坎就一路顶着
        // 走满时限——实测就是"蹭着建筑边卡住"的那个样子。巡视点之间只有几格,
        // 起一次 A* 的代价很低,而避障是它天生就会的事。
        if (nav == null) {
            Vec3 dest = wanderTarget;
            nav = PlayerNav.to(player,
                    () -> new GoalCompiler.Compiled(
                            NavGoal.nearGround(BlockPos.containing(dest), 1.5),
                            protectedCells(), null, false),
                    WALK_SPEED, () -> false, this);
        }
        boolean done = switch (nav.tick()) {
            case ARRIVED, FAILED -> true;
            case RUNNING -> !nav.planningInFlight() && ++wanderTicks > WANDER_WALK_TICKS;
        };
        if (done) {
            stopNav();
            InputDriver.halt(player);
            wanderTarget = null;
            wanderTicks = 0;
            // 到一个点就停一拍看一眼,再去下一个——不是站四秒走两秒
            workPause = Math.max(workPause, LAYER_PAUSE_TICKS);
        }
    }

    /**
     * 下一个落脚点:选离<b>接下来要盖的那一片</b>最近的外沿点。
     *
     * <p>这是"看起来在干活"与"看起来在遛弯"的分界。匀速绕圈时,她的走位和她的
     * 产出是两条毫不相干的动画,叠在一起人一眼就看得出是假的。施工顺序是低层
     * 优先、层内蛇形,接下来几十格在空间上本就连成一片;把落脚点绑到那一片,
     * 她就会自然地沿着正在砌的那面墙挪,盖完东墙走到南边——同样是绕外圈,
     * 但因果对上了。
     */
    private Vec3 nextWanderPoint() {
        if (wanderPoints.isEmpty()) {
            return null;
        }
        Vec3 focus = upcomingFocus();
        if (focus == null) {
            return wanderPoints.get(wanderIndex++ % wanderPoints.size());
        }
        Vec3 best = wanderPoints.get(0);
        double bestDist = Double.MAX_VALUE;
        for (Vec3 point : wanderPoints) {
            double dx = point.x - focus.x;
            double dz = point.z - focus.z;
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = point;
            }
        }
        return best;
    }

    /** 当前这一层还没建的部分的水平重心;没有待建格则 null。 */
    private Vec3 upcomingFocus() {
        double x = 0;
        double z = 0;
        int n = 0;
        for (int i = layerStart; i < layerEnd && n < WANDER_LOOKAHEAD_CELLS; i++) {
            BuildTaskRecord.Target t = order.get(i);
            if (placedThisLayer.contains(t.pos().asLong())) {
                continue;
            }
            x += t.pos().getX();
            z += t.pos().getZ();
            n++;
        }
        return n == 0 ? null : new Vec3(x / n + 0.5, siteMin.getY(), z / n + 0.5);
    }

    /**
     * 巡视路线:绕工地包围盒<b>外沿</b>一圈,按顺时针顺序排点。
     *
     * <p>她隔空落位,本来就没有理由进场。站进去唯一的后果是把自己压着的那一格
     * 锁死——身体占格是放置的硬前置(不能把方块塞进活物身体里),而她自己又不
     * 知道该往哪让。实测就栽在这里:1275/1276,差的正是她脚上那一格。
     *
     * <p>让她始终在场外,这一整类失败就不存在了,不需要任何"挪窝解锁"的补救。
     * 观感上也更像那么回事——绕着工地巡场,而不是站在半成品里面。
     */
    private void computeWanderPoints() {
        int x0 = siteMin.getX() - SITE_MARGIN;
        int x1 = siteMax.getX() + SITE_MARGIN;
        int z0 = siteMin.getZ() - SITE_MARGIN;
        int z1 = siteMax.getZ() + SITE_MARGIN;
        // 步长随工地大小走:小屋子也要绕出几个点,大宅不至于绕出上百个
        int span = Math.max(x1 - x0, z1 - z0);
        int stride = Math.max(3, span / 8);
        double y = siteMin.getY();
        List<Vec3> points = new ArrayList<>();
        for (int x = x0; x <= x1; x += stride) points.add(new Vec3(x + 0.5, y, z0 + 0.5));
        for (int z = z0; z <= z1; z += stride) points.add(new Vec3(x1 + 0.5, y, z + 0.5));
        for (int x = x1; x >= x0; x -= stride) points.add(new Vec3(x + 0.5, y, z1 + 0.5));
        for (int z = z1; z >= z0; z -= stride) points.add(new Vec3(x0 + 0.5, y, z + 0.5));
        wanderPoints = List.copyOf(points);
    }

    // ------------------------------------------------------------------
    // 施工顺序与工地
    // ------------------------------------------------------------------

    private void computeSite() {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos pos = target.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        if (minX > maxX) {
            BlockPos feet = playerFeet();
            siteMin = feet;
            siteMax = feet;
            return;
        }
        siteMin = new BlockPos(minX, minY, minZ);
        siteMax = new BlockPos(maxX, maxY, maxZ);
    }

    /**
     * 重排本遍顺序:只收还没达标的格。低层在前(下面盖好了上面才有依托),
     * 层内先清障、再骨架、最后贴附;同类蛇形走位(牛耕式),顺序完全确定
     * ——没有"智能选点"可坏,断点续建也就成立。
     *
     * <p><b>贴附件整体推到第二趟</b>,排在所有层之后。火把、告示牌、梯子、地毯、
     * 花草、铁轨、红石、压力板、按钮、挂灯这些东西都要依托别的方块才站得住,而
     * 骨架是逐层长起来的:主趟走到某一层时,它上面的支撑还不存在。同一格反复
     * "放下去—掉下来—再放"不但白费工,还会让完工判定在原地打转。
     *
     * <p>所以主趟只砌立得住的东西,一层一层从下往上;骨架全部立好之后再回头走
     * 一趟,专门贴这些细软件。多走一趟不亏:她本来就是当着玩家的面一层层砌上去
     * 的,收尾时再绕一圈挂灯摆花,恰好是这活儿该有的样子。
     */
    private void rebuildOrder() {
        List<BuildTaskRecord.Target> pending = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (!target.matches(player.level().getBlockState(target.pos()))
                    && !blockedByMode(target) && !hopeless(target)) {
                pending.add(target);
            }
        }
        pending.sort(BUILD_ORDER);
        order = pending;
        resetLayerWindow();
    }

    /** 施工顺序的唯一定义(公开是为了让测试直接钉住它,而不是靠副作用间接猜)。 */
    public static final Comparator<BuildTaskRecord.Target> BUILD_ORDER = Comparator
            .comparingInt((BuildTaskRecord.Target t) -> needsSupport(t.desiredState()) ? 1 : 0)
            .thenComparingInt(t -> t.pos().getY())
            .thenComparingInt(BuildCompanionTask::stage)
            .thenComparingInt(t -> t.pos().getZ())
            .thenComparingInt(t -> (t.pos().getZ() & 1) == 0 ? t.pos().getX() : -t.pos().getX());

    /**
     * 这一格立不立得住:要依托别的方块的算<b>贴附件</b>,推到第二趟。
     *
     * <p>按方块类型判,不按"能不能存活"现场试——现场试要有支撑才知道答案,而主趟
     * 正是支撑还没长出来的时候。类型是封闭集合,一次列完;"能不能存活"是开放的,
     * 每来一个新方块就得被咬一次。
     */
    public static boolean needsSupport(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.HANGING)) {
            return true;   // 挂着的灯笼与告示牌
        }
        var b = state.getBlock();
        return b instanceof net.minecraft.world.level.block.LadderBlock
                || b instanceof net.minecraft.world.level.block.BaseTorchBlock
                || b instanceof net.minecraft.world.level.block.SignBlock
                || b instanceof net.minecraft.world.level.block.BasePressurePlateBlock
                || b instanceof net.minecraft.world.level.block.BaseRailBlock
                || b instanceof net.minecraft.world.level.block.DiodeBlock
                || b instanceof net.minecraft.world.level.block.RedStoneWireBlock
                || b instanceof net.minecraft.world.level.block.CarpetBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock
                || b instanceof net.minecraft.world.level.block.SnowLayerBlock
                // 按钮、拉杆这类贴面件;砂轮同属这一族但它自己立得住
                || (b instanceof net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
                        && !(b instanceof net.minecraft.world.level.block.GrindstoneBlock));
    }

    /**
     * 寻路对工地格的双重禁令:<b>不许拆、不许占</b>。
     *
     * <p>不许拆——否则她会为了抄近路把自己刚砌好的墙打个洞穿过去,一边建一边拆。
     * 不许占——否则寻路会拿垫柱材料把某个目标格填上,那格从此和图纸对不上,还得
     * 先拆再放。
     */
    private LongSet protectedCells() {
        if (siteCells == null) {
            siteCells = new LongOpenHashSet(targetByPos.keySet());
        }
        return siteCells;
    }

    private LongOpenHashSet siteCells;

    /** 把工地格并进寻路收到的禁令集——两条禁令对本任务起的每一次寻路都生效。 */
    private LongSet union(LongSet other) {
        if (other == null || other.isEmpty()) {
            return protectedCells();
        }
        LongOpenHashSet merged = new LongOpenHashSet(protectedCells());
        merged.addAll(other);
        return merged;
    }

    /** 把层窗口对准 order 里最低的那一层。 */
    private void resetLayerWindow() {
        placedThisLayer.clear();
        layerStart = 0;
        layerCursor = 0;
        layerEnd = 0;
        if (order.isEmpty()) {
            return;
        }
        int y = order.get(0).pos().getY();
        while (layerEnd < order.size() && order.get(layerEnd).pos().getY() == y) {
            layerEnd++;
        }
    }

    /** 层内阶段:清障 0 → 骨架 1 → 贴附 2。 */
    private static int stage(BuildTaskRecord.Target target) {
        if (isAirTarget(target)) {
            return 0;
        }
        BlockState state = target.desiredState();
        return state != null && state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                BlockPos.ZERO) ? 1 : 2;
    }

    /**
     * 这一趟的落位速率(开工时算一次,全程恒定)。
     *
     * <p>目标时长封顶、速率由格数除出来:小工程走下限速率,自然比封顶短;大工程
     * 一开始就更快,总时长收敛到封顶值。不是"越盖越快",也没有到点强制收工。
     */
    public static double paceFor(int cellCount, boolean consumeMaterials) {
        int cells = Math.max(1, cellCount);
        return consumeMaterials
                ? Math.max(SURVIVAL_MIN_RATE, cells / SURVIVAL_TARGET_TICKS)
                : Math.min(FREE_MAX_RATE, cells / FREE_TARGET_TICKS);
    }

    /**
     * 施工预计要多少刻——派发方据此定时限。
     *
     * <p>必须和 {@link #paceFor} 用同一个公式算,不能各拍各的:此前时限按"每格
     * 固定几刻"估,而我把"每秒两格"错记成了"每刻两格",于是最慢档的真实开销
     * (每格十刻)被低估了二十倍,五百格的生存建筑会在盖到一半时被判超时——而
     * 一千四百格以下走的都是这个下限速率,也就是大多数房子。
     */
    public static long estimatedTicks(int cellCount, boolean consumeMaterials) {
        return (long) Math.ceil(Math.max(1, cellCount) / paceFor(cellCount, consumeMaterials));
    }

    /** 按格数和目标时长定这一趟的速率(开工时算一次)。 */
    private void computePace() {
        int cells = Math.max(1, r.targets.size());
        cellsPerTick = paceFor(cells, r.consumeMaterials);
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-build] 节奏 {} 格,{} 格/秒,预计 {} 秒",
                cells, String.format("%.1f", cellsPerTick * 20),
                (int) (cells / cellsPerTick / 20));
    }

    private List<BlockPos> pendingPositions() {
        List<BlockPos> out = new ArrayList<>();
        for (int i = layerStart; i < order.size() && out.size() < 64; i++) {
            out.add(order.get(i).pos());
        }
        return out;
    }

    private void drainScaffold() {
        for (BlockPos placed : BuildPlacementRegistry.drainScaffold(player)) {
            if (!targetByPos.containsKey(placed.asLong())) {
                scaffold.add(placed);
            }
        }
    }

    // ------------------------------------------------------------------
    // 判据与账目
    // ------------------------------------------------------------------

    /**
     * 谁都不豁免——包括同伴自己:身体占着/正落进的格子不可放置。
     *
     * <p>自己的身体用直接几何判交,不走实体分区索引——假人在索引里会漏检
     * (法医快照抓到过"双脚站在目标格里却放行",随后往自己身上落方块把自己
     * 封进树叶)。我在哪儿,不需要问索引。
     */
    private boolean blockedByEntity(BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(player.level(), pos, CollisionContext.of(player));
        if (shape.isEmpty()) {
            return false;
        }
        VoxelShape placed = shape.move(pos.getX(), pos.getY(), pos.getZ());
        AABB body = player.getBoundingBox();
        for (AABB piece : placed.toAabbs()) {
            if (piece.intersects(body)) {
                return true;
            }
        }
        return !player.level().isUnobstructed(player, placed);
    }

    private void markObserved(BuildTaskRecord.Target target, boolean completed) {
        if (observedCompleted == null) {
            observedCompleted = new LongOpenHashSet();
        }
        if (completed) {
            observedCompleted.add(target.pos().asLong());
        } else {
            observedCompleted.remove(target.pos().asLong());
        }
    }

    private void updateCompleted() {
        if (observedCompleted == null) {
            observedCompleted = new LongOpenHashSet();
        }
        int completed = 0;
        BlockGetter view = LoadedOnlyView.of(player.level());
        LoadedOnlyView loadedView = view instanceof LoadedOnlyView v ? v : null;
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos pos = target.pos();
            boolean loaded = loadedView == null || loadedView.isLoaded(pos.getX(), pos.getZ());
            if (loaded) {
                BlockState observed = view.getBlockState(pos);
                if (target.matches(observed)
                        || blockedByMode(target)
                        || hopeless(target)
                        || target.desiredState().getBlock() instanceof LiquidBlock
                        || (isAirTarget(target) && observed.getBlock() instanceof LiquidBlock)) {
                    // 液体口径:不放液体目标、清空型目标也不排水——这两类跳过豁免;
                    // 固体目标被液体淹着不豁免,照放,方块直接顶掉水(原版语义)
                    observedCompleted.add(pos.asLong());
                    completed++;
                } else if (observedCompleted.remove(pos.asLong())) {
                    // 曾经达标、现在不达标:只可能是外力(玩家拆、苦力怕炸、
                    // 水火漫过来)。下一遍重排会把它收回队列自动补上;这里只记账,
                    // 收尾时随结果一并交代。
                    //
                    // 【事件挂点】自家的活正在被拆 —— 典型的"有时效、错过就没了"。
                    // {@code observedCompleted.remove} 返回真恰好是那一次边沿。
                    // 收件箱对"有后台任务在跑"的事件是立刻开轮的,正合这一类:
                    // 墙正在被拆,不该等她下次想起来问进度才知道。
                    damagedCells++;
                }
            } else if (observedCompleted.contains(pos.asLong())) {
                completed++;
            }
        }
        r.completed(completed);
    }

    /** 从背包扣掉一个该物品。 */
    private void consumeOne(Item item) {
        if (player.hasInfiniteMaterials()) {
            return;   // 任务中途被切成免耗材画像:记账即刻停手,别扣真方块
        }
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                return;
            }
        }
    }

    private static boolean isAirTarget(BuildTaskRecord.Target target) {
        return target.block() == net.minecraft.world.level.block.Blocks.AIR;
    }

    private boolean isReplaceable(BlockPos pos, BlockState state) {
        return MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state,
                com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest.ALWAYS);
    }

    private boolean hasItem(Item item) {
        return mainInventoryCount(item) > 0;
    }

    private boolean hasItem(Item item, boolean wholeInventory) {
        return hasItems(item, 1, wholeInventory);
    }

    /**
     * 把图纸带来的方块实体数据装进刚放好的那一格:箱子里的东西、告示牌的字、
     * 旗帜的花纹、书架上的书。
     *
     * <p>不装的话,社区图纸建出来是一屋子空箱子和白板告示牌——外形对了,内容全丢,
     * 而这是玩家一眼就能看出来的那种丢。
     *
     * <p>坐标要覆写成落位点:图纸里存的是导出时的世界坐标,原样加载会让方块实体
     * 认为自己在别处。
     */
    private void applyBlockEntityData(BlockPos pos, BlockState placed) {
        if (r.blockEntityData.isEmpty() || !placed.hasBlockEntity()) {
            return;
        }
        var data = r.blockEntityData.get(pos.asLong());
        if (data == null) {
            return;
        }
        var be = player.level().getBlockEntity(pos);
        if (be == null) {
            return;
        }
        try {
            var copy = data.copy();
            copy.putInt("x", pos.getX());
            copy.putInt("y", pos.getY());
            copy.putInt("z", pos.getZ());
            be.loadWithComponents(copy, player.level().registryAccess());
            be.setChanged();
        } catch (RuntimeException ignored) {
            // 数据坏了只影响这一格的内容,方块本身已经放好了,不该让整栋楼停工
        }
    }

    /**
     * 这一格本档不让动吗——让路的判定只有这一处。
     *
     * <p>不让动的格子<b>不进待建集、也算作了结</b>。若只是"放的时候跳过",它每一遍
     * 都会重新排进顺序、每一遍都放不下去,整栋楼陪着它重试到超时,而那一格从第一遍
     * 起就已经注定动不了。
     */
    private boolean blockedByMode(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        BlockState current = player.level().getBlockState(pos);
        if (!r.replaceMode.allows(current, target.desiredState())) {
            return true;
        }
        // 玩家的箱子不能被一堵墙盖掉。让路的档位管"石头挡路要不要顶掉",这一条
        // 管"带方块实体的方块要不要动"——少砌一格墙是遗憾,清掉一箱子东西是事故。
        if (r.replaceBlockEntities || current.isAir()) {
            return false;
        }
        if (current.hasBlockEntity() && !target.matches(current)) {
            return true;
        }
        // 双格方块连另一半一起看:任一半压着方块实体就都不动
        BlockPos other = otherHalfOf(pos, target.desiredState());
        return other != null && player.level().getBlockState(other).hasBlockEntity();
    }

    /**
     * 这一格<b>注定</b>动不了吗——世界边界之外,或者砸不动的东西挡着。
     *
     * <p>和"这一刻放不下去"要分开:后者(区块没加载、她自己站在那格里、材料没到)
     * 下一遍就可能变,该留在待建集里;前者从第一遍起就不会变,留着只会让整栋楼
     * 每一遍都为它重排一次顺序、重试一次,一直耗到超时。
     */
    private boolean hopeless(BuildTaskRecord.Target target) {
        BlockPos pos = target.pos();
        if (!player.level().getWorldBorder().isWithinBounds(pos)) {
            return true;
        }
        return unbreakableAt(pos, target.desiredState());
    }

    /**
     * 这一格砸不动吗——基岩、末地传送门框架这类 {@code destroySpeed == -1} 的东西。
     *
     * <p>不判的话她会对着基岩一遍遍地清、一遍遍地失败,直到超时。<b>双格方块要连
     * 它的另一半一起查</b>:床的另一半在朝向那一格,门与高草的另一半在正上方——
     * 只查自己那一格,会出现"下半放下去了、上半卡在基岩里"的半截货。
     */
    private boolean unbreakableAt(BlockPos pos, BlockState desired) {
        var level = player.level();
        if (level.getBlockState(pos).getDestroySpeed(level, pos) == -1) {
            return true;
        }
        BlockPos other = otherHalfOf(pos, desired);
        return other != null && level.getBlockState(other).getDestroySpeed(level, other) == -1;
    }

    /**
     * 双格方块的另一半在哪:床看朝向那一格,门与高草看正上方。
     *
     * <p>只查自己那一格,会出现"下半放下去了、上半卡在基岩里"或者"下半盖住了玩家
     * 箱子的上半"这类半截货,所以砸不动与箱子保护两处都要连它一起看。
     */
    private static BlockPos otherHalfOf(BlockPos pos, BlockState desired) {
        if (desired == null) {
            return null;
        }
        if (desired.hasProperty(BlockStateProperties.BED_PART)
                && desired.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && desired.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return pos.relative(desired.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        if (desired.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && desired.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return pos.above();
        }
        return null;
    }

    /** 够不够 {@code count} 件——双层砖那种一格吃两件的格子要问这个。 */
    private boolean hasItems(Item item, int count, boolean wholeInventory) {
        if (wholeInventory && NavSettings.get().allowInventory) {
            return mainInventoryCount(item) >= count;
        }
        return hotbarCount(item) >= count;
    }

    private boolean hasItemOnHotbar(Item item) {
        return hotbarCount(item) > 0;
    }

    private int hotbarCount(Item item) {
        Inventory inventory = player.getInventory();
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private int findSlot(Item item, boolean wholeInventory) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        if (wholeInventory && NavSettings.get().allowInventory) {
            return findMainInventorySlot(item);
        }
        return -1;
    }

    private int mainInventoryCount(Item item) {
        Inventory inventory = player.getInventory();
        int count = 0;
        int limit = Math.min(36, inventory.items.size());
        for (int i = 0; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int findMainInventorySlot(Item item) {
        Inventory inventory = player.getInventory();
        int limit = Math.min(36, inventory.items.size());
        for (int i = 9; i < limit; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    private Set<BlockState> availableStates(boolean wholeInventory) {
        Set<BlockState> states = new HashSet<>();
        Inventory inventory = player.getInventory();
        int limit = wholeInventory && NavSettings.get().allowInventory
                ? Math.min(36, inventory.items.size()) : 9;
        for (int i = 0; i < limit; i++) {
            addAvailableState(states, inventory.getItem(i));
        }
        return states;
    }

    private void addAvailableState(Set<BlockState> states, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        try {
            BlockPos feet = playerFeet();
            BlockHitResult hit = new BlockHitResult(player.position(), Direction.UP, feet, false);
            BlockState state = blockItem.getBlock().getStateForPlacement(new BlockPlaceContext(new UseOnContext(
                    player.level(), player, InteractionHand.MAIN_HAND, stack, hit) {}));
            if (state != null) {
                states.add(state);
            }
        } catch (RuntimeException e) {
            states.add(blockItem.getBlock().defaultBlockState());
        }
    }

    // ------------------------------------------------------------------
    // 朝向与寻路桥接
    // ------------------------------------------------------------------

    private void applySteppedAim(Vec3 point) {
        Vec3 eye = player.getEyePosition();
        applySteppedAim(MovementHelper.yawTo(eye, point), MovementHelper.pitchTo(eye, point));
    }

    private void applySteppedAim(float yaw, float pitch) {
        AimProcessor.Rotation next = AIM.step(player.getYRot(), player.getXRot(), yaw, pitch);
        player.setYRot(next.yaw());
        player.setYHeadRot(next.yaw());
        player.setXRot(next.pitch());
    }

    private BlockPos playerFeet() {
        return PathExecutor.playerFeet(player);
    }

    private void registerProvider() {
        if (!providerRegistered) {
            BuildPlacementRegistry.register(player, this);
            providerRegistered = true;
        }
    }

    private void unregisterProvider() {
        if (providerRegistered) {
            BuildPlacementRegistry.unregister(player, this);
            providerRegistered = false;
        }
    }

    private Map<Long, BuildTaskRecord.Target> activeTargetMap() {
        return targetByPos;
    }

    @Override
    public BlockState desiredState(BlockPos placeAt) {
        BuildTaskRecord.Target target = targetByPos.get(placeAt.asLong());
        if (target == null || isAirTarget(target)) {
            return null;
        }
        if (target.matches(player.level().getBlockState(target.pos()))) {
            return null;
        }
        return target.desiredState();
    }

    @Override
    public boolean acceptsPlacement(BlockPos placeAt, BlockState state) {
        BuildTaskRecord.Target target = targetByPos.get(placeAt.asLong());
        return target != null && target.acceptsPlacedState(state);
    }

    @Override
    public CalculationContext forSearch(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forSearch(player, union(sacred), union(deniedPlace),
                (p, view, loaded, safe, s, denied) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, activeTargetMap(), availableStates(true),
                        r.replaceExisting));
    }

    @Override
    public CalculationContext forExecution(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forExecution(player, union(sacred), union(deniedPlace),
                (p, view, loaded, safe, s, denied) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, activeTargetMap(), availableStates(true),
                        r.replaceExisting));
    }

    @Override
    public void suspend() {
        super.suspend();
        unregisterProvider();
        InputDriver.halt(player);
    }

    @Override
    public void resume() {
        registerProvider();
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        unregisterProvider();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
    }

    /**
     * 收尾结算,随 {@code task_finished} 送达:<b>这一趟活总共发生了什么</b>。
     *
     * <p>与 {@code task_status} 的分工——那边只答"还剩多少",这边答"办完了没、
     * 办成什么样"。施工中的即时状况属于第三条路(事件队列),不塞进这两处。
     *
     * <p>只陈述事实。她要不要跟玩家提、怎么提、用什么语言,是她的事。
     */
    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("requested", r.targets.size());
        data.put("completed", r.completed());
        data.put("placed", r.placed());
        data.put("cleared", r.broken());
        data.put("site_min", siteMin == null ? "-" : siteMin.toShortString());
        data.put("site_max", siteMax == null ? "-" : siteMax.toShortString());
        if (damagedCells > 0) {
            // 施工期间被外力拆毁又补回去的格数。她盖得慢或反复返工,原因在这儿。
            data.put("destroyed_while_building", damagedCells);
        }
        if (r.consumeMaterials) {
            Map<Item, Integer> shortfall = shortfallAgainstInventory(remainingNeed());
            if (!shortfall.isEmpty()) {
                data.put("still_short", summarizeShortfall(shortfall));
            }
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return "built " + r.completed() + "/" + r.targets.size()
                + " block(s); placed " + r.placed() + ", cleared " + r.broken()
                + (damagedCells > 0
                        ? "; " + damagedCells + " finished cell(s) were destroyed mid-build by "
                                + "something outside the job and had to be redone"
                        : "")
                + " (" + note + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out while building; completed " + r.completed() + "/" + r.targets.size()
                + " (" + note + ")";
    }

    @Override
    protected String cancelledMessage() {
        return "build interrupted after " + r.completed() + "/" + r.targets.size() + " block(s)";
    }

    private static String label(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
