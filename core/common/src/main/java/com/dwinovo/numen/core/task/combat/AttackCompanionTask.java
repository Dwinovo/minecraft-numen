package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.core.combat.AttackPlan;
import com.dwinovo.numen.core.combat.Battlefield;
import com.dwinovo.numen.core.combat.Loadout;
import com.dwinovo.numen.core.combat.Haven;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.Swing;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@code attack}:打掉指定的实体,<b>近战还是远程由身体判,不由模型判</b>。
 *
 * <h2>为什么合成一个工具</h2>
 * 模型在派发那一刻知道的是"打谁";不知道的是等她走到时还有多远、有没有视线、还剩几支箭、
 * 那东西够不够得着——这些每 tick 都在变,只有身体读得到。让模型选弓还是剑,等于要它拿着
 * 过期信息做决定,还顺带引入一整类错误(派远程攻击而背包里没箭)。
 *
 * <h2>三套武器学,一个判据</h2>
 * 挥击(冷却与无敌帧)、射击(弹道与拉弓)、躲避(势场),各自是真正不同的东西;
 * 选哪一套则只有一处判据 {@link AttackPlan},本能链用的也是同一处。
 *
 * <h2>会炸的东西</h2>
 * 爬行者<b>引信点着之前就是一只普通怪</b>:她够得着 4 格、它 3 格才点火,中间那条一格宽的带
 * 能打到它而不触发。点着了再退也来得及——引信 30 刻,而爆炸伤害到 6 格就归零,从 3 格退出去
 * 疾跑只要十来刻。末影水晶不适用:它没有引信,一打就炸。详见 {@link Menace}。
 */
public final class AttackCompanionTask extends AbstractCompanionTask<AttackTaskRecord> {

    private enum Phase { COMBAT, LOOT }

    private static final double CHASE_SPEED = 1.2;
    private static final int MAX_APPROACH_FAILURES = 3;

    // 弹道常数:箭的物理与两种发射器的初速。
    private static final double MAX_FIRING_RANGE = 32.0;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_HITBOX_RADIUS = 0.5;
    private static final double BOW_FULL_SPEED = 3.0;
    private static final double CROSSBOW_SPEED = 3.15;
    /** 连续几发没能真的射出去就判这把武器不顶用。 */
    private static final int MAX_MISFIRES = 2;
    /** 射击时与目标保持的最小距离——太近了弹道压得太平,而且白白挨打。 */
    private static final double RANGED_MIN_DISTANCE = 5.0;
    /** 组装局面看多远:势场要绕开谁、无差别模式打谁,都取这个半径。 */
    private static final double FIELD_RADIUS = 12.0;

    /**
     * 逃跑时扫多远。必须<b>大于</b> {@link Menace#FLEE_DISTANCE},否则她一边跑一边有新的怪
     * 进入视野,目标每几刻换一次,等于没有目标。
     */
    private static final double FLEE_SCAN_RADIUS = 40.0;

    /** 离落点这么近就算到了,该重新挑下一个。 */
    private static final double HAVEN_ARRIVED = 2.0;

    /**
     * 逃跑路上多久重算一次路线(刻)。
     *
     * <p><b>落点不变,只重算路线</b>:重算时这一刻的怪会折进边成本,路径拐开而方向不变。
     * 不重算的话整段路只算一次——她起跑之后路上冒出来的怪一只都看不见,直接撞过去。
     *
     * <p>二十刻(一秒)是怪走四五格的量级。再密就是把路径反复拆了重建,疾跑的加速起不来。
     */
    private static final int FLEE_REPLAN_TICKS = 20;

    private Phase phase = Phase.COMBAT;
    private Entity target;
    private Vec3 lastTargetPosition;
    private final Map<Integer, Integer> navFailures = new HashMap<>();
    private final Map<Item, Integer> inventoryBaseline = new HashMap<>();
    private final LootSweep loot;

    /**
     * 这一场经手过的 id。无差别模式没有事先的名单,不记下来就无处结算战果
     * ——它打倒的东西会因为"不在请求清单里"而被整场吞掉。
     */
    private final java.util.Set<Integer> touchedIds = new java.util.LinkedHashSet<>();

    /** 退避的寻路连续失败次数。够了就是"退不掉",判据据此改判背水一战。 */
    private int retreatFailures;

    /** 上一行站位日志。数字没变就不再打,免得每 tick 一行把别的全冲掉。 */
    private String lastStandoffLog;

    /** 逃跑探针:上一次采样时她在哪、最近的追兵多远、那一刻的游戏时间。 */
    private net.minecraft.world.phys.Vec3 fleeMark;
    private double fleeMarkNearest;
    private long fleeMarkTick;

