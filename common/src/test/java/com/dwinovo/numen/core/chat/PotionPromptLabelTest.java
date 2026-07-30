package com.dwinovo.numen.core.chat;

import org.junit.jupiter.api.Test;

public final class PotionPromptLabelTest {
    @Test
    void labelsOnlySafeHealingPotionFacts() {
        require(
            PotionPromptLabel.decorate("minecraft:potion", false, false, true)
                .equals("minecraft:potion[regeneration]"),
            "a drinkable regeneration potion must be visible as regeneration, not a generic potion"
        );
        require(
            PotionPromptLabel.decorate("minecraft:potion", false, true, false)
                .equals("minecraft:potion[instant_health]"),
            "a drinkable instant-health potion must expose its healing effect"
        );
        require(
            PotionPromptLabel.decorate("minecraft:splash_potion", false, false, true)
                .equals("minecraft:splash_potion[regeneration]"),
            "a splash potion must keep its non-drinkable item id while exposing regeneration"
        );
        require(
            PotionPromptLabel.decorate("minecraft:potion", true, false, true)
                .equals("minecraft:potion[unsafe]"),
            "a potion containing a harmful effect must never be advertised as safe healing"
        );
        require(
            PotionPromptLabel.decorate("minecraft:potion", false, false, false)
                .equals("minecraft:potion[non_healing]"),
            "water and unrelated potions must not look like healing consumables"
        );
        require(
            PotionPromptLabel.decorate("minecraft:bow", false, false, false)
                .equals("minecraft:bow"),
            "ordinary inventory items must remain unchanged"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
