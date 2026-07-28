package com.dwinovo.numen.core.scaffold;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Matches observed block placements to short-lived intents emitted by navigation. */
public final class TemporaryScaffoldTracker {
    private static final long MAX_INTENT_AGE_TICKS = 1L;

    private record Key(String dimensionId, int x, int y, int z) {
    }

    private record Intent(NavigationPlacementRole role, long expectedAtGameTime) {
    }

    private static final Map<UUID, LinkedHashMap<Key, Intent>> EXPECTED =
        new LinkedHashMap<>();

    private TemporaryScaffoldTracker() {
    }

    public static synchronized void expectNavigationPlacement(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z,
        NavigationPlacementRole role,
        long gameTime
    ) {
        if (companionId == null || dimensionId == null || role == null) {
            return;
        }
        LinkedHashMap<Key, Intent> intents = EXPECTED.computeIfAbsent(
            companionId,
            ignored -> new LinkedHashMap<>()
        );
        prune(intents, gameTime);
        intents.put(new Key(dimensionId, x, y, z), new Intent(role, gameTime));
    }

    public static synchronized boolean recordObservedPlacement(
        UUID companionId,
        String dimensionId,
        int x,
        int y,
        int z,
        String placedBlockId,
        String previousBlockId,
        boolean explicitBuildTarget,
        long gameTime
    ) {
        if (companionId == null || dimensionId == null) {
            return false;
        }

        LinkedHashMap<Key, Intent> intents = EXPECTED.get(companionId);
        if (intents == null) {
            return false;
        }
        Key key = new Key(dimensionId, x, y, z);
        Intent intent = intents.remove(key);
        prune(intents, gameTime);
        if (intents.isEmpty()) {
            EXPECTED.remove(companionId);
        }
        if (explicitBuildTarget || intent == null) {
            return false;
        }
        long age = gameTime - intent.expectedAtGameTime();
        if (age < 0L || age > MAX_INTENT_AGE_TICKS) {
            return false;
        }
        return TemporaryScaffoldLedger.recordPlacement(
            companionId,
            dimensionId,
            x,
            y,
            z,
            placedBlockId,
            previousBlockId,
            intent.role(),
            gameTime
        );
    }

    public static synchronized void clear(UUID companionId) {
        EXPECTED.remove(companionId);
        TemporaryScaffoldLedger.clear(companionId);
    }

    private static void prune(Map<Key, Intent> intents, long gameTime) {
        intents.entrySet().removeIf(entry -> {
            long age = gameTime - entry.getValue().expectedAtGameTime();
            return age < 0L || age > MAX_INTENT_AGE_TICKS;
        });
    }
}