    private RangedShot shot;
    private int misfires;
    private double followRadius = MAX_FIRING_RANGE - 8.0;
    private int lastPlanLogTick = -1000;
    private AttackPlan.Action lastLoggedAction;
    /** 上一刻的决定。判据靠它做迟滞与承诺,见 {@link AttackPlan#decide}。 */
    private AttackPlan.Move lastMove;

    /**
     * 正在逃跑。<b>一旦开始,终点只有一条:{@link Menace#FLEE_DISTANCE} 格内没有敌对生物。</b>
     *
     * <p>中途不再问判据。判据的视野是十二格,她一跑出十二格,追她的那些就掉出视野,判据当场
     * 宣布"没有东西再追你了"、任务成功收场、反射链下一刻又开一场 —— 实测她每次跑到十一二格
     * 就停,那个三十二格的终点一次都没轮到。
     *
     * <p>与其把判据的视野也调到三十二(那只是把同一个数抄到第二处),不如让逃跑自己管自己的
     * 终点:判据管打架,逃跑管跑掉,各有各的判据,谁也别替谁发言。
     */
    private boolean fleeing;

    /**
     * 逃跑的<b>落点</b>。一次挑定,跑到才换 —— 方向的连续性就是不绕圈的全部原因。
     *
     * <p>路径本身仍然每次重规划都重算,新冒出来的怪由边成本({@code Avoidance.forGoal})
     * 折进去,路线会拐开而<b>目标不变</b>。以前重算连方向一起重掷,所以既反应了也绕圈了。
     */
    private BlockPos haven;

    /** 这一段逃跑路线是哪一刻算的。到点就重算,见 {@link #FLEE_REPLAN_TICKS}。 */
    private long havenPlannedAt;

    public AttackCompanionTask(NumenPlayer player, AttackTaskRecord record) {
        super(player, record);
        this.loot = new LootSweep(player);
    }

    @Override
    protected void onStart() {
        snapshotInventory(inventoryBaseline);
        // 这场仗归我管了 —— 本能链别再为同一件事抢身体。空闲时自动解除,不必显式还。
        player.pauseReflex(MobDefenseChain.ID);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;
        if (phase == Phase.LOOT) return tickLoot();

        // 逃跑中:只问那一条,不问判据。
        if (fleeing) {
            return tickFlee();
        }
        Battlefield field = surveyField();
        for (var f : field.foes()) {
            if (f.authorized()) {
                touchedIds.add(f.id());
            }
        }
        settleFinishedTargets();
        AttackPlan.Move move = AttackPlan.decide(field, lastMove);
        lastMove = move;
        logMove(move, field);

        Entity chosen = move.foeId() == AttackPlan.NO_FOE ? null : liveEntity(move.foeId());
        if (chosen != target) {
            stopNav();
            abortShot();
            target = chosen;
            followRadius = MAX_FIRING_RANGE - 8.0;
        }
        if (target != null) {
            lastTargetPosition = target.position();
            loot.rememberPreexisting(BlockPos.containing(lastTargetPosition));
        }
        // 攻击与移动<b>正交</b>:每刻先问一次"冷却好了吗、够得着谁吗",够得着就打 ——
        // 不管这一刻在靠近、在拉开、还是站着。攻击不影响寻路,最多让她回个头。
        tickWeapon(field);
        return switch (move.action()) {
            case SKIRMISH -> closeIn();
            case RANGED -> shootAt(Loadout.forTarget(player, target));
            case AVOID -> backAway();
            case DISENGAGE -> {
                fleeing = true;
                yield tickFlee();
            }
            case ABANDON -> abandonTarget();
            case DONE -> finish();
        };
    }

    // ==================== 局面 ====================

