package com.dwinovo.numen.core.survival;

/** Pure threshold decision for reserving an offhand totem at immediate death risk. */
public final class TotemSafetyPolicy {
    public enum Action {
        NONE,
        EQUIP,
        RESTORE
    }

    public record State(
        float health,
        boolean hasTotem,
        boolean offhandTotem,
        boolean ongoingDanger,
        boolean equippedBySystem,
        int safeTicks
    ) {
    }

    public record Decision(Action action, float priority) {
    }

    private TotemSafetyPolicy() {
    }

    public static Decision decide(State state) {
        boolean immediateRisk = state.health() <= 4.0f
            || (state.ongoingDanger() && state.health() <= 8.0f);
        if (state.hasTotem() && !state.offhandTotem() && immediateRisk) {
            return new Decision(Action.EQUIP, 9.5f);
        }
        if (state.equippedBySystem()
            && !state.ongoingDanger()
            && state.health() >= 12.0f
            && state.safeTicks() >= 100) {
            return new Decision(Action.RESTORE, 2.5f);
        }
        return new Decision(Action.NONE, Float.NEGATIVE_INFINITY);
    }
}
