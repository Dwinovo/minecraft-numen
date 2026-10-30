package com.dwinovo.numen.pathing.search;

import java.util.List;

import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.world.Kinematics;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 挖几格里的任意一格的终点价:终点格每少够着一格,停下多付一格平走的耗时除以格数(下界,估价从终点反着算时当种子的初值)。 */
class DigAnyEndCostTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void anEndCellChargesAShareForEveryTargetItDoesNotReach() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(4, 64, 0);
        Goal both = Goals.dig(List.of(new Goals.DigTarget(a, 0), new Goals.DigTarget(b, 3)), SURVIVAL, Goals.Clearing.ANY);
        LongSet reachA = Goals.dig(a, SURVIVAL, Goals.Clearing.ANY).endCells();
        LongSet reachB = Goals.dig(b, SURVIVAL, Goals.Clearing.ANY).endCells();
        LongSet shared = new LongOpenHashSet(reachA);
        shared.retainAll(reachB);
        LongSet onlyA = new LongOpenHashSet(reachA);
        onlyA.removeAll(reachB);
        double share = Kinematics.walkTicksPerBlock(SURVIVAL) / 2;
        assertTrue(!shared.isEmpty() && !onlyA.isEmpty());
        assertEquals(0, both.endCost(shared.iterator().nextLong()), 1e-9, "两格都够得着,取它们里最低的价 0");
        assertEquals(share, both.endCost(onlyA.iterator().nextLong()), 1e-9, "只够得着价为 0 的那一格,少一格");
        assertEquals(0, Goals.at(a).endCost(BlockPos.asLong(0, 64, 0)), "别的目标默认 0");
    }
}
