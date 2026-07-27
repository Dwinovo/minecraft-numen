package com.dwinovo.numen.core.mining;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Keeps mining target types stable while navigation calculates and executes paths. */
public final class ActiveMiningTargets {
    private static final Map<UUID, Set<String>> BY_COMPANION = new LinkedHashMap<>();

    private ActiveMiningTargets() {
    }

    public static synchronized void begin(UUID companionId, Collection<String> targetIds) {
        if (companionId == null || targetIds == null) {
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String targetId : targetIds) {
            if (targetId != null && !targetId.isBlank()) {
                normalized.add(targetId.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (normalized.isEmpty()) {
            BY_COMPANION.remove(companionId);
        } else {
            BY_COMPANION.put(companionId, Set.copyOf(normalized));
        }
    }

    public static synchronized Set<String> ids(UUID companionId) {
        return BY_COMPANION.getOrDefault(companionId, Set.of());
    }

    public static synchronized void clear(UUID companionId) {
        BY_COMPANION.remove(companionId);
    }
}
