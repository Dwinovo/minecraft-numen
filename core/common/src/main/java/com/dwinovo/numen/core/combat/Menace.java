package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Creeper;

import java.util.ArrayList;
import java.util.List;

/**
 * 「该不该靠近」——与「够不够得着」正交的另一维。
 *
 * <p>{@link AttackPlan} 用够不够得着决定<b>拿什么武器</b>;这里决定<b>要不要保持距离</b>。
 * 爬行者恰恰是"完全够得着"的,只按前一维判就会直接判近战——那正是会被炸的那条路。
 *
 * <h2>爬行者的几条线都来自原版,不是调出来的</h2>
 * {@code SwellGoal.tick()} 里只有三条判定:目标进 3 格({@code distanceToSqr < 9.0})开始点火;
 * 拉开到 7 格外({@code > 49.0})<b>或</b>断掉视线,引信倒退;否则一路涨到
 * {@code maxSwell = 30} 刻炸。所以"退到 7 格外"不是保守估计,是引信真正开始倒走的那条线。
 *
 * <p>断视线那条同样有效而且更快(躲到方块后面比跑七格近得多),但它不进势场:
 * 给 A* 的每个候选格做一次射线检测太贵。绕到 7.5 格外的路径本来就常常顺带绕过了地形。
 */
public final class Menace {

    /** 原版 {@code SwellGoal} 里引信开始倒退的距离(49 = 7²),加半格余量。 */
    public static final double CREEPER_SAFE_DISTANCE = 7.5;

    /**
     * 末影水晶被打碎时炸的威力是 {@code 6.0F},原版爆炸的伤害波及到威力的两倍远,
     * 所以 12 格外才是真的挨不着。爬行者威力 3、半径 6,却只需退到 7.5——那条线不是按
     * 爆炸半径画的,是按引信倒退画的,两者管的不是同一件事。
     */
    public static final double CRYSTAL_SAFE_DISTANCE = 12.0;

    /**
     * 正在点火的爬行者比寻常威胁危险几倍。倍数越大,同样的距离在势场里越贵,她越舍得绕远。
     * 五倍是让"贴着一只点火爬行者过去"贵到几乎不会被选中,同时还没贵到宁可挖穿一座山。
     */
    private static final double FUSING_WEIGHT = 5.0;

    /** 会炸但还没点着的:走近它就会点着,所以比寻常怪更该绕。 */
    private static final double EXPLOSIVE_WEIGHT = 2.0;

    /** 寻常敌对生物。进场是为了别一边躲爬行者一边撞进它怀里,不是为了绕着它走。 */
    private static final double ORDINARY_WEIGHT = 1.0;

    /**
     * 势场强度。<b>调大</b>她更坚决地绕开,极端时宁可挖直线也不走近路;<b>调小</b>她会为了
     * 近路从爬行者边上擦过去,小到一定程度等于无视。
     */
    public static final double AVOID_PENALTY = 40.0;

    private Menace() {}

    /** 这个东西是不是"能打到但不该贴上去"。 */
    public static boolean keepAwayFrom(Entity entity) {
        return entity instanceof Creeper || entity instanceof EndCrystal;
    }

    /** 该离它多远。{@link #keepAwayFrom} 为假时问这个没有意义,返回 0。 */
    public static double safeDistanceFrom(Entity entity) {
        if (entity instanceof Creeper) {
            return CREEPER_SAFE_DISTANCE;
        }
        return entity instanceof EndCrystal ? CRYSTAL_SAFE_DISTANCE : 0.0;
    }

    /** 引信正在涨——它已经在倒计时,不是"可能会炸"。 */
    public static boolean fusing(Entity entity) {
        return entity instanceof Creeper creeper
                && (creeper.getSwellDir() > 0 || creeper.isIgnited());
    }

    /**
     * 敌对生物看的是 {@link Enemy} 这个标记接口,<b>不是 {@code Monster}</b>。
     *
     * <p>史莱姆、岩浆怪、恶魂、幻翼都 {@code extends Mob/FlyingMob implements Enemy},
     * 疣猪兽甚至 {@code extends Animal} —— 按 {@code Monster} 扫会把它们整个漏掉,
     * 于是她逃跑时从史莱姆身上碾过去,而且血线兜底也永远不触发(链子根本没被叫醒)。
     */
    public static boolean hostile(Entity entity) {
        return entity instanceof Enemy;
    }

    /** 半径内所有活着的敌对生物,不管它有没有盯上她。 */
    public static List<Mob> hostilesAround(Entity self, double radius) {
        List<Mob> found = new ArrayList<>();
        for (Mob m : self.level().getEntitiesOfClass(Mob.class,
                self.getBoundingBox().inflate(radius))) {
            if (m != self && hostile(m) && m.isAlive() && self.distanceToSqr(m) <= radius * radius) {
                found.add(m);
            }
        }
        return found;
    }

    /**
     * 把两批实体折成势场。
     *
     * <p>权重分三档:引信已经在走的最重(它有明确的倒计时),会炸但还没点的次之
     * (走近就会点),其余按寻常算。同样的距离,权重越大在势场里越贵,她越舍得绕远。
     *
     * @param engaging   正在追她的 —— 要为它们拉开整个安全距离才算脱身
     * @param bystanders 还没盯上她的 —— 路过绕开就行,不为它们多跑
     * @return 空表示无事可躲,调用方不该建躲避目标
     */
    public static List<GoalAvoidEntities.Threat> field(Iterable<? extends Entity> engaging,
                                                       Iterable<? extends Entity> bystanders) {
        List<GoalAvoidEntities.Threat> threats = new ArrayList<>();
        add(threats, engaging, true);
        add(threats, bystanders, false);
        return threats;
    }

    private static void add(List<GoalAvoidEntities.Threat> out,
                            Iterable<? extends Entity> entities, boolean mustClear) {
        if (entities == null) {
            return;
        }
        for (Entity entity : entities) {
            if (entity == null || !entity.isAlive()) {
                continue;
            }
            out.add(new GoalAvoidEntities.Threat(
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                    fusing(entity) ? FUSING_WEIGHT
                            : keepAwayFrom(entity) ? EXPLOSIVE_WEIGHT
                            : ORDINARY_WEIGHT,
                    mustClear));
        }
    }
}
