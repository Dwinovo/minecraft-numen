package com.dwinovo.numen.core.task.dig;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.nav.DigQuote;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.core.scan.NearbyEntities;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.DropTracker;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
 * 挖掉方块的执行,只此一份:{@code work dig} 与建造清场都交到这里。每一格都经她的手({@link BlockDigger} → 原版挖掘循环外套
 * 权限层),用工具、有掉落、进实际账;创造模式下原版一下就碎,生存模式按工具与方块算时间——模式由原版的手自己分。
 *
 * <h2>挖哪些格</h2>
 * 点名的区域在派发时解析成格子({@link DigTaskRecord#cells}):扫描来的格只挖还是当时那种方块的,框出来的格与点里面有什么挖什么
 * ({@link DigTaskRecord#wants})。格子途中被别人挖掉或变了,照常挖剩下的,回执如实交代。
 *
 * <h2>只在跟前干</h2>
 * 候选只取工作区({@link DigTaskRecord#work})里的,走动关在区里({@link WorkArea#confine}):挪几步、走进刚挖开的洞、捡弹开的
 * 掉落物;开不出远路是结构上做不到。区外的只报告——还有几格、最近一格在哪、照抄就能开路的写法({@link Beyond})——不去。
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOres</b> — fed on demand from the cells in the work area, nearest first, and {@link #prune}d every
 *       tick (drop ones dug / no longer wanted / unworkable / hazardous), sorted by distance, capped at
 *       {@link #MAX_TARGETS}.</li>
 *   <li><b>in place</b> — a target the body can reach from where it stands ({@link Goals#dig}: within block reach,
 *       not occupying it) is broken on the spot, cheapest first. The digger takes the best tool and clears what
 *       stands in the line of sight first, if it may ({@link #mayClear}).</li>
 *   <li><b>one goal over the field</b> — otherwise one search over {@link Goals#anyOf} of the same dig goals
 *       ({@link #field}) inside the work area, so it walks to the closest reachable target. Arrival and the in-place
 *       pick are one criterion.</li>
 *   <li><b>够不着是一批的属性,不是某一格的罪</b> — 复合目标在区里搜不出路,意思是<b>这一刻这一批都到不了</b>,不记账到任何一格:
 *       收工,寻路给的原因原样带上,下一步是开路的写法({@link #unreachable})。什么都没挖、没捡超过 {@link #STALL_TICKS}
 *       刻,同样收工。</li>
 * </ol>
 *
 * <h2>主人的东西</h2>
 * 选目标不看权限:主人放的原木和野树一样是候选,需要主人同意的格按同意倍率定价、排在后面({@link #targetCost})。轮到一格,动手
 * 之前把这次挖掘交给权限层({@link #permit}):要问就站着等主人点头(逐格问,不先整条规划);不许就带着理由收场。走动的规格
 * 不许动要问的格({@link DigTaskRecord#SPEC}),挡在路上的这种格她不碰。
 */
public final class DigCompanionTask extends AbstractCompanionTask<DigTaskRecord> {

    private static final int MAX_TARGETS = 64;          // cap on tracked target cells
    /** 名单低于此数就从区里还没收进名单的格补货。 */
    private static final int QUERY_LOW_WATER = 16;
    /** 两次补货的最小间隔(tick)。 */
    private static final int QUERY_MIN_GAP_TICKS = 20;
    /** 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable} —— 站位说够得着,
     *  可射线始终成不了(挡在中间的挖不得、瞄准量化)。没有这条,挖掘会永远等一个
     *  不会来的射线。 */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /**
     * 这么多刻(二十秒)里既没挥一下、没挖掉一格,也没捡到东西,就算真卡住了:导航报到了、手边却没有该挖的,一遍遍重新
     * 规划却回到原地,这种循环寻路自己看不见,只有这把尺子量得出来。区里的一趟路走不了这么久。
     */
    private static final int STALL_TICKS = 400;

    private final List<BlockPos> knownOres = new ArrayList<>();
    /**
     * 当前地形下挖不动的格子 —— <b>只有 {@code NO_SHOT} 进得来</b>:站位说够得着,却连续二十刻拉不出射线。这是关于<b>这一格</b>
     * 的、可复现的事实。她挖掉任何一格,地形就变了,整份作废重来。
     */
    private final Set<BlockPos> unworkable = new HashSet<>();
    /** 手里的工具收不到掉落的格:收场时点名是工具的事,而不是说"什么都没有"。 */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /**
     * 每个候选"到了之后挖它"的价钱(刻),{@link #prune} 每刻按成本模型现算:与寻路给路上一格定价同一份,需要主人同意的乘同意
     * 倍率;许可不许的是无穷,不进目标。
     */
    private final Map<BlockPos, Double> digCosts = new HashMap<>();
    /** 许可不许挖的候选说的理由(最近一格的);没有为 null。一格都挖不得时拿它收场。 */
    private String deniedWhy;
    /**
     * 按总价挑目标的导航在这一格落定了(搜索挑中的就是脚下):手边够得着、价钱不超过 {@link #settledPrice} 的就是该挖的,
     * 不再因为别处估价更低而让路。挖掉一格或挪了窝就作废。
     */
    private BlockPos settledAt;
    /** 落定那一刻停在这一格的到达价:停下来是为了捡脚边的掉落物(到达价 0),就不能拿它当挖一格贵东西的理由。 */
    private double settledPrice;
    /** 要挖的方块掉的东西(按服务端战利品表,用她身上挖得最快的那件模拟)。数件数数的是它们。 */
    private Set<Item> dropItems = Set.of();
    /** 开工时背包里已有的件数:count 数的是在它之上新到手的("再弄 N 个")。 */
    private int baseline;
    /** 地上要去捡的掉落物,每刻重找。 */
    private List<BlockPos> drops = List.of();
    /**
     * 她自己敲出来、还没进包的那几件掉落物(实体 id)。认 id 不数附近的:主人扔在旁边的、开工前就躺着的,物品类型全对得上,
     * 算进来会让她少挖。原版拾取是同一刻里先进背包、再移除实体,所以两头不重叠;实体没了自动出账,走不到的也出账
     * ({@link #unreachableDrops})。
     */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet ourDrops = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
    /**
     * 走不到的掉落物(实体 id):够数之后导航只奔地上的掉落物,这一批搜不出路、或她守着它们卡住了,就说明它们一件也进不了包。
     * 它们出账、不再当目标,够没够数只按还拿得到的算,她接着挖别的补上。
     */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet unreachableDrops =
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /** 已经记过账的格:每一格只在挖开它之后认一次。 */
    private final Set<BlockPos> claimedCells = new HashSet<>();

    /** 这一刻"够了,别再敲新的了"——到手加上还没进包的已经够数。见 {@link #inFlight()}。 */
    private boolean quotaMet;
    /** 上一刻数到的进度,有变化就是进展。 */
    private int lastGathered;

    /** 区里要挖、还没收进名单的格。挖不动的格也回到这里,地形一变还能再收。 */
    private final Set<BlockPos> remaining = new HashSet<>();
    /** 挖掉了的要挖的格(她的手挖的、导航顺路挖的、为拉射线挖掉的),按挖掉的先后。 */
    private final Set<BlockPos> dug = new LinkedHashSet<>();
    /** 轮到时已经不要挖了、而旅程账上也没有她挖过的格(别人挖掉、换掉的)。 */
    private final Set<BlockPos> gone = new HashSet<>();
    /** 挖不成的候选:挖不动的方块、贴着流体或悬空落沙的——{@link #breakable} 说不的。 */
    private final Set<BlockPos> ruledOut = new HashSet<>();
    /** 走动的规格:记录上的那份({@link DigTaskRecord#spec}),关在工作区里。 */
    private final RouteSpec spec;
    /** 给目标本身定价的规格:同一份,改地形一档放到 {@link RouteSpec.Alter#ANY}——目标是这件活自己要挖的,要问的照价乘倍。 */
    private final RouteSpec targetSpec;
    /** 给目标定价、判挖不挖得成的成本模型,每刻按此刻的身体与权限重组。 */
    private DigQuote pricing;
    /** 在走的那一趟朝着的目标,以及它是按哪一份名单与价钱编的——名单或价钱变了才把新目标交给那一趟。 */
    private Goal field;
    private FieldKey fieldKey;

    /** 编目标用的名单:每个有价的候选与它的价钱、地上要捡的掉落物。 */
    private record FieldKey(Map<BlockPos, Double> ores, List<BlockPos> drops) {}

    /** 距下一次补货的冷却(tick)。 */
    private int queryCooldown;
    private String progressNote = "done";
    /** The target currently returning {@code NO_SHOT}, and for how many consecutive ticks. */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** 上一次真有进展时的 {@link #workTicks()} —— 卡死判定的量尺。 */
    private long lastProgressWork;

    private final BlockDigger digger;
    /** 正在挖的目标;挖掘器这一刻可能在挖挡在它前面的那一格,锁的是目标。没有为 null。 */
    private BlockPos digTarget;

    public DigCompanionTask(NumenPlayer player, DigTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
        this.spec = record.work.confine(record.spec);
        this.targetSpec = spec.edit().alter(RouteSpec.Alter.ANY).build();
    }

    /** 挖掉了的要挖的格,按挖掉的先后:派它的一方(建造清场)按这份记账。 */
    public Set<BlockPos> dug() {
        return Set.copyOf(dug);
    }

    @Override
    protected List<Precondition> preconditions() {
        // 一种都收不到掉落就当场失败:挖了只会把方块毁掉。个别收不到的格由 prune 剔掉,所以混着的(煤挖得了、钻石挖不了)照挖
        return List.of(() -> {
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
            boolean anyHarvestable = r.targets.stream().anyMatch(
                    b -> canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                return new Precondition.Failure(
                        "can't harvest " + r.label + " with the current tools — digging it would destroy it without"
                                + " any drop. Equip a suitable tool (e.g. a pickaxe) first; to break a block regardless"
                                + " of drops, move_goto its coordinates with arrive:use and run use block left on it"
                                + " with whatever is in hand.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    @Override
    protected void onStart() {
        // 件数按到手的物品数,不按挖掉的格:先算这些方块掉什么,再记下已有多少,进度是在它之上的增量
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        Level level = player.level();
        r.cells.intersect(r.work.cells()).forEach((x, y, z, seen) -> {
            BlockPos p = new BlockPos(x, y, z);
            if (DigTaskRecord.wants(seen, level.getBlockState(p))) {
                remaining.add(p);
            }
        });
        lastProgressWork = workTicks();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] dig start targets={} count={} what={} cells={} feet={} work={}",
                r.label, r.count, r.what, remaining.size(), player.blockPosition().toShortString(),
                r.work.describe());
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:有掉落 = 数拾取到的物品;无掉落(创造)或挖完为止 = 数挖掉的格
        boolean untilGone = r.count == DigTaskRecord.UNTIL_GONE;
        // 导航顺路挖开的格也是她挖的,先入账再算够没够
        sweepNavBreaks();
        int gathered = WorkProfile.of(player).dropsLoot() && !untilGone
                ? Math.max(0, inventoryMatch() - baseline)
                : dug.size();
        if (gathered != lastGathered) {
            lastGathered = gathered;
            noteProgress();
        }
        r.setMined(gathered);
        // 完工只认到手的:回执里那句 "gathered 12/12" 得是真的
        if (!untilGone && gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }
        // 还敲不敲下一块:背包是滞后指标(掉落物有 10 tick 拾取延迟),算上已经敲掉、还躺在地上的,够了就只去捡
        quotaMet = !untilGone && gathered + inFlight() >= r.count;

        Level level = player.level();
        pricing = DigQuote.of(player, targetSpec);
        prune();
        maybeQuery();

        // 0) 接着挖正在挖的那一格,锁住它直到碎掉或她站的地方够不着了
        if (digTarget != null) {
            if (quotaMet) {
                // 够数了,手上这块也不敲完:敲完就是多一块
                digger.cancel();
                digTarget = null;
            } else if (!r.wantsAt(digTarget, level.getBlockState(digTarget)) || !canWork(digTarget)) {
                digger.cancel();
                digTarget = null;
            } else {
                return digProgress(digTarget);
            }
        }

        drops = droppedItems();

        // 1) 站着就够得着的目标就地挖——只在站定时:在走的那一趟走到它自己的终点,那里才是搜索挑的站位
        BlockPos reachable = nav == null ? reachableTarget() : null;
        if (reachable != null) {
            // 动手之前:这一格交给权限层。要问就站着等主人,不许就带着理由收场
            Permit permit = permit(Action.breakBlock(reachable, level.getBlockState(reachable)));
            if (permit.state() == PermitState.WAITING) {
                player.controls().stop();
                return TaskState.RUNNING;
            }
            if (permit.state() == PermitState.REFUSED) {
                fail("could not dig " + r.label + ": " + permit.refusal() + "; " + soFar(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
            return digProgress(reachable);
        }

        // 2) 在区里奔向整片目标与地上的掉落物(一个复合目标),走到够得着的地方;掉落物走过去就捡起来
        if ((!quotaMet && !knownOres.isEmpty()) || !drops.isEmpty()) {
            TaskState stalled = stalledOut();
            if (stalled != null) {
                return stalled;
            }
            boolean moved = refreshField();
            if (field == null) {
                // 名单上的每一格许可都不许挖,地上也没有要捡的:权限层的拒绝就是这件活的结果
                fail("could not dig " + r.label + ": " + deniedWhy + "; " + soFar(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
            if (nav == null) {
                nav = Trip.to(player, field, spec, towardField());
            } else if (moved) {
                // 名单每几刻就变:新目标交给在走的这一趟,停点还算数就照走,不算数才重搜
                nav.retarget(field, towardField());
            }
            Trip.Status status = nav.tick();
            switch (status) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    // 搜索按总价挑中的就是这儿:手边够得着的就挖,别再为别处的估价让路
                    Feet here = Feet.of(player);
                    settledAt = here == null ? null : here.node();
                    settledPrice = here == null ? 0 : field.arrival(player.level(), settledAt.getX(), settledAt.getY(),
                            settledAt.getZ(), here.stance());
                    stopNav();
                    // 到了却没有可挖的,不构成关于任何一格的证据(到的是掉落物成员,或这一刻人在空中):只重新规划,
                    // 真卡住了由 STALL_TICKS 那把尺子收工
                    return TaskState.RUNNING;
                }
                case FAILED -> {
                    // 这一批在区里都到不了:目标撒在区里的全部候选上,搜不出路说的是全体,不拿最近那颗顶罪
                    com.dwinovo.numen.core.Constants.LOG.info(
                            "[numen-task] dig nav failed ({}): {} | 目标 {} 个,nearest={}",
                            nav.failType(), nav.failReason(), knownOres.size(), nearestOreInfo());
                    return unreachable(nav.failReason(), nav.failType());
                }
            }
        }

        // 3) 区里没有要挖的、地上也没有要捡的:挖到过就算成功;区外的只报告,去不去是模型的决定
        if (r.getMined() > 0) {
            progressNote = "nothing left to dig in my work area, " + r.work.describe() + leftovers(null)
                    + beyondClause();
            return TaskState.SUCCESS;
        }
        return nothingDug();
    }

    // ---- goals ----

    /**
     * 把此刻的名单编成目标({@link #field}):每个有价的候选"站到够得着它的地方、再付挖它的价钱",加上地上每件掉落物"站到它
     * 那一格"——一次搜索奔向其中最便宜的那个。名单与价钱没变就不重编。
     *
     * @return 目标换了
     */
    private boolean refreshField() {
        Map<BlockPos, Double> ores = new LinkedHashMap<>();
        if (!quotaMet) {
            for (BlockPos ore : knownOres) {
                double cost = digCost(ore);
                if (Double.isFinite(cost)) {
                    ores.put(ore, cost);
                }
            }
        }
        FieldKey key = new FieldKey(ores, List.copyOf(drops));
        if (key.equals(fieldKey)) {
            return false;
        }
        fieldKey = key;
        field = field(key);
        return true;
    }

    /** 名单编成的目标;一个成员都没有为 null。 */
    private Goal field(FieldKey key) {
        var stats = Snapshots.stats(player);
        List<Goal> members = new ArrayList<>(key.ores().size() + key.drops().size());
        key.ores().forEach((ore, cost) -> members.add(Goals.priced(Goals.dig(ore, stats), cost)));
        for (BlockPos drop : key.drops()) {
            members.add(dropGoal(drop));     // items, not blocks
        }
        return members.isEmpty() ? null : Goals.anyOf(members);
    }

    /**
     * 去捡一件掉落物的目标:躺在区里的站进它那一格(原版拾取一定够得着);弹到区外的她进不去,站在区边上挨着它
     * ({@link DropTracker#pickUp}:原版拾取框横向外扩一格)。
     */
    private Goal dropGoal(BlockPos drop) {
        return r.work.contains(player.level().dimension(), drop) ? Goals.at(drop) : DropTracker.pickUp(drop);
    }

    /** 给人说"朝哪儿"的那一格:最近的候选,没有就是最近的掉落物。 */
    private BlockPos towardField() {
        BlockPos ore = nearestOre();
        if (ore != null) {
            return ore;
        }
        BlockPos feet = player.blockPosition();
        return drops.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(feet);
    }

    /** 到了之后挖它的价钱;这一刻没算过的按不许挖的价。 */
    private double digCost(BlockPos ore) {
        return digCosts.getOrDefault(ore, Double.POSITIVE_INFINITY);
    }

    /** 挑目标用的总价:走到够得着它的地方(目标的估价,与交给搜索的同一把尺)加上挖它的价钱。 */
    private double targetCost(BlockPos ore, BlockPos feet) {
        return Goals.dig(ore, Snapshots.stats(player)).estimate(feet.getX(), feet.getY(), feet.getZ()) + digCost(ore);
    }

    /** 身体此刻站着的地方够不够得着 {@code ore}——原地就挖与导航到位是这同一个判据。 */
    private boolean canWork(BlockPos ore) {
        Feet here = Feet.of(player);
        return here != null && here.in(Goals.dig(ore, Snapshots.stats(player)));
    }

    /**
     * 这一格挖不挖得成:按给目标定价的成本模型问——工作区外的格、物理上挖不了的(挖不动、贴着流体、顶着落沙、世界边界外)。
     * 许可不许的也算挖得成:那是动手时权限层的事,价钱是无穷。
     */
    private boolean breakable(BlockPos pos, BlockState state) {
        return pricing.breakable(pos, state);
    }

    /**
     * 挡在视线上的那一格可不可以先挖开:和目标过同一道({@link #breakable}),工作区外的不碰——她的手只在区里动。许不许挖由
     * 挖掘器问权限层。
     */
    private boolean mayClear(BlockPos occluder) {
        return breakable(occluder, player.level().getBlockState(occluder));
    }

    /** 身上有没有能让它掉东西的工具(整个背包,不只快捷栏:她能从包里拿工具挖)。不要求工具的方块总是有。 */
    private static boolean canHarvest(Container inv, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 要去捡的掉落物,走过去原版就捡起来:只捡要挖的方块会掉的东西。贴着某个已知目标的不单独设目标——挖那一格自然会带身体
     * 过去;够数之后不再挖,那时每一件都得自己走过去捡。
     */
    private List<BlockPos> droppedItems() {
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : nearbyDrops()) {
            BlockPos p = ie.blockPosition();
            if (!quotaMet && nearKnownOre(p)) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * 这件活要捡的掉落物:要挖的方块会掉的物品,不在 {@link #unreachableDrops} 里;躺在工作区里的,加上她自己敲出来的
     * ({@link #ourDrops})——挖区边上那一格时掉落物可能弹出区外一两格,站在区边上还捡得着,那也是这一单的收获。
     */
    private List<ItemEntity> nearbyDrops() {
        Level level = player.level();
        List<ItemEntity> out = new ArrayList<>(NearbyEntities.in(level, r.work.area(), ItemEntity.class,
                this::wanted));
        if (level instanceof ServerLevel sl) {
            for (int id : ourDrops) {
                if (sl.getEntity(id) instanceof ItemEntity ie && !ie.isRemoved() && wanted(ie)
                        && !r.work.contains(level.dimension(), ie.blockPosition())) {
                    out.add(ie);
                }
            }
        }
        return out;
    }

    /** 这件掉落物是这件活要的:要挖的方块会掉的物品,而且没被记成走不到。 */
    private boolean wanted(ItemEntity ie) {
        return dropItems.contains(ie.getItem().getItem()) && !unreachableDrops.contains(ie.getId());
    }

    /** 距任一已知目标 3 格内(distSqr ≤ 9)——挖那一格自然会带身体过去。 */
    private boolean nearKnownOre(BlockPos p) {
        return knownOres.stream().anyMatch(ore -> ore.distSqr(p) <= 9);
    }

    /**
     * 就地挖的挑法:站着就够得着的已知目标里最便宜的那个({@link #canWork}),一样贵挑近的。它与导航判"到了"是同一个判据,
     * 所以到了总有东西可挖,挡着视线的归挖掘器清。
     *
     * <p>够得着的也可能不是该挖的:别处按乐观估价更便宜({@link #targetCost}:走过去 + 挖它)就先让导航按总价去挑;导航挑完仍停在
     * 这儿({@link #settledAt})而且认下的价钱够挖它({@link #settledPrice}),别处的便宜只是估价上的,那就挖手边的。
     *
     * <p>够数了({@link #quotaMet})手边也没有该挖的:这一条必须写在这里——导航的"到了"问的就是这个函数,门槛若只挡在原地
     * 就挖那一侧,导航会拿手边那一格当"到了"、任务却不挖,两边来回推。
     */
    private BlockPos reachableTarget() {
        if (quotaMet || !player.onGround()) return null;
        Feet here = Feet.of(player);
        if (here == null) return null;
        Level level = player.level();
        BlockPos feet = here.node();
        var stats = Snapshots.stats(player);
        BlockPos best = null;
        double bestCost = Double.MAX_VALUE;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (!r.wantsAt(ore, level.getBlockState(ore)) || !here.in(Goals.dig(ore, stats))) {
                continue;
            }
            double cost = digCost(ore);
            double d = ore.distSqr(feet.above());
            if (cost > bestCost || (cost == bestCost && d >= bestD)) {
                continue;
            }
            bestCost = cost;
            bestD = d;
            best = ore;
        }
        if (best == null || (feet.equals(settledAt) && bestCost <= settledPrice)) {
            return best;
        }
        for (BlockPos ore : knownOres) {
            if (!ore.equals(best) && targetCost(ore, feet) < bestCost) {
                return null;
            }
        }
        // 地上等着捡的也按同一把尺:捡起来只花走过去的路程
        for (BlockPos drop : drops) {
            if (dropGoal(drop).estimate(feet.getX(), feet.getY(), feet.getZ()) < bestCost) {
                return null;
            }
        }
        return best;
    }

    // ---- digging (progressive, tick-by-tick like a real player) ----

    /**
     * 挖一刻(挖掘器自己把挖它最快的那件拿到手上);目标碎掉的那一刻把它划出名单、记进挖掉的账。{@code BROKE_OCCLUDER}(为拉出
     * 射线挖开的那一格)不是目标,目标留着。
     *
     * <p>连续的 {@code NO_SHOT}(站位说够得着,可始终成不了射线)满 {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进
     * {@link #unworkable} 接着往下走——这是唯一一处按格记账的地方,因为它是唯一一件关于那一格的可复现事实。
     */
    private TaskState digProgress(BlockPos pos) {
        digTarget = pos.immutable();
        switch (digger.digStep(pos, this::mayClear, this::recordAction)) {
            case BROKE_TARGET -> {
                digTarget = null;
                knownOres.remove(pos);
                dug.add(pos.immutable());
                noteProgress();
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的"挖不动"结论全部作废
                unworkable.clear();
                settledAt = null;
                claimDrops(pos);
                clearNoShot();
            }
            case REFUSED -> {
                // 动手前放行之后世界变了,或挡在前面的遮挡物不许挖:权限层的拒绝就是这件活的结果
                digger.cancel();
                fail("could not dig " + r.label + ": " + digger.refusal().reason() + "; " + soFar(),
                        FailureType.REFUSED);
                return TaskState.FAILED;
            }
            case NO_SHOT -> {
                if (pos.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        unworkable.add(pos.immutable());
                        knownOres.remove(pos);
                        remaining.add(pos.immutable());   // 地形一变(挖掉任何一格)还能再收
                        digger.cancel();
                        digTarget = null;
                        clearNoShot();
                    }
                } else {
                    noShotPos = pos.immutable();
                    noShotTicks = 1;
                }
            }
            case BROKE_OCCLUDER -> {
                // 为了拉出射线挖掉的是挡在前面的那一格:进旅程账,回执交代;它若也是要挖的格就算她挖的
                recordBreak(digger.lastBroken());
                claimDrops(digger.lastBroken() == null ? null : digger.lastBroken().pos());
                noteProgress();
                clearNoShot();
            }
            // PROGRESSING — 手在挥:是进展
            default -> {
                noteProgress();
                clearNoShot();
            }
        }
        return TaskState.RUNNING;
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    // ---- item counting (progress = matching items held in the inventory) ----

    /**
     * 她挖开的这一格掉出来的东西记进 {@link #ourDrops}。每一格只认一次,就在挖开它之后那一刻;无掉落画像(创造)下这本账
     * 永远是空的,进度改数挖掉的格。
     */
    private void claimDrops(BlockPos cell) {
        if (cell == null || !WorkProfile.of(player).dropsLoot() || !claimedCells.add(cell.immutable())) {
            return;
        }
        AABB box = new AABB(cell).inflate(1.5);
        for (ItemEntity ie : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            // 走不到的那几件躺在刚挖开的格子旁边也不再认领——认领了又会算进够数
            if (dropItems.contains(ie.getItem().getItem()) && !unreachableDrops.contains(ie.getId())) {
                ourDrops.add(ie.getId());
            }
        }
    }

    /**
     * 把导航这条路上挖开的格也入账:为了走过去而挖开的格和她照着目标敲掉的一样,掉的东西都进她的包。实际账({@link Trip#reports})
     * 是"她挖了什么"的唯一出处,所以问它。停下时账会并进旅程账,所以 {@link #stopNav()} 之前再扫一次。
     */
    private void sweepNavBreaks() {
        if (nav == null) {
            return;
        }
        for (Report report : nav.reports()) {
            for (EditLedger.Entry entry : report.ledger().entries()) {
                if (entry instanceof EditLedger.Dug d) {
                    claimDrops(d.pos());
                }
            }
        }
    }

    @Override
    protected void stopNav() {
        sweepNavBreaks();
        super.stopNav();
    }

    /** 已经敲出来、还没进包的件数;顺手把没了的出账。 */
    private int inFlight() {
        int sum = 0;
        var it = ourDrops.iterator();
        while (it.hasNext()) {
            net.minecraft.world.entity.Entity e = player.level() instanceof ServerLevel sl
                    ? sl.getEntity(it.nextInt()) : null;
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()
                    || !dropItems.contains(ie.getItem().getItem())) {
                it.remove();
                continue;
            }
            sum += ie.getItem().getCount();
        }
        return sum;
    }

    /** 身上带着的、要挖的方块会掉的物品件数;盔甲、副手不算采集所得。 */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        return com.dwinovo.numen.core.PlayerInv.carriedCount(player.getInventory(),
                s -> dropItems.contains(s.getItem()));
    }

    /** 要挖的方块掉什么:按服务端战利品表、用身上挖它最快的那件模拟一次;没有战利品的算它自己的物品。 */
    private Set<Item> computeDropItems() {
        Set<Item> items = new HashSet<>();
        if (!(player.level() instanceof ServerLevel level)) {
            for (Block b : r.targets) items.add(b.asItem());
            return items;
        }
        BlockPos origin = player.blockPosition();
        for (Block b : r.targets) {
            BlockState state = b.defaultBlockState();
            List<ItemStack> drops;
            try {
                drops = Block.getDrops(state, level, origin, null, player, bestToolFor(state));
            } catch (RuntimeException broken) {
                drops = List.of();
            }
            if (drops.isEmpty()) {
                items.add(b.asItem());
            } else {
                for (ItemStack d : drops) items.add(d.getItem());
            }
        }
        return items;
    }

    /** 身上挖 {@code state} 最快的那件——挖掘真正会拿的那件,模拟的掉落才对得上(精准采集、时运都算)。 */
    private ItemStack bestToolFor(BlockState state) {
        Inventory inv = player.getInventory();
        ItemStack best = inv.getItem(inv.selected);
        float bestSpeed = best.getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = s;
            }
        }
        return best;
    }

    // ---- target list maintenance ----

    /** 按需补货:名单空了,或快吃完且过了间隔,就从区里还没收进名单的格里收。 */
    private void maybeQuery() {
        --queryCooldown;
        if (knownOres.isEmpty() || (knownOres.size() < QUERY_LOW_WATER && queryCooldown <= 0)) {
            queryCooldown = QUERY_MIN_GAP_TICKS;
            admit();
        }
    }

    /**
     * 补货:从区里还没收进名单的格里由近及远收,收满 {@link #MAX_TARGETS} 个为止。挖不动的格留在那儿等地形变;其余收不进的
     * ({@link #stillCandidate} 记了账)就此划掉。
     */
    private void admit() {
        if (remaining.isEmpty() || knownOres.size() >= MAX_TARGETS) {
            return;
        }
        BlockPos feet = player.blockPosition();
        List<BlockPos> nearestFirst = new ArrayList<>(remaining);
        nearestFirst.sort(Comparator.comparingDouble(feet::distSqr));
        Level level = player.level();
        for (BlockPos p : nearestFirst) {
            if (knownOres.size() >= MAX_TARGETS) {
                break;
            }
            if (unworkable.contains(p)) {
                continue;
            }
            remaining.remove(p);
            if (stillCandidate(level, p)) {
                knownOres.add(p);
            }
        }
        prune();
    }

    /**
     * 这一格还算不算候选:还要挖({@link DigTaskRecord#wants})、没被记成挖不动、挖得成、手里的工具收得到掉落。许不许挖不在
     * 这里剪。收不进的记账,回执交代。
     */
    private boolean stillCandidate(Level level, BlockPos p) {
        BlockState state = level.getBlockState(p);
        if (!r.wantsAt(p, state)) {
            // 不用挖了:旅程账上有,就是她顺路挖的(导航穿过它、为拉射线挖掉的遮挡物),算她挖掉的一格;
            // 账上没有,才记成别人动过
            if (brokeOnTheWay(p)) {
                dug.add(p.immutable());
            } else {
                gone.add(p.immutable());
            }
            return false;
        }
        if (unworkable.contains(p)) {
            return false;
        }
        if (!breakable(p, state)) {
            ruledOut.add(p.immutable());
            return false;
        }
        // 工具门:收不到掉落的格记下来,收场时说"要更好的工具",而不是误导的"什么都没有"
        if (!WorkProfile.of(player).instaBreak() && !canHarvest(player.getInventory(), state)) {
            unharvestable.add(p.immutable());
            return false;
        }
        return true;
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        knownOres.removeIf(p -> !stillCandidate(level, p));
        knownOres.sort(Comparator.comparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_TARGETS) {
            List<BlockPos> farther = knownOres.subList(MAX_TARGETS, knownOres.size());
            remaining.addAll(farther);   // 只是暂时排不上,不是没了
            farther.clear();
        }
        // 挖每一格的价钱:与寻路给路上一格定价同一个成本模型,需要主人同意的乘倍率,不许的是无穷——挑目标按价,不按剪
        digCosts.clear();
        for (BlockPos p : knownOres) {
            DigQuote.Price price = pricing.price(p, level.getBlockState(p));
            digCosts.put(p, price.cost());
            if (!Double.isFinite(price.cost())) {
                deniedWhy = price.refusal() instanceof Verdict verdict ? verdict.reason()
                        : "the permission layer does not allow it";
            }
        }
    }

    /** Nearest known target to the feet, or null. */
    private BlockPos nearestOre() {
        BlockPos feet = player.blockPosition();
        return knownOres.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    /** 日志用的最近目标:{@code 316,64,391 minecraft:oak_log dy=+0 dist=1.0} 或 {@code none}。 */
    private String nearestOreInfo() {
        BlockPos n = nearestOre();
        if (n == null) {
            return "none";
        }
        BlockPos feet = player.blockPosition();
        String block = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(n).getBlock()).toString();
        int dy = n.getY() - feet.getY();
        return n.toShortString() + " " + block + " dy=" + (dy >= 0 ? "+" + dy : dy)
                + " dist=" + String.format("%.1f", Math.sqrt(feet.distSqr(n)));
    }

    /** 挥了一下、挖掉了一格或捡到了东西:卡死计时重新起算。 */
    private void noteProgress() {
        lastProgressWork = workTicks();
    }

    /**
     * 真卡住了吗:{@link #STALL_TICKS} 个干活的刻({@link #workTicks()},等规划、等主人的刻不算)里什么进展都没有。
     *
     * @return 该收工就给终态,否则 null
     */
    private TaskState stalledOut() {
        long idle = workTicks() - lastProgressWork;
        if (idle < STALL_TICKS) {
            return null;
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] dig 卡住 {} 刻:没挖、没捡 | feet={} 名单 {} 个",
                idle, player.blockPosition().toShortString(), knownOres.size());
        return unreachable("stuck at " + player.blockPosition().toShortString() + " with nothing to dig in place for "
                + STALL_TICKS / 20 + " seconds", FailureType.NO_PATH);
    }

    /**
     * 剩下的在区里一个都到不了,收工:挖到过就算成功,如实交代剩下多少没够着;一个没挖到就按 {@code type} 失败。下一步是开路的
     * 写法——区外的路线不关在区里,从别处也许够得着。
     *
     * <p>够数之后导航的目标只有地上的掉落物,这时到不了的是那批掉落物:记进 {@link #unreachableDrops},接着挖别的补上。
     *
     * @param why  为什么到不了,原话进回执(寻路的结局由 NavText 说)
     * @param type 归到哪一种失败
     */
    private TaskState unreachable(String why, FailureType type) {
        if (quotaMet && !knownOres.isEmpty()) {
            return writeOffDrops(why);
        }
        stopNav();
        BlockPos nearest = nearestOre();
        String what = knownOres.isEmpty() ? "the drops lying on the ground"
                : (r.getMined() > 0 ? "the remaining " : "any of the ") + knownOres.size() + " " + noun();
        String next = nearest == null || r.beyond.openTheWay(nearest).isEmpty() ? ""
                : " " + r.beyond.openTheWay(nearest);
        if (r.getMined() > 0) {
            progressNote = "then could not reach " + what + " in my work area: " + why + "." + next
                    + leftovers(null) + beyondClause();
            return TaskState.SUCCESS;
        }
        fail("could not reach " + what + " in my work area (" + r.work.describe() + "); gathered 0: " + why + "."
                + next + leftovers(null) + beyondClause(), type);
        return TaskState.FAILED;
    }

    /** 够数所靠的那批掉落物走不到:全部出账,下一刻按还拿得到的重新算够没够。 */
    private TaskState writeOffDrops(String why) {
        List<ItemEntity> lost = nearbyDrops();
        for (ItemEntity ie : lost) {
            unreachableDrops.add(ie.getId());
            ourDrops.remove(ie.getId());
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] dig 够数靠的 {} 件掉落物走不到({}),出账接着挖 | feet={} 名单 {} 个",
                lost.size(), why, player.blockPosition().toShortString(), knownOres.size());
        stopNav();
        noteProgress();
        return TaskState.RUNNING;
    }

    /** 挖不成的候选为什么挖不成,回执里的说法。 */
    private static final String RULED_OUT_WHY = "unbreakable, or fluid or loose falling blocks beside them";

    /** 回执里怎么称呼要挖的东西:这些方块的格子。 */
    private String noun() {
        return "cells of " + r.label;
    }

    /** 到目前为止的收获,一句话。 */
    private String soFar() {
        return r.count == DigTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + " of " + r.total + " cells"
                : "gathered " + r.getMined();
    }

    /**
     * 要挖却没挖成的各因为什么;都没有是空串。
     *
     * @param told 回执正文已经说过的那一类(失败的主因),不再重复;没有为 null
     */
    private String leftovers(Set<BlockPos> told) {
        List<String> parts = new ArrayList<>(4);
        if (!gone.isEmpty() && told != gone) {
            parts.add(gone.size() + " were gone or had changed before I got to them");
        }
        if (!unharvestable.isEmpty() && told != unharvestable) {
            parts.add(unharvestable.size() + " can't be harvested with the current tools");
        }
        if (!unworkable.isEmpty() && told != unworkable) {
            parts.add(unworkable.size() + " gave no clear shot from any stance");
        }
        if (!ruledOut.isEmpty() && told != ruledOut) {
            parts.add(ruledOut.size() + " can't be broken here (" + RULED_OUT_WHY + ")");
        }
        return parts.isEmpty() ? "" : "; not dug: " + String.join(", ", parts);
    }

    /**
     * 一格没挖到、也没有可去挖的了,按缘由收场并带上计数:手里的工具收不下({@code WRONG_TOOL})、没有站位拉得出射线
     * ({@code NO_PATH})、挖不成({@code MINED_OUT})、区里的格都不用挖了({@code TARGET_LOST},说区外还剩什么、怎么过去,或给
     * 再扫一遍的那一行)。「走不到」那一档不在这里 —— 它由 {@link #unreachable} 收工。
     */
    private TaskState nothingDug() {
        if (!unharvestable.isEmpty()) {
            fail("found " + unharvestable.size() + " " + noun() + " but none can be harvested with"
                    + " the current tools (digging would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (gear wear) and retry; to break a block regardless of"
                    + " drops, move_goto its coordinates with arrive:use and run use block left on it with whatever"
                    + " is in hand." + leftovers(unharvestable) + beyondClause(), FailureType.WRONG_TOOL);
        } else if (!unworkable.isEmpty()) {
            fail("found " + unworkable.size() + " " + noun() + " nearby but no clear shot at any"
                    + " of them from any stance I could take; gathered 0" + leftovers(unworkable) + beyondClause(),
                    FailureType.NO_PATH);
        } else if (!ruledOut.isEmpty()) {
            fail("found " + ruledOut.size() + " " + noun() + " but none of them can be broken here ("
                    + RULED_OUT_WHY + "); gathered 0" + leftovers(ruledOut) + beyondClause(), FailureType.MINED_OUT);
        } else {
            // 区里的格都不用挖了:区外还有就说在哪、怎么过去;点的是扫描来的区域就给再扫一遍的那一行
            fail("all " + r.total + " cell(s) of " + r.what + " in my work area (" + r.work.describe()
                    + ") were gone or had changed before I got to them; gathered 0"
                    + (!r.beyond.isEmpty() ? beyondClause()
                            : r.into != null ? ". " + rescan() + " adds what is there now" : ""),
                    FailureType.TARGET_LOST);
        }
        return TaskState.FAILED;
    }

    /** 再扫一遍这件活的方块、扫进点名的那块区域的那一行。 */
    private String rescan() {
        return DigTaskRecord.rescan(String.join(" ", r.targets.stream()
                .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList()), r.into.name());
    }

    /** 回执里说区外的那一截(以 {@code "; "} 起头);区外什么都没有是空串。 */
    private String beyondClause() {
        return r.beyond.isEmpty() ? "" : "; " + r.beyond.told(player.blockPosition());
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        digger.cancel();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        if (r.count == DigTaskRecord.UNTIL_GONE) {
            data.put("cells", r.total);
            data.put("dug", r.getMined());
        } else {
            data.put("requested", r.count);
            data.put("gathered", r.getMined());
        }
        return data;
    }

    /** {@code gathered 3/8 oak_log} 或 {@code dug 3/12 cells of oak_log}。 */
    private String tally() {
        return r.count == DigTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + "/" + r.total + " cells of " + r.label
                : "gathered " + r.getMined() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String successMessage() {
        return tally() + " (" + progressNote + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after I " + tally();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after I " + tally();
    }
}
