package com.dwinovo.numen.core.pathing.astar;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.Movement;

import net.minecraft.core.BlockPos;

import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.FakePath;
import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.NEVER_GOAL;
import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.TestMovement;
import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SplicedPath 的拼接与拒绝判据。 */
class SplicedPathTest {

    @Test
    void spliceOnMatchingSeam() {
        // first: x 0..9,second: x 9..14,接缝 (9,64,0)
        NavPath first = line(0, 10, NEVER_GOAL);
        NavPath second = line(9, 6, NEVER_GOAL);
        Optional<SplicedPath> spliced = SplicedPath.trySplice(first, second, false);
        assertTrue(spliced.isPresent());
        SplicedPath path = spliced.get();
        assertEquals(15, path.length());
        assertEquals(14, path.movements().size());
        assertEquals(new BlockPos(0, 64, 0), path.getSrc());
        assertEquals(new BlockPos(14, 64, 0), path.getDest());
    }

    @Test
    void rejectWhenSeamDoesNotMatch() {
        NavPath first = line(0, 10, NEVER_GOAL);
        NavPath second = line(20, 6, NEVER_GOAL);
        assertTrue(SplicedPath.trySplice(first, second, false).isEmpty());
    }

    @Test
    void rejectNulls() {
        NavPath first = line(0, 10, NEVER_GOAL);
        assertTrue(SplicedPath.trySplice(first, null, false).isEmpty());
        assertTrue(SplicedPath.trySplice(null, first, false).isEmpty());
    }

    @Test
    void rejectOverlapWhenNotAllowed() {
        // second 从 first 终点出发但折返穿过 first 的中段 → 有重叠,拒绝
        NavPath first = line(0, 10, NEVER_GOAL); // 0..9
        List<BlockPos> positions = List.of(
                new BlockPos(9, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(7, 64, 0));
        List<Movement> movements = List.of(
                new TestMovement(positions.get(0), positions.get(1), 1),
                new TestMovement(positions.get(1), positions.get(2), 1));
        NavPath second = new FakePath(positions, movements, NEVER_GOAL);
        assertTrue(SplicedPath.trySplice(first, second, false).isEmpty());
    }

    @Test
    void overlapCutoffWhenAllowed() {
        // 允许重叠截断:first 在第一个共同格位处让位给 second
        NavPath first = line(0, 10, NEVER_GOAL); // 0..9
        List<BlockPos> positions = List.of(
                new BlockPos(9, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(8, 65, 0));
        List<Movement> movements = List.of(
                new TestMovement(positions.get(0), positions.get(1), 1),
                new TestMovement(positions.get(1), positions.get(2), 1));
        NavPath second = new FakePath(positions, movements, NEVER_GOAL);
        Optional<SplicedPath> spliced = SplicedPath.trySplice(first, second, true);
        assertTrue(spliced.isPresent());
        // first 截到 (8,64,0)(下标 8),second 从其后接上 (8,65,0)
        assertEquals(new BlockPos(0, 64, 0), spliced.get().getSrc());
        assertEquals(new BlockPos(8, 65, 0), spliced.get().getDest());
        assertEquals(10, spliced.get().length());
    }

    @Test
    void numNodesAccumulates() {
        NavPath first = line(0, 10, NEVER_GOAL);
        NavPath second = line(9, 6, NEVER_GOAL);
        SplicedPath path = SplicedPath.trySplice(first, second, false).orElseThrow();
        assertEquals(0, path.getNumNodesConsidered());
    }
}
