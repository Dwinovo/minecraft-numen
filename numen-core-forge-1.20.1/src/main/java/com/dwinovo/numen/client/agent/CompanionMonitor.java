package com.dwinovo.numen.client.agent;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/** Low-frequency, transition-based observation that avoids polling the LLM or flooding context. */
final class CompanionMonitor {

    record Alert(String xml, boolean urgent) { }

    private static final int SAMPLE_TICKS = 100;
    private int ticks;
    private String lastState = "";
    private final AutonomyMemory memory;

    CompanionMonitor(AutonomyMemory memory) {
        this.memory = memory;
    }

    Alert tick(AbstractClientPlayer entity) {
        if (entity == null || ++ticks % SAMPLE_TICKS != 0) return null;
        int hostiles = entity.level.getEntitiesOfClass(Mob.class,
                new AABB(entity.blockPosition()).inflate(12.0D), mob -> mob instanceof Enemy && mob.isAlive()).size();
        int emptySlots = 0;
        for (var stack : entity.getInventory().items) if (stack.isEmpty()) emptySlots++;
        boolean criticalHealth = entity.getHealth() <= 6.0F;
        boolean lowHealth = entity.getHealth() <= 10.0F;
        boolean lowFood = entity.getFoodData().getFoodLevel() <= 6;
        boolean lava = entity.isInLava();
        boolean inventoryFull = emptySlots == 0;
        boolean nighttime = entity.level.isNight();
        int reservationShortages = reservationShortages(entity);
        String state = criticalHealth + ":" + lowHealth + ":" + lowFood + ":" + lava + ":"
                + inventoryFull + ":" + nighttime + ":" + (hostiles > 0) + ":" + reservationShortages;
        if (state.equals(lastState)) return null;
        lastState = state;
        if (!(criticalHealth || lowHealth || lowFood || lava || inventoryFull || nighttime
                || hostiles > 0 || reservationShortages > 0)) return null;
        String xml = "<event kind=\"monitor\" hp=\"" + Math.round(entity.getHealth() * 10.0F) / 10.0F
                + "\" hunger=\"" + entity.getFoodData().getFoodLevel() + "\" hostiles=\"" + hostiles
                + "\" empty_slots=\"" + emptySlots + "\" night=\"" + nighttime + "\" lava=\"" + lava
                + "\" reservation_shortages=\"" + reservationShortages
                + "\">State changed. Recheck before continuing; protect survival and reserved resources.</event>";
        return new Alert(xml, criticalHealth || lava || (hostiles > 0 && lowHealth));
    }

    private int reservationShortages(AbstractClientPlayer entity) {
        int shortages = 0;
        for (AutonomyMemory.Reservation reservation : memory.reservations()) {
            int carried = 0;
            for (var stack : entity.getInventory().items) {
                if (!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString().equals(reservation.item())) carried += stack.getCount();
            }
            if (carried < reservation.count()) shortages++;
        }
        return shortages;
    }
}