    /**
     * 把这一刻的世界折成 {@link Battlefield}。
     *
     * <p>点名模式下"被授权"是模型给的那份清单;无差别模式下是"这一刻在追我的"——会分裂的怪
     * 裂出来的新 id 因此自动进场,而点名的清单一裂开就作废了。
     */
    private Battlefield surveyField() {
        List<Battlefield.Foe> foes = new ArrayList<>();
        for (var mob : Menace.hostilesAround(player, FIELD_RADIUS)) {
            boolean engaging = mob.getTarget() == player || mob == player.getLastHurtByMob();
            boolean authorized = r.indiscriminate ? engaging : r.entityIds.contains(mob.getId());
            if (authorized && r.terminal(mob.getId())) {
                authorized = false;   // 打完了、丢了、或判过够不着的,不再是候选
            }
            foes.add(new Battlefield.Foe(
                    mob.getId(),
                    player.distanceTo(mob),
                    Menace.explodes(mob),
                    Menace.armed(mob),
                    Menace.tooClose(mob, player),
                    engaging,
                    reachable(mob.getId()),
                    authorized));
        }
        // 点名模式还可能被要求打不敌对的东西(一只鸡、一个末影水晶),它们不在敌对扫描里。
        if (!r.indiscriminate) {
            for (int id : r.entityIds) {
                if (r.terminal(id) || containsId(foes, id)) {
                    continue;
                }
                Entity e = liveEntity(id);
                if (e != null) {
                    foes.add(new Battlefield.Foe(id, player.distanceTo(e),
                            Menace.explodes(e), Menace.armed(e), Menace.tooClose(e, player),
                            false, reachable(id), true));
                }
            }
        }
        Loadout loadout = Loadout.forTarget(player, player);
        return new Battlefield(
                Menace.effectiveHealth(player),
                reachToTarget(),
                loadout.hasMelee(), loadout.hasRanged(),
                retreatFailures >= MAX_APPROACH_FAILURES, foes);
    }

    private static boolean containsId(List<Battlefield.Foe> foes, int id) {
        for (var f : foes) {
            if (f.id() == id) return true;
        }
        return false;
    }

    private boolean reachable(int id) {
        Integer fails = navFailures.get(id);
        return fails == null || fails < MAX_APPROACH_FAILURES;
    }

    private Entity liveEntity(int id) {
        Entity e = ((ServerLevel) player.level()).getEntity(id);
        return e == null || e.isRemoved() || e == player ? null : e;
    }

    /** 把已经有结果的目标记进账本(死了 / 不见了)。 */
    private void settleFinishedTargets() {
        for (int id : r.indiscriminate ? List.copyOf(touchedIds) : r.entityIds) {
            if (r.terminal(id)) {
                continue;
            }
            Entity e = ((ServerLevel) player.level()).getEntity(id);
            if (e == null || e.isRemoved()) {
                if (r.strikes(id) > 0) {
                    r.defeated(id);
                    beginLoot(lastTargetPosition);
                } else {
                    r.lost(id);
                }
            } else if (e instanceof LivingEntity living && living.isDeadOrDying()) {
                r.defeated(id);
                beginLoot(lastTargetPosition);
            }
        }
    }

    private void noteApproachFailure(int id) {
        if (navFailures.merge(id, 1, Integer::sum) >= MAX_APPROACH_FAILURES) {
            r.unreachable(id);
        }
    }

