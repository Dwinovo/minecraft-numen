package com.dwinovo.numen.pathing.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.plan.MoveKind;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** 这一趟的记忆:抬高过的估价只升不降、走不通的步、冻下来的一份不随后来的写变;不经搜索。 */
class TripMemoryTest {

    private static final BlockPos A = new BlockPos(1, 64, 1);
    private static final BlockPos B = new BlockPos(2, 64, 1);

    @Test
    void aRaisedEstimateOnlyGoesUp() {
        TripMemory memory = new TripMemory();
        memory.raise(A.asLong(), 30);
        memory.raise(A.asLong(), 10);
        assertEquals(30, memory.frozen().raised(A.asLong()));
        memory.raise(A.asLong(), 50);
        assertEquals(50, memory.frozen().raised(A.asLong()));
        assertEquals(0, memory.frozen().raised(B.asLong()), "没抬过的格不变");
    }

    @Test
    void aBlockedStepIsOnlyThatKindFromThatCellToThatCell() {
        TripMemory memory = new TripMemory();
        memory.block(MoveKind.WALK, A, B);
        TripMemory.Frozen frozen = memory.frozen();
        assertTrue(frozen.blocks(MoveKind.WALK, A.asLong(), B.asLong()));
        assertFalse(frozen.blocks(MoveKind.ASCEND, A.asLong(), B.asLong()), "换一种走法是另一步");
        assertFalse(frozen.blocks(MoveKind.WALK, B.asLong(), A.asLong()), "反过来是另一步");
        memory.block(MoveKind.WALK, A, B);
        assertEquals(1, memory.blockedSteps(), "同一步记两次还是一步");
    }

    @Test
    void aFrozenCopyDoesNotChangeWhenTheMemoryDoes() {
        TripMemory memory = new TripMemory();
        memory.raise(A.asLong(), 5);
        TripMemory.Frozen before = memory.frozen();
        assertSame(before, memory.frozen(), "没变过就是同一份");
        memory.raise(A.asLong(), 9);
        memory.block(MoveKind.WALK, A, B);
        assertEquals(5, before.raised(A.asLong()));
        assertFalse(before.blocks(MoveKind.WALK, A.asLong(), B.asLong()));
        assertEquals(9, memory.frozen().raised(A.asLong()));
    }

    @Test
    void changingTheGoalForgetsTheRaisedEstimatesButNotTheBlockedSteps() {
        TripMemory memory = new TripMemory();
        memory.raise(A.asLong(), 5);
        memory.block(MoveKind.WALK, A, B);
        memory.forgetRaised();
        assertEquals(0, memory.frozen().raised(A.asLong()));
        assertTrue(memory.frozen().blocks(MoveKind.WALK, A.asLong(), B.asLong()), "世界的事实换了目标也在");
    }
}
