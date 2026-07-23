package com.dwinovo.numen.core.task.survival;

import com.dwinovo.numen.task.TaskChain;

/**
 * The PURE decision core of the autonomous survival layer — the "should I take the
 * body, and how hard" math, split out of the chains so it can be unit-tested
 * headless (no Minecraft). Each method takes only primitives polled from the
 * vanilla {@code ServerPlayer} state (food level, health, fall distance, whether a
 * threat is present / a tool is held) and returns either a spike priority ABOVE
 * {@link TaskChain#LLM_BASE_PRIORITY} (so the chain preempts the LLM task) or
 * {@link Float#NEGATIVE_INFINITY} (dormant).
 *
 * <h2>Priority ranking (why these magnitudes)</h2>
 * A falling body is the most imminent death, so MLG outranks everything; an active
 * mob is next; hunger (a slow drain) next; being stuck (annoying, not lethal) is
 * the lowest survival concern and must never outrank fighting or eating. All sit
 * above the LLM base (0) so any firing survival concern preempts the task:
 * <pre>  MLG(10) &gt; breath(6) &gt; mob-defense(5) &gt; food-regen(4) &gt; food-hunger(3) &gt; unstuck(2) &gt; llm(0)</pre>
 */
public final class SurvivalDecisions {

    private SurvivalDecisions() {}

    public static final float DORMANT = Float.NEGATIVE_INFINITY;

    // ---- priority magnitudes (all > LLM_BASE_PRIORITY = 0) ----
    public static final float MLG_PRIORITY = 10.0f;
    /** Above mob-defense: drowning is a hard timer — surface first, fight after. */
    public static final float BREATH_PRIORITY = 6.0f;
    public static final float MOB_DEFENSE_PRIORITY = 5.0f;
    public static final float FOOD_REGEN_PRIORITY = 4.0f;
    public static final float FOOD_HUNGER_PRIORITY = 3.0f;
    public static final float UNSTUCK_PRIORITY = 2.0f;
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
     * How much the auto-eat chain wants the body. Fires only when the body actually
     * holds something edible; a bigger spike when hurt (eating restores regen) than
     * for plain hunger.
     */
    public static float foodPriority(int foodLevel, float health, boolean hasEdible) {
        if (!hasEdible) return DORMANT;
        if (health <= LOW_HEALTH && foodLevel < REGEN_FOOD_LEVEL) return FOOD_REGEN_PRIORITY;
        if (foodLevel <= HUNGRY_LEVEL) return FOOD_HUNGER_PRIORITY;
        return DORMANT;
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

    /** How much the mob-defense chain wants the body: a fixed spike while a threat is present. */
    public static float mobDefensePriority(boolean threatPresent) {
        return threatPresent ? MOB_DEFENSE_PRIORITY : DORMANT;
    }

    /**
     * How much the MLG chain wants the body. Fires only while airborne, past a
     * lethal-ish fall distance, and able to save itself (a water bucket or a soft
     * block on hand) — otherwise there is nothing useful to do.
     */
    public static float mlgPriority(boolean onGround, double fallDistance, boolean canSave) {
        if (onGround) return DORMANT;
        if (!canSave) return DORMANT;
        if (fallDistance < MLG_FALL_TRIGGER) return DORMANT;
        return MLG_PRIORITY;
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
     * How much the breath chain wants the body: a hard spike while the head is
     * submerged with depleted air. Head above water → dormant immediately (air
     * refills on its own); air above the threshold → not yet a concern.
     */
    public static float breathPriority(boolean headUnderWater, int airSupply) {
        if (!headUnderWater) return DORMANT;
        if (airSupply > LOW_AIR_TICKS) return DORMANT;
        return BREATH_PRIORITY;
    }
}
