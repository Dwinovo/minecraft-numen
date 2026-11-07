package com.dwinovo.numen.task.move;

import com.dwinovo.numen.pathing.api.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跟随受理前的试搜:没搜完(预算用完、路伸进没加载的区块)不能证明跟不上,照样受理;确证走不到的(没有路、要改地形、没料……)才当场回
 * "跟不上"。
 */
class FollowProbeTest {

    @Test
    void aProbeThatRanOutOfBudgetOrIntoUnloadedChunksIsNoProofSheCannotKeepUp() {
        assertTrue(FollowCompanionTask.unfinished(new Outcome.OutOfBudget(null)));
        assertTrue(FollowCompanionTask.unfinished(new Outcome.Unloaded()));
    }

    @Test
    void aProbeThatProvesThereIsNoWayIsARefusal() {
        assertFalse(FollowCompanionTask.unfinished(new Outcome.NoRoute(null)));
        assertFalse(FollowCompanionTask.unfinished(new Outcome.NeedsChanges(List.of())));
        assertFalse(FollowCompanionTask.unfinished(new Outcome.NoMaterials(3)));
    }
}
