package com.dwinovo.numen.plugins.minecraft.task.build;

import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.plugins.minecraft.FailureType;
import com.dwinovo.numen.plugins.minecraft.WorkProfile;
import com.dwinovo.numen.plugins.minecraft.nav.Feet;
import com.dwinovo.numen.plugins.minecraft.nav.Terrain;
import com.dwinovo.numen.plugins.minecraft.task.base.AbstractCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.base.Precondition;
import com.dwinovo.numen.api.entity.BlockWriter;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskState;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多格建造任务:她站在原地,把施工图里的格一格一格落进世界,每一格轮到一次。
 *
 * <p><b>不走、不看距离</b>——放一格不问她离它多远、中间隔着什么({@link BuildPlacer}),所以施工里没有走路:她站在哪儿开工就
 * 站在哪儿放完。受理之前看一眼:此刻放得进去的格一个都没有(都得先挖开、缺料)就当场拒绝,说清还差什么。
 *
 * <p><b>不挖</b>——生存模式下图纸要的格里立着别的方块,那一格由 {@code minecraft.work.dig} 挖开(挖的判据、工具、掉落、权限都是它的),
 * 这里不碰,回执里算进"要先挖开的";创造模式照原版一下就碎:图纸直接写上去顶掉原来的。
 *
 * <p><b>走一遍</b>——一次调用把整份设计走一遍({@link BuildQueue}):低层先、层内就近({@link BuildOrder#from}),每刻最多落
 * {@value BuildOrder#CELLS_PER_TICK} 格。每一格轮到时先问方块自己站不站得住;站不住的排到队尾、别的都放完再问,只重试这一次,
 * 第二次还站不住就放弃,回执说这一格留不住、周围是什么。没放好的不是失败,走完一遍照常交回回执,按原因分组
 * ({@link BuildOutstanding})说清是什么挡住了、下一步做什么。再来一轮是脚本的事({@code minecraft.build.diff} 再调
 * {@code minecraft.build.place})。
 *
 * <p><b>放下就更新,世界说了算</b>——每一格由 {@link BuildPlacer} 直接写进世界,邻居当场按各自的规则反应(红石线连好、玻璃板与
 * 栅栏连上、楼梯算好形状);放下之后原版弹掉的,收工对图纸时如实进回执。
 *
 * <p>保留下来的是施工自己的事:生存模式逐格扣料、生成摆设。
 *
 * <p><b>施工与表演分开</b>——施工只管下一格放哪、放没放成、差什么;朝落下的格转头、挥手、粒子归演出组件 {@link BuildShowmanship},
 * 它手里没有放置入口,改不了世界。
 *
 * <p><b>分工</b>——本类只持有施工的调度状态机(相位、遍、落位循环)与轮扫对账;每一格的情形在 {@link BuildSurvey},
 * 单格判据在 {@link BuildCellRules},一格怎么落进世界在 {@link BuildPlacer},背包口径在 {@link BuildInventory},材料账本在
 * {@link BuildLedger},摆设与善后在 {@link BuildFixtures},演出在 {@link BuildShowmanship},顺序与节奏的纯函数在 {@link BuildOrder},
 * 收不了尾时的缺格清单在 {@link BuildOutstanding}。
 */
public final class BuildCompanionTask extends AbstractCompanionTask<BuildTaskRecord, BuildCompanionTask.Placed> {

    /** CONSENT:开工前整批问主人;WORK:施工。 */
    private enum Phase { CONSENT, WORK }

    /** 干不下去时的结论:失败的理由与类型。 */
    private record Ending(String why, FailureType type) {}

    private final BuildCellRules rules;
    private final BuildInventory inv;
    private final BuildFixtures fixtures;
    private final BuildLedger ledger;
    private final BuildPlacer placer;
    private final BuildShowmanship show;

    /** 轮到了却没放好的格(位置 → 原因与细节);没轮到的格的原因收场时按此刻的情形现判。 */
    private final Map<Long, Miss> missed = new LinkedHashMap<>();

    /** 一格轮到了却没放好。 */
    private record Miss(BuildOutstanding.Cause cause, String detail) {}

    private LongOpenHashSet observedCompleted;
    private Phase phase = Phase.WORK;

    /** 工地包围盒(全体目标格的最小/最大角)。 */
    private final BlockPos siteMin;
    private final BlockPos siteMax;

    /** 这一次要放的格,按施工顺序({@link BuildOrder#from})排好;开工时建。 */
    private BuildQueue<BuildTaskRecord.Target> queue;
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

    /** 开工前要问主人的清单(这一次要放的格里裁决为要问的)。 */
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
        this.placer = new BuildPlacer(player, inv);
        this.show = new BuildShowmanship(player, inv);
    }

    /** 按此刻的世界与背包数这件活的每一格。 */
    private BuildSurvey survey() {
        return new BuildSurvey(player, r, rules, inv);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(this::checkMaterials, this::checkPlaceable);
    }

    /**
     * 还有没了结的格,却没有一格此刻放得进去(都得先挖开、缺料):当场拒绝,说清还剩什么、先做什么。全都对上了不拒:
     * 这一趟照常收尾(挂件),回执说什么都没变。
     */
    private Precondition.Failure checkPlaceable() {
        BuildSurvey.Tally tally = survey().tally();
        if (tally.count(BuildSurvey.State.READY) > 0 || tally.left() == 0) {
            return null;
        }
        String hint = !tally.dig().isEmpty() ? Call.of("minecraft.work.dig", tally.dig().get(0)) : null;
        return new Precondition.Failure("nothing of it to place yet. " + remaining(tally), FailureType.UNKNOWN, hint);
    }

    /** 还剩什么、下一步照抄什么:要先挖开的几格与最近的一格(挖它的那一行),缺料的几格。 */
    private static String remaining(BuildSurvey.Tally tally) {
        List<String> parts = new ArrayList<>();
        if (!tally.dig().isEmpty()) {
            BlockPos nearest = tally.dig().get(0);
            parts.add(tally.dig().size() + " cell(s) hold another block that must be dug out first — the nearest is "
                    + nearest.getX() + " " + nearest.getY() + " " + nearest.getZ() + ": minecraft.work.dig("
                    + LuaCodecs.literal(nearest) + ")");
        }
        int shortCells = tally.count(BuildSurvey.State.SHORT);
        if (shortCells > 0) {
            parts.add(shortCells + " cell(s) hold another block and you carry nothing to put there");
        }
        return "Still to do: " + String.join("; ", parts) + ".";
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
        rescanAll();
        collectConsent();
        if (consentItems.isEmpty()) {
            beginWork();
        } else {
            phase = Phase.CONSENT;
        }
    }

    /** 开工:把此刻放得进去的格排成队。 */
    private void beginWork() {
        queue = new BuildQueue<>(pendingNow(), BuildOrder.from(player.position()));
        phase = Phase.WORK;
    }

    /**
     * 施工前把这一次要放的格(放得进去的)整批过一遍权限层,裁决为要问的合成一张卡。放行的照建;不许的
     * (规则、模式、外部强制)由 {@link BuildCellRules#blockedByMode} 照常跳过。
     */
    private void collectConsent() {
        var gate = com.dwinovo.numen.api.permission.Permission.gateFor(player);
        BuildSurvey survey = survey();
        List<com.dwinovo.numen.api.permission.ConsentItem> items = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            BuildSurvey.State state = survey.of(target);
            if (state != BuildSurvey.State.READY) {
                continue;
            }
            for (com.dwinovo.numen.api.permission.Action action : rules.actionsFor(target)) {
                var verdict = gate.judgeLive(action, player.serverLevel());
                if (verdict.asks()) {
                    items.add(gate.consentItem(action, verdict));
                }
            }
        }
        consentItems = List.copyOf(items);
    }

    /**
     * 等主人答复:身体站住。答应了——那些格从此是放行,重扫后开工;拒绝了——这件活停在这里,回执是他的原话与问的是什么,
     * 一格不放(与 {@code minecraft.work.dig} 被拒同一个收场)。
     */
    private TaskState tickConsent() {
        player.controls().stop();
        var answer = gate().consult(consentItems);
        if (answer == null) {
            return TaskState.RUNNING;
        }
        if (!answer.allowed()) {
            return conclude(new Ending(answer.refusal(consentItems), FailureType.REFUSED));
        }
        consentItems = List.of();
        rescanAll();
        beginWork();
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

    /** 施工的一刻:身体站着,从队头取,最多落 {@value BuildOrder#CELLS_PER_TICK} 格。 */
    private TaskState tickWork() {
        TaskState state = runBatch(BuildOrder.CELLS_PER_TICK);
        show.stand();
        return state;
    }

    /**
     * 落一批:从队头一格一格取,预算用完或者队列空了为止。没产出的格(已达标、缺料、她自己站在里面、站不住排到了队尾)不占预算,
     * 接着取下一格。
     */
    private TaskState runBatch(int budget) {
        List<BlockPos> landed = new ArrayList<>();
        BlockState sample = null;
        while (budget > 0 && !queue.isEmpty()) {
            BlockState placed = processCell(queue.next(), landed);
            if (placed != null) {
                budget--;
                sample = placed;
            }
        }
        if (!landed.isEmpty()) {
            show.performWork(landed, sample);
        }
        return queue.isEmpty() ? finishPass() : TaskState.RUNNING;
    }

    /**
     * 处理一格。
     *
     * @param landed 这一格落下了(写进去、创造模式清掉)就往这里记,交给演出
     * @return 落位后的期望状态(有产出);null = 这一格先放下(已达标/缺料/她自己正站在这格里/站不住排到了队尾)
     */
    private BlockState processCell(BuildTaskRecord.Target target, List<BlockPos> landed) {
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
        if (rules.cannotBePlaced(target) || rules.hopeless(target)) {
            return null;
        }
        // 一格不一定只花一件(双层砖两件、雪层按层数),盘点与实扣共用同一个件数
        int cost = r.consumeMaterials && rules.costsMaterial(target) ? target.materialCount() : 0;
        if (cost > 0 && !inv.hasItems(target.item(), cost)) {
            return missed(pos, BuildOutstanding.Cause.SHORT_OF_MATERIAL, null);
        }

        if (occupied && !WorkProfile.of(player).instaBreak()) {
            // 生存:立着的方块由 minecraft.work.dig 挖开,这里不挖,回执算进"要先挖开的"
            return missed(pos, BuildOutstanding.Cause.DIG_FIRST, null);
        }

        // 问方块自己站不站得住,只在这一格轮到的时候问这一处;问在创造顶掉原来的东西之前,站不住就不白拆。
        // 站不住的第一次排到队尾,等别的格先放完;第二次还站不住就放弃,没扣过料
        if (!BuildCellRules.isAirTarget(target) && !placer.canStay(target)) {
            if (queue.later(target)) {
                return null;
            }
            return missed(pos, BuildOutstanding.Cause.NOTHING_HOLDS_IT, whyNotStay(target, current));
        }

        if (occupied) {
            // 创造:原版一下就碎——手把它写成空气,邻居照原版反应(贴着它的火把掉下来);要放方块的接着落位。
            // 主人不许、要问的(开工前整批问过之后仍冒出来的),这一格不动,和施工前复查跳过的一样留在待建里
            BlockWriter.Write cleared = player.writer().clear(pos);
            if (cleared instanceof BlockWriter.Write.Refused) {
                return null;
            }
            if (cleared instanceof BlockWriter.Write.Unchanged) {
                return missed(pos, BuildOutstanding.Cause.REFUSED, null);
            }
            r.brokeOne();
            if (BuildCellRules.isAirTarget(target)) {
                markObserved(target, true);
                landed.add(pos);
                return desired;
            }
        }
        if (BuildCellRules.isAirTarget(target)) {
            markObserved(target, current.isAir());
            return null;
        }

        // 落没落成由她的手(经 BuildPlacer)看世界判:写不进去(超出建造高度)是世界没变,不记账;主人不许、要问的这一格不动
        BuildPlacer.Placement placement = placer.place(target, cost);
        if (placement == null || placement.write() instanceof BlockWriter.Write.Unchanged) {
            return missed(pos, BuildOutstanding.Cause.REFUSED, null);
        }
        if (placement.write() instanceof BlockWriter.Write.Refused) {
            return null;
        }
        BlockState now = player.level().getBlockState(pos);
        landed.add(pos);
        r.placedOne(player, occupied, placement.spent());
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
     * {@code target} 此刻在世界里是 {@code now},为什么没留住(没放成,或放下去之后被弹掉)——只说服务端此刻知道的:方块自己的
     * 存活判断(canSurvive)过不过、周围六格是什么;样子被改了就说改成了什么。
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
                BlockPos beside = pos.relative(side);
                around.add(side.getName() + " " + com.dwinovo.numen.api.permission.Listing.coords(beside) + " is "
                        + BuildOutstanding.name(player.level().getBlockState(beside).getBlock()));
            }
            return id + " at " + at + " cannot stay there — nothing holds it (" + String.join(", ", around) + ")";
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

    /** 队列空了:对一遍世界,没放好的照实进回执。 */
    private TaskState finishPass() {
        // 收工要判完工,这一次必须精确——每刻那次只轮扫一片
        rescanAll();
        return conclude(null);
    }

    // ------------------------------------------------------------------
    // 二、收场
    // ------------------------------------------------------------------

    /** 活到头了:整份走完一遍({@code why} 为 null)或干不下去了。身体站住,按结论收场。 */
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

    /** 此刻放得进去的格({@link BuildSurvey.State#READY},开工前问过主人之后不许的照常跳过)。 */
    private List<BuildTaskRecord.Target> pendingNow() {
        BuildSurvey survey = survey();
        List<BuildTaskRecord.Target> pending = new ArrayList<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (survey.of(target) == BuildSurvey.State.READY && !rules.blockedByMode(target)) {
                pending.add(target);
            }
        }
        return pending;
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
     * {@link BuildOrder#CELLS_PER_TICK} 格——为看清 30 格的变化去重算三万格,这笔账不划算。
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
                outstanding(), r.replaced(), r.broken(),
                siteMin == null ? Optional.empty() : Optional.of(List.of(siteMin, siteMax)),
                // 施工期间被外力拆毁又补回去的格数:她盖得慢或反复返工,原因在这儿
                damagedCells > 0 ? Optional.of(damagedCells) : Optional.empty(),
                shortfall.isEmpty() ? Optional.empty() : Optional.of(BuildLedger.summarizeShortfall(shortfall)),
                r.spentWithData().isEmpty() ? Optional.empty() : Optional.of(r.spentWithData()));
    }

    /** {@code minecraft.build.place} 交回的值。 */
    @com.dwinovo.numen.api.sdk.Doc("What one minecraft.build.place did.")
    public record Placed(@com.dwinovo.numen.api.sdk.Doc("Cells it was given to do.") int requested,
                         @com.dwinovo.numen.api.sdk.Doc("Cells still to do.") int left,
                         @com.dwinovo.numen.api.sdk.Doc("Cells that now match.") int completed,
                         @com.dwinovo.numen.api.sdk.Doc("Blocks placed.") int placed,
                         @com.dwinovo.numen.api.sdk.Doc("The cells that were not placed, grouped by what stopped them, "
                                 + "each with a sentence on what it was and a next step; empty when everything went in. "
                                 + "Each cell got one turn in this call, a cell that could not stay got a second.")
                         List<BuildOutstanding.Unplaced> unplaced,
                         @com.dwinovo.numen.api.sdk.Doc("Blocks that stood there and were swapped for the right one.")
                         int replaced,
                         @com.dwinovo.numen.api.sdk.Doc("Blocks broken to clear a cell that should be empty.") int cleared,
                         @com.dwinovo.numen.api.sdk.Doc("Two corners of the site.") Optional<List<BlockPos>> site,
                         @com.dwinovo.numen.api.sdk.Doc("Cells something else broke while you built, put back.")
                         Optional<Integer> destroyedWhileBuilding,
                         @com.dwinovo.numen.api.sdk.Doc("What you are short of.") Optional<String> stillShort,
                         @com.dwinovo.numen.api.sdk.Doc("The stacks with data that were spent, as the inventory "
                                 + "lists them (a shulker box with its contents, an enchanted item) and how many; "
                                 + "absent when only plain items were used. Naming only an item spends the stack in "
                                 + "your main hand, else the first slot.") Optional<String> spent) {

        /** 要的样子已经立着,什么都没放。 */
        public static final Placed NOTHING = new Placed(0, 0, 0, 0, List.of(), 0, 0, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
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
                detail = com.dwinovo.numen.plugins.minecraft.build.BuildStates.noItemReason(target.block());
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
        if (r.replaced() > 0) {
            sb.append(" (").append(r.replaced()).append(" replacing what stood there)");
        }
        sb.append(", cleared ").append(r.broken());
        if (!r.spentWithData().isEmpty()) {
            sb.append(", spent ").append(r.spentWithData());
        }
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
