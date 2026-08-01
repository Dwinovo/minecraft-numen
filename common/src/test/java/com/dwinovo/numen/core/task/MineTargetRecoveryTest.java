package com.dwinovo.numen.core.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineTargetRecoveryTest {

    private static final BlockPos A = new BlockPos(1, 20, 3);
    private static final BlockPos B = new BlockPos(5, 20, 7);

    @Test
    void waitsThenRepathsOnceBeforeGivingUp() {
        MineTargetRecovery recovery = new MineTargetRecovery(3, 1);

        assertEquals(MineTargetRecovery.Decision.WAIT, recovery.miss(A));
        assertEquals(MineTargetRecovery.Decision.WAIT, recovery.miss(A));
        assertEquals(MineTargetRecovery.Decision.REPATH, recovery.miss(A));
        assertEquals(1, recovery.repaths());

        assertEquals(MineTargetRecovery.Decision.WAIT, recovery.miss(A));
        assertEquals(MineTargetRecovery.Decision.WAIT, recovery.miss(A));
        assertEquals(MineTargetRecovery.Decision.GIVE_UP, recovery.miss(A));
    }

    @Test
    void changingTargetResetsTicksAndRepathBudget() {
        MineTargetRecovery recovery = new MineTargetRecovery(2, 1);

        assertEquals(MineTargetRecovery.Decision.WAIT, recovery.miss(A));
        assertEquals(MineTargetRecovery.Decision.REPATH, recovery.miss(A));
        assertEquals(MineTargetRecovery.Decision.WAIT, recovery.miss(B));
        assertEquals(B, recovery.target());
        assertEquals(1, recovery.ticks());
        assertEquals(0, recovery.repaths());
    }

    @Test
    void clearDropsAllState() {
        MineTargetRecovery recovery = new MineTargetRecovery(2, 1);
        recovery.miss(A);

        recovery.clear();

        assertNull(recovery.target());
        assertEquals(0, recovery.ticks());
        assertEquals(0, recovery.repaths());
    }

    @Test
    void invalidatedNavigationClearsNoShotBudgetBeforeSamePositionReplacement() throws Exception {
        MineCompanionTask task = new MineCompanionTask(null, null);
        MineTargetRecovery noShot = field(task, "noShotRecovery", MineTargetRecovery.class);
        Field navigationAttempt = MineCompanionTask.class.getDeclaredField("navigationAttempt");
        navigationAttempt.setAccessible(true);

        for (int tick = 1; tick < 20; tick++) {
            assertEquals(MineTargetRecovery.Decision.WAIT, noShot.miss(A));
        }
        assertEquals(MineTargetRecovery.Decision.REPATH, noShot.miss(A));
        assertEquals(A, noShot.target());
        assertEquals(1, noShot.repaths());
        navigationAttempt.set(task, new MineNavigationAttempt(A, MineNavigationAttempt.Kind.ORE));

        Method discard = MineCompanionTask.class.getDeclaredMethod("discardCurrentBusinessTarget");
        discard.setAccessible(true);
        discard.invoke(task);   // the production path for an invalid navigation target

        assertNull(navigationAttempt.get(task));
        assertNull(noShot.target());
        assertEquals(0, noShot.ticks());
        assertEquals(0, noShot.repaths());

        Set<?> oreBlacklist = field(task, "blacklist", Set.class);
        assertTrue(oreBlacklist.isEmpty());
        for (int tick = 1; tick < 20; tick++) {
            assertEquals(MineTargetRecovery.Decision.WAIT, noShot.miss(A));
        }
        assertEquals(MineTargetRecovery.Decision.REPATH, noShot.miss(A));
        assertEquals(1, noShot.repaths());
        assertTrue(oreBlacklist.isEmpty());
    }

    @Test
    void oreFailureBlacklistsOnlyTheRecordedOre() {
        Set<BlockPos> oreBlacklist = new HashSet<>();
        Set<BlockPos> dropBlacklist = new HashSet<>();

        MineNavigationAttempt selected = MineNavigationAttempt.nearest(
                B, java.util.List.of(A, B), java.util.List.of());
        selected.recordFailure(oreBlacklist, dropBlacklist);

        assertEquals(B, selected.pos());
        assertEquals(MineNavigationAttempt.Kind.ORE, selected.kind());
        assertFalse(oreBlacklist.contains(A));
        assertTrue(oreBlacklist.contains(B));
        assertTrue(dropBlacklist.isEmpty());
    }

    @Test
    void dropFailureCannotBlacklistAnOre() {
        Set<BlockPos> oreBlacklist = new HashSet<>();
        Set<BlockPos> dropBlacklist = new HashSet<>();

        MineNavigationAttempt selected = MineNavigationAttempt.nearest(
                B, java.util.List.of(A), java.util.List.of(B));
        selected.recordFailure(oreBlacklist, dropBlacklist);

        assertEquals(B, selected.pos());
        assertEquals(MineNavigationAttempt.Kind.DROP, selected.kind());
        assertTrue(oreBlacklist.isEmpty());
        assertTrue(dropBlacklist.contains(B));
    }

    @Test
    void oreAndDropBlacklistsStayIndependentAtTheSamePosition() {
        Set<BlockPos> oreBlacklist = new HashSet<>();
        Set<BlockPos> dropBlacklist = new HashSet<>();

        new MineNavigationAttempt(A, MineNavigationAttempt.Kind.ORE)
                .recordFailure(oreBlacklist, dropBlacklist);
        assertTrue(oreBlacklist.contains(A));
        assertFalse(dropBlacklist.contains(A));

        new MineNavigationAttempt(A, MineNavigationAttempt.Kind.DROP)
                .recordFailure(oreBlacklist, dropBlacklist);
        assertTrue(oreBlacklist.contains(A));
        assertTrue(dropBlacklist.contains(A));
    }

    private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(owner));
    }
}
