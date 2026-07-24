package com.dwinovo.numen.core.pathing.astar;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;

import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.ALWAYS_GOAL;
import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.NEVER_GOAL;
import static com.dwinovo.numen.core.pathing.astar.AstarTestSupport.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** staticCutoff 边界与 cutoffAtLoadedChunks 开关行为。 */
class StaticCutoffTest {

    @Test
    void shorterThanMinimumIsUntouched() {
        NavPath path = line(0, 29, NEVER_GOAL);
        assertSame(path, path.staticCutoff(NEVER_GOAL));
    }

    @Test
    void exactlyMinimumLosesNothing() {
        // length=30:newLength=(30-30)*0.9+30-1=29,截到下标 29 仍是全部 30 格
        NavPath path = line(0, 30, NEVER_GOAL);
        NavPath cut = path.staticCutoff(NEVER_GOAL);
        assertEquals(30, cut.length());
    }

    @Test
    void thirtyOneLosesOne() {
        // length=31:newLength=(1)*0.9+29=29 → 保留 30 格
        NavPath path = line(0, 31, NEVER_GOAL);
        NavPath cut = path.staticCutoff(NEVER_GOAL);
        assertEquals(30, cut.length());
        assertEquals(new BlockPos(29, 64, 0), cut.getDest());
    }

    @Test
    void longPathLosesTenPercentOfExcess() {
        // length=50:newLength=(20)*0.9+29=47 → 保留 48 格
        NavPath path = line(0, 50, NEVER_GOAL);
        NavPath cut = path.staticCutoff(NEVER_GOAL);
        assertEquals(48, cut.length());
        assertEquals(new BlockPos(0, 64, 0), cut.getSrc());
        assertEquals(new BlockPos(47, 64, 0), cut.getDest());
        assertEquals(47, cut.movements().size());
    }

    @Test
    void destInGoalIsNeverCut() {
        NavPath path = line(0, 100, ALWAYS_GOAL);
        assertSame(path, path.staticCutoff(ALWAYS_GOAL));
    }

    @Test
    void nullGoalIsNeverCut() {
        NavPath path = line(0, 100, NEVER_GOAL);
        assertSame(path, path.staticCutoff(null));
    }

    @Test
    void loadBoundaryCutoffDisabledByDefault() {
        NavPath path = line(0, 50, NEVER_GOAL);
        // 默认开关关:即使谓词说全都未加载也不截
        assertSame(path, path.cutoffAtLoadedChunks((x, z) -> false));
    }

    @Test
    void loadBoundaryCutoffWhenEnabled() {
        NavSettings settings = NavSettings.get();
        boolean saved = settings.cutoffAtLoadBoundary;
        settings.cutoffAtLoadBoundary = true;
        try {
            NavPath path = line(0, 50, NEVER_GOAL);
            // x≥20 起未加载:截到第一个未加载格(下标 20,含)
            ChunkLoadedTest test = (x, z) -> x < 20;
            NavPath cut = path.cutoffAtLoadedChunks(test);
            assertEquals(21, cut.length());
            assertEquals(new BlockPos(20, 64, 0), cut.getDest());
        } finally {
            settings.cutoffAtLoadBoundary = saved;
        }
    }

    @Test
    void cutoffPathKeepsMovementAlignment() {
        NavPath path = line(0, 40, NEVER_GOAL);
        CutoffPath cut = new CutoffPath(path, 5, 15);
        assertEquals(11, cut.length());
        assertEquals(10, cut.movements().size());
        assertEquals(new BlockPos(5, 64, 0), cut.getSrc());
        assertEquals(new BlockPos(15, 64, 0), cut.getDest());
        // sanityCheck 已在构造里跑过;再显式验证一次首尾衔接
        cut.sanityCheck();
        assertTrue(cut.movements().get(0).getSrc().equals(cut.positions().get(0)));
    }
}
