package com.dwinovo.numen.task.build;

import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.task.move.Arrival;
import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.WorkProfile;
import com.dwinovo.numen.nav.Feet;
import com.dwinovo.numen.nav.Terrain;
import com.dwinovo.numen.task.base.AbstractCompanionTask;
import com.dwinovo.numen.task.base.Precondition;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多格建造任务:把施工图里手够得着的格一批一批落进世界,每一格轮到一次;够不着的格先走到够得着的地方,再放。
 *
 * <p><b>够不着就走过去</b>——每一格够不够得着与走过去的到达目标 {@link Arrival#place} 是同一个判据({@link BuildSurvey}):一遍把
 * 站在这里够得着的格放完,还有够不着的就走到够得着最低一层剩下的格的地方({@link #walkTo}),再放一遍,到放完或真到不了。挖开挡着的
 * 是 {@code numen.work.dig} 的事。受理之前看一眼:够得着的与走得过去的格一个都没有就当场拒绝,说清还差什么。
 *
 * <p><b>不挖</b>——生存模式下图纸要的格里立着别的方块,那一格由 {@code numen.work.dig} 挖开(挖的判据、工具、掉落、权限都是它的),
 * 这里不碰,回执里算进"要先挖开的";创造模式照原版一下就碎:图纸直接写上去顶掉原来的。
 *
 * <p><b>走一遍,每格只试一次</b>——一次调用把整份设计走一遍:低层先、贴附件后(托着它们的先立起来)、够不着的走过去放。每一格轮到
 * 一次,轮过就不回头;没放好的不是失败,走完一遍照常交回回执,按原因分组({@link BuildOutstanding})说清是什么挡住了、下一步做什么。
 * 再来一轮是脚本的事({@code numen.build.diff} 再调 {@code numen.build.place})。
 *
 * <p><b>放下就更新,世界说了算</b>——两种放法都带着原版的方块更新:邻居当场按原版规则反应(红石线连好、玻璃板与栅栏连上、楼梯
 * 算好形状),原版弹掉的(火把没有依附)就是没放住,如实进回执。
 *
 * <p>保留下来的是施工自己的事:生存模式逐格扣料、低层先放、生成摆设。
 *
 * <p><b>像人一样放</b>——每一格由 {@link BuildPlacer} 落进世界:她站在原地从点得中的面手放,生存模式一秒四格;手放做不出那个样子的
 * 格(悬空没有面可点、点得中的任何位置原版都放不成那个状态)直接写进去,回执的 {@code set_directly} 如实数着这些格。
 *
 * <p><b>施工与表演分开</b>——施工只管下一格放哪、放没放成、差什么;直接写的格上的转头、挥手、粒子归演出组件 {@link BuildShowmanship},
 * 它手里没有放置入口,改不了世界。
 *
 * <p><b>分工</b>——本类只持有施工的调度状态机(相位、遍、层窗口、落位循环)与轮扫对账;每一格的情形在 {@link BuildSurvey},
 * 单格判据在 {@link BuildCellRules},一格怎么落进世界在 {@link BuildPlacer},背包口径在 {@link BuildInventory},材料账本在
 * {@link BuildLedger},摆设与善后在 {@link BuildFixtures},演出在 {@link BuildShowmanship},顺序与节奏的纯函数在 {@link BuildOrder},
 * 收不了尾时的缺格清单在 {@link BuildOutstanding}。
 */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord, BuildCompanionTask.Placed> {

    /** CONSENT:开工前整批问主人;WALK:走到够得着剩下的格的地方;WORK:施工。 */
    private enum Phase { CONSENT, WALK, WORK }

    /** 干不下去时的结论:失败的理由与类型。 */
    private record Ending(String why, FailureType type) {}

    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;
    private final BuildLedger ledger;
    private final BuildPlacer placer;
    private final BuildShowmanship show;

    /** 这一次调用里已经轮到过的格:每格只试一次,轮过就不回头。 */
    private final LongOpenHashSet tried = new LongOpenHashSet();
    /** 轮到了却没放好的格(位置 → 原因与细节);没轮到的格的原因收场时按此刻的情形现判。 */
    private final Map<Long, Miss> missed = new LinkedHashMap<>();
    /** 为它们走过却走不到的格:不再为它们走。 */
    private final LongOpenHashSet unwalkable = new LongOpenHashSet();
    /** 上一次出发去走的时候已经轮到过几格:走到之后一格新的也没轮到,就是走了也到不了,不再走。 */
    private int triedWhenWalking = -1;
    /** 走去放时没走成的缘由(没有路、被拒、超时);没出过事是 null。 */
    private String walkFailure;

    /** 一格轮到了却没放好。 */
    private record Miss(BuildOutstanding.Cause cause, String detail) {}

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

    private String note = "done";

    /** 正在走去放的那些格(够不着的最低一层);没在走是 null。 */
    private List<BlockPos> walkingTo;

    /** 开工前要问主人的清单(这一次够得着的格里裁决为要问的)。 */
    private List<com.dwinovo.numen.api.permission.ConsentItem> consentItems = List.of();

    public BuildCompanionTask(NumenPlayer player, BuildTaskRecord record) {
        super(player, record);
        this.rules = new BuildCellRules(player, record);
        this.inv = new BuildInventory(player);
        this.fixtures = new BuildFixtures(player, record);
        this.ledger = new BuildLedger(record, rules, inv);
        BlockPos[] box = siteBox(player, record.targets);
        this.siteMin = box[0];
        this.siteMax = box[1];
        this.placer = new BuildPlacer(player, inv, record.consumeMaterials);
        this.show = new BuildShowmanship(player, inv);
    }

    /** 按她此刻站的地方数这件活的每一格。 */
    private BuildSurvey survey() {
        return new BuildSurvey(player, r, rules, inv);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(this::checkMaterials, this::checkReach);
    }

    /**
     * 还有没了结的格,却既够不着也走不到(都得先挖开、缺料、立不住):当场拒绝,说清还剩什么、先做什么。够得着的与够不着的(走过去再放)
     * 都受理。全都对上了不拒:这一趟照常收尾(挂件),回执说什么都没变。
     */
    private Precondition.Failure checkReach() {
        BuildSurvey.Tally tally = survey().tally();
        if (tally.count(BuildSurvey.State.REACH) > 0 || !tally.far().isEmpty() || tally.left() == 0) {
            return null;
        }
        int unheld = tally.count(BuildSurvey.State.UNHELD);
        if (unheld == tally.left()) {
            // 剩下的全都放下去立不住:不是走过去的事
            return new Precondition.Failure(unheld + " cell(s) would not stay where the design puts them: nothing "
                    + "holds them there.", FailureType.NO_SUPPORT);
        }
        String hint = !tally.dig().isEmpty() ? Call.of("numen.work.dig", tally.dig().get(0)) : null;
        return new Precondition.Failure("nothing of it to place yet. " + remaining(tally), FailureType.OUT_OF_REACH,
                hint);
    }

    /**
     * 还剩什么、下一步照抄什么:够不着的几格与最低最近的一格(走过去的那一行),要先挖开的几格与最近的一格(挖它的那一行),
     * 缺料的几格。什么都不剩是 "nothing left to do here"。
     */
    private static String remaining(BuildSurvey.Tally tally) {
        List<String> parts = new ArrayList<>();
        int reach = tally.count(BuildSurvey.State.REACH);
        if (reach > 0) {
            // 每格轮到一次:撑着它们的这一次才立起来、或这一次没放进去的,下一次放
            parts.add(reach + " cell(s) within reach go in on the next numen.build.place");
        }
        if (!tally.far().isEmpty()) {
            BlockPos next = tally.far().get(0);
            parts.add(tally.far().size() + " cell(s) out of reach — the lowest nearest is " + xyz(next)
                    + ", which numen.build.place walks up to");
        }
        if (!tally.dig().isEmpty()) {
            BlockPos next = tally.dig().get(0);
            parts.add(tally.dig().size() + " cell(s) hold another block that must be dug out first — the nearest is "
                    + xyz(next) + ": numen.work.dig(" + lua(next) + ")");
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
     * 开工盘料:一格都付不起才当场拒绝(什么也开始不了);付得起的就放,付不起的格进回执的缺料分组。
     */
    private Precondition.Failure checkMaterials() {
        if (!r.consumeMaterials || canPayForAnyPendingCell()) {
            return null;
        }
        Map<Item, Integer> shortfall = ledger.shortfallAgainstInventory(ledger.remainingNeed());
        return new Precondition.Failure(
                "not enough materials to place any of it — " + BuildLedger.summarizeShortfall(shortfall)
                        + ". Nothing was placed. Survival mode consumes 1 item per cell; gather these, "
                        + "then call it again — anything already standing is skipped.",
                FailureType.NO_MATERIAL);
    }

    @Override
    protected void onStart() {
        observedCompleted = new LongOpenHashSet();
        skippedPos = new LongOpenHashSet();
        computePace();
        beginPass();
    }

    /**
     * 开一遍:在她此刻站的地方重扫、重排够得着的格,问主人要问的。开工时与每次走到新的地方之后各开一遍;手边一格都够不着
     * 就是一遍空的,收遍时接着走。
     */
    private void beginPass() {
        rescanAll();
        rebuildOrder();
        // 每一站有自己的一份期限:一次调用走几站(走路的时间不算,子活跑时这件活的期限冻着)
        r.extendDeadlineTo(player.level().getGameTime()
                + BuildOrder.deadlineTicks(r.targets.size(), r.consumeMaterials));
        collectConsent();
        phase = consentItems.isEmpty() ? Phase.WORK : Phase.CONSENT;
        placeCredit = 0;
    }

    /**
     * 施工前把这一次够得着的格整批过一遍权限层,裁决为要问的合成一张卡。放行的照建;不许的(规则、模式、外部强制)由
     * {@link BuildCellRules#blockedByMode} 照常跳过。
     */
    private void collectConsent() {
        var gate = com.dwinovo.numen.api.permission.Permission.gateFor(player);
        BuildSurvey survey = survey();
        List<com.dwinovo.numen.api.permission.ConsentItem> items = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (survey.of(target) != BuildSurvey.State.REACH) {
                continue;
            }
            for (com.dwinovo.numen.api.permission.Action action : rules.actionsFor(target)) {
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
        if (phase == Phase.WALK) {
            return tickWalk();
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

    /**
     * 走到够得着剩下的格的地方:走完了在新的地方开一遍;走不到(没路、被拒、超时)就带着那一趟的原因收场,放下的照实交代。
     */
    private TaskState tickWalk() {
        TaskResult walked = walkTo(Arrival.place(walkingTo));
        if (walked == null) {
            return TaskState.RUNNING;
        }
        List<BlockPos> failedFor = walkingTo;
        walkingTo = null;
        if (!walked.success()) {
            // 走不到不是失败:放下的照实交代,够不着的进回执;为这一格走不通,别的格还接着走
            walkFailure = walked.message();
            unwalkable.addAll(failedFor.stream().map(BlockPos::asLong).toList());
            triedWhenWalking = -1;
            return endPass();
        }
        beginPass();
        return TaskState.RUNNING;
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
     * 落一批:在<b>当前最低的未完成层</b>里,挑离她最远的几格;这一层轮完了接着翻下一层,预算用完或者这一遍到头为止。
     * 顺序低层优先(上面的东西得有底下的东西撑着),层内由远及近(见 {@link #farthestPendingInLayer})。
     */
    private TaskState runBatch(int budget) {
        List<BlockPos> written = new ArrayList<>();
        BlockState sample = null;
        boolean passOver = false;
        while (budget > 0) {
            BuildTaskRecord.Target target = farthestPendingInLayer();
            if (target == null) {
                // 这一层还有没轮到的格(站在里面的、够不着的):先收站、走去放了再往上走,不然她在坑里,墙一层层盖过头顶
                if (layerHasUntried() || !advanceLayer()) {
                    passOver = true;
                    break;
                }
                continue;
            }
            BlockState placed = processCell(target, written);
            if (placed != null) {
                budget--;
                placeCredit -= 1.0;
                sample = placed;
            }
        }
        if (!written.isEmpty()) {
            show.performWork(written, sample);
        }
        if (passOver) {
            return endPass();
        }
        return TaskState.RUNNING;
    }

    /**
     * 当前层里离她最远、这一层还没轮到过的一格待建;这一层都轮到过了是 null。
     *
     * <p>{@code placedThisLayer} 只保证"这一层每格都轮到过一次",不决定顺序;真正的顺序由距离决定,先放远的:手放要点
     * 邻格的面,先放近处的格会把站在这边看远处面的视线挡住,人砌一整片也是从远处往自己这边退着砌。
     */
    private BuildTaskRecord.Target farthestPendingInLayer() {
        BuildTaskRecord.Target best = null;
        int bestStage = Integer.MAX_VALUE;
        double bestDist = -1;
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
            if (stage < bestStage || d > bestDist) {
                bestStage = stage;
                bestDist = d;
                best = t;
            }
        }
        if (best != null) {
            placedThisLayer.add(best.pos().asLong());
            tried.add(best.pos().asLong());
        }
        return best;
    }

    /** 当前层所在的那一层高度上,有没有还没轮到过、也没对上、也不是主动不去动的格。 */
    private boolean layerHasUntried() {
        if (layerStart >= order.size()) {
            return false;
        }
        int y = order.get(layerStart).pos().getY();
        for (BuildTaskRecord.Target t : r.targets) {
            if (t.pos().getY() == y && !tried.contains(t.pos().asLong()) && !unwalkable.contains(t.pos().asLong())
                    && !t.matches(rules.peek(t.pos())) && !rules.refused(t) && !rules.hopeless(t)) {
                return true;
            }
        }
        return false;
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
     * @param written 手没有放、由本方法直接写进世界的格往这里记(直接写的落位与创造模式清掉的格),交给演出
     * @return 落位后的期望状态(有产出);null = 本遍先放下(已达标/缺料/她自己
     *         正站在这格里)
     */
    private BlockState processCell(BuildTaskRecord.Target target, List<BlockPos> written) {
        BlockPos pos = target.pos();
        BlockState desired = target.desiredState();
        // 加载判定要在读取<b>之前</b>:反过来的话第一句 getBlockState 就已经把区块
        // 同步生成出来了,后面这句永远为真,等于没判。
        if (!player.level().isLoaded(pos)) {
            return missed(pos, BuildOutstanding.Cause.NOT_LOADED, null);   // 区块这一刻没加载
        }
        BlockState current = player.level().getBlockState(pos);
        if (target.matches(current)) {
            markObserved(target, true);
            return null;
        }
        if (BuildCellRules.leavesLiquid(target, current)) {
            return null;   // 不放液体、也排不掉:不去动,回执单列 liquid
        }

        boolean occupied = !current.isAir() && !(current.getBlock() instanceof LiquidBlock);

        // 先过完所有门禁,再动手破坏。此前顺序是反的:先 clear 掉挡路的方块,再去
        // 检查活物占位与材料——于是"目标砖用完了"这种最常见的情形下,她会把玩家的
        // 草坪挖出一条沟,然后一格墙都没砌。她自己站在那格里时更糟:脚下先被挖空。
        // 破坏是不可撤销的,所以它必须是这一格的最后一道动作,不是第一道。
        if (rules.blockedByEntity(pos, desired)) {
            // 谁都不豁免——包括她自己:身体占着的格子不放。防的是把方块塞进活物身体里这类真事故。
            return missed(pos, BuildOutstanding.Cause.BLOCKED_BY_ENTITY, whoStands(pos));
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
        if (cost > 0 && !inv.hasItems(target.item(), cost)) {
            return missed(pos, BuildOutstanding.Cause.SHORT_OF_MATERIAL, null);
        }

        if (occupied) {
            if (!WorkProfile.of(player).instaBreak()) {
                // 生存:立着的方块由 numen.work.dig 挖开,这里不挖,回执算进"要先挖开的"
                return missed(pos, BuildOutstanding.Cause.DIG_FIRST, null);
            }
            // 创造:原版一下就碎——写成空气,邻居照原版反应(贴着它的火把掉下来),所以这一次通知邻居;要放方块的接着落位
            if (!player.level().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL)) {
                return missed(pos, BuildOutstanding.Cause.REFUSED, null);
            }
            r.brokeOne();
            if (BuildCellRules.isAirTarget(target)) {
                markObserved(target, true);
                written.add(pos);
                return desired;
            }
        }
        if (BuildCellRules.isAirTarget(target)) {
            markObserved(target, current.isAir());
            return null;
        }

        // 落没落成由 BuildPlacer 看世界判:手放被拒(保护、事件被取消)与直接写不进去(超出建造高度)都是世界没变,不记账。
        BuildPlacer.Way way = placer.place(target, cost);
        if (way == null) {
            return missed(pos, BuildOutstanding.Cause.REFUSED, null);
        }
        BlockState now = player.level().getBlockState(pos);
        if (way == BuildPlacer.Way.SET_DIRECTLY) {
            written.add(pos);
        }
        r.placedOne(occupied, way == BuildPlacer.Way.SET_DIRECTLY);
        if (!target.matches(now)) {
            // 放下去了,原版随即弹掉或改了样子:以世界为准,不算完成、不记账,这一次也不重放。动过手,算这一刻的预算
            missed.put(pos.asLong(), new Miss(BuildOutstanding.Cause.DID_NOT_STAY, whyNotStay(target, now)));
            return desired;
        }
        markObserved(target, true);
        return now;
    }

    /** 这一格轮到了却没放好:记下原因,本次调用不再回头;没产出。 */
    private BlockState missed(BlockPos pos, BuildOutstanding.Cause cause, String detail) {
        missed.put(pos.asLong(), new Miss(cause, detail));
        return null;
    }

    /** 站在这一格里的是谁:她自己、或身份格式的某只实体;认不出是空。 */
    private String whoStands(BlockPos pos) {
        for (net.minecraft.world.entity.Entity e : player.level().getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(pos), e -> !e.isSpectator())) {
            return e == player ? "you are standing in it" : com.dwinovo.numen.api.sdk.EntityLabel.of(e) + " is in it";
        }
        return null;
    }

    /**
     * 放下去的 {@code target} 此刻在世界里是 {@code now},为什么没留住——只说服务端此刻知道的:原版的存活判断(canSurvive)过不过、
     * 周围六格是什么;样子被改了就说改成了什么。
     */
    private String whyNotStay(BuildTaskRecord.Target target, BlockState now) {
        BlockState wanted = target.desiredState();
        BlockPos pos = target.pos();
        String id = BuildOutstanding.name(wanted.getBlock());
        String at = com.dwinovo.numen.api.permission.Listing.coords(pos);
        if (now.getBlock() == wanted.getBlock()) {
            return id + " at " + at + " was reshaped by the world into " + now;
        }
        if (!wanted.canSurvive(player.level(), pos)) {
            List<String> around = new ArrayList<>();
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                BlockPos next = pos.relative(side);
                around.add(side.getName() + " " + com.dwinovo.numen.api.permission.Listing.coords(next) + " is "
                        + BuildOutstanding.name(player.level().getBlockState(next).getBlock()));
            }
            return id + " at " + at + " cannot stay there — vanilla dropped it because nothing holds it (" + String.join(", ", around)
                    + ")";
        }
        return id + " at " + at + " was turned into " + BuildOutstanding.name(now.getBlock()) + " by the world";
    }

    /**
     * 没有待建的格(都对上了、都不去动)算付得起——那不是缺料,别的判据会说;有待建的格,其中有一格她此刻付得起也算。
     *
     * <p>逐格问而不是拿"总需求 vs 背包"的聚合缺口来推:聚合缺口回答的是"全部建完还差多少",而这里要回答的是"能不能开始放"。
     * 她可能凑不齐整栋楼,却还能砌十堵墙。
     */
    private boolean canPayForAnyPendingCell() {
        BuildSurvey survey = survey();
        boolean pending = false;
        for (BuildTaskRecord.Target target : r.targets) {
            if (target.matches(rules.peek(target.pos()))) continue;
            if (rules.blockedByMode(target) || rules.hopeless(target)) continue;
            pending = true;
            if (survey.affordable(target)) {
                return true;
            }
        }
        return !pending;
    }

    /**
     * 这一站放完了:整份都对上了就收工;还有够不着的,走到够得着最低一层里最远那一格的地方,在那里再放一站(轮过的格不再轮);没有就收场,
     * 没放好的照实进回执。
     */
    private TaskState endPass() {
        // 收站要判完工,这一次必须精确——每刻那次只轮扫一片
        rescanAll();
        if (r.completed() + skippedCells >= r.targets.size()) {
            return conclude(null);
        }
        BuildSurvey survey = survey();
        List<BuildTaskRecord.Target> pendingNow = pendingHere();
        // 付不起的格走过去也放不了,不为它们走路(回执按缺料说)
        List<BlockPos> far = survey.tally().far().stream()
                .filter(p -> survey.affordable(r.targetAt(p)) && !unwalkable.contains(p.asLong())).toList();
        int lowestHere = pendingNow.stream().mapToInt(t -> t.pos().getY()).min().orElse(Integer.MAX_VALUE);
        boolean lowerFar = !far.isEmpty() && far.get(0).getY() < lowestHere;
        // 这一站放下的方块让原来点不着的格有了能点的面:在原地再放一站,只放没轮到过的——但更低一层还有够不着的格就先去放那些
        if (!pendingNow.isEmpty() && !lowerFar) {
            beginPass();
            return TaskState.RUNNING;
        }
        // 上一趟走过去之后一格新的也没轮到:再走也是原地,剩下的算够不着
        if (far.isEmpty() || tried.size() == triedWhenWalking) {
            if (!pendingNow.isEmpty()) {
                beginPass();
                return TaskState.RUNNING;
            }
            return conclude(null);
        }
        // 最低一层里离她最远的那一格:站到够得着它的地方,在那里先放远处够得着的,再退回来——近处的格留到最后放,
        // 站在这边点远处邻格的视线才不被先放下的近格挡住
        int lowest = far.get(0).getY();
        Vec3 here = player.position();
        BlockPos farthest = far.stream().filter(p -> p.getY() == lowest)
                .max(java.util.Comparator.comparingDouble(p -> p.distToCenterSqr(here.x, here.y, here.z))).orElseThrow();
        walkingTo = List.of(farthest);
        triedWhenWalking = tried.size();
        phase = Phase.WALK;
        return TaskState.RUNNING;
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
     * 走完一遍。整份都对上了才收尾:生成摆设、外围补水,放一把庆祝的粒子;还剩格时只交代没放好的格与原因,摆设留给放全那一次。
     */
    private TaskState finish() {
        player.controls().stop();
        if (r.completed() + skippedCells < r.targets.size()) {
            note = BuildOutstanding.sentence(outstanding());
            return TaskState.SUCCESS;
        }
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
        order = pendingHere();
        resetLayerWindow();
    }

    /** 此刻站的地方没轮到过、够得着的格,按施工顺序排好。 */
    private List<BuildTaskRecord.Target> pendingHere() {
        BuildSurvey survey = survey();
        List<BuildTaskRecord.Target> pending = new ArrayList<>();
        LongOpenHashSet reach = new LongOpenHashSet();
        for (BuildTaskRecord.Target target : r.targets) {
            if (survey.of(target) == BuildSurvey.State.REACH && !rules.blockedByMode(target)
                    && !tried.contains(target.pos().asLong())) {
                pending.add(target);
                reach.add(target.pos().asLong());
            }
        }
        // 托着它的还没立起来、放下去立不住的格:托着它的那一格也在这一站里放,才收进来(低层先、贴附件后,轮到它时托着它的已经
        // 立起来了);托着它的在够不着的地方,等走过去之后的那一站
        for (BuildTaskRecord.Target target : r.targets) {
            if (survey.of(target) != BuildSurvey.State.UNHELD || tried.contains(target.pos().asLong())
                    || rules.blockedByMode(target) || !survey.reaches(target.pos())) {
                continue;
            }
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                if (reach.contains(target.pos().relative(side).asLong())) {
                    pending.add(target);
                    break;
                }
            }
        }
        pending.sort(BuildOrder.BUILD_ORDER);
        return pending;
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
        com.dwinovo.numen.Constants.LOG.debug(
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
            if (target.matches(observed)) {
                // 要放液体、要排水的格不在这里:它们是"不去动"({@code rules.refused}),从分母里摘出、回执单列 liquid;
                // 固体目标被液体淹着照放,方块直接顶掉水(原版语义)
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
                    missed.put(key, new Miss(BuildOutstanding.Cause.DID_NOT_STAY,
                            BuildOutstanding.name(target.desiredState().getBlock()) + " at "
                                    + com.dwinovo.numen.api.permission.Listing.coords(pos)
                                    + " was in place and something removed it"));
                }
            }
        }
        skippedCells = skippedPos.size();
        r.completed(observedCompleted.size());
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
                r.setDirectly(), outstanding(), r.replaced(), r.broken(),
                siteMin == null ? Optional.empty() : Optional.of(List.of(siteMin, siteMax)),
                // 施工期间被外力拆毁又补回去的格数:她盖得慢或反复返工,原因在这儿
                damagedCells > 0 ? Optional.of(damagedCells) : Optional.empty(),
                shortfall.isEmpty() ? Optional.empty() : Optional.of(BuildLedger.summarizeShortfall(shortfall)));
    }

    /** {@code numen.build.place} 交回的值。 */
    @com.dwinovo.numen.api.sdk.Doc("What one numen.build.place did.")
    public record Placed(@com.dwinovo.numen.api.sdk.Doc("Cells it was given to do.") int requested,
                         @com.dwinovo.numen.api.sdk.Doc("Cells still to do.") int left,
                         @com.dwinovo.numen.api.sdk.Doc("Cells that now match.") int completed,
                         @com.dwinovo.numen.api.sdk.Doc("Blocks placed.") int placed,
                         @com.dwinovo.numen.api.sdk.Doc("Of those placed, the ones written straight into the world "
                                 + "because a hand could not put them in that shape from where you stand (nothing to "
                                 + "click against, or no click gives that state).") int setDirectly,
                         @com.dwinovo.numen.api.sdk.Doc("The cells that were not placed, grouped by what stopped them, "
                                 + "each with a sentence on what it was and a next step; empty when everything went in. "
                                 + "Each cell got one turn in this call.") List<BuildOutstanding.Unplaced> unplaced,
                         @com.dwinovo.numen.api.sdk.Doc("Blocks that stood there and were swapped for the right one.")
                         int replaced,
                         @com.dwinovo.numen.api.sdk.Doc("Blocks broken to clear a cell that should be empty.") int cleared,
                         @com.dwinovo.numen.api.sdk.Doc("Two corners of the site.") Optional<List<BlockPos>> site,
                         @com.dwinovo.numen.api.sdk.Doc("Cells something else broke while you built, put back.")
                         Optional<Integer> destroyedWhileBuilding,
                         @com.dwinovo.numen.api.sdk.Doc("What you are short of.") Optional<String> stillShort) {

        /** 要的样子已经立着,什么都没放。 */
        public static final Placed NOTHING = new Placed(0, 0, 0, 0, 0, List.of(), 0, 0, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /**
     * 此刻没放好的格,按原因分组:轮到过的用当时记下的原因,没轮到的按此刻的情形现判({@link BuildSurvey.State})。
     * 被摘出分母的(让路档位不许、权限不许)是 {@code left_alone}。
     */
    private List<BuildOutstanding.Unplaced> outstanding() {
        BuildSurvey survey = survey();
        List<BuildOutstanding.Cell> cells = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            BlockPos pos = target.pos();
            BlockState actual = rules.peek(pos);
            BuildSurvey.State state = survey.of(target);
            if (state == BuildSurvey.State.DONE) {
                if (target.heldWater()) {
                    cells.add(new BuildOutstanding.Cell(BuildOutstanding.Cause.LIQUID, target, actual, BuildOutstanding.LIQUID_HELD));
                }
                continue;
            }
            Miss miss = missed.get(pos.asLong());
            BuildOutstanding.Cause cause;
            String detail = null;
            if (miss != null) {
                cause = miss.cause();
                detail = miss.detail();
            } else if (target.noItem()) {
                cause = BuildOutstanding.Cause.NO_ITEM;
                detail = com.dwinovo.numen.build.BuildStates.noItemReason(target.block());
            } else if (state == BuildSurvey.State.LIQUID) {
                cause = BuildOutstanding.Cause.LIQUID;
                detail = target.liquid() ? BuildOutstanding.LIQUID_PLACE : BuildOutstanding.LIQUID_DRAIN;
            } else if (state == BuildSurvey.State.SKIPPED) {
                cause = BuildOutstanding.Cause.LEFT_ALONE;
            } else if (state == BuildSurvey.State.DIG) {
                cause = BuildOutstanding.Cause.DIG_FIRST;
            } else if (state == BuildSurvey.State.SHORT) {
                cause = BuildOutstanding.Cause.SHORT_OF_MATERIAL;
            } else if (!survey.affordable(target)) {
                cause = BuildOutstanding.Cause.SHORT_OF_MATERIAL;
            } else if (state == BuildSurvey.State.UNHELD) {
                cause = BuildOutstanding.Cause.NOTHING_HOLDS_IT;
            } else if (state == BuildSurvey.State.FAR) {
                cause = BuildOutstanding.Cause.OUT_OF_REACH;
                detail = walkFailure == null ? null : "the walk there failed: " + walkFailure;
            } else {
                cause = BuildOutstanding.Cause.UNFINISHED;
            }
            cells.add(new BuildOutstanding.Cell(cause, target, actual, detail));
        }
        Map<Item, Integer> shortfall = r.consumeMaterials
                ? ledger.shortfallAgainstInventory(ledger.remainingNeed()) : Map.of();
        return BuildOutstanding.report(cells, shortfall.isEmpty() ? "" : BuildLedger.summarizeShortfall(shortfall));
    }

    /** 放了、换了、拆了多少格,这一句收工与收不了工都用。 */
    private String tally() {
        StringBuilder sb = new StringBuilder("placed ").append(r.placed());
        if (r.setDirectly() > 0) {
            sb.append(" (").append(r.setDirectly()).append(" written straight in: a hand could not put them in that "
                    + "shape from where you stand)");
        }
        if (r.replaced() > 0) {
            sb.append(" (").append(r.replaced()).append(" replacing what stood there)");
        }
        sb.append(", cleared ").append(r.broken());
        return sb.toString();
    }

    @Override
    protected String successMessage() {
        return "built " + r.completed() + "/" + r.targets.size()
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
