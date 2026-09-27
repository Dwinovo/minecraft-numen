package com.dwinovo.numen.core.task.build;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工地外圈:一圈列首尾相接、相邻两列只差一步、每一列都在包围盒外恰好 {@link SiteRing#MARGIN} 格的那条边上。 */
class SiteRingTest {

    @Test
    void consecutiveColumnsAreOneStepApartAndTheRingCloses() {
        BlockPos min = new BlockPos(10, 64, 20);
        BlockPos max = new BlockPos(14, 66, 23);
        SiteRing ring = SiteRing.around(min, max);
        int width = (max.getX() - min.getX()) + 2 * SiteRing.MARGIN;
        int depth = (max.getZ() - min.getZ()) + 2 * SiteRing.MARGIN;
        assertEquals(2 * (width + depth), ring.size(), "外圈每一列只出现一次");
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < ring.size(); i++) {
            int step = Math.abs(ring.x(i + 1) - ring.x(i)) + Math.abs(ring.z(i + 1) - ring.z(i));
            assertEquals(1, step, "第 " + i + " 列到下一列不是一步(最后一列要接回第一列)");
            boolean onEdge = ring.x(i) == min.getX() - SiteRing.MARGIN || ring.x(i) == max.getX() + SiteRing.MARGIN
                    || ring.z(i) == min.getZ() - SiteRing.MARGIN || ring.z(i) == max.getZ() + SiteRing.MARGIN;
            assertTrue(onEdge, "第 " + i + " 列不在外圈上");
            assertTrue(seen.add(BlockPos.asLong(ring.x(i), 0, ring.z(i))), "第 " + i + " 列重复了");
        }
    }

    @Test
    void theNearestColumnIsTheOneUnderfoot() {
        SiteRing ring = SiteRing.around(new BlockPos(0, 64, 0), new BlockPos(4, 64, 4));
        int i = ring.nearest(-1.5, 2.5);
        assertEquals(-2, ring.x(i));
        assertEquals(2, ring.z(i));
        assertTrue(ring.distance(i, -1.5, 2.5) < 1e-9);
    }
}
