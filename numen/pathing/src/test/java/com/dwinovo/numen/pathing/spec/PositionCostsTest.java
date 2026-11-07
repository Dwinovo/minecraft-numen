package com.dwinovo.numen.pathing.spec;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.dwinovo.numen.pathing.spec.PositionCosts.Use;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 按位置的禁令与加价:包围盒外的格不进哈希表,答案与逐格查表一样。 */
class PositionCostsTest {

    private static long cell(Random random, int range) {
        return BlockPos.asLong(random.nextInt(2 * range + 1) - range, 60 + random.nextInt(11), random.nextInt(2 * range + 1) - range);
    }

    @Test
    void cellsOutsideTheBoxAreNeitherForbiddenNorCharged() {
        Random random = new Random(3);
        for (int round = 0; round < 200; round++) {
            Set<Long> forbidden = new HashSet<>();
            Map<Long, Double> charged = new HashMap<>();
            PositionCosts.Builder builder = PositionCosts.builder();
            Use forbidUse = Use.values()[random.nextInt(4)];
            Use chargeUse = Use.values()[random.nextInt(4)];
            for (int i = 0; i < random.nextInt(8); i++) {
                long c = cell(random, 20);
                forbidden.add(c);
                builder.forbid(forbidUse, c);
            }
            for (int i = 0; i < random.nextInt(8); i++) {
                long c = cell(random, 20);
                double cost = 1 + random.nextInt(5);
                charged.merge(c, cost, Double::sum);
                builder.add(chargeUse, c, cost);
            }
            PositionCosts table = builder.build();
            for (int i = 0; i < 400; i++) {
                long c = cell(random, 40);
                for (Use use : Use.values()) {
                    assertEquals(use == forbidUse && forbidden.contains(c), table.forbids(use, c));
                    assertEquals(use == chargeUse ? charged.getOrDefault(c, 0.0) : 0.0, table.extra(use, c));
                }
            }
        }
    }

    @Test
    void aRegionOrAConfinementHasNoBoxButStillAnswers() {
        long inside = BlockPos.asLong(1000, 70, 1000);
        PositionCosts region = PositionCosts.builder().forbid(Use.PASS, c -> BlockPos.getX(c) > 900).build();
        assertTrue(region.forbids(Use.PASS, inside));
        assertFalse(region.forbids(Use.PASS, BlockPos.asLong(0, 70, 0)));
        LongOpenHashSet only = new LongOpenHashSet();
        only.add(BlockPos.asLong(5, 64, 5));
        PositionCosts confined = PositionCosts.builder().confine(Use.DIG, only).build();
        assertFalse(confined.forbids(Use.DIG, BlockPos.asLong(5, 64, 5)));
        assertTrue(confined.forbids(Use.DIG, inside));
        assertFalse(confined.forbids(Use.PLACE, inside));
    }
}
