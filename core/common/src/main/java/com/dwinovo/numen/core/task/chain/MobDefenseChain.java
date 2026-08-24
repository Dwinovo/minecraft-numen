package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.task.combat.AttackCompanionTask;
import com.dwinovo.numen.core.task.combat.AttackTaskRecord;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/**
 * 危险来了就<b>自动开一场战斗</b>——然后把打法完全交给 {@code attack}。
 *
 * <h2>它不再自己打</h2>
 * 这条链曾经是一整套独立的战斗系统:自己选武器、自己追、自己退。于是同一件事有了两份实现,
 * 两层还会为身体互相抢——她在十格外射爬行者,链子把她拽开,拉到一半的弓作废;拉开又交还,
 * 弓刚拉起来链子又拽。
 *
 * <p>现在它只做一件事:<b>判断该不该开打,然后派一场 {@link AttackCompanionTask}</b>。
 * 挥击、弹道、走位、退避、扛不扛得住,全归那一份判据,和模型自己派的 {@code attack} 走的是
 * 同一段代码。<b>走位也是战斗的一部分</b>,不该由另一个系统代管。
 *
 * <h2>为什么仍然是一条反射链,而不是直接换掉她手上的活</h2>
 * 反射链是<b>抢占 + 归还</b>:她挖着矿被打断,打完矿照样接着挖({@code stop(PREEMPTED)} 明确
 * 不动逻辑字段)。若改成顶掉当前任务槽,挖矿就真没了——持久化存的是那次工具调用而不是进度,
 * 重派等于从头挖一遍。
 *
 * <h2>什么算危险</h2>
 * 看的是<b>它已经逼到多近</b>:还远就有时间(模型看得见它,该由模型决定),近了就没有提前量,
 * 当场接管。这条线按威胁类型取自 {@link Menace}——爬行者 7.5(引信开始倒退的距离)、
 * 末影水晶 12(爆炸威力的两倍)、寻常怪 {@link #MELEE_DANGER}。
 */
public final class MobDefenseChain implements Task, Reflex {

    /** 本能名册里的 id。别处按住这条本能时用它,见 {@code NumenPlayer.pauseReflex}。 */
    public static final String ID = "mob_defense";

    /** 看多远。超出这个半径的不算"身边"。 */
    private static final double SCAN_RADIUS = 12.0;

    /**
     * 寻常近战怪逼到这么近就算危险。
     *
     * <p>爬行者与末影水晶那两条线是从原版推出来的(引信倒退距离、爆炸威力两倍),这一条不是
     * ——它是"它下一步就能打到我"的经验值。要更硬该去读每种怪自己的攻击距离。
     */
    private static final double MELEE_DANGER = 4.0;

    /**
     * 危险离开后还盯这么久才算真的没事。
     *
     * <p>没有它,一只跟她跑得几乎一样快的怪会在边界上一进一出,每进出一次就重开一场仗。
     */
    private static final long CALM_GRACE_TICKS = 40;

    /** {@link #dangerLastSeenTick} 的"从没见过"哨兵。不参与减法,免得负溢出。 */
    private static final long NEVER = Long.MIN_VALUE;

    /** 自动开的这场仗。null = 这一刻没在打。 */
    private AttackCompanionTask fight;
    /** 最后一刻还看得见危险的游戏时间。 */
    private long dangerLastSeenTick = NEVER;

    public MobDefenseChain() {
    }

