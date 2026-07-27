package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.entity.NumenPlayer;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

public final class CombatDeathEvents {
    private static final CombatDeathLedger DEATHS = new CombatDeathLedger(1_200L);

    private CombatDeathEvents() {
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (entity instanceof NumenPlayer player) {
            ShieldCombatController.release(player);
        }
        DEATHS.recordDeath(
            level.dimension().identifier().toString(),
            entity.getId(),
            entity.getUUID(),
            level.getGameTime()
        );
    }

    public static boolean diedSince(
        ServerLevel level,
        int entityId,
        UUID entityUuid,
        long taskStart
    ) {
        return DEATHS.diedSince(
            level.dimension().identifier().toString(),
            entityId,
            entityUuid,
            taskStart,
            level.getGameTime()
        );
    }
}
