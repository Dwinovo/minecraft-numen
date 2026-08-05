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

    // ---- food thresholds (vanilla FoodData is 0..20) ----
    /** Natural regeneration needs food &ge; 18, so eating below this while hurt buys HP back. */
    public static final int REGEN_FOOD_LEVEL = 18;
    /** Health (of 20) at/below which we treat "hurt + not full food" as worth an eat for regen. */
    public static final float LOW_HEALTH = 12.0f;
    /** Food level at/below which the body is genuinely hungry (below 7 it can no longer sprint). */
    public static final int HUNGRY_LEVEL = 6;

    // ---- threat thresholds ----
    /** Health (of 20) at/below which we always flee rather than trade blows. */
    public static final float FLEE_HEALTH = 8.0f;

    // ---- fall thresholds ----
    /** Fall distance (blocks) above which an MLG save is worth attempting (vanilla fall damage &gt; 1 heart). */
    public static final double MLG_FALL_TRIGGER = 4.0;

    /**
     * 进食触发条件:身上真有吃的,而且要么饿了、要么受伤且没到自然回血线。
     */
    public static boolean foodTriggered(int foodLevel, float health, boolean hasEdible) {
        if (!hasEdible) return false;
        if (health <= LOW_HEALTH && foodLevel < REGEN_FOOD_LEVEL) return true;   // 受伤了,吃回自然回血线
        return foodLevel <= HUNGRY_LEVEL;
    }

    /** How the threat-response chain reacts to a present threat. */
    public enum ThreatResponse { NONE, FIGHT, FLEE }

    /**
     * Fight-vs-flee: with a threat present, flee when too hurt to trade blows or
     * when unarmed (survival never auto-acquires a weapon); otherwise fight back.
     */
    public static ThreatResponse decideThreatResponse(boolean threatPresent, float health, boolean armed) {
        if (!threatPresent) return ThreatResponse.NONE;
        if (health <= FLEE_HEALTH) return ThreatResponse.FLEE;
        return armed ? ThreatResponse.FIGHT : ThreatResponse.FLEE;
    }

    /** 有威胁就触发。 */
    public static boolean mobDefenseTriggered(boolean threatPresent) {
        return threatPresent;
    }

    /**
     * 摔落缓冲触发条件:人在空中、已经掉过致伤距离、并且手上有救命的东西
     * (水桶或软方块)——三者缺一就没有可做的事。
     */
    public static boolean mlgTriggered(boolean onGround, double fallDistance, boolean canSave) {
        if (onGround) return false;
        if (!canSave) return false;               // 手上没水桶也没软方块,抢了身体也没用
        return fallDistance >= MLG_FALL_TRIGGER;
    }

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
