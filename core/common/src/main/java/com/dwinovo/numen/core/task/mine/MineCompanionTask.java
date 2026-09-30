package com.dwinovo.numen.core.task.mine;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.core.nav.DigQuote;
import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.core.scan.NearbyEntities;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.pathing.api.Report;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Verdict;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mine} — the scan → path → dig gathering loop, run on the
 * companion player body (a server-side fake player, so every break goes
 * through real server-side interaction rules, not client input).
 *
 * <h2>候选只有一个来处:点名的区域</h2>
 * 候选是点名的区域里扫描过的格(派发时解析好、记在任务记录里):先看、再规划、后执行,找方块是 {@code scan blocks --into}
 * 的事,挖矿自己不找。每格还得是扫描时看到的那种方块({@link Cells.Seen#holds}),不往外扩。格子途中被别人挖掉或变了,
 * 照常挖剩下的,回执如实交代;全都没了就如实收场。
 *
 * <h2>只在工作区里干</h2>
 * 候选只取工作区({@link MineBlockTaskRecord#work})里的:受理时她脚下那一格为中心,半径由寻路一次看得清的范围推出
 * ({@link WorkArea})。区里每一步都在一次规划的视野里,走过去加挖掉一起定价、就地挖通、垫高、捡掉落物都照旧;区外的
 * 只报告——还有几个、最近一个在哪、多远、先 move_goto 过去再挖({@link Beyond})——不去。要不要离开这块地方是有后果的
 * 决定,归模型。
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOres</b> — fed on demand from the area's cells inside the work area, nearest first,
 *       and {@link #prune}d every tick (drop ones mined / no longer holding what was seen /
 *       unworkable / hazardous), sorted by distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>in place</b> — a target the body can reach from where it stands ({@link Goals#dig}: within
 *       block reach, not occupying it) is broken on the spot, cheapest first, auto-switching to the best
 *       tool — no pathing. The digger clears what stands in the line of sight first, if it can be broken
 *       at all ({@link #breakable}).</li>
 *   <li><b>one goal over the field</b> — otherwise head for the whole ore field at once:
 *       one search over {@link Goals#anyOf} of the same dig goals ({@link #oreField}), so it walks to
 *       the CLOSEST reachable ore (not greedy-nearest, which is often the walled-in one).
 *       Arrival and the in-place pick are one criterion, so wherever the search ends, the
 *       dig side agrees; ending out of sight of the ore still counts, the digger clears the way.</li>
 *   <li><b>够不着是一批的属性,不是某一格的罪</b> — 复合目标搜不出路,意思是<b>这一刻这一批都到不了</b>,
 *       不是"最近那颗有问题",所以不记账到任何一格。目标全在工作区里、工作区在一次规划的视野里,搜索的结论
 *       就是对这一批的完整回答:一次没走到就收工,寻路给的失败类型与原因、各自的下一步原样带上
 *       ({@link #unreachable},话由 {@link com.dwinovo.numen.core.nav.NavText} 说)。站着既没挖掉一格、也没挪窝
 *       超过 {@link #STALL_TICKS} 刻,同样收工。</li>
 * </ol>
 *
 * <h2>主人的东西</h2>
 * 选目标不看权限:主人放的原木和野树一样是候选。路线规格默认是 {@link RouteSpec.Alter#ANY}(模型给的
 * {@code spec} 叠在上面,{@code avoid_break} 这类限制经 {@link #breakable} 直接作用到候选上),
 * 需要主人同意的格在成本模型里乘同意倍率——挑目标按"走过去 + 挖它"的同一套定价({@link #targetCost}),
 * 附近有野树时自然先挖野树。轮到一格,动手之前把这次挖掘交给
 * 权限层({@link #permit}):要问就等主人点头,同一行规则问出来的同一种方块从此本任务内不再问;
 * 不许(主人拒绝、观察模式、服务器退回)就按 {@link FailureType#REFUSED} 带着理由收场。路上要穿过需要
 * 同意的格,由导航在开走前问。mine 自己不判、不跳、不问,只提出动作。
 *
 * <p>A custom reactive task: it owns its own phase machine, so it grows on
 * {@link AbstractCompanionTask} directly (the shared lifecycle / failure plumbing /
 * result envelope) while keeping the whole scan-path-mine loop in {@link #onTick()}.
 */
public final class MineCompanionTask extends AbstractCompanionTask<MineBlockTaskRecord> {

    private static final int MAX_ORES = 64;            // cap on tracked target locations
    /** 名单低于此数就从区域里还没收进名单的格补货。 */
    private static final int QUERY_LOW_WATER = 16;
    /** 两次补货的最小间隔(tick)。 */
    private static final int QUERY_MIN_GAP_TICKS = 20;
    /** 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable} —— 站位说够得着,
     *  可射线始终成不了(挡在中间的挖不得、瞄准量化)。没有这条,挖掘会永远等一个
     *  不会来的射线。 */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /**
     * 既没挖掉一格、也没挪窝多远,持续这么多刻就算真卡住了(二十秒)。
     *
     * <p><b>两个条件同时成立才算</b>:走过去的每一刻都不算卡 —— 她在动。只有"站着不动又什么都没挖出来"才是卡住:
     * 导航报到了、手边却没有该挖的(见 ARRIVED 那一支),一遍遍重新规划却回到原地,这种循环寻路自己看不见,只有
     * 这把尺子量得出来;那种状态没有出口,只能收工报给主人。
     */
    private static final int STALL_TICKS = 400;

    /** 挪出这么远就算"她在动",进度计时重新起算。 */
    private static final double STALL_MOVE = 2.0;

    private final List<BlockPos> knownOres = new ArrayList<>();
    /**
     * 当前地形下挖不动的格子 —— <b>只有 {@code NO_SHOT} 进得来</b>:站位说够得着,却连续
     * 二十刻拉不出射线(挡在中间的挖不得、瞄准量化)。这是关于<b>这一格</b>的、可复现的事实。
     *
     * <p>"走不到"不进这里:那是一批的属性,不是某一格的罪。掉落物更不进 —— 够不着的掉落物
     * 在复合目标下根本不会被选中。
     *
     * <p>而且它<b>不是永久的</b>:她成功挖掉任何一格,地形就变了(挡射线的那个檐口可能正好
     * 被挖了),整份作废重来。
     */
    private final Set<BlockPos> unworkable = new HashSet<>();
    /** Targets pruned because no carried tool harvests them (force=false only) — kept so the
     *  terminal failure can name the tool problem instead of reporting an empty field. */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /**
     * 每个候选"到了之后挖它"的价钱(刻),{@link #prune} 每刻按成本模型现算:与寻路给路上一格定价同一份(挖掘耗时加挖掘
     * 罚分,需要主人同意的乘同意倍率);许可不许的是无穷,不进目标。
     */
    private final Map<BlockPos, Double> digCosts = new HashMap<>();
    /** 许可不许挖的候选说的理由(最近一格的);没有为 null。一格都挖不得时拿它收场。 */
    private String deniedWhy;
    /**
     * 按总价挑目标的导航在这一格落定了(搜索挑中的就是脚下):手边够得着、价钱不超过 {@link #settledPrice}
     * 的就是该挖的,不再因为别处估价更低而让路。挖掉一格或挪了窝就作废。
     */
    private BlockPos settledAt;
    /**
     * 落定那一刻停在这一格的到达价——搜索按总价挑中这儿时认下的价钱。停下来是为了捡脚边的掉落物(到达价 0),
     * 就不能拿它当挖一格贵东西(主人的原木)的理由。
     */
    private double settledPrice;
    /** Items the target blocks drop (simulated via the server loot tables). The
     *  count is over THESE in the inventory, not blocks broken — redstone_ore yields ~4 redstone. */
    private Set<Item> dropItems = Set.of();
    /** Matching items already in the inventory when the task began — the count is the DELTA above this
     *  (companion semantics: "gather N more", not an absolute "have N in the inventory"). */
    private int baseline;
    /** Nearby dropped items to collect (walked over for native pickup), refreshed per tick. */
    private List<BlockPos> drops = List.of();
    /**
     * 她自己敲出来、还没进包的那几件掉落物(实体 id)。
     *
     * <p><b>为什么认 id 而不是数附近的掉落物</b>:主人扔在旁边的、开工前就躺在那儿的、
     * 别人挖的,物品类型全都对得上。把它们算进来会让她少挖。认 id 才框得住"我这一单造出来的"。
     *
     * <p><b>为什么不会重复计</b>:原版拾取是同一刻里先进背包、再 {@code discard()} 实体,
     * 所以那一刻它从这本账消失、在背包增量里出现,两头不重叠;背包满了只捡走一半时实体还活着、
     * {@code getCount()} 变小,账也跟着对。
     *
     * <p>实体没了(烧了、被主人捡了、自然消失)就自动出账,她自己再补一块——不需要超时或重试。
     * 走不到的也出账,见 {@link #unreachableDrops}。
     */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet ourDrops =
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /**
     * 走不到的掉落物(实体 id)。够数之后导航只奔地上的掉落物;这一批在完整的图上搜不出路、或她守着它们
     * 卡住了,就说明它们一件也进不了包——砍树冠时原木常弹到树叶顶上,站在地上够不着,她也没有垫脚的方块。
     *
     * <p>它们从 {@link #ourDrops} 出账、不再当目标,够没够数只按还拿得到的算,她接着挖别的补上。不出账的话,
     * "到手 + 在路上"永远够数:她不再挖,又捡不到,只能缺着数收工。记的是这一批,不挑哪一件顶罪——
     * 目标里只有它们,搜不出路说的就是它们全体。
     */
    private final it.unimi.dsi.fastutil.ints.IntOpenHashSet unreachableDrops =
            new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

    /** 已经记过账的格:每一格只在挖开它之后认一次。 */
    private final Set<BlockPos> claimedCells = new HashSet<>();

    /** 这一刻"够了,别再敲新的了"——到手加上还没进包的已经够数。见 {@link #inFlight()}。 */
    private boolean quotaMet;
    /** 无掉落画像(创造)下的进度计数:破坏的目标方块数——背包增量在
     *  这种画像下恒为 0,数拾取物会让任务铲平半径 32 chunk 后报败。 */
    private int brokenTargets;

    /** 区域里落在工作区里、还没收进名单的格。挖不动的格也回到这里,地形一变还能再收。 */
    private final Set<BlockPos> remaining = new HashSet<>();
    /** 轮到时已经不是扫描时那种方块、而旅程账上也没有她挖过的格(别人挖掉、换掉的)。 */
    private final Set<BlockPos> gone = new HashSet<>();
    /** 挖不成的候选:挖不动的方块、规格禁挖的、贴着流体或悬空落沙的——{@link #breakable} 说不的。 */
    private final Set<BlockPos> ruledOut = new HashSet<>();
    /** 这件活的路线规格:mine 的默认叠上模型给的。只管走过去的那条路。 */
    private final RouteSpec spec;
    /**
     * 给目标本身定价的规格:同一份规格,改地形一档放到 {@link RouteSpec.Alter#ANY}。目标是这件活自己要挖的,许不许改地形只管
     * 走过去的路;要问主人的照价乘倍,不许的无穷。
     */
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
    /** The ore currently returning {@code NO_SHOT}, and for how many consecutive ticks. */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** 上一次真有进展(挖掉一格)或明显挪窝时的 {@link #workTicks()} 与位置 —— 卡死判定的量尺。 */
    private long lastProgressWork;
    private BlockPos lastProgressPos;

    /** 这件活的工作区:候选只取区里的。 */
    private final WorkArea work;
    /** 要挖的区域里落在工作区外的格:开工时就分出来,只报告,不去。 */
    private Beyond beyond = Beyond.NONE;

    // Progressive dig (blocks break tick-by-tick at legitimate player speed, not
    // instabreak) — shared with the path executor so all breaking reads the same.
    private final BlockDigger digger;
    /** 正在挖的目标;挖掘器这一刻可能在挖挡在它前面的那一格,锁的是目标,不是挖掘器手里那一格。没有为 null。 */
    private BlockPos digTarget;

    public MineCompanionTask(NumenPlayer player, MineBlockTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
        this.spec = record.spec;
        this.targetSpec = record.spec.edit().alter(RouteSpec.Alter.ANY).build();
        this.work = record.work;
    }

    @Override
    protected List<Precondition> preconditions() {
        // Fail fast if NO requested target is harvestable with the current inventory — mining it
        // would destroy the block for no drop ({@link #canHarvest}, whole-inventory). prune() then drops any
        // individual unharvestable
        // cell, so a mixed request (e.g. coal we can mine + diamond we can't) still works.
        return List.of(() -> {
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
            boolean anyHarvestable = r.targets.stream().anyMatch(
                    b -> canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                return new Precondition.Failure(
                        "can't harvest " + r.label + " with the current tools — mining it would"
                        + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe)"
                        + " first; to just destroy a block regardless of drops, move_goto its coordinates with"
                        + " arrive:use and run use block left on it.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    @Override
    protected void onStart() {
        // Count toward `count` by ITEMS gathered, not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        // 工作区里的格排进待收,区外的记下只报告
        r.scanned.intersect(work.cells()).forEach((x, y, z, seen) -> remaining.add(new BlockPos(x, y, z)));
        List<BlockPos> outside = new ArrayList<>();
        r.scanned.minus(work.cells()).forEach((x, y, z, seen) -> outside.add(new BlockPos(x, y, z)));
        beyond = new Beyond(outside);
        lastProgressWork = workTicks();
        lastProgressPos = player.blockPosition();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine start targets={} count={} area={} feet={} work={}",
                r.label, r.count, r.areaName, player.blockPosition().toShortString(),
                work.describe());
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:有掉落 = 数拾取到的物品(一块矿可能出多个);
        // 无掉落(创造) = 数破坏的目标方块——否则永远数不满。挖完点名的团为止的,数挖掉的格。
        boolean untilGone = r.count == MineBlockTaskRecord.UNTIL_GONE;
        // 导航顺路挖开的格也是她挖的,先入账再算够没够——漏了它们,账就会一时多一时少
        sweepNavBreaks();
        int gathered = WorkProfile.of(player).dropsLoot() && !untilGone
                ? Math.max(0, inventoryMatch() - baseline)
                : brokenTargets;
        r.setMined(gathered);
        // 完工只认到手的:回执里那句 "gathered 12/12" 得是真的
        if (!untilGone && gathered >= r.count) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }
        // 还敲不敲下一块,是另一个问题:背包是滞后指标(掉落物有 10 tick 拾取延迟),
        // 只看到手会在那段空窗里多敲两三块。算上已经敲掉、还躺在地上的,够了就只去捡。
        quotaMet = !untilGone && gathered + inFlight() >= r.count;


        Level level = player.level();

        // Maintain the ore list every tick — INCLUDING while a dig below is latched:
        // prune (cheap — knownOres is capped at 64) revalidates against the live world;
        // the list is refilled from the area's cells when it runs low.
        pricing = DigQuote.of(player, targetSpec);
        prune();
        maybeQuery();

        // 0) Continue an in-progress dig, locked onto its target (no re-selection)
        //    until it breaks or the body no longer stands where it can work it.
        if (digTarget != null) {
            if (quotaMet) {
                // 够数了,手上这块也不敲完:敲完就是多一块
                digger.cancel();
                digTarget = null;
            } else if (level.getBlockState(digTarget).isAir() || !canWork(digTarget)) {
                digger.cancel();
                digTarget = null;
            } else {
                return mineProgress(digTarget);
            }
        }

        drops = droppedItems();

        // 1) Mine any target whose stance we already stand in (no pathing) — but only standing still. While a walk is
        //    under way it runs to its end: the route ends at the stance the search picked, and arriving there is where
        //    the walk stops the body on the node's centre. Stopping it mid-stride the moment the feet cross into a
        //    stance leaves the eyes off that centre, where the block the stance reaches can lie beyond the arm.
        BlockPos reachable = nav == null ? reachableTarget() : null;
        if (reachable != null) {
            // 动手之前:这一格交给权限层。要问就站着等主人,不许就带着理由收场
            Permit permit = permit(Action.breakBlock(reachable, level.getBlockState(reachable)));
            if (permit.state() == PermitState.WAITING) {
                player.controls().stop();
                return TaskState.RUNNING;
            }
            if (permit.state() == PermitState.REFUSED) {
                fail("could not mine " + r.label + ": " + permit.refusal() + "; " + soFar(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
            return mineProgress(reachable);
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        if ((!quotaMet && !knownOres.isEmpty()) || !drops.isEmpty()) {
            TaskState stalled = stalledOut();
            if (stalled != null) {
                return stalled;
            }
            boolean moved = refreshField();
            if (field == null) {
                // 名单上的每一格许可都不许挖,地上也没有要捡的:权限层的拒绝就是这件活的结果
                fail("could not mine " + r.label + ": " + deniedWhy + "; " + soFar(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
            if (nav == null) {
                // 一个目标撒在整片矿上(加上地上的掉落物):路上可以顺手挖掉目标——顺路挖开的也是进展,
                // prune 把那一格划掉,掉落物成员去捡,够没够数看背包。模型收紧了规格(不许改地形)而没有干净的路时,
                // 回执照实说要改几格、放宽到哪一档,放不放宽是她的决定
                nav = Trip.to(player, field, spec, towardField());
            } else if (moved) {
                // 名单每几刻就变(挖掉的划掉、新查到的并进来、挖不成的剔掉):新目标交给在走的这一趟,
                // 停点还算数就照走,不算数才重搜
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
                    // 这一趟走完了;下一趟从这儿起,要走时再开
                    stopNav();
                    // [ANCHOR arrived-dud] 到了,却没有可挖的。站位与原地就挖是同一个判据,所以这
                    // <b>不构成关于任何一颗矿的证据</b>:她到的是目标里的<b>掉落物</b>成员(刚捡完
                    // 东西,附近本来就没矿;够数之后只剩掉落物成员),或者这一刻人在空中(reachableTarget
                    // 第一行就要求 onGround)。
                    //
                    // 所以这里只重新规划。真卡住了由 STALL_TICKS 那把尺子收工,不记账到某一格。
                    if (reachableTarget() == null && !knownOres.isEmpty()) {
                        com.dwinovo.numen.core.Constants.LOG.debug(
                                "[numen-task] mine ARRIVED 但够不到 feet={} nearestOre={} —— 重规划",
                                player.blockPosition().toShortString(), nearestOreInfo());
                    }
                    return TaskState.RUNNING;   // a reachable shaft is handled next tick
                }
                case FAILED -> {
                    // [ANCHOR nav-failed] 这一批都到不了。
                    //
                    // <b>这句话的主语是"这一批",不是"最近那颗"。</b>目标撒在工作区里的全部候选上,
                    // 搜不出路的意思是一个都到不了 —— 拿"离脚最近的"顶罪只是猜,所以什么都不记。
                    // 工作区在一次规划的视野里,名单一变目标就交给在走的这一趟(retarget),所以这个结局
                    // 回答的就是此刻这一批:收工,寻路说的原因与下一步原样交给模型。
                    com.dwinovo.numen.core.Constants.LOG.info(
                            "[numen-task] mine nav failed ({}): {} | 目标 {} 个,nearestOre={}",
                            nav.failType(), nav.failReason(), knownOres.size(), nearestOreInfo());
                    return unreachable(nav.failReason(), nav.failType());
                }
            }
        }

        // 3) No ore known and nothing dropped nearby.
        //    Finish with whatever we gathered (the tool's contract: "fewer than count in
        //    range still succeeds") — the body does not leave its work area looking for more;
        //    what lies beyond it is reported, and going there is the model's call.
        if (r.getMined() > 0) {
            progressNote = "nothing left to dig in my work area, " + work.describe() + leftovers(null)
                    + beyondClause();
            return TaskState.SUCCESS;
        }
        return noOreFailure();
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
        field = oreField(key);
        return true;
    }

    /** 名单编成的目标;一个成员都没有为 null。 */
    private Goal oreField(FieldKey key) {
        var stats = Snapshots.stats(player);
        List<Goal> members = new ArrayList<>(key.ores().size() + key.drops().size());
        key.ores().forEach((ore, cost) -> members.add(Goals.priced(Goals.dig(ore, stats), cost)));
        for (BlockPos drop : key.drops()) {
            members.add(Goals.at(drop));     // items, not blocks
        }
        return members.isEmpty() ? null : Goals.anyOf(members);
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

    /**
     * 挑目标用的总价:走到够得着它的地方(目标的估价,与交给搜索的同一把尺)加上挖它的价钱。
     */
    private double targetCost(BlockPos ore, BlockPos feet) {
        return Goals.dig(ore, Snapshots.stats(player)).estimate(feet.getX(), feet.getY(), feet.getZ()) + digCost(ore);
    }

    /** 身体此刻站着的地方够不够得着 {@code ore}——原地就挖与导航到位是这同一个判据。 */
    private boolean canWork(BlockPos ore) {
        Feet here = Feet.of(player);
        return here != null && here.in(Goals.dig(ore, Snapshots.stats(player)));
    }

    /**
     * 这一格挖不挖得成:按给目标定价的成本模型问——规格的按位置与按种类禁令、物理上挖不挖得了(挖不动、贴着流体、顶着
     * 落沙、世界边界外)。许可不许的也算挖得成:那是动手时权限层的事,价钱是无穷。挡着视线的遮挡物过同一道。
     */
    private boolean breakable(BlockPos pos, BlockState state) {
        return pricing.breakable(pos, state);
    }

    /**
     * 身上有没有能让它掉东西的工具(整个背包,不只快捷栏:她能从包里拿工具挖)。不要求工具的方块总是有。
     */
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

    /** Dropped items worth collecting, walked over for native pickup: only items the
     *  targets actually drop (a stray rotten flesh isn't this task's business), within
     *  its work area ({@link #nearbyDrops}). A drop sitting next to a known ore is skipped —
     *  mining that ore walks us there anyway. Just-broken cells linger as members for
     *  {@link #DROP_LOITER_TICKS} so the spawning drop isn't left behind. */
    private List<BlockPos> droppedItems() {
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : nearbyDrops()) {
            BlockPos p = ie.blockPosition();
            // 贴着某颗已知矿的掉落物不单独设目标——挖那颗矿自然会带身体过去。够数之后
            // 不再去挖任何矿,这条捷径就不成立了,那时每一件都得自己走过去捡。
            if (!quotaMet && nearKnownOre(p)) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * 这件活要捡的掉落物:目标会掉的物品,不在 {@link #unreachableDrops} 里;躺在工作区里的,加上她自己敲出来的
     * ({@link #ourDrops})——挖区边上那一格时掉落物可能弹出区外一两格,那也是这一单的收获,够没够数算着它。
     */
    private List<ItemEntity> nearbyDrops() {
        Level level = player.level();
        List<ItemEntity> out = new ArrayList<>(NearbyEntities.in(level, work.area(), ItemEntity.class,
                this::wanted));
        if (level instanceof ServerLevel sl) {
            for (int id : ourDrops) {
                if (sl.getEntity(id) instanceof ItemEntity ie && !ie.isRemoved() && wanted(ie)
                        && !work.contains(level.dimension(), ie.blockPosition())) {
                    out.add(ie);
                }
            }
        }
        return out;
    }

    /** 这件掉落物是这件活要的:目标会掉的物品,而且没被记成走不到。 */
    private boolean wanted(ItemEntity ie) {
        return dropItems.contains(ie.getItem().getItem()) && !unreachableDrops.contains(ie.getId());
    }

    /** 距任一已知矿位 3 格内(distSqr ≤ 9)——挖那颗矿自然会带身体过去。 */
    private boolean nearKnownOre(BlockPos p) {
        return knownOres.stream().anyMatch(ore -> ore.distSqr(p) <= 9);
    }

    /**
     * The in-place mining pick: the cheapest known target the body can reach from where it stands right
     * now ({@link #canWork}) — mined on the spot, no pathing; equal prices go to the nearest. It is the
     * very criterion the navigator arrives by, so an arrival always has something to dig here, and
     * whatever blocks the line of sight is the digger's to clear. Anything not workable from here is
     * left to the navigator.
     *
     * <p>够得着的也可能不是该挖的:别处有按乐观估价就更便宜的({@link #targetCost}:走过去 + 挖它),
     * 就先让导航按总价去挑。导航挑完仍停在这儿({@link #settledAt}),而且认下的价钱够挖它
     * ({@link #settledPrice}),说明别处的便宜只是估价上的,那就挖手边的。
     *
     * <p>够数了({@link #quotaMet})手边也没有该挖的:再挖就多了。这一条必须写在这里,不能由调用方各自
     * 另加——导航的"到了"问的就是这个函数,门槛若只挡在原地就挖那一侧,导航会拿手边那根当"到了"、
     * 任务却不挖,两边来回推,她钉在原地不去捡那几件够数所靠的掉落物。
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
            if (level.getBlockState(ore).isAir() || !here.in(Goals.dig(ore, stats))) {
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
            if (Goals.at(drop).estimate(feet.getX(), feet.getY(), feet.getZ()) < bestCost) {
                return null;
            }
        }
        return best;
    }

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. The digger clears only occluders
     *  that pass the same cut as the targets themselves ({@link #breakable}). The progress count
     *  is read from the inventory each tick, not here — one block can yield several items, and the drops
     *  take a moment to be picked up.
     *
     *  <p>Recovery: 连续的 {@code NO_SHOT}(站位说够得着,可挖掘始终成不了射线)记数,满
     *  {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进 {@link #unworkable} 继续往下走,
     *  而不是永远等一个不会来的射线。<b>记的是这一格,不是猜一格</b> —— 这是唯一一处
     *  按格记账的地方,因为它是唯一一件关于那一格的可复现事实。 */
    private TaskState mineProgress(BlockPos pos) {
        digTarget = pos.immutable();
        switch (digger.digStep(pos, occluder -> breakable(occluder, player.level().getBlockState(occluder)),
                this::recordAction)) {
            case BROKE_TARGET -> {
                digTarget = null;
                knownOres.remove(pos);
                brokenTargets++;
                noteProgress();
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的"挖不动"结论全部作废。
                unworkable.clear();
                settledAt = null;
                claimDrops(pos);
                clearNoShot();
            }
            case REFUSED -> {
                // 挖掘落点的裁决不许(动手前放行之后世界变了,或挡在前面的遮挡物不许挖):
                // 权限层的拒绝就是这件活的结果,带着理由收场
                digger.cancel();
                fail("could not mine " + r.label + ": " + digger.refusal().reason() + "; " + soFar(),
                        FailureType.REFUSED);
                return TaskState.FAILED;
            }
            case NO_SHOT -> {
                if (pos.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        unworkable.add(pos.immutable());
                        knownOres.remove(pos);
                        remaining.add(pos.immutable());   // 地形一变(挖掉任何一格)还能再收
                        digger.cancel();   // release the in-progress-dig latch on this ore
                        digTarget = null;
                        clearNoShot();
                    }
                } else {
                    noShotPos = pos.immutable();
                    noShotTicks = 1;
                }
            }
            case BROKE_OCCLUDER -> {
                // 为了拉出射线挖掉的是挡在前面的那一格,不是目标:进旅程账,回执交代,它若也是要挖的格就算她挖的。
                // 挖掉一格就是进展,一路挖开几片树叶不算卡住
                recordBreak(digger.lastBroken());
                claimDrops(digger.lastBroken() == null ? null : digger.lastBroken().pos());
                noteProgress();
                clearNoShot();
            }
            // PROGRESSING — real progress; reset the stall counter.
            default -> clearNoShot();
        }
        return TaskState.RUNNING;
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    // ---- item counting (progress = matching items held in the inventory) ----

    /**
     * 她挖开的这一格掉出来的东西记进 {@link #ourDrops}。每一格只认一次,就在挖开它之后那一刻。
     *
     * <p>不卡实体年龄:那一刻躺在这一格里的东西,她走过去照样会捡进包、照样会被
     * {@link #inventoryMatch()} 数到。既然到手时算,承诺时就得一起算,两边才对得上。
     *
     * <p>无掉落画像(创造)下这本账永远是空的,进度改数敲掉的格数。
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
     * 把导航这条路上挖开的格也入账。
     *
     * <p>为了走过去而挖开的格和她照着目标敲掉的格一样,掉的东西都会进她的包。实际账
     * ({@link Trip#reports} / {@link #brokeOnTheWay})是"她挖了什么"的唯一出处,所以这里问它,
     * 而不是另设一套记录。停下时账会并进旅程账,所以 {@link #stopNav()} 之前再扫一次。
     */
    private void sweepNavBreaks() {
        if (nav == null) {
            return;
        }
        for (Report report : nav.reports()) {
            for (EditLedger.Entry entry : report.ledger().entries()) {
                if (entry instanceof EditLedger.Dug dug) {
                    claimDrops(dug.pos());
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

    /** Matching items currently carried (sum of stack counts whose item the targets drop). 盔甲/副手不算采集所得。 */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        return com.dwinovo.numen.core.PlayerInv.carriedCount(player.getInventory(),
                s -> dropItems.contains(s.getItem()));
    }

    /** The item set the target blocks drop — the server loot table rolled once per
     *  target with the best harvesting tool we carry (so an ore yields its
     *  ingot/gem, stone yields cobblestone, etc.). Falls back to the block's own item if it has no loot. */
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

    /** The inventory item that mines {@code state} fastest — the tool the dig will actually use, so the
     *  simulated drops match the real ones (e.g. respects a Silk Touch / Fortune pick if carried). */
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

    // ---- ore list maintenance ----

    /** 按需补货:名单空了,或快吃完且过了间隔,就从区域里还没收进名单的格里收。 */
    private void maybeQuery() {
        --queryCooldown;
        if (knownOres.isEmpty() || (knownOres.size() < QUERY_LOW_WATER && queryCooldown <= 0)) {
            queryCooldown = QUERY_MIN_GAP_TICKS;
            admitNamed();
        }
    }

    /**
     * 补货:从区域里还没收进名单的格里由近及远收,收满 {@link #MAX_ORES} 个为止。挖不动的格留在
     * 那儿等地形变;其余收不进的({@link #stillCandidate} 记了账)就此划掉。
     */
    private void admitNamed() {
        if (remaining.isEmpty() || knownOres.size() >= MAX_ORES) {
            return;
        }
        BlockPos feet = player.blockPosition();
        List<BlockPos> nearestFirst = new ArrayList<>(remaining);
        nearestFirst.sort(Comparator.comparingDouble(feet::distSqr));
        Level level = player.level();
        for (BlockPos p : nearestFirst) {
            if (knownOres.size() >= MAX_ORES) {
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
     * 这一格还算不算候选:还是扫描时看到的那种方块({@link Cells.Seen#holds})、没被记成挖不动、挖得成、手里的
     * 工具收得到掉落。问的是"挖不挖得成",按这件活自己的规格算;许不许挖不在这里剪。收不进的记账,回执交代。
     */
    private boolean stillCandidate(Level level, BlockPos p) {
        var state = level.getBlockState(p);
        if (state.isAir() || !r.scanned.seenAt(p).holds(state)) {
            // 区域里的格不在了:旅程账上有,就是她顺路挖的(导航穿过它、为拉射线挖掉的遮挡物),算她挖掉的一格;
            // 账上没有,才记成别人动过。一格只会从名单或待收里各验出一次"不在了",不会重复计数
            if (brokeOnTheWay(p)) {
                brokenTargets++;
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
        // Harvestability gate. Tool-skipped cells are remembered so the terminal failure
        // can say "you need a better tool" instead of the misleading "nothing found" (the
        // tool situation can also CHANGE mid-task: the only good pick breaking makes this
        // fire on re-prune).
        if (!WorkProfile.of(player).instaBreak()
                && !canHarvest(player.getInventory(), state)) {
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
        if (knownOres.size() > MAX_ORES) {
            List<BlockPos> farther = knownOres.subList(MAX_ORES, knownOres.size());
            remaining.addAll(farther);   // 只是暂时排不上,不是没了
            farther.clear();
        }
        // 挖每一块的价钱:与寻路给路上一格定价同一个成本模型,需要主人同意的乘倍率,不许的是无穷——挑目标按价,不按剪。
        // 挖的时候站着、眼睛不在水里
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

    /** Nearest known ore to the feet, or null — for the "near ore exists but heading far" diagnostics. */
    private BlockPos nearestOre() {
        BlockPos feet = player.blockPosition();
        return knownOres.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    /** Log-friendly nearest-ore descriptor (ASCII so it survives any log encoding):
     *  "316,64,391 minecraft:oak_log dy=+0 dist=1.0" or "none". dy = ore.y - feet.y (spot "it's 4 up,
     *  needs pillaring" vs "same level"); the block id spots a mis-handled type (vine/leaves/etc.). */
    private String nearestOreInfo() {
        BlockPos n = nearestOre();
        if (n == null) {
            return "none";
        }
        BlockPos feet = player.blockPosition();
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(n).getBlock()).toString();
        int dy = n.getY() - feet.getY();
        return n.toShortString() + " " + block + " dy=" + (dy >= 0 ? "+" + dy : dy)
                + " dist=" + String.format("%.1f", Math.sqrt(feet.distSqr(n)));
    }



    /** 挖掉了一格,或者明显挪了窝 —— 两者都算进展,卡死计时重新起算。 */
    private void noteProgress() {
        lastProgressWork = workTicks();
        lastProgressPos = player.blockPosition();
    }

    /**
     * 真卡住了吗。<b>既没挖掉一格、也没挪出 {@link #STALL_MOVE} 格</b>,持续
     * {@link #STALL_TICKS} 刻才算 —— 走远路去挖矿一刻都不算,她在动。刻数是干活的刻
     * ({@link #workTicks()}):等规划的刻不算卡住,往下挖 170 格的搜索还没回来时她只是在等路。
     *
     * @return 该收工就给终态,否则 null
     */
    private TaskState stalledOut() {
        if (lastProgressPos == null
                || player.blockPosition().distSqr(lastProgressPos) > STALL_MOVE * STALL_MOVE) {
            noteProgress();
            return null;
        }
        long idle = workTicks() - lastProgressWork;
        if (idle < STALL_TICKS) {
            return null;
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine 卡住 {} 刻:没挖掉任何一格、也没挪窝 | feet={} 名单 {} 个",
                idle, player.blockPosition().toShortString(), knownOres.size());
        return unreachable("stuck at " + player.blockPosition().toShortString() + " with nothing minable in place for "
                + STALL_TICKS / 20 + " seconds", FailureType.NO_PATH);
    }

    /**
     * 剩下的一个都到不了,收工:挖到过就算成功,如实交代剩下多少没够着;一个没挖到就按 {@code type} 失败。
     *
     * <p>够数之后导航的目标只有地上的掉落物,这时到不了的是那批掉落物,不是还没挖的目标:把它们记进
     * {@link #unreachableDrops},接着挖别的补上,不收工。
     *
     * @param why  为什么到不了,原话进回执(寻路的结局由 NavText 说,连同它的下一步)
     * @param type 归到哪一种失败(寻路的结局由 NavText 归)
     */
    private TaskState unreachable(String why, FailureType type) {
        if (quotaMet && !knownOres.isEmpty()) {
            return writeOffDrops(why);
        }
        stopNav();
        String what = knownOres.isEmpty() ? "the drops lying on the ground"
                : (r.getMined() > 0 ? "the remaining " : "any of the ") + knownOres.size() + " " + noun();
        if (r.getMined() > 0) {
            progressNote = "then could not reach " + what + " in my work area: " + why + leftovers(null)
                    + beyondClause();
            return TaskState.SUCCESS;
        }
        fail("could not reach " + what + " in my work area (" + work.describe() + "); gathered 0: " + why
                + leftovers(null) + beyondClause(), type);
        return TaskState.FAILED;
    }

    /** 够数所靠的那批掉落物走不到:全部出账,清掉无路的局面与卡住的计时,下一刻按还拿得到的重新算够没够。 */
    private TaskState writeOffDrops(String why) {
        List<ItemEntity> lost = nearbyDrops();
        for (ItemEntity ie : lost) {
            unreachableDrops.add(ie.getId());
            ourDrops.remove(ie.getId());
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine 够数靠的 {} 件掉落物走不到({}),出账接着挖 | feet={} 名单 {} 个",
                lost.size(), why, player.blockPosition().toShortString(), knownOres.size());
        stopNav();
        noteProgress();
        return TaskState.RUNNING;
    }

    /** 挖不成的候选为什么挖不成,回执里的说法。 */
    private static final String RULED_OUT_WHY = "unbreakable, excluded by the spec, or fluid or loose falling"
            + " blocks beside them";

    /** 回执里怎么称呼要挖的东西:这些方块的格子。 */
    private String noun() {
        return "cells of " + r.label;
    }

    /** 到目前为止的收获,一句话。 */
    private String soFar() {
        return r.count == MineBlockTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + " of " + r.cells() + " cells"
                : "gathered " + r.getMined();
    }

    /**
     * 找到了或点名了、却没挖成的各因为什么;都没有是空串。
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
        return parts.isEmpty() ? "" : "; not mined: " + String.join(", ", parts);
    }

    /**
     * 一格没挖到、也没有可去挖的了,按缘由收场并带上计数:手里的工具收不下({@code WRONG_TOOL})、没有站位拉得出射线
     * ({@code NO_PATH})、挖不成({@code MINED_OUT})、区里的格都不在了({@code TARGET_LOST},说区外还剩什么、怎么过去,或给
     * 再扫一遍的那一行)。「走不到」那一档不在这里 —— 它由 {@link #unreachable} 收工。
     */
    private TaskState noOreFailure() {
        if (!unharvestable.isEmpty()) {
            // Targets exist but the carried tools can't make them drop — the actionable
            // problem is the tool, not the deposit. Names the escape hatches explicitly.
            fail("found " + unharvestable.size() + " " + noun() + " but none can be harvested with"
                    + " the current tools (mining would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (gear wear) and retry; to just destroy"
                    + " blocks regardless of drops, move_goto each one's coordinates with arrive:use and run use block left on it."
                    + leftovers(unharvestable) + beyondClause(), FailureType.WRONG_TOOL);
        } else if (!unworkable.isEmpty()) {
            fail("found " + unworkable.size() + " " + noun() + " nearby but no clear shot at any"
                    + " of them from any stance I could take; gathered 0" + leftovers(unworkable) + beyondClause(),
                    FailureType.NO_PATH);
        } else if (!ruledOut.isEmpty()) {
            fail("found " + ruledOut.size() + " " + noun() + " but none of them can be broken here ("
                    + RULED_OUT_WHY + "); gathered 0"
                    + leftovers(ruledOut) + beyondClause(), FailureType.MINED_OUT);
        } else {
            // 区里的格都不在了:区外还有就说在哪、怎么过去,没有了就给再扫一遍的那一行——去不去、扫不扫是模型的决定
            fail("all " + (r.cells() - beyond.cells().size()) + " scanned cells of " + r.areaName
                    + " in my work area (" + work.describe() + ") were gone or had changed since the scan; gathered 0"
                    + (currentBeyond().isEmpty()
                            ? ". Nothing of " + r.areaName + " is left to dig; " + r.rescan() + " adds what is there now"
                            : beyondClause()),
                    FailureType.TARGET_LOST);
        }
        return TaskState.FAILED;
    }

    /** 此刻还在那儿的区外目标:要挖的区域里落在工作区外、还是扫描时那种方块的格。 */
    private Beyond currentBeyond() {
        Level level = player.level();
        return beyond.keep(p -> r.scanned.seenAt(p).holds(level.getBlockState(p)));
    }

    /** 回执里说区外的那一截(以 {@code "; "} 起头);区外什么都没有是空串。 */
    private String beyondClause() {
        Beyond left = currentBeyond();
        if (left.isEmpty()) {
            return "";
        }
        BlockPos from = player.blockPosition();
        return "; " + left.told(r.areaName, from);
    }

    @Override
    protected void cleanup() {
        // super.cleanup() = stopNav() (nav.stop clears the overlay when a nav exists) + an explicit
        // so a task that finished while shaft-mining (nav == null) still
        // clears its lingering goal boxes. Then release the dig.
        super.cleanup();
        digger.cancel();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        if (r.count == MineBlockTaskRecord.UNTIL_GONE) {
            data.put("cells", r.cells());
            data.put("dug", r.getMined());
        } else {
            data.put("requested", r.count);
            data.put("gathered", r.getMined());
        }
        return data;
    }

    /** {@code gathered 3/8 oak_log} 或 {@code dug 3/12 cells of oak_log}。 */
    private String tally() {
        return r.count == MineBlockTaskRecord.UNTIL_GONE
                ? "dug " + r.getMined() + "/" + r.cells() + " cells of " + r.label
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
