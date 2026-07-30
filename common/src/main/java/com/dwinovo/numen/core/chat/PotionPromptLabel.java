package com.dwinovo.numen.core.chat;

import java.util.Set;

/** Adds only the healing facts the model needs while preserving the real potion item id. */
public final class PotionPromptLabel {
    private static final Set<String> POTION_IDS = Set.of(
        "minecraft:potion",
        "minecraft:splash_potion",
        "minecraft:lingering_potion"
    );

    private PotionPromptLabel() {
    }

    public static String decorate(
        String itemId,
        boolean harmful,
        boolean instantHealth,
        boolean regeneration
    ) {
        if (!POTION_IDS.contains(itemId)) {
            return itemId;
        }
        if (harmful) {
            return itemId + "[unsafe]";
        }
        if (instantHealth) {
            return itemId + "[instant_health]";
        }
        if (regeneration) {
            return itemId + "[regeneration]";
        }
        return itemId + "[non_healing]";
    }
}
