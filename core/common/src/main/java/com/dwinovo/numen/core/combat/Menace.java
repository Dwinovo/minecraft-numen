package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;

import net.minecraft.world.entity.Entity;
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

    /** 还没开始倒计时的(没点火的爬行者、没被打的水晶)也值得让一让,但只是让一让。 */
    private static final double IDLE_WEIGHT = 1.0;

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
     * 把一批实体折成势场里的威胁点。不该躲的直接不进场——势场里多一个无关的点,
     * 她就会为了绕开一只普通僵尸多走冤枉路。
     *
     * @return 可能为空;空表示"没什么要躲的",调用方不该建躲避目标
     */
    public static List<GoalAvoidEntities.Threat> threatsAmong(Iterable<? extends Entity> entities) {
        List<GoalAvoidEntities.Threat> threats = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity == null || !entity.isAlive() || !keepAwayFrom(entity)) {
                continue;
            }
            threats.add(new GoalAvoidEntities.Threat(
                    entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(),
                    fusing(entity) ? FUSING_WEIGHT : IDLE_WEIGHT));
        }
        return threats;
    }
}
