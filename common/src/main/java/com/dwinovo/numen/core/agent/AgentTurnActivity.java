package com.dwinovo.numen.core.agent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Server-side lease for a client agent turn that may span several tool calls. */
public final class AgentTurnActivity {
    private static final long LEASE_TICKS = 100L;
    private static final ConcurrentMap<UUID, Long> LAST_ACTIVE_TICK =
        new ConcurrentHashMap<>();

    private AgentTurnActivity() {
    }

    public static void observe(UUID companionId, boolean active, long serverTick) {
        if (companionId == null) {
            return;
        }
        if (active) {
            LAST_ACTIVE_TICK.put(companionId, serverTick);
        } else {
            LAST_ACTIVE_TICK.remove(companionId);
        }
    }

    public static boolean isActive(UUID companionId, long serverTick) {
        Long observedAt = LAST_ACTIVE_TICK.get(companionId);
        if (observedAt == null) {
            return false;
        }
        long age = serverTick - observedAt;
        if (age >= 0L && age <= LEASE_TICKS) {
            return true;
        }
        LAST_ACTIVE_TICK.remove(companionId, observedAt);
        return false;
    }

    public static void clear(UUID companionId) {
        if (companionId != null) {
            LAST_ACTIVE_TICK.remove(companionId);
        }
    }

    public static void clearAll() {
        LAST_ACTIVE_TICK.clear();
    }
}
