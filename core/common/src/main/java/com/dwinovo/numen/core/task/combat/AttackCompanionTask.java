package com.dwinovo.numen.core.task.combat;

import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.Ballistics;
import com.dwinovo.numen.core.combat.AttackPlan;
import com.dwinovo.numen.core.combat.Battlefield;
import com.dwinovo.numen.core.combat.Loadout;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.combat.Swing;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.nav.Trip;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Hotbar;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.Threat;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.Preparation;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

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

    private Entity target;
    private Vec3 lastTargetPosition;
    /**
     * 这一刻 {@link #FIELD_RADIUS} 内活着的敌对生物——<b>一刻只扫一次</b>,在 {@link #surveyField}
     * 里;举盾、走位的躲避场都读这一份。"场上有哪些怪"各算各的,就会出现判据说打、腿说没人的局面。
     */
    private List<Mob> hostiles = List.of();

    /**
     * 上一次搜索<b>搜不出路</b>的目标。够不着是拓扑性质,不是距离性质 —— 悬崖对面三格的
     * 骷髅离得很近却没有路,所以只有寻路自己说得清。
     *
     * <p>每次重搜刷新:搜出路了就移出去。它不是一次判死,是"上一段搜索的结论"。
     */
    private final java.util.Set<Integer> noPath = new java.util.HashSet<>();


    /**
     * 这一场经手过的 id。无差别模式没有事先的名单,不记下来就无处结算战果
     * ——它打倒的东西会因为"不在请求清单里"而被整场吞掉。
     */
    private final java.util.Set<Integer> touchedIds = new java.util.LinkedHashSet<>();

    /** 上一行站位日志。数字没变就不再打,免得每 tick 一行把别的全冲掉。 */
    private String lastStandoffLog;

    private RangedShot shot;
    private int misfires;
    private int lastPlanLogTick = -1000;
    private AttackPlan.Action lastLoggedAction;
    /** 上一刻的决定。判据靠它做迟滞与承诺,见 {@link AttackPlan#decide}。 */
    private AttackPlan.Move lastMove;

    public AttackCompanionTask(NumenPlayer player, AttackTaskRecord record) {
        super(player, record);
    }

    /**
     * 受理之前:点名的目标里还在的,权限层对每一只都说不许打,这件活就开始不了——和开打后一只都没打成时说的是同一句话
     * ({@link #refusedSummary}),当场回。要问主人的不算开始不了,开打后问;无差别清场的对手是开打那一刻谁在追她,这里不判。
     */
    @Override
    protected Preparation preparation() {
        if (r.indiscriminate) {
            return Preparation.READY;
        }
        var gate = Permission.gateFor(player);
        List<String> denied = new ArrayList<>();
        for (int id : r.entityIds) {
            Entity e = liveEntity(id);
            if (e == null) {
                continue;   // 找不到的照旧交给任务记成丢失
            }
            Verdict verdict = gate.judgeLive(Action.attack(e), player.serverLevel());
            if (verdict.kind() != Verdict.Kind.DENY) {
                return Preparation.READY;
            }
            denied.add(refusal(id, verdict.reason()));
        }
        return denied.isEmpty() ? Preparation.READY
                : Preparation.refused(TaskResult.fail(com.dwinovo.numen.agent.script.ErrorKind.DENIED,
                        "could not attack: " + String.join("; ", denied), null));
    }

    @Override
    protected void onStart() {
        // 这场仗归我管了 —— 本能链别再为同一件事抢身体。空闲时自动解除,不必显式还。
        player.pauseReflex(MobDefenseChain.ID);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) return TaskState.CANCELLED;

        Battlefield field = surveyField();
        for (var f : field.foes()) {
            if (f.authorized()) {
                touchedIds.add(f.id());
            }
        }
        settleFinishedTargets();
        // 扛不住的时候跑是逃跑本能的事(FleeChain):它抢过身体跑开,跑开了把身体交还这里。回来时她还扛不住、也没有谁
        // 在追她,就不再回去打——收工说清楚,下一步是程序的事
        if (Menace.outmatched(player) && field.foes().stream().noneMatch(Battlefield.Foe::engaging)
                && field.foes().stream().anyMatch(Battlefield.Foe::authorized)) {
            stopNav();
            abortShot();
            fail("too hurt to go back to the fight (effective health "
                    + Math.round(Menace.effectiveHealth(player)) + "); heal first (numen.inv.eat), then attack again",
                    FailureType.HAZARD);
            return TaskState.FAILED;
        }
        AttackPlan.Move move = AttackPlan.decide(field, lastMove);
        lastMove = move;
        logMove(move, field);
        if (move.action() == AttackPlan.Action.DONE && awaitingOwner) {
            player.controls().stop();   // 没别的可打,等主人点头
            return TaskState.RUNNING;
        }

        Entity chosen = move.foeId() == AttackPlan.NO_FOE ? null : liveEntity(move.foeId());
        if (chosen != target) {
            stopNav();
            abortShot();
            target = chosen;
        }
        if (target != null) {
            lastTargetPosition = target.position();
        }
        // 攻击与移动<b>正交</b>:每刻先问一次"冷却好了吗、够得着谁吗",够得着就打 ——
        // 不管这一刻在靠近、在拉开、还是站着。攻击不影响寻路,最多让她回个头。
        tickShield();
        tickWeapon(field);
        return switch (move.action()) {
            case SKIRMISH -> {
                bowFighting = false;
                yield closeIn();
            }
            case BOW -> {
                bowFighting = true;
                yield bowFight();
            }
            case DONE -> finish();
        };
    }

    // ==================== 局面 ====================

    /**
     * 把这一刻的世界折成 {@link Battlefield}。
     *
     * <p>点名模式下"被授权"是模型给的那份清单;无差别模式下是"这一刻在追我的"——会分裂的怪
     * 裂出来的新 id 因此自动进场,而点名的清单一裂开就作废了。
     *
     * <p>进场之前先过权限层({@link #cleared}):攻击层"够得着就打"只看局面,所以门必须开在这里,
     * 一处。要问的这一刻不进局面,一张卡问主人,主人点头下一刻进场;不许的记进账本,回执说清是谁、
     * 为什么。自卫换目标时新冒出来的要问的,同样再问。
     */
    private Battlefield surveyField() {
        hostiles = Menace.hostilesAround(player, FIELD_RADIUS);
        List<Entity> candidates = new ArrayList<>();
        for (var mob : hostiles) {
            boolean engaging = mob.getTarget() == player || mob == player.getLastHurtByMob();
            boolean authorized = r.indiscriminate ? engaging : r.entityIds.contains(mob.getId());
            if (authorized && !r.terminal(mob.getId())) {
                candidates.add(mob);
            }
        }
        if (!r.indiscriminate) {
            for (int id : r.entityIds) {
                Entity e = r.terminal(id) ? null : liveEntity(id);
                if (e != null && !candidates.contains(e)) {
                    candidates.add(e);
                }
            }
        }
        java.util.Set<Integer> cleared = cleared(candidates);
        List<Battlefield.Foe> foes = new ArrayList<>();
        for (var mob : hostiles) {
            // 点名的一场仗只看点名的那几只:打完了就收工,下一只打不打、打哪只是程序的事(numen.fight.clear),路上被别的
            // 贴脸是本能的事(反击链)。别的怪不进局面,也就不会被顺手砍、不会把这场仗拖着不收
            if (!r.indiscriminate && !r.entityIds.contains(mob.getId())) {
                continue;
            }
            boolean engaging = mob.getTarget() == player || mob == player.getLastHurtByMob();
            boolean authorized = r.indiscriminate ? engaging : r.entityIds.contains(mob.getId());
            if (authorized && !r.terminal(mob.getId()) && !cleared.contains(mob.getId())) {
                authorized = false;   // 在等主人点头:在场,但这一刻不是目标
            }
            if (r.terminal(mob.getId())) {
                // 打完了、丢了、走不到又射不到、或者不许打的:<b>整只移出局面</b>。留着当"还有
                // 东西在追我"的话,判据会永远喊走位 —— 一只在悬崖对面射她的骷髅就能把任务钉死。
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
                if (e != null && cleared.contains(id)) {
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
                loadout.hasMelee(), loadout.hasRanged(), foes);
    }

    /**
     * 这一批要打的交给权限层:放行的进场;要问的合成一张卡、等主人答复期间不进场;不许的(主人拒绝、
     * 规则、模式、外部强制)记进账本,从此不在局面里。
     *
     * @return 这一刻放行的实体 id
     */
    private java.util.Set<Integer> cleared(List<Entity> candidates) {
        List<Action> attacks = new ArrayList<>(candidates.size());
        for (Entity e : candidates) {
            attacks.add(Action.attack(e));
        }
        List<Permit> permits = permitAll(attacks);
        java.util.Set<Integer> cleared = new java.util.HashSet<>();
        awaitingOwner = false;
        for (int i = 0; i < candidates.size(); i++) {
            int id = candidates.get(i).getId();
            switch (permits.get(i).state()) {
                case ALLOWED -> cleared.add(id);
                case REFUSED -> r.refused(id, permits.get(i).refusal());
                case WAITING -> awaitingOwner = true;
            }
        }
        return cleared;
    }

    /** 这一刻有要打的在等主人点头:局面里没别的可打也不收场。 */
    private boolean awaitingOwner;

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
                } else {
                    r.lost(id);
                }
            } else if (e instanceof LivingEntity living && living.isDeadOrDying()) {
                r.defeated(id);
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
        player.controls().stop();
        stopNav();
        if (r.indiscriminate || !r.defeated().isEmpty()) {
            succeed();
            return TaskState.SUCCESS;
        }
        if (!r.refused().isEmpty() && r.refused().size() + r.lost().size() + r.unreachable().size()
                >= r.entityIds.size()) {
            // 一只都没打:不是找不到,是不许打。让模型去问主人,别换个法子再试。
            fail("could not attack: " + refusedSummary(), FailureType.REFUSED);
            return TaskState.FAILED;
        }
        fail("none of the requested entity ids could be attacked", FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    /** {@code entity 12 needs the owner's consent (has an owner); entity 15 ...}。 */
    private String refusedSummary() {
        List<String> parts = new ArrayList<>();
        r.refused().forEach((id, why) -> parts.add(refusal(id, why)));
        return String.join("; ", parts);
    }

    /** 不许打的一只怎么说:{@code entity 12 denied by rule …}。 */
    private static String refusal(int id, String why) {
        return "entity " + id + " " + why;
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
        for (var mob : hostiles) {
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
        Feet here = Feet.of(player);
        if (here == null) {
            return;   // 悬在半空:没有站着的那一格,够不够得着无从按站位判
        }
        Entity victim = null;
        double best = Double.MAX_VALUE;
        for (var f : field.foes()) {
            // 只砍这场仗里过了权限的:够得着的别的东西不是她该替自己决定去打的,也没过权限层
            if (!f.authorized() || f.armed() || f.distance() >= best) {
                continue;   // 引信在走的不碰:打它等于自己引爆
            }
            // 够不够得着与走位的到达问同一个目标(reachOf):走位说站到了,这里就挥得出去
            Entity e = liveEntity(f.id());
            if (e != null && here.in(reachOf(e))) {
                victim = e;
                best = f.distance();
            }
        }
        if (victim == null) {
            return;
        }
        ItemStack before = player.getMainHandItem();
        if (loadout.hasMelee()) {
            Hotbar.hold(player, loadout.melee().slot());
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
    private void driveApproach() {
        // <b>没有目标也要走。</b>判据的 SKIRMISH 可以是"对全场的"(挑不出能打的,但还有
        // 东西追她),那时该退开等机会。什么时候不用走由 standoffGoal 说(既无目标也无怪才返回 null)。
        Goal goal = standoffGoal();
        if (goal == null) {
            stopNav();
            return;
        }
        BlockPos toward = target != null && !target.isRemoved() ? target.blockPosition() : player.blockPosition();
        if (nav == null) {
            nav = Trip.to(player, goal, RouteSpec.defaults(), toward);
        } else {
            // 目标与怪每刻都在挪:每刻把这一刻的站位交给在走的这一趟,停点还算数就照走
            nav.retarget(goal, toward);
        }
        Trip.Status status = nav.tick();
        if (status == Trip.Status.ARRIVED) {
            // 站到位了。这一趟走完就收,下一刻按那时的站位再开一趟——一直站着不跟位,别的怪就能从容贴上来
            stopNav();
        }
        // <b>只有真 NO-PATH 才算够不着</b>:搜索烧完整个预算也没找出路线。目标丢了、被围死、
        // 重规划抖动都是另外的事,拿它们当够不着会把两格外的普通僵尸也判死。
        boolean noRoute = status == Trip.Status.FAILED
                && (nav.failType() == FailureType.NO_PATH
                        || nav.failType() == FailureType.TERRAIN_BLOCKED);
        if (status == Trip.Status.FAILED) {
            // 别的失败也要留声:走位导航当刻就失败、下一刻重建,在日志里是一片安静的
            // SKIRMISH——站着不动却什么都没说,排查时只能靠猜。
            if (!noRoute) {
                Constants.LOG.info("[numen-attack] 走位导航失败({}),重建: {}",
                        nav.failType(), nav.failReason());
            }
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
    }

    /**
     * 她能够到当前目标的中心距离。目标没了就退回她自己那一格的量。
     *
     * <p>大史莱姆宽 2.04,半宽就一格出头 —— 按 3.0 硬比会把它判成"够不着",而原版玩家
     * 打得到。判据的够到距离与站位的吸引半径必须是这同一个数。
     */
    /** 这一刻走的是弓那一套吗。环的内外沿、以及攻击层用什么,都看它。 */
    private boolean bowFighting;

    /**
     * 走位环的外沿,按离它的中心距:剑是够到距离({@link Swing#reachTo},用来判内沿还剩不剩一条带、打站位日志;剑的环本身由
     * {@link #reachOf} 判),弓是 {@link #BOW_MAX_DISTANCE}。
     */
    private double skirmishOuter() {
        return bowFighting ? BOW_MAX_DISTANCE : reachToTarget();
    }

    /**
     * 走位环的内沿:剑是"它够得着我",弓是"拉得开弓的距离"。它够得比她的外沿还远(大史莱姆、点着的苦力怕)时,
     * 打得着又挨不着的那条带本来就不存在,内沿归零:环退成"走进够得着的地方"。
     */
    private double skirmishInner() {
        double inner = bowFighting ? BOW_MIN_DISTANCE
                : target == null ? 0.0 : Menace.rawDangerRadius(target, player);
        return inner < skirmishOuter() ? inner : 0.0;
    }

    /**
     * 站在哪一格手够得着 {@code e}:眼睛到它此刻的碰撞箱小于她的实体交互距离(第 0 层 {@link Goals#touch})。走位的到达与攻击层
     * 出手都问它,按她此刻站的那一格判({@link Feet#in})。
     */
    private Goal reachOf(Entity e) {
        return Goals.touch(e.getBoundingBox(), Snapshots.stats(player),
                Swing.reachOf(player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)));
    }

    /** 剑的走位环:够得着它,而且出了它够得着她的距离(内沿为零时只要够得着)。 */
    private Goal swordRing() {
        Goal reach = reachOf(target);
        double inner = skirmishInner();
        if (inner <= 0) {
            return reach;
        }
        return Goals.allOf(List.of(reach,
                Goals.awayFrom(List.of(new Threat(target.getX(), target.getY(), target.getZ(), inner)))));
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
     * <h2>目标自己由环管</h2>
     * 它当然也会打她:环的外沿把她拉进够到距离,内沿就是它自己的危险半径,把她顶在够不着的地方,
     * 中间那条缝就是拉扯的位置。缝宽是原版碰撞箱给的 —— 僵尸 3.30 对 2.73,半格出头。躲避场只收
     * 别的怪;它够得比她还远时内沿归零,她只能走进它的范围去打。
     *
     * <h2>被围住的时候</h2>
     * 没有合格的格子时,搜索交出离目标最近的那一段先走着,走完再搜。
     */
    private Goal standoffGoal() {
        // 躲避场只收敌对生物:它们才有危险半径。目标本身归下面的环管——点名的猪牛鸡不是
        // 敌对生物,不在这份名单里,但照样是要走过去打的目标。"有没有目标"与"附近有没有怪"
        // 是两个问题,这里早退只看前者是否也为空:既无目标也无怪,才真的没处可站。
        // 两份材料都引用本刻的判断:目标是判据选的那一只,怪是 surveyField 扫的那一份。
        var field = hostiles;
        // 威胁场只收此刻还活着的:"场上有没有怪"与"远离谁"问的是同一份名单,不然扫到的那只这一刻刚死,名单就空了
        List<Threat> threats = Menace.field(player, field);
        boolean haveTarget = target != null && !target.isRemoved();
        if (!haveTarget && threats.isEmpty()) {
            return null;
        }
        logStandoff(field);
        if (!haveTarget) {
            // <b>没有目标也照样走位</b>:环退化成"离每一只都出了它的危险半径"。
            // 场上只剩一只点着的爬行者(她没弓打不了)时走的就是这一支 —— 退开等引信熄,
            // 而不是跑三十二格。
            return Goals.awayFrom(threats);
        }
        // 走位是<b>一个环</b>:外沿别跟丢,内沿是它够不着她(别的怪由躲避场管)。太近自然往外走,太远
        // 自然往回走 —— "拉开"不是另一个动作。
        //
        // 剑的外沿<b>就是攻击层出手的那个判据</b>(reachOf:站在这一格眼睛够得着它此刻的碰撞箱),同一个目标、
        // 同一套站位坐标。走位说到了,攻击层就挥得出去;它挪出去了,这一格就不再算到,下一趟接着往前走。
        //
        // 内沿用<b>裸</b>攻击距离(2.02),不加格量化补偿,从格心量到它此刻的位置。带宽约 1.28 格,比格量化误差
        // 0.71 宽出一截。弓的环在 8~12 格,问的是拉不拉得开弓,按它所在的那一列量。
        Goal ring = bowFighting
                ? Goals.within(Goals.column(target.getBlockX(), target.getBlockZ()), skirmishInner(), skirmishOuter())
                : swordRing();
        // 要打的这一只离多远由环管(内沿就是它够不着她的距离),躲避场只收别的怪:再把它放进去,它够得比她还远时
        // "够得着它"与"出了它的危险半径"两头都要,就没有一格站得下
        List<Threat> others = Menace.field(player, field.stream().filter(mob -> mob != target).toList());
        if (others.isEmpty()) {
            return ring;
        }
        // 弓那一套的内沿对<b>每一只</b>都成立:她要跟所有怪保持五格,不只是当前目标。
        List<Threat> keepOff = bowFighting
                ? others.stream()
                        .map(t -> new Threat(t.x(), t.y(), t.z(), Math.max(t.radius(), BOW_MIN_DISTANCE)))
                        .toList()
                : others;
        return Goals.allOf(List.of(ring, Goals.awayFrom(keepOff)));
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
        Hotbar.hold(player, weapon.slot());
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

    // ==================== 拾荒 ====================

    // ==================== 收尾与回执 ====================

    private void abortShot() {
        if (shot != null) {
            shot.abort();
            shot = null;
        }
    }

    @Override
    protected void cleanup() {
        abortShot();
        player.controls().releaseAll();
        super.cleanup();
    }

    /**
     * 每只经手过的(编号、怎样了、挨了几下):点名的那一只总在里面;一共出手几下。经手过的要全列,不能只列点名的——无差别那一种
     * 点名清单是空的,只列它会把整场战果吞掉。
     */
    @Override
    protected Fought value() {
        java.util.Set<Integer> touched = new java.util.LinkedHashSet<>(r.entityIds);
        touched.addAll(r.defeated());
        touched.addAll(r.lost());
        touched.addAll(r.unreachable());
        touched.addAll(r.refused().keySet());
        List<Fought.Foe> fought = new java.util.ArrayList<>();
        for (int id : touched) {
            fought.add(new Fought.Foe(id, r.status(id), r.strikes(id)));
        }
        return new Fought(fought, r.strikes());
    }

    private String tally() {
        return r.indiscriminate
                ? r.defeated().size() + " hostiles"
                : r.defeated().size() + "/" + r.entityIds.size() + " requested entities";
    }

    /** 打倒了什么,它掉的东西留在地上:捡是 {@code numen.work.collect} 的事。 */
    private String drops() {
        return r.defeated().isEmpty() ? "" : "; what they dropped lies on the ground: `numen.work.collect()` picks it up";
    }

    @Override
    protected String successMessage() {
        String refusedNote = r.refused().isEmpty() ? "" : "; left alone: " + refusedSummary();
        if (r.indiscriminate) {
            return "fought off " + tally() + "; nothing is coming after you any more" + refusedNote + drops();
        }
        int incomplete = r.lost().size() + r.unreachable().size();
        return "defeated " + tally()
                + (incomplete == 0 ? "" : " (" + incomplete + " targets could not be completed)")
                + refusedNote + drops();
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
