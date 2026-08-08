package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Creeper;

import java.util.ArrayList;
import java.util.List;

/**
 * 「离它多近算危险」——每一只怪一个<b>危险半径</b>,判据和寻路都问这一个数。
 *
 * <h2>半径不是写死的,是从碰撞箱推的</h2>
 * 原版 {@code Mob.isWithinMeleeAttackRange} 判的是"它的碰撞箱水平撑开
 * {@code DEFAULT_ATTACK_REACH} 之后与她的碰撞箱相交",所以够得着多远取决于<b>两边的宽度</b>
 * ——蜘蛛宽 1.4、僵尸宽 0.6,差大半格。换个模组怪、换个体型,半径自己跟着变。
 *
 * <h2>会炸的按别的东西算</h2>
 * 爬行者不近战,它的危险是引信:没点着时危险半径是<b>点火线</b>(走进去它就开始烧),
 * 点着之后是<b>爆炸伤害范围</b>。两行,而且两个数都来自原版。
 *
 * <h2>只有一种度量</h2>
 * 全部是<b>中心到中心的距离</b>。判据拿实体坐标比,寻路拿格心比,而半径里已经含了格心到
 * 格内最远点那半格({@link #CELL_SLACK})——所以"格心算出来安全"就保证"实际位置也安全"。
 * 两边各用各的度量时,判据说"快躲"、寻路说"你已经躲开了",导航一建就到达、一步不走,
 * 她站在原地被打死。
 */
public final class Menace {

    /** 原版 {@code Creeper.explosionRadius} 默认值;充能的翻倍。NBT 改过的少见,不追。 */
    private static final double CREEPER_BLAST_RADIUS = 3.0;

    /** 末影水晶被打碎时的爆炸威力,原版写死 {@code 6.0F}。它没有引信,打碎即炸。 */
    private static final double CRYSTAL_BLAST_RADIUS = 6.0;

    /**
     * 原版怪物近战判定往外扩的那一截:{@code Mob.DEFAULT_ATTACK_REACH}
     * ({@code Math.sqrt(2.04) - 0.6} ≈ 0.83)。
     */
    private static final double MOB_ATTACK_REACH = Math.sqrt(2.04) - 0.6;

    /**
     * 格心到格内最远点的距离(√2/2 ≈ 0.71)。<b>不是手感参数,是几何常数</b>。
     *
     * <p>寻路只能按方块格算,而她实际站在格里的哪个角落是不定的。半径里含上这半格,
     * "按格心算出来安全"才等价于"实际位置也安全"——否则两种度量最大差一格四,
     * 判据与寻路会各说各话。
     */
    private static final double CELL_SLACK = Math.sqrt(2.0) / 2.0;

    /**
     * 势场强度:把"贴着一只怪走"折成"多走几格路"的汇率。
     *
     * <p>标定的口径是<b>绕开一只贴在危险半径上的怪,值一格半的路</b>。势能按半径的倍数算,
     * 从半径处退开一格势能掉四成半({@code 1 - (3.04/4.04)²}),乘 15 约合 7 点成本,
     * 而走一格约 4.6 —— 正好一格半。
     *
     * <p><b>不能再大了</b>:势场只进估价({@code h}),不进边成本({@code g})。估价必须是剩余
     * 成本的下界 A* 才敢剪枝,而这一项往人堆里走时会反向增长。它盖过路程量级之后搜索会烧光
     * 节点预算返回无路 —— 取 800 那次实测连两格的退路都算不出来。
     */
    public static final double AVOID_PENALTY = 15.0;

    /**
     * 逃跑要拉开多远才算甩掉。
     *
     * <p>它必须<b>远大于</b>危险半径:后者是"退出去就能接着打"的两三格,前者是"它已经跟不动
     * 了"。两件事共用一个数的时候,她退两格就判"跑掉了"、站住、被追上,于是走走停停。
     *
     * <p><b>这也是逃跑唯一的终点</b>:三十二格内没有敌对生物就算跑掉了。不再另设行为判据
     * ——那种判据("还有没有人在逼近")在她一跑起来就必然成立,追兵按定义不再缩短距离,
     * 于是起跑两秒后宣布脱离,而她身后两格还跟着三只。
     */
    public static final double FLEE_DISTANCE = 32.0;

    private Menace() {}

    /** 它会炸——不管这一刻炸没炸。 */
    public static boolean explodes(Entity entity) {
        return entity instanceof Creeper || entity instanceof EndCrystal;
    }

    /**
     * 它<b>现在就要炸了</b>。爬行者只在引信点着之后才算:点着之前它就是一只普通怪,
     * 而末影水晶<b>没有引信</b>,打它的那一刻就炸,所以无条件成立。
     */
    public static boolean armed(Entity entity) {
        return entity instanceof EndCrystal || fusing(entity);
    }

    /** 引信正在涨——它已经在倒计时,不是"可能会炸"。 */
    public static boolean fusing(Entity entity) {
        return entity instanceof Creeper creeper
                && (creeper.getSwellDir() > 0 || creeper.isIgnited());
    }

    /**
     * <b>危险半径</b>:离它比这更近,她就该躲。判据与寻路问的是同一个函数。
     *
     * <p>含 {@link #CELL_SLACK},所以可以直接拿格心去比。
     */
    public static double dangerRadius(Entity foe, Entity self) {
        return rawDangerRadius(foe, self) + CELL_SLACK;
    }

