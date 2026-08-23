package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.cache.LoadedOnlyView;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.movements.BuildPlacementRegistry;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>分工</b>——本类只持有施工的调度状态机(相位、遍、层窗口、落位循环)与
 * 轮扫对账;单格判据在 {@link BuildCellRules},背包口径在 {@link BuildInventory},
 * 材料账本在 {@link BuildLedger},摆设与善后在 {@link BuildFixtures},演出在
 * {@link BuildShowmanship},顺序与节奏的纯函数在 {@link BuildOrder}。
 */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord>
        implements BuildPlacementRegistry.Provider, PlayerNav.ContextProvider {

    private static final double WALK_SPEED = 1.0;

    /** 赴工地的时限:走不到就地开工,绝不因为路不通而不干活。 */
    private static final int TRAVEL_BUDGET_TICKS = 30 * 20;
    /** 单刻落位硬上限:再快也不能一刻塞几百格,那是卡顿不是建造。 */
    private static final int MAX_CELLS_PER_TICK = 8;
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

    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;
    private final BuildLedger ledger;
    private final BuildShowmanship show;

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
    /** 已砌好又被外力弄没的格数(收工时用来解释"为什么磨了这么久")。 */
    private int damagedCells;
    /**
     * 决定<b>不去动</b>的格:让路档位不许、玩家的箱子压着、基岩挡着、边界之外、
     * 出了建造高度。
     *
     * <p>它们既不算完成也不算待办——算完成就是说谎(那一格根本没动),算待办就永远
     * 收不了工(它们从第一遍起就不会变)。所以单独记一笔,收工时如实交代。
     *
     * <p>存成<b>位置集合</b>而不是一个计数器,和 {@code observedCompleted} 同一个道理:
     * 判定要读世界,而区块会卸载。计数器每遍重算的话,一格在加载时被判"不去动"、随后
     * 区块滑出加载范围,它就既不在完成集也不在跳过集里——{@code completed + skipped}
     * 永远差这一格,整栋楼收不了工,最后以"她站不住"失败。集合有记忆,计数器没有。
     */
    private LongOpenHashSet skippedPos = new LongOpenHashSet();
    /** {@code skippedPos.size()} 的缓存——判完工在热路径上,不必每次问集合。 */
    private int skippedCells;
    private int passStartCompleted;
    private int barrenPasses;
    /**
     * 本遍已经证明<b>付不起剩下任何一格</b>——缺料这件事在这一刻就成立了,不必走完这遍。
     *
     * <p>此前判"干不下去了"用的是"一遍走完没有进展",那是个<b>过程量</b>,时间分辨率
     * 就是一遍;而"她付不起剩下任何一格"是个<b>状态量</b>,在她第一次付不起的那一刻
     * 就已经成立。用过程量推断状态量必然慢一拍,而那一拍里所有的演出停顿(每层 18 刻、
     * 收遍 60 刻)都在为一个早就成立的结论排队——玩家看到的是她溜达一分钟才说没料。
     */
    private boolean passStarved;
    /** 本层开始时已落位的格数——演出停顿要"这一层真砌了东西"才付。 */
    private int layerStartPlaced;
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

    /** 挪窝状态。 */
    private List<Vec3> wanderPoints = List.of();
    private int wanderIndex;
    private int wanderTicks;
    private Vec3 wanderTarget;

    private String note = "done";

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        this.rules = new BuildCellRules(player, record);
        this.inv = new BuildInventory(player);
        this.fixtures = new BuildFixtures(player, record, inv);
        this.ledger = new BuildLedger(player, record, rules, inv, fixtures);
        this.show = new BuildShowmanship(player, inv);
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
        Map<Item, Integer> need = ledger.remainingNeed();
        Map<Item, Integer> shortfall = ledger.shortfallAgainstInventory(need);
        List<BuildTaskRecord.CellNeed> exactShort = ledger.needShortfall(ledger.remainingCellNeeds());
        if (shortfall.isEmpty() && exactShort.isEmpty()) {
            return null;
        }
        if (r.allowPartial) {
            // 整幢图纸一趟本来就运不完:能开工就开工,补给跑几趟是常态而不是错误。
            // 只有一格都买不起时才拦——那才是真的开不了工。
            for (Item item : need.keySet()) {
                if (inv.hasItem(item, true)) {
                    return null;
                }
            }
        }
        return new Precondition.Failure(
                "not enough materials yet — " + BuildLedger.summarizeShortfall(shortfall, exactShort)
                        + ". Nothing was placed. Survival mode consumes 1 item per cell; gather these, "
                        + "then send the SAME call again — anything already standing is skipped, so a "
                        + "restocked repeat picks up exactly where this left off.",
                FailureType.NO_MATERIAL);
    }

    private Precondition.Failure checkExistingBlocks() {
        if (r.replaceExisting) {
            return null;
        }
        for (BuildTaskRecord.Target target : r.targets) {
            BlockState state = rules.peek(target.pos());
            if (!target.matches(state)
                    && (BuildCellRules.isAirTarget(target) || !isReplaceable(target.pos(), state))) {
                return new Precondition.Failure("target " + target.shortPos()
                        + " is occupied; enable replacement or clear it first", FailureType.TARGET_LOST);
            }
        }
        return null;
    }

    @Override
    protected void onStart() {
        observedCompleted = new LongOpenHashSet();
        skippedPos = new LongOpenHashSet();
        computeSite();
        computeWanderPoints();
        registerProvider();
        rescanAll();
        rebuildOrder();
        computePace();
        passStartCompleted = r.completed();
        phase = Phase.TRAVEL;
    }

    @Override
    protected TaskState onTick() {
        // 蹲姿由施工分支决定并保持:落位只在批次刻发生,若每 tick 复位成站立,
        // 中间那些刻就会把她弹起来,看起来是在抖而不是在蹲着贴边放。
        player.setShiftKeyDown(phase == Phase.WORK && show.crouching());
        registerProvider();
        updateCompleted();
        drainScaffold();

        // 每刻只轮扫一片,所以这个判定可能用着一轮之前的旧数据。收工是不可回头的
        // 一步(撤脚手架、生成摆设、报成功),所以真要收工之前必须再精确核一次:
        // 否则一格刚被玩家拆掉、轮扫还没转到它,她就会带着一个缺口报"全部达标"。
        if (r.completed() + skippedCells >= r.targets.size()) {
            rescanAll();
            if (r.completed() + skippedCells >= r.targets.size()) {
                return finish();
            }
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
                    () -> new GoalCompiler.Compiled(goal, protectedCells()),
                    WALK_SPEED, () -> false, this);
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
     * <p>顺序低层优先(上面的东西得有底下的东西撑着),层内则<b>跟着她的位置走</b>,
     * 不走固定蛇形。落位顺序跟她在哪无关的话,观感是"她在那边溜达,方块在这边冒出来"
     * ——两条互不相干的动画叠在一起,一眼就假。绑上之后,她走到东墙东墙就长,绕到
     * 南边南边接着长。<b>因果对上,比加多少粒子都管用。</b>
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
            if (passStarved) {
                break;   // 剩下一格都付不起,别再往下翻了
            }
        }
        if (!touched.isEmpty()) {
            show.performWork(touched, sample);
        }
        // 付不起剩下任何一格:当场收遍。走完剩下的层不会改变结论,只会让玩家多等
        // (每层还有 18 刻的演出停顿),而结论在第一次付不起的那一刻就已经成立了。
        if (passStarved) {
            return endPass();
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
        int bestStage = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        Vec3 me = player.position();
        for (int i = layerStart; i < layerEnd; i++) {
            BuildTaskRecord.Target t = order.get(i);
            if (placedThisLayer.contains(t.pos().asLong())) {
                continue;
            }
            // 层内先按 stage 分档,同档之内才比远近——"先清障、再骨架、最后贴附"
            // 的先后要真起作用;走位的自然感留在同一档之内。
            int stage = BuildOrder.stage(t);
            if (stage > bestStage) {
                continue;
            }
            double dx = t.pos().getX() + 0.5 - me.x;
            double dz = t.pos().getZ() + 0.5 - me.z;
            double d = dx * dx + dz * dz;
            if (stage < bestStage || d < bestDist) {
                bestStage = stage;
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
        //
        // 但这一拍要<b>这一层真砌了东西</b>才付:一格没砌的层没什么可扫的,那个停顿就
        // 只是在没干活的时候继续计费。缺料时她会把剩下的层一层层空翻过去,每层白停
        // 18 刻——玩家看到的是她慢悠悠溜达,而她其实早就干不下去了。
        if (r.placed() > layerStartPlaced) {
            workPause = LAYER_PAUSE_TICKS;
        }
        layerStartPlaced = r.placed();
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
        BlockState desired = target.desiredState();
        if (desired != null && desired.getBlock() instanceof LiquidBlock) {
            return null;   // 流体不承接:布水与排水都不做
        }
        // 加载判定要在读取<b>之前</b>:反过来的话第一句 getBlockState 就已经把区块
        // 同步生成出来了,后面这句永远为真,等于没判。
        if (!player.level().isLoaded(pos)) {
            return null;   // 区块这一刻没加载:临时状况,下一遍再来(不算注定动不了)
        }
        BlockState current = player.level().getBlockState(pos);
        if (target.matches(current)) {
            markObserved(target, true);
            return null;
        }

        boolean occupied = !current.isAir() && !(current.getBlock() instanceof LiquidBlock);

        // 先过完所有门禁,再动手破坏。此前顺序是反的:先 clear 掉挡路的方块,再去
        // 检查活物占位与材料——于是"目标砖用完了"这种最常见的情形下,她会把玩家的
        // 草坪挖出一条沟,然后一格墙都没砌。她自己站在那格里时更糟:脚下先被挖空。
        // 破坏是不可撤销的,所以它必须是这一格的最后一道动作,不是第一道。
        if (rules.blockedByEntity(pos, desired)) {
            // 谁都不豁免——包括她自己:身体占着的格子这遍先放下,下一遍她已经挪开了。
            // 防的是把方块塞进活物身体里这类真事故。
            return null;
        }
        // 让路档位与方块实体保护要在<b>动手前复查</b>,不能只在遍首排队时查过一次。
        // 一遍可能跑好几分钟:玩家在这期间往目标格放了个箱子,而队列是几分钟前排的,
        // 于是下面那句 clear 会把它挖掉。生存模式带方块实体掉落所以东西不至于消失,
        // 但"带方块实体的方块一律不动"这句承诺就破了——而那是我们自己写进工具描述、
        // 也是玩家唯一能依赖的保证。
        if (rules.blockedByMode(target) || rules.hopeless(target)) {
            return null;
        }
        // 一格不一定只花一件(双层砖两件、雪层按层数),盘点与实扣共用同一个件数
        int cost = r.consumeMaterials && rules.costsMaterial(target) ? target.materialCount() : 0;
        // 有料单的格走另一道闸门:花盆要盆和花两件都在,旗帜要那面绣好的才算数
        List<BuildTaskRecord.CellNeed> needs = ledger.needsFor(target);
        if (cost > 0 && !needs.isEmpty()) {
            for (BuildTaskRecord.CellNeed need : needs) {
                if (inv.countMatching(need) < 1) {
                    noteShortage(need.stack().getItem(), 1);
                    return null;
                }
            }
        } else if (cost > 0 && !inv.hasItems(target.item(), cost, true)) {
            noteShortage(target.item(), cost);
            return null;
        }

        if (occupied) {
            clear(pos);
            if (BuildCellRules.isAirTarget(target)) {
                markObserved(target, true);
                return desired;
            }
        }
        if (BuildCellRules.isAirTarget(target)) {
            markObserved(target, current.isAir() || current.getBlock() instanceof LiquidBlock);
            return null;
        }

        if (target.itemPlace()) {
            placeWithItem(target, pos);
            // 落没落成看世界,不看返回值:物品的 place 可以吃掉点击却什么都没放。
            // 拒收(保护、事件被取消、立不住)就本遍放下——零进展遍机制照常裁决。
            BlockState now = player.level().getBlockState(pos);
            if (!target.matches(now)) {
                return null;
            }
            r.placedOne();
            markObserved(target, true);
            return now;
        }

        // 写不进去就什么都不算:setBlock 在超出建造高度时直接返回假、世界毫无变化。
        // 照样扣料 + 记一笔 placed 的后果是,那一格永远对不上、每遍重来,三遍下来
        // 材料凭空消失三倍,而进度报的比实际多三倍。hopeless 已经把这类格从分母里
        // 摘掉了,这里是第二道:世界说没写成,就是没写成。
        if (!player.level().setBlock(pos, desired, PLACE_FLAGS)) {
            return null;
        }
        applyBlockEntityData(pos, desired);
        // 放完给方块一次"我被放下了"的回调:命名牌、告示牌、部分方块实体靠它初始化。
        //
        // <p>这一句和上面 PLACE_FLAGS 那段是有张力的:那段刻意不走通知链路,而这条
        // 回调会把一部分邻居更新引回来——门/床/高草的 setPlacedBy 自己用带更新的方式
        // 写另一半,活塞的会去判断该不该伸出。这是<b>知情的取舍</b>:双格方块的另一半
        // 本来就该由这条回调来造,所以次半根本不进目标集(见
        // {@code BuildStates#isSecondaryHalf})。别改成两半都进集、次半记 0 件靠
        // "轮到它时比对已成立直接短路"兜着——那条推理对门成立(另一半恒在正上方,
        // 按 y 排序主半必先落位),对床不成立:床的两半同 y,朝北时床头的 z 更小会先
        // 落位,而床的回调写的是"朝向再往外一格",那一格在目标集之外。活塞伸出是原版
        // 该有的行为(我们只禁止把活塞头当建材单独摆)。写在这里是为了让下一个排查
        // "为什么某些格被改写"的人不必先怀疑 PLACE_FLAGS 失效。
        //
        // 兜住异常——这条回调本是给"玩家手持物品放置"设计的,我们没有那个上下文,
        // 个别方块会在里面自己炸掉,而那不该让整栋楼停工。
        try {
            // 回调拿到的是"她手里那件东西"。有料单的格给单子上第一叠真货(带花纹的
            // 旗帜),不是一件同名的白货——回调会从里面读组件。
            desired.getBlock().setPlacedBy(player.level(), pos, desired, player,
                    needs.isEmpty() ? new ItemStack(target.item()) : needs.get(0).stack().copy());
        } catch (RuntimeException ignored) {
            // 放置本身已经成功,回调失败只影响那一格的附加数据
        }
        if (!needs.isEmpty()) {
            if (cost > 0) {
                for (BuildTaskRecord.CellNeed need : needs) {
                    inv.consumeMatching(need);
                }
            }
        } else {
            for (int k = 0; k < cost; k++) {
                inv.consumeOne(target.item());
            }
        }
        r.placedOne();
        markObserved(target, true);
        return desired;
    }

    /**
     * 记一笔缺料,并在<b>本遍第一次</b>缺料时判断这一遍是不是已经死了。
     *
     * <p>{@code passMissing.isEmpty()} 恰好是那一次边沿,不必另记状态去重。
     * (【事件挂点】要把"她没料了"推给她时也在这里 emit,前提是先在 GameEvents.Kind
     * 里登记一个词——那是 numen-api 的改动。)
     *
     * <p>判据是<b>状态量</b>:剩下的待建格里,还有没有哪怕一格是她此刻付得起的。有——
     * 这一遍还能推进,照常走;一格都没有——这一遍已经证明是死的,不必再把剩下的层空翻
     * 一遍(每层还要停 18 刻),更不必等三个零进展遍。
     */
    private void noteShortage(Item item, int count) {
        boolean firstShortageThisPass = passMissing.isEmpty();
        passMissing.merge(item, count, Integer::sum);
        if (firstShortageThisPass && !passStarved && !canStillAffordAnyPendingCell()) {
            passStarved = true;
        }
    }

    /**
     * 剩下的待建格里,还有没有哪怕一格是她此刻付得起的。
     *
     * <p>逐格问而不是拿"总需求 vs 背包"的聚合缺口来推:聚合缺口回答的是"全部建完还差
     * 多少",而这里要回答的是"还能不能再放下一格"。两者不等价——她可能凑不齐整栋楼,
     * 却还能砌十堵墙,那时候停下来是错的。
     */
    private boolean canStillAffordAnyPendingCell() {
        for (BuildTaskRecord.Target target : r.targets) {
            if (target.matches(rules.peek(target.pos()))) continue;
            if (rules.blockedByMode(target) || rules.hopeless(target)) continue;
            int cost = r.consumeMaterials && rules.costsMaterial(target) ? target.materialCount() : 0;
            if (cost <= 0) {
                return true;   // 不花料的格(清空格)永远付得起
            }
            var needs = ledger.needsFor(target);
            if (needs.isEmpty()) {
                if (inv.hasItems(target.item(), cost, true)) {
                    return true;
                }
            } else {
                boolean affordable = true;
                for (BuildTaskRecord.CellNeed need : needs) {
                    if (inv.countMatching(need) < 1) {
                        affordable = false;
                        break;
                    }
                }
                if (affordable) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 原生车道:把这件物品拿在手里,对目标格来一次真右键({@code gameMode.useItemOn})。
     *
     * <p>图纸格照图直写是对的——精确落位是它的语义;而没提任何摆放要求的单格 set
     * 要的是"放一个工作台",那是玩家动作:朝向随她的视线,模组钩在物品放置流程上的
     * 转换(换方块、造方块实体)照常发生,放置事件可被领地类模组取消——她放不了的
     * 地方,主人亲手也放不了。扣料也交给原版从手上的那叠扣,与
     * {@code BuildInventory.consumeOne} 同一判据(都按 {@code hasInfiniteMaterials})。
     *
     * <p>只按主手,不走 {@code Interaction} 的双手按键:那是准星语义(主手没吃掉就轮
     * 副手),在这里副手若拿着别的方块,会把错的东西放进格子。
     *
     * <p>命中点合成在格子中心:格内是可替换方块时 {@code BlockPlaceContext} 原地落位,
     * 不需要邻面,悬空格也放得出——能不能立住由原版 {@code canSurvive} 说了算。
     */
    private void placeWithItem(BuildTaskRecord.Target target, BlockPos pos) {
        ItemStack restore = null;
        if (r.consumeMaterials) {
            int slot = inv.findSlot(target.item(), true);
            if (slot < 0) {
                return;   // 付得起的闸门刚过,到这儿没了只可能是同刻竞态:这遍放下
            }
            player.holdInHand(slot);
        } else {
            // 免耗材:凭空一叠拿在手里,放完把原来的东西还回去,不动她的真背包
            restore = player.getMainHandItem();
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new ItemStack(target.item()));
        }
        InputDriver.lookAt(player, Vec3.atCenterOf(pos));
        try {
            var result = player.gameMode.useItemOn(player, player.level(),
                    player.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(
                            Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
            if (result.consumesAction()) {
                player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
        } finally {
            if (restore != null) {
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, restore);
            }
        }
    }

    /**
     * 清掉挡路的方块:生存掉落物品(她清出来的木头该归玩家),免耗材不掉。
     *
     * <p>掉落按主手物品结算,而主手此刻拿的是<b>正在砌的那个方块</b>(演出需要),
     * 不是镐。所以石头与矿石这一类清了不掉东西——"归玩家"只在不需要工具的方块上
     * 成立。要让它全成立就得在清障前临时换成镐,那会和演出打架,故此处照实记下。
     * 破坏特效走原版 levelEvent,音效与碎屑与玩家自己挖一模一样。
     */
    private void clear(BlockPos pos) {
        var level = player.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        if (r.consumeMaterials) {
            try {
                net.minecraft.world.level.block.Block.dropResources(
                        state, level, pos, level.getBlockEntity(pos), player,
                        player.getMainHandItem());
            } catch (RuntimeException e) {
                // 掉落要跑战利品表,而战利品表是数据包能改的东西——模组或整合包的一张
                // 坏表不该让整栋楼停在这一格。清障本身照做:少掉一件东西是遗憾,清不掉
                // 就永远建不下去。
                com.dwinovo.numen.core.Constants.LOG.warn(
                        "[numen-build] {} 的掉落结算失败,方块照清", state, e);
            }
        }
        level.levelEvent(2001, pos, net.minecraft.world.level.block.Block.getId(state));
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
        r.brokeOne();
    }

    /**
     * 一遍扫完的裁决。有进展就开下一遍(补漏);零进展先挪窝重试(剩下的多半
     * 正压在她自己脚下),连着几遍颗粒无收才认账——缺料是邀请,不是错误。
     */
    private TaskState endPass() {
        // 收遍要判完工,这一次必须精确——每刻那次只轮扫一片
        rescanAll();
        if (r.completed() + skippedCells >= r.targets.size()) {
            return finish();
        }
        boolean progressed = r.completed() > passStartCompleted;
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-build] 收遍 {}/{} 本遍+{} 缺料{} 零进展遍{} 断料{}",
                r.completed(), r.targets.size(), r.completed() - passStartCompleted,
                passMissing.size(), barrenPasses, passStarved);
        // 断料:不重试、不挪窝。判据的分野是"这个恢复动作能不能改变卡住的原因"——
        // 挪窝能把她的身体从格子里挪开,却改变不了背包里的任何东西。为一个改不了的
        // 原因重试三遍,每遍还带 60 刻的挪窝和每层 18 刻的停顿,就是纯粹在耗玩家的
        // 时间;而回执本来就写着"补料后重发同一调用",重发很便宜。
        //
        // 注意这里不看 progressed:本遍砌了二十格然后断料,和一格没砌就断料,对玩家
        // 是同一件事——她现在动不了了,而且再等下去也不会变。
        if (passStarved) {
            fail("built " + r.completed() + "/" + r.targets.size()
                    + " and ran out — " + ledger.missingReason(passMissing), FailureType.NO_MATERIAL);
            return TaskState.FAILED;
        }
        if (progressed) {
            barrenPasses = 0;
        } else if (++barrenPasses >= MAX_BARREN_PASSES) {
            if (!passMissing.isEmpty()) {
                // 有格子缺料、但不是断料(别的格还付得起,只是这一遍恰好没推进)。
                // 先报干了多少,再报还差什么——玩家要的是"还要凑多少才能收工",
                // 不是一句材料不足。已经砌好的部分留在世界里,不回滚。
                fail("built " + r.completed() + "/" + r.targets.size()
                        + " and ran out — " + ledger.missingReason(passMissing), FailureType.NO_MATERIAL);
                return TaskState.FAILED;
            }
            // 挪了窝也补不上:留案再交代。盖不完就是盖不完,不粉饰成成功。
            dumpOutstanding();
            fail(diagnoseOutstanding() + "; built " + r.completed() + "/" + r.targets.size(),
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        // 零进展而且不是断料:那多半是她自己站在剩下的格子里。这时候挪窝是对症的
        // ——身体挪开,格子就能放——所以给它三遍和走完一程的时间。
        if (!progressed) {
            wanderTarget = null;
            wanderTicks = WANDER_INTERVAL_TICKS;
            workPause = WANDER_WALK_TICKS + 20;
        }
        rebuildOrder();
        passStartCompleted = r.completed();
        passMissing.clear();
        passStarved = false;   // 下一遍重新判:期间玩家可能补过料
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
            if (target.matches(rules.peek(target.pos()))) continue;
            // 主动不去动的格不算"缺格":它们不在分母里,更不该在留案里冒充病灶
            if (skippedPos.contains(target.pos().asLong())) continue;
            byLayer.merge(target.pos().getY(), 1, Integer::sum);
            if (examples.size() < 12) {
                examples.add(target);
            }
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-build] 收不了尾 {}/{} feet={} 缺格分层={}",
                r.completed(), r.targets.size(), player.blockPosition().toShortString(), byLayer);
        for (BuildTaskRecord.Target target : examples) {
            BlockPos pos = target.pos();
            BlockState desired = target.desiredState();
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-build] 缺 {} 期望={} 实际={} 立得住={} 占身={} 有料={}",
                    pos.toShortString(), desired, rules.peek(pos),
                    desired.canSurvive(player.level(), pos), rules.blockedByEntity(pos, desired),
                    !r.consumeMaterials || inv.hasItem(target.item(), true));
        }
    }

    /**
     * 收不了尾时的病因分类。
     *
     * <p>剩下的格子放不下去只有四种可能,而玩家的应对完全不同:让占着的人挪开、
     * 让拆的人住手、去补材料、或者认下图纸里原版不允许的那几格。所以必须分开报,
     * 不能笼统一句 "could not be placed" —— 判据本来就算出来了,裁决做了却不交代
     * 理由,和没裁决一样难用。
     */
    private String diagnoseOutstanding() {
        int occupied = 0;
        int unsupported = 0;
        int broke = 0;
        BlockPos sample = null;
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos pos = target.pos();
            if (target.matches(rules.peek(pos))) {
                continue;
            }
            // 主动不去动的格不参与病因分类。不排掉的话,玩家箱子压着的那七格
            // blockedByEntity 为假、canSurvive 为真,会一路落进"她站不住"那一档——
            // 而它们从头到尾没被建过。这正是上一轮要修掉的那个误归因换了张报文。
            if (skippedPos.contains(pos.asLong())) {
                continue;
            }
            if (sample == null) {
                sample = pos;
            }
            BlockState desired = target.desiredState();
            if (rules.blockedByEntity(pos, desired)) {
                occupied++;
            } else if (!desired.canSurvive(player.level(), pos)) {
                unsupported++;
            } else {
                broke++;
            }
        }
        // 分母里已经不含跳过的格,这里也不能含——否则数值虚高,玩家去找一批不存在的坑
        int missing = r.targets.size() - r.completed() - skippedCells;
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

    /** 收工:撤掉自己垫的脚手架、生成摆设、外围补水,放一把庆祝的粒子。 */
    private TaskState finish() {
        for (BlockPos pos : scaffold) {
            if (targetByPos.containsKey(pos.asLong())) continue;
            BlockState state = player.level().getBlockState(pos);
            if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
                player.level().destroyBlock(pos, r.consumeMaterials);
            }
        }
        scaffold.clear();
        fixtures.spawnAll();
        fixtures.nudgeSurroundingWater(siteMin, siteMax);
        show.celebrate(siteMin, siteMax);
        InputDriver.halt(player);
        if (r.completed() + skippedCells >= r.targets.size()) {
            // 三种交代要并列,不能互相吃掉:此前 skippedFixtures 一非零就只报摆设,
            // 那句"有几格没动"被整段吞掉——两件事同时发生时回执只说一半。
            List<String> notes = new ArrayList<>();
            if (skippedCells > 0) {
                // 不说"全对上了"——有格子我们主动没动,得说清有几格、为什么
                notes.add("left " + skippedCells + " cell(s) alone: something with contents was "
                        + "already there, or the spot cannot be built on");
            }
            if (r.droppedAtLoad() > 0) {
                notes.add(r.droppedAtLoad() + " cell(s) of the blueprint were dropped on load"
                        + " (liquids, or blocks with no item to pay with)");
            }
            if (fixtures.skippedFixtures() > 0) {
                notes.add("short " + fixtures.skippedFixtures() + " fixture(s) (item frames / armour stands"
                        + " / paintings)");
            }
            if (fixtures.skippedPayloads() > 0) {
                notes.add(fixtures.skippedPayloads() + " item(s) the blueprint had in its frames / on its"
                        + " armour stands were left out — those need the exact same item"
                        + " (enchantments and all), so they went up empty");
            }
            note = notes.isEmpty() ? "all requested cells match" : String.join("; ", notes);
        }
        return TaskState.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 三、在工地里动起来
    // ------------------------------------------------------------------

    /**
     * 挪窝。走位交给寻路——巡视点之间只有几格,起一次 A* 的代价很低,而避障是
     * 它天生就会的事;直线步进遇到墙、树、坎就一路顶着走满时限。
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
        if (nav == null) {
            Vec3 dest = wanderTarget;
            nav = PlayerNav.to(player,
                    () -> new GoalCompiler.Compiled(
                            NavGoal.nearGround(BlockPos.containing(dest), 1.5),
                            protectedCells()),
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
     * <p>这是"看起来在干活"与"看起来在遛弯"的分界。施工顺序是低层优先、层内蛇形,
     * 接下来几十格在空间上本就连成一片;把落脚点绑到那一片,她就会自然地沿着正在
     * 砌的那面墙挪——同样是绕外圈,但因果对上了。
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
            BlockPos feet = com.dwinovo.numen.core.pathing.execute.PathExecutor.playerFeet(player);
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
     * <p><b>贴附件整体推到第二趟</b>,排在所有层之后(见 {@link BuildOrder})。
     */
    private void rebuildOrder() {
        List<BuildTaskRecord.Target> pending = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (!target.matches(rules.peek(target.pos()))
                    && !rules.blockedByMode(target) && !rules.hopeless(target)) {
                pending.add(target);
            }
        }
        pending.sort(BuildOrder.BUILD_ORDER);
        order = pending;
        resetLayerWindow();
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
        layerStartPlaced = r.placed();   // 新一层的起点,演出停顿按它判"这层干活了没"
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

    /** 按格数和目标时长定这一趟的速率(开工时算一次)。 */
    private void computePace() {
        int cells = Math.max(1, r.targets.size());
        cellsPerTick = BuildOrder.paceFor(cells, r.consumeMaterials);
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-build] 节奏 {} 格,{} 格/秒,预计 {} 秒",
                cells, String.format("%.1f", cellsPerTick * 20),
                (int) (cells / cellsPerTick / 20));
    }

    private void drainScaffold() {
        for (BlockPos placed : BuildPlacementRegistry.drainScaffold(player)) {
            if (!targetByPos.containsKey(placed.asLong())) {
                scaffold.add(placed);
            }
        }
    }

    // ------------------------------------------------------------------
    // 对账
    // ------------------------------------------------------------------

    private void markObserved(BuildTaskRecord.Target target, boolean completed) {
        if (observedCompleted == null) {
            observedCompleted = new LongOpenHashSet();
        }
        long key = target.pos().asLong();
        if (completed) {
            observedCompleted.add(key);
            // 两个集合互斥:自己刚放好的格不可能同时是"不去动"的格
            skippedPos.remove(key);
        } else {
            observedCompleted.remove(key);
        }
        skippedCells = skippedPos.size();
        r.completed(observedCompleted.size());
    }

    /**
     * 每刻重扫多少格。全量重扫一张满额图纸(32768 格)每刻要三五万次方块查询、
     * 十万次属性查找,峰值吃掉整刻预算的一半,而它盯的那个量每刻最多变
     * {@code MAX_CELLS_PER_TICK} 格——为看清 8 格的变化去重算三万格,这笔账不划算。
     *
     * <p>所以每刻只轮扫一片:自己动过的格由 {@link #markObserved} 即时更新,轮扫
     * 只负责发现<b>外力</b>改动(玩家拆墙、苦力怕炸)。满额图纸一轮 64 刻扫完,
     * 三秒内必然发现——比"墙正在被拆"这件事本身的时间尺度快得多。
     */
    private static final int RESCAN_PER_TICK = 512;

    /** 轮扫游标。 */
    private int rescanCursor;

    /** 每刻的轮扫:只看一片,外力改动最迟一轮之后被发现。 */
    private void updateCompleted() {
        rescan(RESCAN_PER_TICK);
    }

    /**
     * 全量重扫。开工与收遍各一次——收遍要判完工,那一次必须是精确的。
     */
    private void rescanAll() {
        rescanCursor = 0;
        rescan(r.targets.size());
    }

    /**
     * 重扫一片目标格,把结果并进两个集合。
     *
     * <p>完成数取 {@code observedCompleted.size()} 而不是本次数出来的个数:两个集合
     * 才是真源,而轮扫只碰其中一片。集合互斥(进一个必出另一个),所以两个 size 相加
     * 就是"已了结的格数",判完工用得着的正是它。
     */
    private void rescan(int budget) {
        if (observedCompleted == null) {
            observedCompleted = new LongOpenHashSet();
        }
        int total = r.targets.size();
        int n = Math.min(budget, total);
        BlockGetter view = LoadedOnlyView.of(player.level());
        LoadedOnlyView loadedView = view instanceof LoadedOnlyView v ? v : null;
        for (int k = 0; k < n; k++) {
            if (rescanCursor >= total) {
                rescanCursor = 0;
            }
            BuildTaskRecord.Target target = r.targets.get(rescanCursor++);
            BlockPos pos = target.pos();
            long key = pos.asLong();
            // 未加载的格保持原判:完成集与跳过集都有记忆,不能因为看不见就翻案
            if (loadedView != null && !loadedView.isLoaded(pos.getX(), pos.getZ())) {
                continue;
            }
            BlockState observed = view.getBlockState(pos);
            if (target.matches(observed)
                    || target.desiredState().getBlock() instanceof LiquidBlock
                    || (BuildCellRules.isAirTarget(target) && observed.getBlock() instanceof LiquidBlock)) {
                // 液体口径:不放液体目标、清空型目标也不排水——这两类跳过豁免;
                // 固体目标被液体淹着不豁免,照放,方块直接顶掉水(原版语义)
                observedCompleted.add(key);
                skippedPos.remove(key);
            } else if (rules.blockedByMode(target) || rules.hopeless(target)) {
                // 注意:这一支要在 damagedCells 之前。玩家把挡路的箱子搬走时,
                // 这一格会从"不去动"变成"待办",若走下面那支就会被记成
                // "已砌好又被拆了"——而它从头到尾没被建过。
                // 这一格我们不会去动:让路的档位不许、玩家的箱子压在那儿、
                // 基岩挡着、或者在世界边界之外。它<b>不算建好了</b>——从分母里
                // 去掉,单独记一笔。此前是塞进分子冒充完成,于是一栋盖在既有
                // 村民房上的图纸能报出"built 812/812(all requested cells
                // match)",而床、箱子、营火那七格根本没动过。分母法不会说谎,
                // 分子法一定说谎。
                skippedPos.add(key);
                observedCompleted.remove(key);
            } else {
                skippedPos.remove(key);
                if (observedCompleted.remove(key)) {
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
            }
        }
        skippedCells = skippedPos.size();
        r.completed(observedCompleted.size());
    }

    private boolean isReplaceable(BlockPos pos, BlockState state) {
        return MovementHelper.isReplaceable(pos.getX(), pos.getY(), pos.getZ(), state,
                com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest.ALWAYS);
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
            // setChanged 只把区块标脏,不发同步包;而 PLACE_FLAGS 那一包在装数据
            // <b>之前</b>就已经发出去了,里面还没有方块实体的载荷。不补这一下,
            // 告示牌的字、旗帜的花纹在客户端是空白的,要等区块重载才出现——正是
            // 这段代码本来要解决的那个症状。
            player.level().sendBlockUpdated(pos, placed, placed,
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        } catch (RuntimeException ignored) {
            // 数据坏了只影响这一格的内容,方块本身已经放好了,不该让整栋楼停工
        }
    }

    // ------------------------------------------------------------------
    // 寻路桥接
    // ------------------------------------------------------------------

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

    @Override
    public BlockState desiredState(BlockPos placeAt) {
        BuildTaskRecord.Target target = targetByPos.get(placeAt.asLong());
        if (target == null || BuildCellRules.isAirTarget(target)) {
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
        return ContextFactory.forSearch(player, union(sacred), union(deniedPlace), permit(),
                (p, view, loaded, safe, s, denied, permit) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, permit, targetByPos,
                        inv.availableStates(true), r.replaceExisting));
    }

    @Override
    public CalculationContext forExecution(NumenPlayer player, LongSet sacred, LongSet deniedPlace) {
        return ContextFactory.forExecution(player, union(sacred), union(deniedPlace), permit(),
                (p, view, loaded, safe, s, denied, permit) -> new BuildCalculationContext(
                        p, view, loaded, safe, s, denied, permit, targetByPos,
                        inv.availableStates(true), r.replaceExisting));
    }

    /** 施工就是改地形:挖错块、搭脚手架、被自己封顶时拆一块出去,都是这个任务的本分。 */
    @Override
    public com.dwinovo.numen.core.pathing.moves.TerrainPermit permit() {
        return com.dwinovo.numen.core.pathing.moves.TerrainPermit.TERRAFORM;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        super.stop(companion, why);
        // 让位时摘掉放置提供者;下一次 onTick 每刻都会重新登记(registerProvider 幂等),
        // 所以不需要一个"恢复"钩子——原来那个 resume() 全代码库零调用者,是死代码。
        unregisterProvider();
        InputDriver.halt(player);
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
            Map<Item, Integer> shortfall = ledger.shortfallAgainstInventory(ledger.remainingNeed());
            if (!shortfall.isEmpty()) {
                data.put("still_short", BuildLedger.summarizeShortfall(shortfall));
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
}
