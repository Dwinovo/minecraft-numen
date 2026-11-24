package com.dwinovo.numen.pathing.drive;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.search.Goals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 到达:到没到只看目标自己的判定;不经 {@link Driver}。 */
class ArrivalTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld flat() {
        return new TestWorld().floor(-4, -4, 20, 4, Y - 1);
    }

    @Test
    void arrivingIsStandingInsideTheGoal() {
        BlockPos goalCell = new BlockPos(5, Y, 0);
        assertTrue(Arrival.arrived(Goals.at(goalCell), flat(), SURVIVAL, goalCell, false));
        assertFalse(Arrival.arrived(Goals.at(goalCell), flat(), SURVIVAL, new BlockPos(4, Y, 0), false));
    }

    /** 够着一格方块:手够得着就算到,中间隔着一堵墙也算;离得够不着的不算。 */
    @Test
    void reachingABlockArrivesWhateverStandsInBetween() {
        BlockPos furnace = new BlockPos(0, Y, 0);
        TestWorld world = flat().set(furnace, Blocks.FURNACE.defaultBlockState())
                .fill(1, Y, -4, 1, Y + 2, 4, Blocks.STONE.defaultBlockState());
        assertTrue(Arrival.arrived(Goals.reach(furnace, SURVIVAL), world, SURVIVAL, new BlockPos(3, Y, 0), false));
        assertFalse(Arrival.arrived(Goals.reach(furnace, SURVIVAL), world, SURVIVAL, new BlockPos(9, Y, 0), false));
    }
}