    /**
     * 危险来了就醒。<b>打完不设冷却</b>——没有危险时 {@link #dangersNear} 本来就是空的,
     * 链子自然不会醒,冷却在这里没有作用,只有副作用:那几秒里新出现的危险她一动不动。
     * 实测四次重伤都发生在这个窗口里。
     *
     * <p>冷却原本管的是"退无可退"(老注释:hands the body back to the LLM),但那件事的正解
     * 不是等几秒再试一次,而是{@code cornered} 那一维——退不掉就打。
     */
    @Override
    public boolean canRun(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        // 有人正在替这条本能干活(模型派的 attack),就别抢 —— 除非她已经扛不住,
        // 那一档只有本能看得见。按住的是本能不是目标,所以会分裂的怪不会让它失效。
        if (fight == null && companion.reflexPaused(ID) && !Menace.outmatched(companion)) {
            return false;
        }
        if (fight != null) {
            return true;   // 打着呢,打完再说
        }
        if (SurvivalDecisions.mobDefenseTriggered(!dangersNear(companion).isEmpty())) {
            return true;
        }
        // 宽限期内不撒手:怪刚出半径不代表没事了,这一刻放手下一刻就得重来。
        return dangerLastSeenTick != NEVER && now - dangerLastSeenTick < CALM_GRACE_TICKS;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (!dangersNear(companion).isEmpty()) {
            dangerLastSeenTick = companion.level().getGameTime();
        }
        if (fight == null) {
            if (dangersNear(companion).isEmpty()) {
                return TaskState.RUNNING;   // 宽限期里的空转,别开新的一场
            }
            begin(companion);
            return TaskState.RUNNING;
        }
        TaskState state = fight.tick(companion);
        if (state != TaskState.RUNNING) {
            end(companion, state);
        }
        return TaskState.RUNNING;
    }

    /**
     * 开打。<b>无差别</b>:身边的危险不是模型点名的,而且会分裂的怪一裂开,点名就作废了。
     *
     * <p>不设截止时间——它的终点是"没人再追我",由 {@code attack} 自己判;
     * 给一个闹钟只会在打到一半时把她扔在原地。
     */
    private void begin(NumenPlayer companion) {
        long now = companion.level().getGameTime();
        AttackTaskRecord record = new AttackTaskRecord(
                "reflex-" + now, now + NO_DEADLINE, List.of(), true);
        fight = new AttackCompanionTask(companion, record);
        fight.start(companion);
        com.dwinovo.numen.Constants.LOG.info("[numen-defense] 自动接管 —— 身边 {} 个危险",
                dangersNear(companion).size());
    }

    /** 长到等同于没有截止时间;终点由"没人再追我"说了算。 */
    private static final long NO_DEADLINE = 20L * 60L * 60L * 24L;

    private void end(NumenPlayer companion, TaskState state) {
        String line = fight.result(state).message();
        fight = null;
        dangerLastSeenTick = NEVER;
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        com.dwinovo.numen.Constants.LOG.info("[numen-defense] 收场 {} —— {}", state, line);
        // <b>不急</b>:她的后台任务照跑,黄了自有 task_finished 报。这条只是让主人翻聊天流时
        // 看得懂她刚才为什么打了一架、或者挪了二十格。攒着搭下一轮的车就够。
        com.dwinovo.numen.event.NumenEvents.body(companion,
                "hit danger and handled it on instinct — " + line);
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        if (fight != null) {
            // 被更急的链抢走(摔落、换气):只松开身体,这场仗的状态一个不动,回来接着打。
            fight.stop(companion, why);
        }
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "身边有危险就自动开打,打法与她自己派的 attack 完全一致";
    }

    // ---- 什么算危险 ----

    /**
     * 身边<b>已经近到没有提前量</b>的威胁。
     *
     * <p>只算正在针对她的——防守不是挑衅,一只路过的僵尸猪灵不该被"防御"链招惹。还没逼近的
     * 那些也不进来:模型看得见它们,该由它决定要不要动手。
     *
     * <p>模型自己派的 {@code attack} 已经认领的目标同样不算:那场仗有人管了。但她扛不住时
     * 一律接管——那一档只有本能看得见。
     */
    private List<Mob> dangersNear(NumenPlayer companion) {
        LivingEntity attacker = companion.getLastHurtByMob();
        List<Mob> near = new ArrayList<>();
        for (Mob m : Menace.hostilesAround(companion, SCAN_RADIUS)) {
            if (m != attacker && m.getTarget() != companion) {
                continue;
            }

            // "够危险了没有"与站位、退避问的是<b>同一个函数</b>:它自己的危险半径。
            // 用一条固定的线时每种怪都判错——爬行者要七格,僵尸两格就够。
            if (Menace.tooClose(m, companion)) {
                near.add(m);
            }
        }
        return near;
    }
}
