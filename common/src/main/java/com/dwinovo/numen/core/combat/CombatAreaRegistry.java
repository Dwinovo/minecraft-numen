package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import java.util.Collection;
import java.util.Optional;

/** Server-side bridge between a bounded entity scan and its follow-up attack task. */
public final class CombatAreaRegistry {
    private static final int RECENT_SCANS = 4;
    private static final long SCAN_TTL_NANOS = 5L * 60L * 1_000_000_000L;
    private static final CombatScanMemory MEMORY = new CombatScanMemory(RECENT_SCANS, SCAN_TTL_NANOS);

    private CombatAreaRegistry() {
    }

    public static void remember(NumenPlayer player, CombatArea area, Collection<Integer> entityIds) {
        MEMORY.record(player.getUUID(), dimension(player), area, entityIds, System.nanoTime());
    }

    public static CombatArea resolve(NumenPlayer player, Collection<Integer> entityIds) {
        Optional<CombatArea> remembered = MEMORY.find(
            player.getUUID(),
            dimension(player),
            entityIds,
            System.nanoTime()
        );
        return remembered.orElseGet(() -> CombatArea.samePlane(
            player.getX(),
            player.getY(),
            player.getZ(),
            64.0
        ));
    }

    private static String dimension(NumenPlayer player) {
        return player.level().dimension().identifier().toString();
    }
}