    /** 打完了 —— 名单清空(点名),或没人再追她(无差别)。 */
    private TaskState finish() {
        InputDriver.halt(player);
        stopNav();
        if (r.indiscriminate || !r.defeated().isEmpty()) {
            succeed();
            return TaskState.SUCCESS;
        }
        fail("none of the requested entity ids could be attacked", FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    private void logMove(AttackPlan.Move move, Battlefield field) {
        if (move.action() == lastLoggedAction && player.tickCount - lastPlanLogTick < 40) return;
        lastLoggedAction = move.action();
        lastPlanLogTick = player.tickCount;
        Constants.LOG.info("[numen-attack] {} foe={} dist={} melee={} ranged={} hp_eff={} 场上={}",
                move.action(),
                move.foeId() == AttackPlan.NO_FOE ? "全场" : move.foeId(),
                move.foeId() == AttackPlan.NO_FOE ? "-"
                        : String.format("%.1f", distanceOf(field, move.foeId())),
                field.hasMelee(), field.hasRanged(),
                String.format("%.0f", field.effectiveHealth()), field.foes().size());
    }

    private static double distanceOf(Battlefield field, int id) {
        var f = field.byId(id);
        return f == null ? -1 : f.distance();
    }

    // ==================== 近战 ====================

    /**
     * 攻击系统。<b>与移动正交</b>:每刻问一次「冷却好了吗、够得着谁吗」,够得着就挥 ——
     * 不看她这一刻在靠近、在拉开还是站着,也不改变她的去向。
     *
     * <p>目标<b>自己挑</b>,不用判据那个 {@code move.foeId()}:拉开({@code AVOID})是全场的
     * 动作,那时判据给的目标是"全场"、任务里的 {@code target} 是空 —— 手里那把剑等于不存在。
     * 实测她躲的时候一刀不还。
     *
     * @param field 这一刻的局面,复用 onTick 已经扫好的那份
     */
    private void tickWeapon(Battlefield field) {
        if (player.isUsingItem()) {
            return;   // 正在拉弓,别打断
        }
        Loadout loadout = Loadout.forTarget(player, player);
        if (!loadout.hasMelee()) {
            return;
        }
        Entity victim = null;
        double best = Double.MAX_VALUE;
        for (var f : field.foes()) {
            if (!f.authorized() || f.armed() || f.distance() >= best) {
                continue;   // 引信在走的不碰:打它等于自己引爆
            }
            Entity e = liveEntity(f.id());
            if (e != null && f.distance() <= Swing.reachTo(
                    player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), e.getBbWidth())) {
                victim = e;
                best = f.distance();
            }
        }
        if (victim == null) {
            return;
        }
        ItemStack before = player.getMainHandItem();
        player.holdInHand(loadout.melee().slot());
        boolean weaponChanged = player.getMainHandItem() != before;
        if (!Swing.mayStrike(weaponChanged, victim instanceof LivingEntity hurt && hurt.hurtTime > 0,
                player.getAttackStrengthScale(0.0f))) {
            return;
        }
        InputDriver.lookAt(player, victim.getEyePosition());
        // 疾跑会让原版取消暴击判定(Player.attack 里 flag1 带 !isSprinting)。
        player.setSprinting(false);
        player.attack(victim);
        player.swing(InteractionHand.MAIN_HAND);
        r.strike(victim.getId());
    }

    private boolean targetRecovering() {
        return target instanceof LivingEntity living && living.hurtTime > 0;
    }

    private TaskState closeIn() {
        driveApproach();
        return TaskState.RUNNING;
    }

    /**
     * 走位:保持在目标够得着的距离上,同时离别的敌对生物远一点。
     *
     * <p>{@code MELEE} 与 {@code CLOSE_IN} 共用这一段 —— 它们只差"要不要挥",站位是一样的。
     * 分开写的时候,姿态一变就会拆掉刚算好的路径,而击退每砍一刀就让姿态变一次。
     */
    private void driveApproach() {
        if (target == null) {
            return;
        }
        if (nav == null) {
            // 目标会动:要 trackGoal 而不是 toGoal —— 后者一旦到达就永久 ARRIVED,
            // 她会站在原地不再跟位,别的怪就能从容贴上来。
            nav = PlayerNav.trackGoal(player, this::standoffGoal, CHASE_SPEED,
                    () -> target == null || target.isRemoved());
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { }
            case FAILED -> {
                stopNav();
                noteApproachFailure(target.getId());
            }
        }
    }

    /**
     * 她能够到当前目标的中心距离。目标没了就退回她自己那一格的量。
     *
     * <p>大史莱姆宽 2.04,半宽就一格出头 —— 按 3.0 硬比会把它判成"够不着",而原版玩家
     * 打得到。判据的够到距离与站位的吸引半径必须是这同一个数。
     */
    private double reachToTarget() {
        double native0 = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
        return target == null || target.isRemoved()
                ? Swing.reachOf(native0)
                : Swing.reachTo(native0, target.getBbWidth());
    }

    /**
     * 战斗站位:走到够得着目标的地方,<b>而且脚下这一格不在任何一只的危险半径里</b>。
     *
     * <h2>为什么到达要问危险半径</h2>
     * 只问"够不够得着"的时候,她走进目标的球形邻域就判到达,而<b>一旦到达 A* 就不再搜索</b>,
     * 势场那份估价一次也用不上 —— 僵尸慢慢挪过来,她那一格仍然合格,于是不重新规划、不躲。
     * 躲得掉爆炸却防不了偷袭,根子在这。
     *
     * <p>光靠这一条还不够:目标是开路那一刻的<b>快照</b>。真正每刻重问的是判据那一侧
     * ({@code Battlefield.anyTooClose},用实时距离),这里管的是"落脚点别选在人家嘴边"。
     *
     * <h2>目标自己也在势场里</h2>
     * 它当然也会打她,所以不需要另画一条内沿:吸引项把她拉进够到距离,它自己的危险半径把她
     * 顶在够不着的地方,中间那条缝就是拉扯的位置。缝宽是原版碰撞箱给的 —— 僵尸 3.30 对 2.73,
     * 半格出头。
     *
     * <h2>被围住的时候</h2>
     * 没有合格的格子也不会失败:引擎的七档 {@code bestSoFar} 会交出这次搜索里最好的一段。
     */
    private NavGoal standoffGoal() {
        if (target == null || target.isRemoved()) {
            return null;
        }
        var field = Menace.hostilesAround(player, FIELD_RADIUS);
        logStandoff(field);
        // 威胁的间距给<b>零</b>:它们只让经过的格子变贵(边成本)与影响估价,
        // <b>不参与"到没到"</b>。
        //
        // 带内沿的时候到达条件是"在够到距离内、且离每一只都出了它的危险半径" —— 对骷髅那是
        // 一条 0.57 格宽的环(够到 3.30、它的危险半径 2.73),格分辨率下常常一格不剩;它还
        // 一边拉弓一边后退,环一直在跑。目标不可达 → 寻路连续失败 → 判成"够不着" → 她停在
        // 边缘再也不动。而"太近了"本来就该由判据每刻用<b>实时距离</b>问(anyTooClose → AVOID),
        // 不该让寻路去够那条环。
        return NavGoal.approachAvoiding(
                NavGoal.near(target.blockPosition(), reachToTarget()),
                Menace.AVOID_PENALTY,
                Menace.field(player, field).stream().map(t -> t.withClearance(0.0)).toList());
    }

    /** 站位日志只在数字真的变了时打一行——每 tick 一行会把别的全冲掉。 */
    private void logStandoff(List<Mob> field) {
        int tooClose = 0;
        for (var mob : field) {
            if (Menace.tooClose(mob, player)) {
                tooClose++;
            }
        }
        String line = String.format("目标=%d 距离=%.1f 够到=%.2f 危险半径=%.2f 太近=%d 场上=%d",
                target.getId(), player.distanceTo(target), reachToTarget(),
                Menace.dangerRadius(target, player), tooClose, field.size());
        if (!line.equals(lastStandoffLog)) {
            lastStandoffLog = line;
            Constants.LOG.info("[numen-attack] 站位 {}", line);
        }
    }

    /** 远程找射击位:同一段站位,只是吸引半径由射程给,不是够到距离。 */
    private NavGoal approachGoal(double radius) {
        if (target == null || target.isRemoved()) {
            return null;
        }
        return NavGoal.approachAvoiding(
                NavGoal.near(target.blockPosition(), Math.max(0.0, radius)),
                Menace.AVOID_PENALTY,
                Menace.field(player, Menace.hostilesAround(player, FIELD_RADIUS))
                        .stream().map(t -> t.withClearance(0.0)).toList());
    }

    // ==================== 远程 ====================

    private TaskState shootAt(Loadout loadout) {
        Loadout.Pick weapon = loadout.ranged();
        if (weapon == null) {
            return abandonTarget();
        }
        if (player.distanceTo(target) < RANGED_MIN_DISTANCE && !Menace.armed(target)) {
            abortShot();
            return backAway();
        }
        boolean crossbow = RangedShot.isCrossbow(weapon);
        Ballistics.Aim aim = Ballistics.findArrowShot(player.level(), player, target,
                shotVelocity(crossbow), ARROW_GRAVITY, ARROW_DRAG, ARROW_HITBOX_RADIUS,
                MAX_FIRING_RANGE, !crossbow);
        if (aim == null) {
            abortShot();
            return seekShotWindow();   // 没有弹道窗口:挪个位置再看
        }

        stopNav();
        navFailures.remove(target.getId());
        InputDriver.halt(player);
        InputDriver.lookAt(player, aim.lookPoint());

        ItemStack before = player.getMainHandItem();
        player.holdInHand(weapon.slot());
        if (player.getMainHandItem() != before && shot == null) {
            return TaskState.RUNNING;   // 这一刻只换手,下一刻才起手
        }
        if (!RangedShot.stillHolding(crossbow, player.getMainHandItem())) {
            abortShot();
            return TaskState.RUNNING;
        }
        if (player.isUsingItem() && shot == null) {
            return TaskState.RUNNING;
        }
        if (shot == null) {
            shot = new RangedShot(player, crossbow);
        }
        if (shot.tick(aim, target)) {
            boolean fired = shot.fired();
            shot = null;
            if (fired) {
                r.strike(target.getId());
                misfires = 0;
            } else if (++misfires >= MAX_MISFIRES) {
                fail("the bow or crossbow did not launch an arrow", FailureType.WRONG_TOOL);
                return TaskState.FAILED;
            }
        }
        return TaskState.RUNNING;
    }

    private double shotVelocity(boolean crossbow) {
        return shot != null ? shot.projectileVelocity(BOW_FULL_SPEED, CROSSBOW_SPEED)
                : crossbow ? CROSSBOW_SPEED : BOW_FULL_SPEED * RangedShot.bowPowerForTicks(15);
    }

    /** 射不到:逼近一档再看。逼到不能再逼还是没窗口,才算这只够不着。 */
    private TaskState seekShotWindow() {
        if (nav == null) {
            nav = PlayerNav.trackGoal(player, () -> approachGoal(followRadius), CHASE_SPEED,
                    () -> target == null || target.isRemoved());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> {
                double next = Math.max(RANGED_MIN_DISTANCE + 1.0, followRadius - 5.0);
                if (next < followRadius - 0.01) {
                    followRadius = next;
                } else if (target != null) {
                    noteApproachFailure(target.getId());
                }
                stopNav();
            }
        }
        return TaskState.RUNNING;
    }

    // ==================== 躲避 ====================

    /**
     * 拉开距离:站到离每一只都出了它<b>危险半径</b>的地方。
     *
     * <p>逃跑不走这儿 —— 那是"挑一个落点跑到底"({@link #tickFlee}),两件事的形状不一样:
     * 拉扯的终点是一圈约束(两三格,退出去就能接着打),逃跑的终点是一个坐标。
     *
     * @param terminal 退到哪算完
     * @return 导航这一刻的状态
     */
    private PlayerNav.Status driveRetreat(BooleanSupplier terminal) {
        if (nav == null) {
            Constants.LOG.info("[numen-attack] 退避 拉开 —— {} 只在危险半径内",
                    Menace.dangersAround(player, FIELD_RADIUS).size());
            // 威胁快照<b>在 supplier 里面</b>取:它每次重规划调一次,坐标跟着刷新。
            // 放在外面就是把开路那一刻钉死,它们追上来之后她还按旧位置退。
            nav = PlayerNav.trackGoal(player, () -> {
                var field = Menace.hostilesAround(player, FIELD_RADIUS);
                return field.isEmpty() ? null
                        : NavGoal.avoid(Menace.AVOID_PENALTY, Menace.field(player, field));
            }, CHASE_SPEED, terminal);
        }
        PlayerNav.Status status = nav.tick();
        if (status != PlayerNav.Status.RUNNING) {
            stopNav();
        }
        if (status == PlayerNav.Status.FAILED) {
            retreatFailures++;
        } else if (status == PlayerNav.Status.RUNNING) {
            retreatFailures = 0;   // 走得动就不是被围住
        }
        return status;
    }

    /**
     * 挪出危险半径,然后接着打。判据说"该躲了"和这里说"躲够了"问的是<b>同一个函数</b>
     * ({@code Menace.tooClose}),不会一个说还危险另一个说已经到位。
     *
     * <p>不留余量:够到距离比危险半径大出半格到一格,退到边缘她就能挥刀。
     */
    private TaskState backAway() {
        driveRetreat(() -> Menace.dangersAround(player, FIELD_RADIUS).isEmpty());
        return TaskState.RUNNING;
    }

    /**
     * 脱离接触:她扛不住了,先活下来。
     *
     * <p>终止条件就是那 {@link Menace#FLEE_DISTANCE} 格 —— 与逃跑目标的到达条件同一个数。
     */
    /**
     * 逃跑。<b>只有一个判据:三十二格内还有没有敌对生物。</b>
     *
     * <p>跑法是"挑一个落点跑到底",不是"越远越好":后者在十几只怪围着时目标无解,只能靠
     * {@code bestSoFar},而逃跑势场 {@code 1/d²} 五格之后就平了,排序里几乎是噪声 ——
     * 于是每次重规划挑的方向都不一样,她在二十来格见方的框里绕圈。
     */
    private TaskState tickFlee() {
        var around = Menace.hostilesAround(player, Menace.FLEE_DISTANCE);
        if (around.isEmpty()) {
            endFlee();
            Constants.LOG.info("[numen-attack] 脱离成功 —— {} 格内没有敌对生物",
                    (int) Menace.FLEE_DISTANCE);
            // 说清楚为什么脱离:扛不住,还是这一架根本打不了。模型对这两种能做的事不一样。
            fail(Menace.outmatched(player)
                            ? "broke off — too hurt to keep fighting; nothing is near you now"
                            : "broke off — nothing here can be fought with what you carry "
                                    + "(explosive, or out of reach with no bow); you are clear now",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (haven == null || player.blockPosition().closerThan(haven, HAVEN_ARRIVED)) {
            haven = Haven.awayFrom(player, Menace.hostilesAround(player, FLEE_SCAN_RADIUS));
            stopNav();
            Constants.LOG.info("[numen-attack] 逃向 {} —— {} 格内 {} 只",
                    haven, (int) Menace.FLEE_DISTANCE, around.size());
        }
        if (haven == null) {
            // 四面八方都没有可站的落点(未加载、悬崖、水面)。交回判据,别挂在这儿。
            endFlee();
            Constants.LOG.info("[numen-attack] 没有可跑的方向 —— 交回判据");
            return TaskState.RUNNING;
        }
        long now = player.level().getGameTime();
        if (nav != null && now - havenPlannedAt >= FLEE_REPLAN_TICKS) {
            stopNav();   // 到点重算:落点不变,只让这一刻的怪进边成本
        }
        if (nav == null) {
            // 目标是一个坐标,永远可达;<b>威胁表挂在目标上</b>,搜索器据此建边成本惩罚球
            // ({@code Avoidance.forGoal})。间距给零 —— 它们只让路线变贵,不改变"到没到"。
            BlockPos landing = haven;
            havenPlannedAt = now;
            nav = PlayerNav.toGoal(player, () -> NavGoal.approachAvoiding(
                            NavGoal.nearGround(landing, HAVEN_ARRIVED),
                            Menace.AVOID_PENALTY, roadHazards()),
                    CHASE_SPEED, () -> false);
        }
        PlayerNav.Status status = nav.tick();
        probeFlee(player.level().getGameTime(), status);
        if (status == PlayerNav.Status.FAILED) {
            stopNav();
            haven = null;   // 这个方向走不通,下一刻换一个
            if (++retreatFailures >= MAX_APPROACH_FAILURES) {
                endFlee();
                Constants.LOG.info("[numen-attack] 跑不掉 —— 交回判据,背水一战");
            }
        } else if (status == PlayerNav.Status.ARRIVED) {
            stopNav();
            haven = null;   // 到了,下一刻重新挑
            retreatFailures = 0;
        } else {
            retreatFailures = 0;
        }
        return TaskState.RUNNING;
    }

    /**
     * 路上要绕开谁。<b>间距给零</b>:它们只让经过的格子变贵(边成本 ×4)与影响估价,
     * 不参与"到没到"——落点旁边站着一只怪也算到了,不然她永远到不了、也就永远不换落点。
     */
    private java.util.List<com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities.Threat>
            roadHazards() {
        return Menace.field(player, Menace.hostilesAround(player, FLEE_SCAN_RADIUS)).stream()
                .map(t -> t.withClearance(0.0))
                .toList();
    }

    /** 收掉逃跑状态。终点、跑不掉、没方向 —— 三个出口都过这里。 */
    private void endFlee() {
        fleeing = false;
        haven = null;
        stopNav();
        InputDriver.halt(player);
    }

    /**
     * 脱离接触:她扛不住了,先活下来。
     *
     * <p>终止条件就是那 {@link Menace#FLEE_DISTANCE} 格 —— 与逃跑目标的到达条件同一个数。
     *
     * <p>势场收当前<b>所有</b>敌对生物:逃跑路上撞进第二只怪,是旧的单点逃离目标最典型的死法。
     */

    /**
     * 逃跑探针。每秒一行,回答三个只能靠位置分辨的问题:
     *
     * <ul>
     *   <li><b>挪了几格 ≈ 0</b> —— 走不动,问题在执行层,不在判据</li>
     *   <li><b>挪得动但最近距离不变</b> —— 跑得掉但甩不掉,是速度不够</li>
     *   <li><b>最近距离在拉开</b> —— 逻辑对了,只是还没到窗口</li>
     * </ul>
     */
    private void probeFlee(long now, PlayerNav.Status status) {
        net.minecraft.world.phys.Vec3 here = player.position();
        double nearest = Double.MAX_VALUE;
        for (var mob : Menace.hostilesAround(player, Menace.FLEE_DISTANCE)) {
            nearest = Math.min(nearest, player.distanceTo(mob));
        }
        if (fleeMark == null || now - fleeMarkTick >= 20) {
            String moved = fleeMark == null ? "-"
                    : String.format("%.1f", here.distanceTo(fleeMark));
            String closing = fleeMark == null || fleeMarkNearest == Double.MAX_VALUE ? "-"
                    : String.format("%+.1f", nearest - fleeMarkNearest);
            Constants.LOG.info("[numen-flee] {} 脚下={},{},{} 这一秒挪了 {} 格 疾跑={} 饱食={} "
                            + "最近敌人={} (变化 {}) 三十二格内={}",
                    status,
                    (int) here.x, (int) here.y, (int) here.z,
                    moved,
                    player.isSprinting(),
                    player.getFoodData().getFoodLevel(),
                    nearest == Double.MAX_VALUE ? "-" : String.format("%.1f", nearest),
                    closing,
                    Menace.hostilesAround(player, Menace.FLEE_DISTANCE).size());
            fleeMark = here;
            fleeMarkNearest = nearest;
            fleeMarkTick = now;
        }
    }

    /** 这一刻真正在追她的那些(锁定了她、或刚打了她)。 */
    private List<Mob> chasersNow() {
        List<Mob> out = new ArrayList<>();
        for (var mob : Menace.hostilesAround(player, FIELD_RADIUS)) {
            if (mob.getTarget() == player || mob == player.getLastHurtByMob()) {
                out.add(mob);
            }
        }
        return out;
    }

    private TaskState abandonTarget() {
        if (target == null) {
            return TaskState.RUNNING;
        }
        r.unreachable(target.getId());
        Constants.LOG.info("[numen-attack] 放弃 foe={} —— {}", target.getId(),
                Menace.armed(target)
                        ? "不该贴近它,而我没有能用的弓弩"
                        : "走不到它,也没有能用的弓弩");
        stopNav();
        abortShot();
        target = null;
        lastMove = null;
        return TaskState.RUNNING;
    }

    // ==================== 拾荒 ====================

    private void beginLoot(Vec3 where) {
        stopNav();
        abortShot();
        InputDriver.halt(player);
        loot.begin(BlockPos.containing(where != null ? where : player.position()));
        target = null;
        lastMove = null;   // 目标没了,承诺一并作废
        phase = Phase.LOOT;
    }

    private TaskState tickLoot() {
        loot.discover();
        if (loot.settling()) {
            InputDriver.halt(player);
            return TaskState.RUNNING;
        }
        loot.prune();
        if (loot.live().isEmpty()) {
            stopNav();
            loot.finish();
            phase = Phase.COMBAT;
            return TaskState.RUNNING;
        }
        if (nav == null) {
            nav = PlayerNav.toGoal(player, loot::goal, 1.0, () -> loot.live().isEmpty());
        }
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED, FAILED -> {
                loot.noteApproachFailure();
                stopNav();
            }
        }
        return TaskState.RUNNING;
    }

    // ==================== 收尾与回执 ====================

    private void abortShot() {
        if (shot != null) {
            shot.abort();
            shot = null;
        }
    }

    private void snapshotInventory(Map<Item, Integer> out) {
        out.clear();
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty()) out.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private Map<String, Integer> lootGained() {
        Map<Item, Integer> now = new HashMap<>();
        snapshotInventory(now);
        Map<String, Integer> gained = new LinkedHashMap<>();
        now.forEach((item, count) -> {
            int delta = count - inventoryBaseline.getOrDefault(item, 0);
            if (delta > 0) gained.put(BuiltInRegistries.ITEM.getKey(item).toString(), delta);
        });
        return gained;
    }

    @Override
    protected void cleanup() {
        abortShot();
        InputDriver.halt(player);
        player.setShiftKeyDown(false);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        // 逐个报账要覆盖<b>所有经手过的 id</b>,不能只遍历请求清单 —— 无差别模式那份是空的,
        // 照旧遍历会把整场战果吞掉。
        java.util.Set<Integer> touched = new java.util.LinkedHashSet<>(r.entityIds);
        touched.addAll(r.defeated());
        touched.addAll(r.lost());
        touched.addAll(r.unreachable());
        Map<String, Object> byEntity = new LinkedHashMap<>();
        for (int id : touched) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("status", r.status(id));
            entry.put("strikes", r.strikes(id));
            byEntity.put(String.valueOf(id), entry);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mode", r.indiscriminate ? "nearby_hostiles" : "named_ids");
        data.put("requested_entity_ids", r.entityIds);
        data.put("defeated_entity_ids", r.defeated());
        data.put("lost_entity_ids", r.lost());
        data.put("unreachable_entity_ids", r.unreachable());
        data.put("strikes", r.strikes());
        data.put("combat_by_entity", byEntity);
        data.put("loot_gained", lootGained());
        data.put("unreachable_drop_count", loot.unreachableCount());
        return data;
    }

    private String tally() {
        return (r.indiscriminate
                ? r.defeated().size() + " hostiles"
                : r.defeated().size() + "/" + r.entityIds.size() + " requested entities")
                + ", collected " + lootGained();
    }

    @Override
    protected String successMessage() {
        if (r.indiscriminate) {
            return "fought off " + tally() + "; nothing is coming after you any more";
        }
        int incomplete = r.lost().size() + r.unreachable().size();
        return "defeated " + tally()
                + (incomplete == 0 ? "" : " (" + incomplete + " targets could not be completed)");
    }

    @Override
    protected String timeoutMessage() {
        return "attack timed out after defeating " + tally();
    }

    @Override
    protected String cancelledMessage() {
        return "attack interrupted after defeating " + tally();
    }
}