    /**
     * 不含格量化补偿的那一版,只在推导与测试里用。
     *
     *
     * <p><b>引信没点着的爬行者按普通怪算</b>。按点火线(3.0)算的话,加上格量化补偿就是 3.71,
     * 已经超过她够得着的 3.30 —— 窗口是负的,她永远不能挥这一刀,只能绕着走。而点着之后
     * 引信有整整 30 刻,那时再退完全来得及。
     */
    public static double rawDangerRadius(Entity foe, Entity self) {
        if (armed(foe)) {
            return blastSpanOf(foe);   // 引信在走 / 一打就炸的水晶:怕的是爆炸波及多远
        }
        return strikeRangeOf(foe, self);
    }

    /**
     * {@code attacker} 能打到 {@code victim} 的<b>中心距离</b>。
     *
     * <p>原版判的是两个方框相交,那是个<b>方形</b>区域:每根轴上的间隙都小于
     * {@code 半宽 + 0.83 + 半宽} 才挨得着。要从任何方位都够不着,得退到它的<b>外接圆</b>
     * 之外,所以乘 √2 —— 用内切圆是错的,中心距 1.43 时若在对角方向,两轴间隙各 1.01,
     * 照样打得到。
     */
    public static double strikeRangeOf(Entity attacker, Entity victim) {
        double perAxis = attacker.getBbWidth() / 2.0 + MOB_ATTACK_REACH + victim.getBbWidth() / 2.0;
        return perAxis * Math.sqrt(2.0);
    }

    /** 爆炸伤害波及多远:原版爆炸的伤害到威力的<b>两倍</b>远归零。 */
    public static double blastSpanOf(Entity entity) {
        if (entity instanceof Creeper creeper) {
            return (creeper.isPowered() ? CREEPER_BLAST_RADIUS * 2.0 : CREEPER_BLAST_RADIUS) * 2.0;
        }
        return entity instanceof EndCrystal ? CRYSTAL_BLAST_RADIUS * 2.0 : 0.0;
    }

    /**
     * 挨打时用来估算减伤的一记<b>代表性伤害</b>。原版的减伤率与来袭伤害有关(护甲韧性那一项),
     * 所以"能扛多少"必须挑一个伤害档去评估;8 点约等于一只装备了武器的强怪一击。
     */
    private static final float NOMINAL_HIT = 8.0f;

    /**
     * 她还扛得住多少 —— <b>按护甲折算后的有效血量</b>。
     *
     * <p>光看血量会把"满血裸奔"和"满血下界合金"判成一样危险,而后者能多扛四五倍。
     * 减伤不自己算:交给原版的 {@link CombatRules#getDamageAfterAbsorb},护甲、韧性、
     * 以及模组改过的公式一并跟着走。传的伤害源不带武器,所以不会触发附魔那条分支,
     * 也就没有副作用({@code LivingEntity.getDamageAfterArmorAbsorb} 会磨损护甲,不能用)。
     *
     * @return 折算后的有效血量;没有护甲时就等于血量本身
     */
    public static double effectiveHealth(LivingEntity self) {
        float health = self.getHealth();
        if (!(self.level() instanceof ServerLevel level)) {
            return health;
        }
        float afterArmor = CombatRules.getDamageAfterAbsorb(self, NOMINAL_HIT,
                level.damageSources().generic(),
                self.getArmorValue(), (float) self.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        if (afterArmor <= 0.0f) {
            return Double.MAX_VALUE;   // 伤害被吃干净了:这一档她无敌
        }
        return health * (NOMINAL_HIT / afterArmor);
    }

    /** 她扛不住了吗。阈值在 {@link AttackPlan} 那一处,这里只是把它问一遍。 */
    public static boolean outmatched(LivingEntity self) {
        return AttackPlan.outmatched(effectiveHealth(self));
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
     * 战斗走位用的威胁场:间距取<b>裸</b>危险半径(不含格量化补偿)。
     *
     * <p>这就是走位环的<b>内沿</b> —— 离每一只都出了它够得着的距离。外沿是她的够到距离,
     * 由调用方给。带宽因此约 1.28 格,比格量化误差 0.71 宽出一截。
     */
    public static List<GoalAvoidEntities.Threat> field(LivingEntity victim,
                                                       Iterable<? extends Entity> mobs) {
        List<GoalAvoidEntities.Threat> threats = new ArrayList<>();
        for (Entity mob : mobs) {
            if (mob != null && mob.isAlive()) {
                threats.add(new GoalAvoidEntities.Threat(mob.getX(), mob.getY(), mob.getZ(),
                        dangerRadius(mob, victim), rawDangerRadius(mob, victim)));
            }
        }
        return threats;
    }

    /** 这一只此刻是不是已经进了它的危险半径。判据与寻路同一把尺子、同一套坐标。 */
    public static boolean tooClose(Entity foe, LivingEntity self) {
        return !GoalAvoidEntities.clearOf(self.getBlockX() + 0.5, self.getBlockZ() + 0.5,
                foe.getX(), foe.getZ(), dangerRadius(foe, self));
    }

}
