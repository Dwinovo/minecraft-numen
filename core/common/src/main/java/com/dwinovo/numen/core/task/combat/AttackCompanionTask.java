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
import net.minecraft.world.item.Items;
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
    /** 退避的寻路连续失败几次算"退不掉"。 */
    private static final int MAX_RETREAT_FAILURES = 3;

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

    /**
     * 弓战斗的环内沿:比这更近就拉不开弓 —— 弹道压得平,而且白白挨打。
     *
     * <p>它<b>就是</b>弓那一套的"危险半径",和剑那一套的 {@code Menace.rawDangerRadius}
     * 同一个位置、不同的数。以前它是散在判据里的一个 {@code if},和剑的环互相打架。
     *
     * <p>八格,不是五格:<b>拉满一张弓要二十刻</b>,这二十刻里僵尸能走四格半。五格的话她刚
     * 拉到一半人就贴脸了,只能中断重来 —— 实测她在 0.6~2.9 格里挣扎,最后被爬行者炸死。
     * 内沿要装得下"拉一次弓的工夫对方能走多远"。
     */
    private static final double BOW_MIN_DISTANCE = 8.0;

    /**
     * 弓战斗的环外沿。<b>不是射程上限</b> —— 三十二格的话她能站在天边,而箭有下坠、目标
     * 会走,那么远基本射不中。十二格是"稳稳能中、又够得开"的量级:太远就往回走。
     */
    private static final double BOW_MAX_DISTANCE = 12.0;

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

    /**
     * 上一次搜索<b>搜不出路</b>的目标。够不着是拓扑性质,不是距离性质 —— 悬崖对面三格的
     * 骷髅离得很近却没有路,所以只有寻路自己说得清。
     *
     * <p>每次重搜刷新:搜出路了就移出去。它不是一次判死,是"上一段搜索的结论"。
     */
    private final java.util.Set<Integer> noPath = new java.util.HashSet<>();

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

    private RangedShot shot;
    private int misfires;
    private int lastPlanLogTick = -1000;
    private AttackPlan.Action lastLoggedAction;
    /** 上一刻的决定。判据靠它做迟滞与承诺,见 {@link AttackPlan#decide}。 */
    private AttackPlan.Move lastMove;

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
        }
        if (target != null) {
            lastTargetPosition = target.position();
            loot.rememberPreexisting(BlockPos.containing(lastTargetPosition));
        }
        // 攻击与移动<b>正交</b>:每刻先问一次"冷却好了吗、够得着谁吗",够得着就打 ——
        // 不管这一刻在靠近、在拉开、还是站着。攻击不影响寻路,最多让她回个头。
        tickShield();
        tickWeapon(field);
        if (move.action() != AttackPlan.Action.DISENGAGE && haven != null) {
            haven = null;   // 不再逃跑了:落点作废,下次要跑再重新挑
            stopNav();
        }
        return switch (move.action()) {
            case SKIRMISH -> {
                bowFighting = false;
                yield closeIn();
            }
            case BOW -> {
                bowFighting = true;
                yield bowFight();
            }
            case DISENGAGE -> tickFlee();
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
            if (r.terminal(mob.getId())) {
                // 打完了、丢了、或者走不到又射不到的:<b>整只移出局面</b>。留着当"还有东西在
                // 追我"的话,判据会永远喊走位 —— 一只在悬崖对面射她的骷髅就能把任务钉死。
                // 躲它归寻路的势场管,那一层看的是场上的怪,不是这份名单。
                continue;
            }
            foes.add(new Battlefield.Foe(
                    mob.getId(),
                    player.distanceTo(mob),
                    Menace.explodes(mob),
                    Menace.armed(mob),
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
                            Menace.explodes(e), Menace.armed(e),
                            false, reachable(id), true));
                }
            }
        }
        Loadout loadout = Loadout.forTarget(player, player);
        return new Battlefield(
                Menace.effectiveHealth(player),
                reachToTarget(),
                loadout.hasMelee(), loadout.hasRanged(),
                retreatFailures >= MAX_RETREAT_FAILURES, foes);
    }

    private static boolean containsId(List<Battlefield.Foe> foes, int id) {
        for (var f : foes) {
            if (f.id() == id) return true;
        }
        return false;
    }

    private boolean reachable(int id) {
        return !noPath.contains(id);
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

    /**
     * 搜不出路那一刻裁一次:<b>射得到就改用弓,连弹道都没有就是无解</b>,这一只不打了。
     *
     * <pre>
     * 有路           → 剑
     * 没路 + 有弹道  → 弓
     * 没路 + 没弹道  → 放弃这只(换下一只,没别的就收工)
     * </pre>
     *
     * <p>只在 NO-PATH 落定那一刻取一次样。搜索烧完整个预算才给得出这个结论,不是抖出来
     * 的;而"这一刻恰好没弹道"确实会抖,所以它不单独构成放弃 —— 两个条件同时成立才算。
     */
    private void judgeNoPath(Entity foe) {
        noPath.add(foe.getId());
        if (Loadout.forTarget(player, foe).hasRanged() && shotExistsTo(foe)) {
            return;   // 走不到但射得到:顶层下一刻自然改判弓
        }
        r.unreachable(foe.getId());
        Constants.LOG.info("[numen-attack] 放弃 目标={} 走不到,也没有弹道", foe.getId());
    }

    /** 这一刻算不算得出一条能打到它的箭道。射不到的角落里的怪就是无解。 */
    private boolean shotExistsTo(Entity foe) {
        return Ballistics.findArrowShot(player.level(), player, foe,
                BOW_FULL_SPEED * RangedShot.bowPowerForTicks(15), ARROW_GRAVITY, ARROW_DRAG,
                ARROW_HITBOX_RADIUS, MAX_FIRING_RANGE, true) != null;
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
     * 盾。与攻击、寻路并列的<b>第三层</b>,同样每刻问一次,同样不管别人在干嘛。
     *
     * <pre>
     * 弓战斗中                       → 不碰(拉弓和举盾抢同一个 useItem,原版硬约束)
     * 有谁进了它的危险半径 且 盾能举 → 举
     * 否则                           → 放
     * </pre>
     *
     * <p>不看攻击冷却:原版举着盾照样能挥刀,两件事不冲突;也不拦攻击层 —— 那样两层就又
     * 耦上了。举着会减速,代价认了:能挡住的那一下比早退半格值。盾被斧子破了会进冷却,
     * 那时她就正常跑。
     *
     * <p>同行没有可抄的(AltoClef 完全没有用盾逻辑,Meteor 管的是怎么破<b>对手</b>的盾),
     * 这套判据与 PR #13 的 {@code ShieldCombatPolicy} 同源,只是去掉了"冷却好了放盾"
     * 那一步 —— 既然能边举边砍,那一步是多余的。
     */
    private void tickShield() {
        if (bowFighting) {
            return;
        }
        boolean raised = shieldRaised();
        boolean threatened = false;
        for (var mob : Menace.hostilesAround(player, FIELD_RADIUS)) {
            if (Menace.tooClose(mob, player)) {
                threatened = true;
                break;
            }
        }
        if (!threatened) {
            if (raised) {
                player.releaseUsingItem();
            }
            return;
        }
        if (raised || player.isUsingItem()) {
            return;   // 已经举着,或者手上占着别的东西
        }
        ItemStack shield = player.getOffhandItem().is(Items.SHIELD)
                ? player.getOffhandItem() : equipShield();
        if (shield.isEmpty() || player.getCooldowns().isOnCooldown(shield.getItem())) {
            return;   // 没盾,或者被斧子破了还在冷却 —— 正常跑
        }
        player.startUsingItem(InteractionHand.OFF_HAND);
    }

    private boolean shieldRaised() {
        return player.isUsingItem()
                && player.getUsedItemHand() == InteractionHand.OFF_HAND
                && player.getUseItem().is(Items.SHIELD);
    }

    /**
     * 副手空着就从背包里拿一面盾装上 —— <b>剑早就能自动换手,盾没道理不能</b>。
     *
     * <p>只在副手<b>空着</b>时装:主人可能正指望那一格放别的东西,不该替他决定。
     */
    private ItemStack equipShield() {
        if (!player.getOffhandItem().isEmpty()) {
            return ItemStack.EMPTY;
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.SHIELD)) {
                ItemStack shield = stack.split(1);
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, shield);
                player.inventoryMenu.broadcastChanges();
                return shield;
            }
        }
        return ItemStack.EMPTY;
    }

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
        if (player.isUsingItem() && !shieldRaised()) {
            return;   // 正在拉弓或吃东西,别打断
        }
        // <b>举着盾照样挥刀</b> —— 原版这两件事不冲突。这条守卫本意是拦"正在拉弓",
        // 却写成了"手上用着任何东西";副手多了一面盾之后,它把攻击层整个锁死:
        // 实测她站在带里(距离 2.0~2.9、带 [2.02, 3.30])一刀不挥,看着像只躲不打。
        // 武器是<b>可选的</b>:拳头一点伤害,鸡四血、羊八血、牛十血,照样打得动。
        // 这里曾经"没有近战武器就直接返回" —— 那是按"打怪"写的前提(赤手对上会还手的
        // 东西不是出路),模型让她打一只鸡时那个前提不成立,她会走到跟前站着不动。
        Loadout loadout = Loadout.forTarget(player, player);
        Entity victim = null;
        double best = Double.MAX_VALUE;
        for (var f : field.foes()) {
            // <b>名单只决定去打谁,不决定砍不砍眼前的。</b>"够得着就打"本来就是攻击层的
            // 定义,掺进"这只在不在名单里"就又把两层耦上了 —— 而且点名模式下路上被贴脸
            // 也不还手,得挨完一路才到目标。
            if (f.armed() || f.distance() >= best) {
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
        if (loadout.hasMelee()) {
            player.holdInHand(loadout.melee().slot());
        }
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

    /**
     * 弓战斗:<b>和剑战斗同一段走位</b>,只是环换了一副(内沿 {@link #BOW_MIN_DISTANCE},
     * 外沿射程)。带内导航自然到达、她停下来,这时才拉弓 —— "什么时候该站定"不用另写。
     */
    private TaskState closeIn() {
        driveApproach();
        return TaskState.RUNNING;
    }

    private TaskState bowFight() {
        // <b>两层并行</b>:脚一直在走位,手一直在拉弓。原版拉弓时本来就能走(只是慢),
        // 是我在 shootAt 里主动 halt 的 —— 于是每刻建一次导航、拆一次,看着像被打断。
        driveApproach();
        return shootAt(Loadout.forTarget(player, target));
    }




    /**
     * 走位:保持在目标够得着的距离上,同时离别的敌对生物远一点。
     *
     * <p>{@code MELEE} 与 {@code CLOSE_IN} 共用这一段 —— 它们只差"要不要挥",站位是一样的。
     * 分开写的时候,姿态一变就会拆掉刚算好的路径,而击退每砍一刀就让姿态变一次。
     */
    private PlayerNav.Status driveApproach() {
        if (nav == null) {
            // <b>没有目标也要走。</b>判据的 SKIRMISH 可以是"对全场的"(挑不出能打的,但还有
            // 东西追她),那时该退开等机会 —— 这里曾经第一行就 {@code target == null} 早退,
            // 于是判据每刻正确地喊"走位"、执行层每刻安静地什么都不做,日志看着一切正常,
            // 直到她被苦力怕炸死。什么时候不用走由 standoffGoal 说(场上空了返回 null)。
            //
            // 目标会动:要 trackGoal 而不是 toGoal —— 后者一旦到达就永久 ARRIVED,
            // 她会站在原地不再跟位,别的怪就能从容贴上来。
            nav = PlayerNav.trackGoal(player, this::standoffGoal, CHASE_SPEED, () -> false);
        }
        PlayerNav.Status status = nav.tick();
        // <b>只有真 NO-PATH 才算够不着</b>:搜索烧完整个预算也没找出路线。目标丢了、被围死、
        // 重规划抖动都是另外的事,拿它们当够不着会把两格外的普通僵尸也判死。
        boolean noRoute = status == PlayerNav.Status.FAILED
                && (nav.failType() == FailureType.NO_PATH
                        || nav.failType() == FailureType.TERRAIN_BLOCKED);
        if (status == PlayerNav.Status.FAILED) {
            stopNav();
        }
        // 弓那一套的环在 8~12 格,和近战的环问的不是同一个问题,它的成败说明不了可达性。
        if (target != null && !bowFighting) {
            if (noRoute) {
                judgeNoPath(target);
            } else {
                noPath.remove(target.getId());
            }
        }
        return status;
    }

    /**
     * 她能够到当前目标的中心距离。目标没了就退回她自己那一格的量。
     *
     * <p>大史莱姆宽 2.04,半宽就一格出头 —— 按 3.0 硬比会把它判成"够不着",而原版玩家
     * 打得到。判据的够到距离与站位的吸引半径必须是这同一个数。
     */
    /** 这一刻走的是弓那一套吗。环的内外沿、以及攻击层用什么,都看它。 */
    private boolean bowFighting;

    /** 走位环的外沿:剑是够到距离,弓是 {@link #BOW_MAX_DISTANCE}。 */
    private double skirmishOuter() {
        return bowFighting ? BOW_MAX_DISTANCE : reachToTarget();
    }

    /** 走位环的内沿:剑是"它够得着我",弓是"拉得开弓的距离"。 */
    private double skirmishInner() {
        return bowFighting ? BOW_MIN_DISTANCE
                : target == null ? 0.0 : Menace.rawDangerRadius(target, player);
    }

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
     * 这里管的是"落脚点别选在人家嘴边"。
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
        var field = Menace.hostilesAround(player, FIELD_RADIUS);
        if (field.isEmpty()) {
            return null;
        }
        logStandoff(field);
        if (target == null || target.isRemoved()) {
            // <b>没有目标也照样走位</b>:环退化成"离每一只都出了它的危险半径"。
            // 场上只剩一只点着的爬行者(她没弓打不了)时走的就是这一支 —— 退开等引信熄,
            // 而不是跑三十二格。
            return NavGoal.avoid(Menace.AVOID_PENALTY, Menace.field(player, field));
        }
        // 走位是<b>一个环</b>:外沿别跟丢,内沿是每一只都够不着她。太近自然往外走,太远
        // 自然往回走 —— "拉开"不是另一个动作。
        //
        // 外沿<b>就是她的够到距离</b>。寻路不负责"打",但必须把她送进打得到的范围,否则
        // 攻击层一辈子没机会 —— 外沿放宽到 4.73 那一版,她走到 4.7 就"到位"停下,而够到
        // 距离只有 3.30,于是站在那儿挨打,实测有效血量 8 掉到 5。
        //
        // 内沿用<b>裸</b>攻击距离(2.02),不加格量化补偿。带宽因此是 1.28 格,比格量化误差
        // 0.71 宽出一截 —— 当初算出"带只有 0.57 格、做不出来",是因为把补偿也叠进了内沿。
        return NavGoal.approachAvoiding(
                NavGoal.ring(target.blockPosition(), skirmishInner(), skirmishOuter()),
                Menace.AVOID_PENALTY,
                bowFighting
                        ? Menace.field(player, field).stream()
                                .map(x -> x.withClearance(
                                        Math.max(x.clearance(), BOW_MIN_DISTANCE)))
                                .toList()
                        : Menace.field(player, field));
        // 弓那一套的内沿对<b>每一只</b>都成立:她要跟所有怪保持五格,不只是当前目标。
    }

    /** 站位日志只在数字真的变了时打一行——每 tick 一行会把别的全冲掉。 */
    private void logStandoff(List<Mob> field) {
        int tooClose = 0;
        for (var mob : field) {
            if (Menace.tooClose(mob, player)) {
                tooClose++;
            }
        }
        String line = target == null || target.isRemoved()
                ? String.format("无目标(只拉开) 太近=%d 场上=%d", tooClose, field.size())
                : String.format("%s 目标=%d 距离=%.1f 带=[%.2f, %.2f] 太近=%d 场上=%d",
                        bowFighting ? "弓" : "剑", target.getId(), player.distanceTo(target),
                        skirmishInner(), skirmishOuter(), tooClose, field.size());
        if (!line.equals(lastStandoffLog)) {
            lastStandoffLog = line;
            Constants.LOG.info("[numen-attack] 站位 {}", line);
        }
    }

    // ==================== 远程 ====================

    private TaskState shootAt(Loadout loadout) {
        Loadout.Pick weapon = loadout.ranged();
        if (weapon == null) {
            return closeIn();   // 弓没了:回去走位,别放弃这只
        }
        // <b>攻击层不管距离。</b>射程之内就射,拉开是寻路的事(环的内沿 BOW_MIN_DISTANCE)。
        //
        // 这里曾经"近于内沿就 abortShot":僵尸一走进八格,拉到一半的弓当场取消;她退开、
        // 重新起手、僵尸又跟进来 —— 一箭都放不出去。距离是走位的判据,混进攻击层就成了
        // 一个把自己打断的开关。
        if (player.distanceTo(target) > BOW_MAX_DISTANCE) {
            return TaskState.RUNNING;   // 射程外:不放,但<b>也不取消</b>,弓接着拉
        }
        boolean crossbow = RangedShot.isCrossbow(weapon);
        Ballistics.Aim aim = Ballistics.findArrowShot(player.level(), player, target,
                shotVelocity(crossbow), ARROW_GRAVITY, ARROW_DRAG, ARROW_HITBOX_RADIUS,
                MAX_FIRING_RANGE, !crossbow);
        if (aim == null) {
            // 这一刻算不出弹道。<b>弓接着拉</b> —— 脚一直在走位,下一刻位置变了自会有窗口,
            // 取消了就白等一次拉满的时间。
            return TaskState.RUNNING;
        }

        // <b>不停脚。</b>攻击层与寻路层正交:挥刀不停脚,拉弓也不该停 —— 原版拉弓时本来
        // 就能走。这里曾经 stopNav() + halt(),而 bowFight 上一行刚 driveApproach() 建好
        // 导航,于是每刻建一次拆一次,箭一直拉不满。
        // <b>只在快松手那一刻转过去。</b>原版的箭朝哪飞只看松手那一刻的视线,拉弓的十几刻
        // 里瞄不瞄没有区别 —— 而每刻转向会把脚带偏(移动按朝向投影),她就一路走进目标脸上。
        // 挥刀早就是这么做的,弓这一支一直没跟上。
        if (shot != null && shot.aboutToRelease()) {
            InputDriver.lookAt(player, aim.lookPoint());
        }

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

    // ==================== 躲避 ====================

    /**
     * 脱离接触:她扛不住了,先活下来。
     *
     * <p>终止条件就是那 {@link Menace#FLEE_DISTANCE} 格 —— 与逃跑目标的到达条件同一个数。
     */
    /**
     * 逃跑这一刻做什么:跑向落点。
     *
     * <p><b>它不是一个"状态"。</b>顶层每刻重判"还打不打得过",打不过就再走一次这里,
     * 血回来了下一刻自然回到战斗——曾经这里是一个闩锁({@code fleeing}),进去就把判据
     * 整个短路,于是血回满了也一直跑,实测一次 DISENGAGE 配四十七行逃跑采样。
     *
     * <p>三十二格是<b>跑的目标</b>,不是状态的出口:跑到了就没什么可跑的,判据自会改口。
     */
    private TaskState tickFlee() {
        var around = Menace.hostilesAround(player, Menace.FLEE_DISTANCE);
        if (around.isEmpty()) {
            clearHaven();
            InputDriver.halt(player);
            Constants.LOG.info("[numen-attack] 脱离成功 —— {} 格内没有敌对生物",
                    (int) Menace.FLEE_DISTANCE);
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
            Constants.LOG.info("[numen-attack] 没有可跑的方向");
            return TaskState.RUNNING;
        }
        long now = player.level().getGameTime();
        if (nav != null && now - havenPlannedAt >= FLEE_REPLAN_TICKS) {
            stopNav();   // 到点重算:落点不变,只让这一刻的怪进边成本
        }
        if (nav == null) {
            BlockPos landing = haven;
            havenPlannedAt = now;
            nav = PlayerNav.toGoal(player, () -> NavGoal.approachAvoiding(
                            NavGoal.nearGround(landing, HAVEN_ARRIVED),
                            Menace.AVOID_PENALTY, roadHazards()),
                    CHASE_SPEED, () -> false);
        }
        PlayerNav.Status status = nav.tick();
        if (status == PlayerNav.Status.FAILED) {
            stopNav();
            haven = null;   // 这个方向走不通,下一刻换一个
            retreatFailures++;
        } else {
            if (status == PlayerNav.Status.ARRIVED) {
                stopNav();
                haven = null;
            }
            retreatFailures = 0;
        }
        return TaskState.RUNNING;
    }

    /** 丢掉落点与导航。跑到了、跑不动了、或者判据改口不跑了,都过这里。 */
    private void clearHaven() {
        haven = null;
        stopNav();
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

    /**
     * 脱离接触:她扛不住了,先活下来。
     *
     * <p>终止条件就是那 {@link Menace#FLEE_DISTANCE} 格 —— 与逃跑目标的到达条件同一个数。
     *
     * <p>势场收当前<b>所有</b>敌对生物:逃跑路上撞进第二只怪,是旧的单点逃离目标最典型的死法。
     */



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
