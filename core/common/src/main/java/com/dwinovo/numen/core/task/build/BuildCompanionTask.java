package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.nav.BuildSite;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多格建造任务:走到工地外圈、一边绕圈一边逐批落位、收工撤掉路上垫下的块。
 *
 * <p><b>施工模型</b>——同伴走到工地外圈(开工时站在工地里就先走出去),然后按稳定的速率一批一批地把方块落进世界,
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
 * <p><b>施工与表演分开</b>——施工只管下一格放哪、放没放成、差什么;走动和放块的动画归演出组件
 * {@link BuildShowmanship},挂在这件活上:施工每刻落完位后叫它走一步,从不问它走得怎样。它手里没有挖掘器、导航与放置
 * 入口,绕圈改不了世界。真要挪身体的两处——开工时走出工地、收工时从自己垫的块上下来——是施工的事,在这里用正式寻路。
 *
 * <p><b>分工</b>——本类只持有施工的调度状态机(相位、遍、层窗口、落位循环)与
 * 轮扫对账;单格判据在 {@link BuildCellRules},背包口径在 {@link BuildInventory},
 * 材料账本在 {@link BuildLedger},摆设与善后在 {@link BuildFixtures},演出在
 * {@link BuildShowmanship},外圈的几何在 {@link SiteRing},顺序与节奏的纯函数在 {@link BuildOrder},
 * 收不了尾时的缺格清单在 {@link BuildOutstanding},路上垫下的块在 {@link EnRouteBlocks}。
 */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord> {

    /** 走到外圈的时限:走不到就地开工,绝不因为路不通而不干活。 */
    private static final int TRAVEL_BUDGET_TICKS = 30 * 20;
    /** 连续几遍零进展才升级处置。 */
    private static final int MAX_BARREN_PASSES = 3;
    /**
     * 一遍零进展(又不是断料)之后,等多少刻再来下一遍。她自己在圈上,压不住格子;剩下的多半被人或活物站着,
     * 裁决要等他们有机会走开——一遍全是"放不下去"会在同一刻跑完,不等的话三遍连着翻完只要三刻。
     */
    private static final int RETRY_WAIT_TICKS = 60;
    /** 收工时从自己垫的块上下来,最远走开几格。 */
    private static final int STEP_OFF_REACH = 4;

    /**
     * 写入标志:{@code UPDATE_CLIENTS}(同步给客户端)+ {@code UPDATE_KNOWN_SHAPE}
     * (跳过形状重算),<b>不含</b> {@code UPDATE_NEIGHBORS}。这笔账不欠着:
     * 收尾 {@link #settleWithWorld()} 让世界统一落定一次。
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
     * <p>压着不通知只管施工期——那一刻世界是半成品,火把写下去时它靠的墙可能还没砌。
     * 建完就该放手,见 {@link #settleWithWorld()}。
     */
    private static final int PLACE_FLAGS =
            net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                    | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;

    /**
     * CONSENT:开工前整批问主人;TRAVEL:走到外圈(开工时在工地里就是走出去);WORK:施工;END:活到头了,撤掉路上
     * 垫下的块再按结论收场。
     */
    private enum Phase { CONSENT, TRAVEL, WORK, END }

    /** 干不下去时的结论:失败的理由与类型。撤完垫块才交出去。 */
    private record Ending(String why, FailureType type) {}

    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;
    private final BuildLedger ledger;
    private final BuildShowmanship show;
    /** 清障与撤垫块的唯一落点:方块只在 {@link BlockDigger} 里被破坏,权限层在那儿把门。 */
    private final BlockDigger digger;
    /** 路上垫下、收场时要撤的块。 */
    private final EnRouteBlocks enRoute;

    private final Map<Long, BuildTaskRecord.Target> targetByPos = new LinkedHashMap<>();
    /** 本遍缺料统计(遍末报告用)。 */
    private final Map<Item, Integer> passMissing = new LinkedHashMap<>();

    private LongOpenHashSet observedCompleted;
    private Phase phase = Phase.TRAVEL;
    /** 动身走向外圈时的 {@link #workTicks()}。 */
    private long travelSince;

    /** 工地包围盒(全体目标格的最小/最大角)。 */
    private final BlockPos siteMin;
    private final BlockPos siteMax;
    /** 包围盒外的那一圈:走出工地走到它上面,演出绕着它走。 */
    private final SiteRing ring;
    /** 走到外圈的路线规格:{@link #SPEC} 并上工地格的三条禁令(编一次,每次走出去都用它)。 */
    private RouteSpec toRing;
    /** 走出工地没走成:她还在工地里时不再反复起寻路,就在原地接着盖;等她出了工地这一笔才清掉。 */
    private boolean stuckInSite;

    /** 本遍施工顺序:低层先、层内清障→骨架→贴附、蛇形走位。 */
    private List<BuildTaskRecord.Target> order = List.of();
    /** 当前层在 order 里的区间,以及本层已经轮到过的格。 */
    private int layerStart;
    private int layerEnd;
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
     * <p>"一遍走完没有进展"是个<b>过程量</b>,时间分辨率就是一遍;"她付不起剩下任何一格"
     * 是个<b>状态量</b>,在她第一次付不起的那一刻就已经成立。用过程量推断状态量必然慢一拍,
     * 那一拍里的每一刻都在为一个早就成立的结论排队——玩家看到的是她绕着工地转一圈才说没料。
     */
    private boolean passStarved;
    /** 零进展遍之后还要等的刻数,见 {@link #RETRY_WAIT_TICKS}。 */
    private int retryWait;

    /** 干不下去时的结论,进了 END 才有;建完了是 null。 */
    private Ending ending;
    /** 收工时已经从垫块上下来过一次(走到了或走不通):不再起第二次,还托着她的那几格留在原处,照实交代。 */
    private boolean steppedOff;

    private String note = "done";

    /** 开工前要问主人的清单(清的格与放的格里裁决为要问的)。 */
    private List<com.dwinovo.numen.permission.ConsentItem> consentItems = List.of();
    /** 清单涉及的目标格——主人拒绝时,这些格按"主人不让动"交代。 */
    private final LongOpenHashSet consentCells = new LongOpenHashSet();
    /** 主人拒绝了的目标格,与他的原话。 */
    private final LongOpenHashSet ownerRefused = new LongOpenHashSet();
    private String ownerWords = "";

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        this.rules = new BuildCellRules(player, record);
        this.inv = new BuildInventory(player);
        this.fixtures = new BuildFixtures(player, record, inv);
        this.ledger = new BuildLedger(player, record, rules, inv, fixtures);
        this.digger = new BlockDigger(player);
        for (BuildTaskRecord.Target target : record.targets) {
            targetByPos.put(target.pos().asLong(), target);
        }
        BlockPos[] box = siteBox(player, record.targets);
        this.siteMin = box[0];
        this.siteMax = box[1];
        this.ring = SiteRing.around(siteMin, siteMax);
        // 小活是一个动作,不演:不给演出那一圈,它就只转头挥手
        this.show = new BuildShowmanship(player, inv, BuildOrder.instant(record.targets.size()) ? null : ring);
        this.enRoute = new EnRouteBlocks(player, digger, pos -> targetByPos.containsKey(pos.asLong()));
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(this::checkMaterials);
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

    @Override
    protected void onStart() {
        observedCompleted = new LongOpenHashSet();
        skippedPos = new LongOpenHashSet();
        rescanAll();
        rebuildOrder();
        computePace();
        passStartCompleted = r.completed();
        collectConsent();
        phase = consentItems.isEmpty() ? Phase.TRAVEL : Phase.CONSENT;
    }

    /**
     * 施工前把要清的格与要放的格整批过一遍权限层,裁决为要问的合成一张卡。放行的照建;
     * 不许的(规则、模式、外部强制)由 {@link BuildCellRules#blockedByMode} 照常跳过。
     */
    private void collectConsent() {
        var gate = com.dwinovo.numen.permission.Permission.gateFor(player);
        List<com.dwinovo.numen.permission.ConsentItem> items = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (target.matches(rules.peek(target.pos()))
                    || !target.mode().allows(rules.peek(target.pos()), target.desiredState())
                    || rules.hopeless(target)) {
                continue;
            }
            for (com.dwinovo.numen.permission.Action action : rules.actionsFor(target)) {
                var verdict = gate.judgeLive(action, player.serverLevel());
                if (verdict.asks()) {
                    items.add(gate.consentItemLive(action, verdict, player.serverLevel()));
                    consentCells.add(target.pos().asLong());
                }
            }
        }
        consentItems = List.copyOf(items);
    }

    /**
     * 等主人答复:身体站住。答应了——那些格从此是放行,重扫重排后开工;拒绝了——那些格仍不许,
     * 施工照常跳过,收工时按"主人不让动"连同他的原话交代。
     */
    private TaskState tickConsent() {
        player.controls().stop();
        var answer = consult(consentItems);
        if (answer == null) {
            return TaskState.RUNNING;
        }
        if (!answer.allowed()) {
            ownerRefused.addAll(consentCells);
            ownerWords = answer.words();
        }
        consentItems = List.of();
        rescanAll();
        rebuildOrder();
        phase = Phase.TRAVEL;
        return TaskState.RUNNING;
    }

    @Override
    protected TaskState onTick() {
        if (phase == Phase.CONSENT) {
            return tickConsent();
        }
        if (phase == Phase.END) {
            return tickEnd();
        }
        updateCompleted();

        // 每刻只轮扫一片,所以这个判定可能用着一轮之前的旧数据。收工是不可回头的
        // 一步(撤垫块、生成摆设、报成功),所以真要收工之前必须再精确核一次:
        // 否则一格刚被玩家拆掉、轮扫还没转到它,她就会带着一个缺口报"全部达标"。
        if (r.completed() + skippedCells >= r.targets.size()) {
            rescanAll();
            if (r.completed() + skippedCells >= r.targets.size()) {
                return conclude(null);
            }
        }
        return phase == Phase.TRAVEL ? tickTravel() : tickWork();
    }

    // ------------------------------------------------------------------
    // 一、走到外圈
    // ------------------------------------------------------------------

    /**
     * 先挪身体再干活吗:她在工地里就得先走出去——站在图纸里会压住自己要放的格,墙砌起来还会把她关在里面;要绕圈的活
     * 她还没站到外圈上,就走过去。
     */
    private boolean mustMove() {
        return inSite() || (!BuildOrder.instant(r.targets.size()) && !show.onRing());
    }

    /** 她的身体碰着工地包围盒吗。 */
    private boolean inSite() {
        return new AABB(siteMin.getX(), siteMin.getY(), siteMin.getZ(),
                siteMax.getX() + 1, siteMax.getY() + 1, siteMax.getZ() + 1).intersects(player.getBoundingBox());
    }

    /**
     * 走到外圈,开工时站在工地里就是走出去。这是施工真要的一步,所以用正式寻路、可以改地形:路上垫下的块收场时撤掉
     * ({@link EnRouteBlocks}),放一块的价钱连撤的那一下一起算({@link #SPEC})。走到了、走不通、超时,都开工——落位不
     * 靠走位,绝不因为路不通而不干活。
     */
    private TaskState tickTravel() {
        if (nav == null) {
            if (!mustMove()) {
                beginWork();
                return TaskState.RUNNING;
            }
            travelSince = workTicks();
            if (toRing == null) {
                toRing = siteSpec();
            }
            nav = Trip.to(player, ring.goal(), toRing, siteCenter());
        }
        boolean done = switch (nav.tick()) {
            case ARRIVED, FAILED -> true;
            // 预算只计"正在往那儿走"的刻(workTicks):等规划的刻长短看机器快慢,不计入。
            case RUNNING -> workTicks() - travelSince > TRAVEL_BUDGET_TICKS;
        };
        if (done) {
            beginWork();
        }
        return TaskState.RUNNING;
    }

    private void beginWork() {
        stopNav();
        player.controls().stop();
        phase = Phase.WORK;
        placeCredit = 0;
        // 没走出去:就在原地接着盖,不反复起寻路去撞同一堵墙;压着的那几格收尾时如实交代
        stuckInSite = inSite();
        if (stuckInSite) {
            com.dwinovo.numen.core.Constants.LOG.debug(
                    "[numen-build] 没走出工地,就地开工 feet={} 工地={}..{}",
                    player.blockPosition().toShortString(), siteMin.toShortString(), siteMax.toShortString());
        }
    }

    // ------------------------------------------------------------------
    // 二、施工
    // ------------------------------------------------------------------

    /**
     * 施工的一刻:按稳定的速率落位,落完叫演出走一步。速率只由信用定——层与层之间不停顿,她走到哪一面、那一面就长。
     */
    private TaskState tickWork() {
        if (!inSite()) {
            stuckInSite = false;
        } else if (!stuckInSite) {
            // 被挤、被推、掉进了工地:先走出去,站在里面会压住要放的格
            phase = Phase.TRAVEL;
            player.controls().releaseAll();
            return TaskState.RUNNING;
        }
        TaskState state = TaskState.RUNNING;
        if (retryWait > 0) {
            retryWait--;
        } else {
            // 速率可以小于每刻一格,所以用信用累积而不是"每 N 刻放一批":
            // 生存慢到每十刻一格时,每一格都自成一批,节奏自然就散开了。
            placeCredit += cellsPerTick;
            int budget = (int) Math.min(placeCredit, BuildOrder.MAX_CELLS_PER_TICK);
            if (budget > 0) {
                state = runBatch(budget);
            }
        }
        if (phase == Phase.WORK) {
            // 落完位再迈步:脸朝哪儿这一刻已经定了,腿按它换算
            show.walk();
        }
        return state;
    }

    /**
     * 落一批:在<b>当前最低的未完成层</b>里,挑离她最近的几格;这一层轮完了接着翻下一层,预算用完或者这一遍到头为止。
     *
     * <p>顺序低层优先(上面的东西得有底下的东西撑着),层内则<b>跟着她的位置走</b>,
     * 不走固定蛇形。落位顺序跟她在哪无关的话,观感是"她在那边溜达,方块在这边冒出来"
     * ——两条互不相干的动画叠在一起,一眼就假。绑上之后,她走到东墙东墙就长,绕到
     * 南边南边接着长。<b>因果对上,比加多少粒子都管用。</b>
     */
    private TaskState runBatch(int budget) {
        List<BlockPos> touched = new ArrayList<>();
        BlockState sample = null;
        boolean passOver = false;
        while (budget > 0 && !passStarved) {
            BuildTaskRecord.Target target = nearestPendingInLayer();
            if (target == null) {
                if (!advanceLayer()) {
                    passOver = true;
                    break;
                }
                continue;
            }
            BlockState placed = processCell(target);
            if (placed != null) {
                budget--;
                placeCredit -= 1.0;
                touched.add(target.pos());
                sample = placed;
            }
        }
        if (!touched.isEmpty()) {
            show.performWork(touched, sample);
        }
        // 付不起剩下任何一格:当场收遍。结论在第一次付不起的那一刻就已经成立,走完剩下的层不会改变它。
        if (passStarved || passOver) {
            return endPass();
        }
        return TaskState.RUNNING;
    }

    /**
     * 当前层里离她最近、这一层还没轮到过的一格待建;这一层都轮到过了是 null。
     *
     * <p>{@code placedThisLayer} 只保证"这一层每格都轮到过一次",不决定顺序;
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

    /** 这一层轮完了,翻到下一层;本遍的层都翻完了返回 false。 */
    private boolean advanceLayer() {
        placedThisLayer.clear();
        layerStart = layerEnd;
        if (layerStart >= order.size()) {
            return false;
        }
        int y = order.get(layerStart).pos().getY();
        int end = layerStart;
        while (end < order.size() && order.get(end).pos().getY() == y) {
            end++;
        }
        layerEnd = end;
        return true;
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
            if (!clear(target)) {
                return null;   // 没清掉(权限层拒了、或砸不动):这遍放下,不往上放
            }
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
            r.placedOne(occupied);
            recordPlaced(pos, now);
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
        // 照图直写不经 BlockItem.place,放置记录在这里记:这一格(连回调补出的另一半)是她放的。物品车道由那里的 mixin 记
        PlacedBlocks.placedBy(player.serverLevel(), pos, player);
        r.placedOne(occupied);
        recordPlaced(pos, desired);
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
     * 一遍,更不必等三个零进展遍。
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
            Hotbar.hold(player, slot);
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
     * 清掉挡路的方块:走挖掘器的原生破坏——生存按主手结算掉落(她清出来的木头该归玩家),
     * 创造不掉,破坏事件照常触发,权限层在那儿把门。
     *
     * <p>掉落按主手物品结算,而主手此刻拿的是<b>正在砌的那个方块</b>(演出需要),
     * 不是镐。所以石头与矿石这一类清了不掉东西——"归玩家"只在不需要工具的方块上
     * 成立。要让它全成立就得在清障前临时换成镐,那会和演出打架,故此处照实记下。
     *
     * @return 这一格真的清空了
     */
    private boolean clear(BuildTaskRecord.Target target) {
        if (!digger.destroyNow(target.pos())) {
            return false;
        }
        r.brokeOne(target.removes() != null);
        recordCleared(target.pos());
        return true;
    }

    /** 放下了一格:这件活盖的是一栋房子的话,记进那一栋——那一栋由哪些格子组成,只有这一处记着。 */
    private void recordPlaced(BlockPos pos, BlockState state) {
        if (r.site != null) {
            Built.of(player.getServer()).placed(r.site, player.getGameProfile().getName(),
                    player.getServer().overworld().getGameTime(), pos, state.getBlock());
        }
    }

    /** 拆掉了一格:这件活盖的是一栋房子的话,从那一栋的记录里划掉。 */
    private void recordCleared(BlockPos pos) {
        if (r.site != null) {
            Built.of(player.getServer()).cleared(r.site, player.getServer().overworld().getGameTime(), pos);
        }
    }

    /**
     * 一遍扫完的裁决。有进展就开下一遍(补漏);零进展先等一等再来(剩下的多半被人或活物站着),连着几遍颗粒无收
     * 才认账——缺料是邀请,不是错误。
     */
    private TaskState endPass() {
        // 收遍要判完工,这一次必须精确——每刻那次只轮扫一片
        rescanAll();
        if (r.completed() + skippedCells >= r.targets.size()) {
            return conclude(null);
        }
        boolean progressed = r.completed() > passStartCompleted;
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-build] 收遍 {}/{} 本遍+{} 缺料{} 零进展遍{} 断料{}",
                r.completed(), r.targets.size(), r.completed() - passStartCompleted,
                passMissing.size(), barrenPasses, passStarved);
        // 断料:不重试、不等。判据的分野是"这个恢复动作能不能改变卡住的原因"——等一等能让站着的人走开,
        // 却改变不了背包里的任何东西。为一个改不了的原因重试三遍就是纯粹在耗玩家的时间;
        // 而回执本来就写着"补料后重发同一调用",重发很便宜。
        //
        // 注意这里不看 progressed:本遍砌了二十格然后断料,和一格没砌就断料,对玩家
        // 是同一件事——她现在动不了了,而且再等下去也不会变。
        if (passStarved) {
            return conclude(new Ending("built " + r.completed() + "/" + r.targets.size()
                    + " and ran out — " + ledger.missingReason(passMissing), FailureType.NO_MATERIAL));
        }
        if (progressed) {
            barrenPasses = 0;
        } else if (++barrenPasses >= MAX_BARREN_PASSES) {
            if (!passMissing.isEmpty()) {
                // 有格子缺料、但不是断料(别的格还付得起,只是这一遍恰好没推进)。
                // 先报干了多少,再报还差什么——玩家要的是"还要凑多少才能收工",
                // 不是一句材料不足。已经砌好的部分留在世界里,不回滚。
                return conclude(new Ending("built " + r.completed() + "/" + r.targets.size()
                        + " and ran out — " + ledger.missingReason(passMissing), FailureType.NO_MATERIAL));
            }
            // 等过了也补不上:走一遍缺格,留案再交代,失败的类型跟主导病因走。盖不完就是盖不完,不粉饰成成功。
            BuildOutstanding outstanding = BuildOutstanding.survey(r.targets, rules, skippedPos, player.level(),
                    damagedCells);
            outstanding.log(r.completed(), r.targets.size(), player.blockPosition(), designFrame());
            return conclude(new Ending(outstanding.describe(designFrame()) + "; built " + r.completed() + "/"
                    + r.targets.size(), outstanding.failure()));
        }
        if (!progressed) {
            retryWait = RETRY_WAIT_TICKS;
        }
        rebuildOrder();
        passStartCompleted = r.completed();
        passMissing.clear();
        passStarved = false;   // 下一遍重新判:期间玩家可能补过料
        return TaskState.RUNNING;
    }

    /** 这件活照的施工图摆在哪儿、朝哪儿;当场执行的原语没有施工图,是 null。 */
    private Placement designFrame() {
        return r.site == null ? null : new Placement(r.site.anchor(), r.site.quarters());
    }

    // ------------------------------------------------------------------
    // 三、收场
    // ------------------------------------------------------------------

    /**
     * 活到头了:建完({@code why} 为 null)或干不下去了。结论先记下,身体站住,进 END——撤完路上垫下的块再交出去,
     * 撤了什么也跟着结论一起说。
     */
    private TaskState conclude(Ending why) {
        ending = why;
        phase = Phase.END;
        stopNav();
        player.controls().releaseAll();
        return TaskState.RUNNING;
    }

    /**
     * 撤掉路上垫下的块再收场。正站在垫块上时先下来——拆掉托着她的那块她会掉下去,而拆掉别的垫块可能正拆掉她下来
     * 要走的路,所以下来之前一块都不拆。下不来就把托着她的那几格留在原处,照实交代。
     */
    private TaskState tickEnd() {
        List<EditLedger.Placed> placed = placedOnTheWay();
        if (!steppedOff && !enRoute.holdingHer(placed).isEmpty()) {
            return stepOff(enRoute.standing(placed));
        }
        stopNav();
        enRoute.takeDown(placedOnTheWay());
        player.controls().stop();
        if (ending != null) {
            fail(ending.why(), ending.type());
            return TaskState.FAILED;
        }
        return finish();
    }

    /**
     * 从垫块上下来:只走不改的寻路,走到几格外一处脚下不是垫块的地方。这是收场真要的一步,但不再为它垫新的块。
     */
    private TaskState stepOff(java.util.Set<BlockPos> blocks) {
        if (nav == null) {
            BlockPos feet = Feet.cell(player);
            Goal off = Goals.allOf(List.of(Goals.ring(feet, 1.0, STEP_OFF_REACH), Goals.offBlocks(blocks)));
            nav = Trip.to(player, off, RouteSpec.defaults(), feet);
        }
        if (nav.tick() != Trip.Status.RUNNING) {
            stopNav();
            steppedOff = true;   // 下一刻重看她站在什么上面,还托着她的就留下
        }
        return TaskState.RUNNING;
    }

    /**
     * 建完之后让世界落定一次:逐格告诉六邻"我在这儿",再通知一圈邻居。
     *
     * <h2>为什么施工期不能做、收尾可以</h2>
     * 施工期世界是<b>半成品</b>——火把写下去时它靠的那面墙可能还没砌,这时候通知邻居
     * 等于拿半成品复核每一格,贴附方块整批弹掉。建完复核的是成品:掉下来的只有在
     * <b>完整世界里也确实站不住</b>的格,而它们本来也只是暂时活着(旁边任何一次方块
     * 更新都会让它们掉)。
     *
     * <h2>两句话各管一件事</h2>
     * {@code updateNeighbourShapes} 让邻居各自重算<b>自己的形状</b>(栅栏伸手、墙连上、
     * 红石线不再是孤点);{@code updateNeighborsAt} 让世界<b>反应</b>(红石通电、站不住的
     * 掉落)。两句都是原版自己的话,所以不必维护"哪些方块要补形状"的清单——列清单一定会漏。
     *
     * @return 落定之后与图纸不同的格数(掉了的 + 形状被重算的);如实进回执,不无声改动
     */
    private int settleWithWorld() {
        var level = player.level();
        List<BlockPos> built = new ArrayList<>();
        for (BuildTaskRecord.Target t : r.targets) {
            if (BuildCellRules.isAirTarget(t)) continue;
            if (level.getBlockState(t.pos()).is(t.desiredState().getBlock())) {
                built.add(t.pos());
            }
        }
        for (BlockPos pos : built) {
            BlockState current = level.getBlockState(pos);
            if (current.isAir()) continue;
            current.updateNeighbourShapes(level, pos, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            level.updateNeighborsAt(pos, current.getBlock());
        }
        // 落定之后还对不对得上图纸:掉了的、形状被邻居改写的,都算"不同"。
        int settled = 0;
        for (BuildTaskRecord.Target t : r.targets) {
            if (BuildCellRules.isAirTarget(t)) continue;
            if (!built.contains(t.pos())) continue;
            if (!t.matches(level.getBlockState(t.pos()))) {
                settled++;
            }
        }
        r.settledAway(settled);
        if (settled > 0) {
            // 落定改了东西就记一笔:排查"我图纸里明明画了"时,第一眼要看的就是它
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] build 落定后 {}/{} 格与图纸不同(站不住的掉了、形状按邻居重算了)",
                    settled, built.size());
        }
        return settled;
    }

    /** 建成收工:让世界落定一次、生成摆设、外围补水,放一把庆祝的粒子;路上垫下的块在这之前已经撤了。 */
    private TaskState finish() {
        int popped = settleWithWorld();
        fixtures.spawnAll();
        fixtures.nudgeSurroundingWater(siteMin, siteMax);
        show.celebrate(siteMin, siteMax);
        player.controls().stop();
        if (r.completed() + skippedCells >= r.targets.size()) {
            // 三种交代要并列,不能互相吃掉:此前 skippedFixtures 一非零就只报摆设,
            // 那句"有几格没动"被整段吞掉——两件事同时发生时回执只说一半。
            List<String> notes = new ArrayList<>();
            // 不说"全对上了"——有格子我们主动没动,得说清有几格、为什么;主人不让动的单说
            int refusedByOwner = 0;
            for (long cell : ownerRefused) {
                if (skippedPos.contains(cell)) {
                    refusedByOwner++;
                }
            }
            if (refusedByOwner > 0) {
                notes.add("left " + refusedByOwner + " cell(s) alone because the owner said no: " + ownerWords);
            }
            if (skippedCells > refusedByOwner) {
                notes.add("left " + (skippedCells - refusedByOwner) + " cell(s) alone: what is there may not"
                        + " be moved, or the spot cannot be built on");
            }
            if (popped > 0) {
                notes.add(popped + " cell(s) ended up different once the world settled — vanilla would not"
                        + " hold them there, or their shape is decided by their neighbours");
            }
            if (r.droppedAtLoad > 0) {
                notes.add(r.droppedAtLoad + " cell(s) of the plan could not be taken along"
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
    // 施工顺序与工地
    // ------------------------------------------------------------------

    /** 工地包围盒:全体目标格的最小角与最大角;一格都没有时就是她脚下那一格。 */
    private static BlockPos[] siteBox(NumenPlayer player, List<BuildTaskRecord.Target> targets) {
        if (targets.isEmpty()) {
            BlockPos feet = Feet.cell(player);
            return new BlockPos[]{feet, feet};
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BuildTaskRecord.Target target : targets) {
            BlockPos pos = target.pos();
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new BlockPos[]{new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ)};
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
     * 寻路对工地格的三条禁令:<b>不许拆、不许埋、不许站进去</b>。
     *
     * <p>不许拆——否则她会为了抄近路把自己刚砌好的墙打个洞穿过去,一边建一边拆。
     * 不许埋——否则寻路会拿垫柱材料把某个目标格填上,那格从此和图纸对不上,还得
     * 先拆再放。
     * 不许站进去——否则走向外圈时会从还是空气的图纸格里抄近路,一条腿走到一半
     * 被截断,她就站在了自己要放的那格里:身体占着的格放不下,收工时报"有人站着"。
     * 日式小屋那次差的两格门口台阶,病根就是这个。前两条是硬禁,第三条是重价:被外力挪进去时她还得走得出来。
     * 三条怎么写进走向外圈那条路的规格,在工地的位置代价那一处({@link BuildSite})。
     */
    private LongSet protectedCells() {
        if (siteCells == null) {
            siteCells = new LongOpenHashSet(targetByPos.keySet());
        }
        return siteCells;
    }

    private LongOpenHashSet siteCells;

    /** 走向外圈那条路的规格:{@link #SPEC} 并上工地格的三条禁令。 */
    private RouteSpec siteSpec() {
        return BuildSite.around(SPEC, protectedCells());
    }

    /** 工地包围盒的中心那一格:走向外圈没走成时,回执里说"朝哪儿"用。 */
    private BlockPos siteCenter() {
        return new BlockPos((siteMin.getX() + siteMax.getX()) / 2, (siteMin.getY() + siteMax.getY()) / 2,
                (siteMin.getZ() + siteMax.getZ()) / 2);
    }

    /** 把层窗口对准 order 里最低的那一层。 */
    private void resetLayerWindow() {
        placedThisLayer.clear();
        layerStart = 0;
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
     * {@link BuildOrder#MAX_CELLS_PER_TICK} 格——为看清 8 格的变化去重算三万格,这笔账不划算。
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
        Terrain view = Terrain.of(player);
        for (int k = 0; k < n; k++) {
            if (rescanCursor >= total) {
                rescanCursor = 0;
            }
            BuildTaskRecord.Target target = r.targets.get(rescanCursor++);
            BlockPos pos = target.pos();
            long key = pos.asLong();
            // 未加载的格保持原判:完成集与跳过集都有记忆,不能因为看不见就翻案
            if (!view.loaded(pos.getX(), pos.getZ())) {
                continue;
            }
            BlockState observed = view.state(pos);
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

    /**
     * 走出工地、走到外圈这一段可以改地形:挖掉挡路的、垫块过坎,都是为了到场干活。路上垫下的块收场时都要撤掉
     * ({@link EnRouteBlocks}),所以放一块的价钱连撤的那一下一起算({@link RouteSpec#takeBack})——定价只在那一处。
     * 起跳比平时贵得多:工地上下层之间蹦跶容易把刚砌的东西踩坏,能绕楼梯就绕。
     */
    static final RouteSpec SPEC = RouteSpec.defaults().edit()
            .alter(RouteSpec.Alter.NATURAL)
            .jumpPenalty(RouteSpec.defaults().jumpPenalty() + 10.0)
            .takeBack(true)
            .build();

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        super.stop(companion, why);
        player.controls().stop();
    }

    /**
     * 任何收场都撤路上垫下的块:自己走到头的在 END 里已经撤过(也先下来过),这里撤的是被叫停、超时时还立着的——
     * 那时身体已不归这件活,没有机会先下来,托着她的那几格留在原处并说明。
     */
    @Override
    protected void cleanup() {
        super.cleanup();
        enRoute.takeDown(placedOnTheWay());
        player.controls().releaseAll();
    }

    /** 撤了哪些垫块、哪些留在原处以及为什么:每一种收场都说。 */
    @Override
    protected String closingNote() {
        return enRoute.describe();
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
        data.put("replaced", r.replaced());
        data.put("cleared", r.broken());
        data.put("removed", r.removed());
        String building = building();
        if (building != null) {
            data.put("building", building);
        }
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

    /**
     * 这件活盖的那一栋叫什么({@code house#1});当场执行的原语不是一栋房子,一格都还没放下的新房子也还没有名字,是 null。
     */
    private String building() {
        if (r.site == null) {
            return null;
        }
        Built.Building b = Built.of(player.getServer()).at(r.site);
        return b == null ? null : b.name();
    }

    /** 放了、换了、拆了多少格,这一句收工与收不了工都用。 */
    private String tally() {
        StringBuilder sb = new StringBuilder("placed ").append(r.placed());
        if (r.replaced() > 0) {
            sb.append(" (").append(r.replaced()).append(" replacing what stood there)");
        }
        sb.append(", cleared ").append(r.broken());
        if (r.removed() > 0) {
            sb.append(" (").append(r.removed()).append(" of them blocks you had put there before)");
        }
        return sb.toString();
    }

    @Override
    protected String successMessage() {
        String building = building();
        return (building == null ? "" : building + ": ") + "built " + r.completed() + "/" + r.targets.size()
                + " block(s); " + tally()
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
