package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.sdk.Call;
import com.dwinovo.numen.sdk.LuaCodecs;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.build.Built;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.permission.PlacedBlocks;
import com.dwinovo.numen.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多格建造任务:站在原地,把施工图里手够得着的格一批一批落进世界,每一格轮到一次就收场。
 *
 * <p><b>只放够得着的</b>——每一格够不够得着与 {@code numen.move.to(…, {arrive = "place"})} 走到的地方同一个判据({@link BuildSurvey}):
 * 她不走动,够不着的格留给下一次,回执说还剩几格、最低最近的一格在哪。走到够得着的地方、挖开挡着的、再放,是脚本的事
 * (库里的 {@code numen.build.raise})。受理之前看一眼:够得着的一格都没有就当场拒绝,说清该先去哪儿。
 *
 * <p><b>不挖</b>——生存模式下图纸要的格里立着别的方块,那一格由 {@code numen.work.dig} 挖开(挖的判据、工具、掉落、权限都是它的),
 * 这里不碰,回执里算进"要先挖开的";创造模式照原版一下就碎:图纸直接写上去顶掉原来的。
 *
 * <p>保留下来的是施工自己的事:生存模式逐格扣料、期望状态精确落位、低层先放(支撑还没长出来的先放着,
 * 留给下一次调用)、整份对上之后让世界落定一次、生成摆设。
 *
 * <p><b>施工与表演分开</b>——施工只管下一格放哪、放没放成、差什么;转头、挥手、粒子归演出组件 {@link BuildShowmanship},
 * 它手里没有放置入口,改不了世界。
 *
 * <p><b>分工</b>——本类只持有施工的调度状态机(相位、遍、层窗口、落位循环)与轮扫对账;每一格的情形在 {@link BuildSurvey},
 * 单格判据在 {@link BuildCellRules},背包口径在 {@link BuildInventory},材料账本在 {@link BuildLedger},摆设与善后在
 * {@link BuildFixtures},演出在 {@link BuildShowmanship},顺序与节奏的纯函数在 {@link BuildOrder},收不了尾时的缺格清单在
 * {@link BuildOutstanding}。
 */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord> {

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

    /** CONSENT:开工前整批问主人;WORK:施工。 */
    private enum Phase { CONSENT, WORK }

    /** 干不下去时的结论:失败的理由与类型。 */
    private record Ending(String why, FailureType type) {}

    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;
    private final BuildLedger ledger;
    private final BuildShowmanship show;

    private final Map<Long, BuildTaskRecord.Target> targetByPos = new LinkedHashMap<>();
    /** 本遍缺料统计(遍末报告用)。 */
    private final Map<Item, Integer> passMissing = new LinkedHashMap<>();

    private LongOpenHashSet observedCompleted;
    private Phase phase = Phase.WORK;

    /** 工地包围盒(全体目标格的最小/最大角)。 */
    private final BlockPos siteMin;
    private final BlockPos siteMax;

    /** 本遍施工顺序:只收够得着的待建格,低层先、层内清障→骨架→贴附。 */
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
    /**
     * 本遍已经证明<b>付不起剩下任何一格</b>——缺料这件事在这一刻就成立了,不必走完这遍。
     *
     * <p>"一遍走完没有进展"是个<b>过程量</b>,时间分辨率就是一遍;"她付不起剩下任何一格"
     * 是个<b>状态量</b>,在她第一次付不起的那一刻就已经成立。用过程量推断状态量必然慢一拍,
     * 那一拍里的每一刻都在为一个早就成立的结论排队——玩家看到的是她绕着工地转一圈才说没料。
     */
    private boolean passStarved;

    private String note = "done";

    /** 开工前要问主人的清单(这一次够得着的格里裁决为要问的)。 */
    private List<com.dwinovo.numen.permission.ConsentItem> consentItems = List.of();

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        this.rules = new BuildCellRules(player, record);
        this.inv = new BuildInventory(player);
        this.fixtures = new BuildFixtures(player, record, inv);
        this.ledger = new BuildLedger(player, record, rules, inv, fixtures);
        for (BuildTaskRecord.Target target : record.targets) {
            targetByPos.put(target.pos().asLong(), target);
        }
        BlockPos[] box = siteBox(player, record.targets);
        this.siteMin = box[0];
        this.siteMax = box[1];
        this.show = new BuildShowmanship(player, inv);
    }

    /** 按她此刻站的地方数这件活的每一格。 */
    private BuildSurvey survey() {
        return new BuildSurvey(player, r, rules, inv, ledger);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(this::checkMaterials, this::checkReach);
    }

    /**
     * 还有没了结的格,却一格都够不着:当场拒绝,说清还剩什么、先去哪儿。全都对上了不拒:这一趟照常收尾(落定、挂件),
     * 回执说什么都没变。
     */
    private Precondition.Failure checkReach() {
        BuildSurvey.Tally tally = survey().tally();
        if (tally.count(BuildSurvey.State.REACH) > 0 || tally.left() == 0) {
            return null;
        }
        int unheld = tally.count(BuildSurvey.State.UNHELD);
        if (unheld == tally.left()) {
            // 剩下的全都放下去立不住:不是走过去的事
            return new Precondition.Failure(unheld + " cell(s) would not stay where the design puts them: nothing "
                    + "holds them there.", FailureType.NO_SUPPORT);
        }
        String hint = !tally.far().isEmpty()
                ? Call.of("numen.move.to", tally.far().get(0), Map.of("arrive", "place"))
                : !tally.dig().isEmpty() ? Call.of("numen.work.dig", tally.dig().get(0)) : null;
        return new Precondition.Failure("nothing of it to place within reach of where you stand. " + remaining(tally),
                FailureType.OUT_OF_REACH, hint);
    }

    /**
     * 还剩什么、下一步照抄什么:够不着的几格与最低最近的一格(走过去的那一行),要先挖开的几格与最近的一格(挖它的那一行),
     * 缺料的几格。什么都不剩是 "nothing left to do here"。
     */
    private static String remaining(BuildSurvey.Tally tally) {
        List<String> parts = new ArrayList<>();
        int reach = tally.count(BuildSurvey.State.REACH);
        if (reach > 0) {
            // 每格轮到一次就收场:撑着它们的这一次才立起来、或这一次没放进去的,下一次放
            parts.add(reach + " cell(s) within reach go in on the next numen.build.place");
        }
        if (!tally.far().isEmpty()) {
            BlockPos next = tally.far().get(0);
            parts.add(tally.far().size() + " cell(s) out of reach — the lowest nearest is " + xyz(next)
                    + ": numen.move.to(" + lua(next) + ", {arrive = \"place\"}) gets you within reach of it");
        }
        if (!tally.dig().isEmpty()) {
            BlockPos next = tally.dig().get(0);
            parts.add(tally.dig().size() + " cell(s) hold another block that must be dug out first — the nearest is "
                    + xyz(next) + ": numen.move.to(" + lua(next) + ", {arrive = \"dig\"}) then numen.work.dig(" + lua(next) + ")");
        }
        int shortCells = tally.count(BuildSurvey.State.SHORT);
        if (shortCells > 0) {
            parts.add(shortCells + " cell(s) hold another block and you carry nothing to put there");
        }
        int unheld = tally.count(BuildSurvey.State.UNHELD);
        if (unheld > 0) {
            parts.add(unheld + " cell(s) would not stay put yet: what holds them is not built");
        }
        return parts.isEmpty() ? "Nothing left to do here." : "Still to do: " + String.join("; ", parts) + ".";
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String lua(BlockPos pos) {
        return LuaCodecs.literal(pos);
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
        phase = consentItems.isEmpty() ? Phase.WORK : Phase.CONSENT;
        placeCredit = 0;
    }

    /**
     * 施工前把这一次够得着的格整批过一遍权限层,裁决为要问的合成一张卡。放行的照建;不许的(规则、模式、外部强制)由
     * {@link BuildCellRules#blockedByMode} 照常跳过。
     */
    private void collectConsent() {
        var gate = com.dwinovo.numen.permission.Permission.gateFor(player);
        BuildSurvey survey = survey();
        List<com.dwinovo.numen.permission.ConsentItem> items = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (survey.of(target) != BuildSurvey.State.REACH) {
                continue;
            }
            for (com.dwinovo.numen.permission.Action action : rules.actionsFor(target)) {
                var verdict = gate.judgeLive(action, player.serverLevel());
                if (verdict.asks()) {
                    items.add(gate.consentItemLive(action, verdict, player.serverLevel()));
                }
            }
        }
        consentItems = List.copyOf(items);
    }

    /**
     * 等主人答复:身体站住。答应了——那些格从此是放行,重扫重排后开工;拒绝了——这件活停在这里,回执是他的原话与问的是什么,
     * 一格不放(与 {@code numen.work.dig} 被拒同一个收场)。
     */
    private TaskState tickConsent() {
        player.controls().stop();
        var answer = consult(consentItems);
        if (answer == null) {
            return TaskState.RUNNING;
        }
        if (!answer.allowed()) {
            return conclude(new Ending(answer.refusal(consentItems), FailureType.REFUSED));
        }
        consentItems = List.of();
        rescanAll();
        rebuildOrder();
        phase = Phase.WORK;
        return TaskState.RUNNING;
    }

    @Override
    protected TaskState onTick() {
        if (phase == Phase.CONSENT) {
            return tickConsent();
        }
        updateCompleted();

        // 每刻只轮扫一片,所以这个判定可能用着一轮之前的旧数据。收工是不可回头的
        // 一步(生成摆设、报成功),所以真要收工之前必须再精确核一次:
        // 否则一格刚被玩家拆掉、轮扫还没转到它,她就会带着一个缺口报"全部达标"。
        if (r.completed() + skippedCells >= r.targets.size()) {
            rescanAll();
            if (r.completed() + skippedCells >= r.targets.size()) {
                return conclude(null);
            }
        }
        return tickWork();
    }

    // ------------------------------------------------------------------
    // 一、施工
    // ------------------------------------------------------------------

    /**
     * 施工的一刻:按稳定的速率落位,身体站着。速率只由信用定——层与层之间不停顿。
     */
    private TaskState tickWork() {
        TaskState state = TaskState.RUNNING;
        // 速率可以小于每刻一格,所以用信用累积而不是"每 N 刻放一批":
        // 生存慢到每十刻一格时,每一格都自成一批,节奏自然就散开了。
        placeCredit += cellsPerTick;
        int budget = (int) Math.min(placeCredit, BuildOrder.MAX_CELLS_PER_TICK);
        if (budget > 0) {
            state = runBatch(budget);
        }
        show.stand();
        return state;
    }

    /**
     * 落一批:在<b>当前最低的未完成层</b>里,挑离她最近的几格;这一层轮完了接着翻下一层,预算用完或者这一遍到头为止。
     * 顺序低层优先(上面的东西得有底下的东西撑着),层内由近及远。
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
     * 真正的顺序由距离决定。
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
            return null;   // 区块这一刻没加载:临时状况,留给下一次调用(不算注定动不了)
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
            // 谁都不豁免——包括她自己:身体占着的格子这一次先放下,下一次调用时她多半已经挪开了。
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
            if (!WorkProfile.of(player).instaBreak()) {
                // 生存:立着的方块由 numen.work.dig 挖开,这里不挖;轮到时还立着就放下,回执算进"要先挖开的"
                return null;
            }
            // 创造:原版一下就碎——写成空气,邻居照原版反应(贴着它的火把掉下来),所以这一次通知邻居;要放方块的接着落位
            if (!player.level().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL)) {
                return null;
            }
            r.brokeOne(target.removes() != null);
            recordCleared(pos);
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
        BuildSurvey survey = survey();
        for (BuildTaskRecord.Target target : r.targets) {
            if (target.matches(rules.peek(target.pos()))) continue;
            if (rules.blockedByMode(target) || rules.hopeless(target)) continue;
            if (survey.affordable(target)) {
                return true;
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
     * 地方,主人亲手也放不了。扣料也交给原版从手上的那叠扣:原版创造模式下不扣,
     * 与 {@code BuildInventory.consumeOne} 按 {@code WorkProfile.freeMaterials} 不扣是同一个模式。
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
     * 一遍走完就收场:够得着的每一格都轮到过一次。断了料,以缺料收场;一格都没放成,以放不下的缘由收场;放成了就是成功,回执说
     * 还剩什么。再来一遍——剩下的等撑着它们的立起来、等站着的人走开、等补了料——是程序的事:{@code numen.build.raise} 再问一次
     * {@code numen.build.diff},接着放。
     */
    private TaskState endPass() {
        // 收遍要判完工,这一次必须精确——每刻那次只轮扫一片
        rescanAll();
        if (r.completed() + skippedCells >= r.targets.size()) {
            return conclude(null);
        }
        boolean progressed = r.completed() > passStartCompleted;
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-build] 收遍 {}/{} 本遍+{} 缺料{} 断料{}",
                r.completed(), r.targets.size(), r.completed() - passStartCompleted,
                passMissing.size(), passStarved);
        // 断料:这一遍砌了二十格然后断料,和一格没砌就断料,对玩家是同一件事——她现在动不了了。先报干了多少,再报还差什么:
        // 玩家要的是"还要凑多少",不是一句材料不足。已经砌好的部分留在世界里,不回滚。
        if (passStarved || (!progressed && !passMissing.isEmpty())) {
            return conclude(new Ending("built " + r.completed() + "/" + r.targets.size()
                    + " and ran out — " + ledger.missingReason(passMissing), FailureType.NO_MATERIAL));
        }
        if (!progressed) {
            // 够得着却一格都放不下:走一遍这些格,留案再交代,失败的类型跟主导病因走。放不下就是放不下,不粉饰成成功。
            BuildOutstanding outstanding = BuildOutstanding.survey(order, rules, skippedPos, player.level(),
                    damagedCells);
            outstanding.log(r.completed(), r.targets.size(), player.blockPosition(), designFrame());
            return conclude(new Ending(outstanding.describe(designFrame()) + "; built " + r.completed() + "/"
                    + r.targets.size(), outstanding.failure()));
        }
        return conclude(null);
    }

    /** 这件活照的施工图摆在哪儿、朝哪儿;当场执行的原语没有施工图,是 null。 */
    private Placement designFrame() {
        return r.site == null ? null : new Placement(r.site.anchor(), r.site.quarters());
    }

    // ------------------------------------------------------------------
    // 二、收场
    // ------------------------------------------------------------------

    /** 活到头了:够得着的放完({@code why} 为 null)或干不下去了。身体站住,按结论收场。 */
    private TaskState conclude(Ending why) {
        player.controls().stop();
        if (why != null) {
            fail(why.why(), why.type());
            return TaskState.FAILED;
        }
        return finish();
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

    /**
     * 够得着的放完了。整份都对上了才收尾:让世界落定一次、生成摆设、外围补水,放一把庆祝的粒子——半成品上落定会让还没有墙
     * 可靠的贴附件掉下来,所以还剩格时只交代还剩什么、下一步去哪儿。
     */
    private TaskState finish() {
        player.controls().stop();
        if (r.completed() + skippedCells < r.targets.size()) {
            note = remaining(survey().tally());
            return TaskState.SUCCESS;
        }
        int popped = settleWithWorld();
        fixtures.spawnAll();
        fixtures.nudgeSurroundingWater(siteMin, siteMax);
        show.celebrate(siteMin, siteMax);
        {
            // 几种交代要并列,不能互相吃掉:两件事同时发生时回执不能只说一半。
            List<String> notes = new ArrayList<>();
            // 不说"全对上了"——有格子我们主动没动,得说清有几格、为什么
            if (skippedCells > 0) {
                notes.add("left " + skippedCells + " cell(s) alone: what is there may not be moved, or the spot "
                        + "cannot be built on");
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
     * 重排本遍顺序:只收站在这里够得着、还没达标的格({@link BuildSurvey.State#REACH},开工前问过主人之后不许的照常跳过)。
     * 低层在前(下面盖好了上面才有依托),层内先清障、再骨架、最后贴附,顺序完全确定。
     *
     * <p><b>贴附件整体推到第二趟</b>,排在所有层之后(见 {@link BuildOrder})。
     */
    private void rebuildOrder() {
        BuildSurvey survey = survey();
        List<BuildTaskRecord.Target> pending = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (survey.of(target) == BuildSurvey.State.REACH && !rules.blockedByMode(target)) {
                pending.add(target);
            }
        }
        pending.sort(BuildOrder.BUILD_ORDER);
        order = pending;
        resetLayerWindow();
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
            } else if (rules.refused(target) || rules.hopeless(target)) {
                // 与 BuildSurvey 的"不去动"同一个判据:要问主人的格还算待办(问了才知道),不从分母里摘掉。
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
                    // 水火漫过来)。下一次调用比差异时它又在要补的格里;这里只记账,
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

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        super.stop(companion, why);
        player.controls().stop();
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        player.controls().releaseAll();
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
    protected Placed value() {
        Map<Item, Integer> shortfall = r.consumeMaterials
                ? ledger.shortfallAgainstInventory(ledger.remainingNeed()) : Map.of();
        return new Placed(r.targets.size(), r.targets.size() - r.completed() - skippedCells, r.completed(), r.placed(),
                r.replaced(), r.broken(), r.removed(), Optional.ofNullable(building()),
                siteMin == null ? Optional.empty() : Optional.of(List.of(siteMin, siteMax)),
                // 施工期间被外力拆毁又补回去的格数:她盖得慢或反复返工,原因在这儿
                damagedCells > 0 ? Optional.of(damagedCells) : Optional.empty(),
                // 整份放完、世界落定一次之后与图纸不同的格:原版的裁决,再放一遍还是这样
                r.settledAway() > 0 ? Optional.of(r.settledAway()) : Optional.empty(),
                shortfall.isEmpty() ? Optional.empty() : Optional.of(BuildLedger.summarizeShortfall(shortfall)));
    }

    /** {@code numen.build.place} 交回的值。 */
    @com.dwinovo.numen.sdk.Doc("What one numen.build.place did.")
    public record Placed(@com.dwinovo.numen.sdk.Doc("Cells it was given to do.") int requested,
                         @com.dwinovo.numen.sdk.Doc("Cells still to do.") int left,
                         @com.dwinovo.numen.sdk.Doc("Cells that now match.") int completed,
                         @com.dwinovo.numen.sdk.Doc("Blocks placed.") int placed,
                         @com.dwinovo.numen.sdk.Doc("Blocks that stood there and were swapped for the right one.")
                         int replaced,
                         @com.dwinovo.numen.sdk.Doc("Blocks broken to clear a cell that should be empty.") int cleared,
                         @com.dwinovo.numen.sdk.Doc("Blocks you placed there before that the file no longer has, "
                                 + "taken away.") int removed,
                         @com.dwinovo.numen.sdk.Doc("The building's name, house#1, for a blueprint.")
                         Optional<String> building,
                         @com.dwinovo.numen.sdk.Doc("Two corners of the site.") Optional<List<BlockPos>> site,
                         @com.dwinovo.numen.sdk.Doc("Cells something else broke while you built, put back.")
                         Optional<Integer> destroyedWhileBuilding,
                         @com.dwinovo.numen.sdk.Doc("When the last cell went in: cells that changed once the world "
                                 + "settled (vanilla would not hold them as drawn, or their neighbours reshape them).")
                         Optional<Integer> settledAway,
                         @com.dwinovo.numen.sdk.Doc("What you are short of.") Optional<String> stillShort) {

        /** 要的样子已经立着,什么都没放。 */
        public static final Placed NOTHING = new Placed(0, 0, 0, 0, 0, 0, 0, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
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
