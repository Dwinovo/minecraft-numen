package com.dwinovo.numen.core.task.survival;


/**
 * 生存反射的<b>纯判据</b>——"现在该不该抢身体",从链里拆出来所以能无头单测
 * (不碰 Minecraft)。每个方法只吃从 {@code ServerPlayer} 上读到的原始值
 * (饱食度、血量、坠落距离、有没有威胁/有没有工具),返回一个布尔:<b>触发了没有</b>。
 *
 * <h2>为什么是布尔而不是优先级数值</h2>
 * 反射之间的先后<b>是固定的</b>(摔落永远比脱困急),不随世界状态变。让每个反射
 * 返回一个浮点"我多想要身体"再挑最大的,就是用连续量表达一个固定序——那些数值
 * 会变成必须小心维护却没人看得懂的魔法数。
 *
 * <p>先后现在写在注册号上({@code NumenCore.registerReflexes},小的先,与原版
 * {@code addGoal(int priority, goal)} 同一惯例),这里只回答"触发没触发"。
 * 顺序是:摔落 &gt; 换气 &gt; 自卫 &gt; 进食 &gt; 脱困——正在坠落是最迫近的死法,
 * 而卡住只是烦人,绝不该压过打架或吃饭。
 */
public final class SurvivalDecisions {

    private SurvivalDecisions() {}

    // ---- fall thresholds ----
    /**
     * 下落速度(格/刻)到这个量级就是"正在摔"。
     *
     * <p>判<b>速度</b>而不是累计落差:玩家的摔落结算在原版里是客户端权威的
     * ({@code Entity.move} 里那一处被 {@code isLocalInstanceAuthoritative} 挡着,真正
     * 结算的是收到移动包时的 {@code doCheckFallDamage}),而 {@code fallDistance} 也在
     * 同一处累加。空壳玩家没有客户端,那个量永远是 0。速度是服务端物理自己算的,拿它当
     * 判据既躲开了这件事,也更贴近这条反射真正关心的东西——她这一刻掉得有多快。
     *
     * <p>原版重力 0.08/刻、阻力 0.98,自由落体约九刻到 0.7 格/刻,那时已经掉了三格出头
     * ——正好压在摔伤起点(落差 3 格)上。
     */
    public static final double MLG_FALL_SPEED = -0.7;

    /** 有威胁就触发。 */
    public static boolean mobDefenseTriggered(boolean threatPresent) {
        return threatPresent;
    }

    /**
     * 摔落缓冲触发条件:人正在快速下落、并且手上有救命的东西(水桶或软方块)。
     *
     * @param grounded 脚落地了,或者人在水里/在爬梯藤——三者都不算"在摔"
     * @param fallSpeed 竖直速度(格/刻),向下为负
     * @param canSave  身上有水桶或软方块;没有的话抢了身体也救不了自己
     */
    public static boolean mlgTriggered(boolean grounded, double fallSpeed, boolean canSave) {
        if (grounded) return false;
        if (!canSave) return false;
        return fallSpeed <= MLG_FALL_SPEED;
    }

    /**
     * 水已经在减速她 —— 该收桶了。比 {@link #MLG_FALL_SPEED} 慢,所以"还在自由落体"
     * 和"已经落进水里"分得开。
     */
    public static final double MLG_SETTLED_SPEED = -0.5;

    // ---- breath thresholds (vanilla air is 0..300 ticks; damage starts at 0) ----
    /**
     * Air ticks at/below which surfacing takes the body. 240 leaves a ~3-second
     * dip tolerance (normal head-bobs while swimming don't trigger), while the
     * remaining 12 seconds of air are ample for any plausible swim-up. The band
     * between wake (240) and full (300) also gives an idle body in deep water a
     * natural bob cycle: sink → head under → air dips past 240 → surface → refill
     * — a fake player has no client holding the jump key, so this chain IS its
     * float instinct.
     */
    public static final int LOW_AIR_TICKS = 240;

    /**
     * 换气触发条件:头没在水里且氧气见底。头一出水面立刻不触发(氧气自己回),
     * 氧气还够也不触发。
     */
    public static boolean breathTriggered(boolean headUnderWater, int airSupply) {
        return headUnderWater && airSupply <= LOW_AIR_TICKS;
    }
}
