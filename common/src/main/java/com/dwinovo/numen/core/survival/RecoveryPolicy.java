package com.dwinovo.numen.core.survival;

import java.util.Comparator;
import java.util.List;

public final class RecoveryPolicy {
    public enum Mode {
        NONE,
        MAINTENANCE,
        NATURAL_REGEN,
        ACTIVE_HEALING
    }

    public enum Kind {
        FOOD,
        INSTANT_HEALTH,
        REGENERATION,
        GOLDEN_APPLE,
        ENCHANTED_GOLDEN_APPLE
    }

    public enum Value {
        ORDINARY,
        VALUABLE,
        RISKY,
        FORBIDDEN
    }

    public record State(
        int foodLevel,
        float saturationLevel,
        float health,
        float maxHealth,
        boolean naturalRegeneration,
        boolean regenerationActive
    ) {
    }

    public record Candidate(
        int slot,
        Kind kind,
        Value value,
        int nutrition,
        float saturationGain,
        float healing
    ) {
        public static Candidate food(int slot, Value value, int nutrition, float saturationGain) {
            return new Candidate(slot, Kind.FOOD, value, nutrition, saturationGain, 0.0f);
        }

        public static Candidate healing(int slot, Kind kind, float healing) {
            return new Candidate(slot, kind, Value.VALUABLE, 0, 0.0f, healing);
        }
    }

    public record Decision(Mode mode, int slot, float priority) {
        public static Decision none() {
            return new Decision(Mode.NONE, -1, Float.NEGATIVE_INFINITY);
        }
    }

    private RecoveryPolicy() {
    }

    public static Decision decide(State state, List<Candidate> candidates) {
        boolean critical = state.health() <= 8.0f;
        boolean needsActiveHealing = critical
            || (!state.naturalRegeneration() && state.health() <= 12.0f);
        if (needsActiveHealing) {
            int healingSlot = selectHealingSlot(state, candidates);
            if (healingSlot >= 0) {
                return new Decision(Mode.ACTIVE_HEALING, healingSlot, 4.5f);
            }
        }

        if (state.naturalRegeneration() && state.health() <= 12.0f && state.foodLevel() < 20) {
            int foodSlot = selectFoodSlot(Mode.NATURAL_REGEN, state, candidates);
            if (foodSlot >= 0) {
                return new Decision(Mode.NATURAL_REGEN, foodSlot, 4.0f);
            }
        }

        if (state.foodLevel() <= 14) {
            int foodSlot = selectFoodSlot(Mode.MAINTENANCE, state, candidates);
            if (foodSlot >= 0) {
                return new Decision(Mode.MAINTENANCE, foodSlot, 3.0f);
            }
        }
        return Decision.none();
    }

    public static boolean allowsRequestedUse(
        State state,
        Candidate requested,
        List<Candidate> candidates
    ) {
        if (requested.kind() == Kind.FOOD) {
            return requested.value() == Value.ORDINARY
                || selectFoodSlot(Mode.MAINTENANCE, state, candidates) == requested.slot();
        }
        Decision decision = decide(state, candidates);
        return decision.mode() == Mode.ACTIVE_HEALING
            && decision.slot() == requested.slot();
    }

    private static int selectHealingSlot(State state, List<Candidate> candidates) {
        float missingHealth = Math.max(0.0f, state.maxHealth() - state.health());
        int slot = bestHealingSlot(candidates, Kind.INSTANT_HEALTH, missingHealth);
        if (slot >= 0) {
            return slot;
        }
        if (!state.regenerationActive()) {
            slot = bestHealingSlot(candidates, Kind.REGENERATION, missingHealth);
            if (slot >= 0) {
                return slot;
            }
        }
        if (state.regenerationActive() && state.health() > 4.0f) {
            return -1;
        }
        slot = bestHealingSlot(candidates, Kind.GOLDEN_APPLE, missingHealth);
        return slot >= 0
            ? slot
            : bestHealingSlot(candidates, Kind.ENCHANTED_GOLDEN_APPLE, missingHealth);
    }

    private static int bestHealingSlot(
        List<Candidate> candidates,
        Kind kind,
        float missingHealth
    ) {
        return candidates.stream()
            .filter(candidate -> candidate.kind() == kind)
            .min(
                Comparator
                    .comparingInt((Candidate candidate) -> candidate.healing() >= missingHealth ? 0 : 1)
                    .thenComparingDouble(
                        candidate -> candidate.healing() >= missingHealth
                            ? candidate.healing()
                            : -candidate.healing()
                    )
                    .thenComparingInt(Candidate::slot)
            )
            .map(Candidate::slot)
            .orElse(-1);
    }

    public static int selectFoodSlot(Mode mode, State state, List<Candidate> candidates) {
        if (mode == Mode.NONE || mode == Mode.ACTIVE_HEALING) {
            return -1;
        }

        Comparator<Candidate> quality = mode == Mode.NATURAL_REGEN
            ? Comparator
                .comparingInt((Candidate candidate) -> reaches(state, candidate, 20) ? 0 : 1)
                .thenComparing(
                    Comparator.comparingDouble(
                        (Candidate candidate) -> effectiveSaturation(state, candidate)
                    ).reversed()
                )
                .thenComparingInt(candidate -> nutritionOverflow(state, candidate))
            : Comparator
                .comparingInt((Candidate candidate) -> reaches(state, candidate, 18) ? 0 : 1)
                .thenComparingInt(candidate -> nutritionOverflow(state, candidate))
                .thenComparing(
                    Comparator.comparingDouble(
                        (Candidate candidate) -> effectiveSaturation(state, candidate)
                    ).reversed()
                );

        int ordinary = bestFoodSlot(candidates, Value.ORDINARY, quality);
        if (ordinary >= 0) {
            return ordinary;
        }
        int valuable = bestFoodSlot(candidates, Value.VALUABLE, quality);
        if (valuable >= 0) {
            return valuable;
        }
        return state.foodLevel() <= 6
            ? bestFoodSlot(candidates, Value.RISKY, quality)
            : -1;
    }

    private static int bestFoodSlot(
        List<Candidate> candidates,
        Value value,
        Comparator<Candidate> quality
    ) {
        return candidates.stream()
            .filter(candidate -> candidate.kind() == Kind.FOOD && candidate.value() == value)
            .min(quality.thenComparingInt(Candidate::slot))
            .map(Candidate::slot)
            .orElse(-1);
    }

    private static boolean reaches(State state, Candidate candidate, int target) {
        return state.foodLevel() + candidate.nutrition() >= target;
    }

    private static int nutritionOverflow(State state, Candidate candidate) {
        return Math.max(0, state.foodLevel() + candidate.nutrition() - 20);
    }

    private static float effectiveSaturation(State state, Candidate candidate) {
        int foodAfterEating = Math.min(20, state.foodLevel() + candidate.nutrition());
        float saturationAfterEating = Math.min(
            foodAfterEating,
            state.saturationLevel() + candidate.saturationGain()
        );
        return Math.max(0.0f, saturationAfterEating - state.saturationLevel());
    }
}
