package com.dwinovo.numen.core.combat;

/** Pure distance-and-risk policy shared by explicit combat and reflex defense. */
public final class CombatTacticPolicy {
    public static final double CREEPER_DANGER_RANGE = 7.0;
    public static final double CREEPER_SPACING_RANGE = 4.0;
    public static final double CREEPER_RANGED_RESUME_RANGE = 7.5;
    public static final double CREEPER_MELEE_RESUME_RANGE = 4.5;
    public static final double CREEPER_CLOSE_HIT_RANGE = 3.0;
    public static final double SPEAR_MIN_RANGE = 2.0;
    public static final double SPEAR_MAX_RANGE = 4.5;
    public static final int CREEPER_OPENING_STRIKE_TICKS = 3;
    public static final int MAX_CREEPER_DANGER_TICKS = 30;
    public static final int MAX_CREEPER_RETREAT_CYCLES = 8;

    public enum Action {
        RETREAT,
        RANGED,
        SPEAR,
        MELEE,
        APPROACH,
        ABANDON
    }

    public record Context(
        boolean creeper,
        boolean fuseActive,
        double distance,
        boolean lineOfSight,
        boolean rangedReady,
        boolean spearReady,
        boolean retreatAvailable,
        int dangerTicks,
        int retreatCycles
    ) {
    }

    public record Engagement(boolean retreatActive, boolean closeHitPending) {
    }

    private CombatTacticPolicy() {
    }

    public static Engagement updateEngagement(
        boolean creeper,
        boolean fuseActive,
        double distance,
        boolean rangedReady,
        Engagement current
    ) {
        if (!creeper) {
            return new Engagement(false, false);
        }
        double resumeRange = rangedReady
            ? CREEPER_RANGED_RESUME_RANGE
            : CREEPER_MELEE_RESUME_RANGE;
        if (current.retreatActive() && !fuseActive && distance >= resumeRange) {
            return new Engagement(false, true);
        }
        if (!current.retreatActive() && !current.closeHitPending()) {
            return new Engagement(false, true);
        }
        return current;
    }

    public static Action decide(Context context) {
        return decide(context, false);
    }

    public static Action decide(Context context, boolean creeperRetreatActive) {
        return decide(context, creeperRetreatActive, false);
    }

    public static Action decide(
        Context context,
        boolean creeperRetreatActive,
        boolean closeHitPending
    ) {
        return decide(context, creeperRetreatActive, closeHitPending, false);
    }

    public static Action decide(
        Context context,
        boolean creeperRetreatActive,
        boolean closeHitPending,
        boolean rangedShotInProgress
    ) {
        if (context.creeper() && context.fuseActive()) {
            if (!context.retreatAvailable()) {
                return Action.ABANDON;
            }
            if (!rangedShotInProgress
                && closeHitPending
                && context.dangerTicks() <= CREEPER_OPENING_STRIKE_TICKS
                && context.lineOfSight()
                && context.distance() <= CREEPER_CLOSE_HIT_RANGE) {
                return Action.MELEE;
            }
            return Action.RETREAT;
        }
        if (context.creeper()
            && context.retreatCycles() >= MAX_CREEPER_RETREAT_CYCLES) {
            return Action.ABANDON;
        }
        if (context.creeper()
            && rangedShotInProgress
            && context.lineOfSight()
            && context.rangedReady()) {
            return Action.RANGED;
        }
        if (context.creeper()
            && closeHitPending
            && context.lineOfSight()
            && context.distance() <= CREEPER_CLOSE_HIT_RANGE) {
            return Action.MELEE;
        }
        if (context.creeper() && context.retreatAvailable()
            && ((creeperRetreatActive
                    && context.distance() < (context.rangedReady()
                        ? CREEPER_RANGED_RESUME_RANGE
                        : CREEPER_MELEE_RESUME_RANGE))
                || (context.rangedReady()
                    && context.distance() < CREEPER_SPACING_RANGE)
                || (!context.rangedReady()
                    && context.spearReady()
                    && context.distance() < CREEPER_SPACING_RANGE))) {
            return Action.RETREAT;
        }
        if (context.lineOfSight()
            && context.rangedReady()
            && (context.creeper() || context.distance() > SPEAR_MAX_RANGE)) {
            return Action.RANGED;
        }
        if (context.lineOfSight()
            && context.spearReady()
            && context.distance() > SPEAR_MIN_RANGE
            && context.distance() <= SPEAR_MAX_RANGE) {
            return Action.SPEAR;
        }
        if (context.lineOfSight() && context.distance() <= SPEAR_MIN_RANGE) {
            return Action.MELEE;
        }
        return Action.APPROACH;
    }
}
