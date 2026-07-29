package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

class FollowConfigIntegrationTest {

    private static final FollowConfig CUSTOM = new FollowConfig(
            2.0, 4.0, 9.0, 20.0, 50.0, 240L,
            false, false, false, false, false);

    @Test
    void defaultConfigurationPreservesStageFourThresholds() {
        FollowConfig config = FollowConfig.defaults();

        assertEquals(new FollowDecisions.Distances(3.0, 5.5),
                FollowDecisions.resolveDistances(null, null, config));
        assertFalse(decide(config, 11.99, false).sprintAllowed());
        assertTrue(decide(config, 12.0, false).sprintAllowed());
        assertFalse(decide(config, 23.99, false).catchingUp());
        assertTrue(decide(config, 24.0, false).catchingUp());
    }

    @Test
    void customStopAndStartDriveHysteresis() {
        assertEquals(FollowRuntimeState.IDLE_NEAR_OWNER,
                decide(CUSTOM, 2.0, false).runtimeState());
        assertEquals(FollowRuntimeState.IDLE_NEAR_OWNER,
                decide(CUSTOM, 3.99, false).runtimeState());
        assertEquals(FollowRuntimeState.FOLLOWING,
                decide(CUSTOM, 4.0, false).runtimeState());
        assertEquals(FollowRuntimeState.FOLLOWING,
                decide(CUSTOM, 3.0, true).runtimeState());
    }

    @Test
    void customSprintCatchUpAndLostBoundariesApply() {
        assertFalse(decide(CUSTOM, 8.99, false).sprintAllowed());
        assertTrue(decide(CUSTOM, 9.0, false).sprintAllowed());
        assertFalse(decide(CUSTOM, 19.99, false).catchingUp());
        assertTrue(decide(CUSTOM, 20.0, false).catchingUp());
        assertEquals(FollowWaitingReason.OWNER_TOO_FAR,
                decide(CUSTOM, 50.0, true).waitingReason());
    }

    @Test
    void customFailureCooldownIsExact() {
        FollowDecisions.Result active = decide(CUSTOM, 8.0, false);

        FollowDecisions.Result failed =
                FollowDecisions.failedAt(active, 10L, CUSTOM);

        assertEquals(250L, failed.failedUntilTick());
        assertEquals(240L, FollowDecisions.remainingCooldownTicks(
                failed.failedUntilTick(), 10L));
    }

    @Test
    void validCompanionOverrideWinsOverConfiguration() {
        assertEquals(new FollowDecisions.Distances(2.5, 6.5),
                FollowDecisions.resolveDistances(2.5, 6.5, CUSTOM));
    }

    @Test
    void overrideStartMustRemainBelowEffectiveSprint() {
        assertEquals(new FollowDecisions.Distances(2.0, 4.0),
                FollowDecisions.resolveDistances(2.5, 9.0, CUSTOM));
    }

    @Test
    void everyInvalidOverrideFallsBackAsOnePair() {
        assertEquals(new FollowDecisions.Distances(2.0, 4.0),
                FollowDecisions.resolveDistances(null, 6.0, CUSTOM));
        assertEquals(new FollowDecisions.Distances(2.0, 4.0),
                FollowDecisions.resolveDistances(6.0, 5.0, CUSTOM));
        assertEquals(new FollowDecisions.Distances(2.0, 4.0),
                FollowDecisions.resolveDistances(Double.NaN, 5.0, CUSTOM));
    }

    @Test
    void chainCapturesExactImmutableConfigurationInstance() {
        OwnerFollowChainTestHarness first =
                new OwnerFollowChainTestHarness(
                        OwnerFollowChainTestHarness.activeAt(6.0, 1L), CUSTOM);
        OwnerFollowChainTestHarness second =
                new OwnerFollowChainTestHarness(
                        OwnerFollowChainTestHarness.activeAt(6.0, 1L), CUSTOM);

        assertSame(CUSTOM, first.chain.config());
        assertSame(CUSTOM, second.chain.config());
        first.chain.tick(null);
        assertTrue(first.chain.hasNavigation());
        assertFalse(second.chain.hasNavigation());
    }

    @Test
    void chainUsesCapturedConfigWithoutReloadingAFile() {
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(
                        OwnerFollowChainTestHarness.activeAt(4.0, 1L), CUSTOM);

        harness.chain.tick(null);
        harness.access.snapshot = OwnerFollowChainTestHarness.activeAt(8.99, 2L);
        harness.chain.tick(null);

        assertFalse(harness.chain.runtimeView().sprintAllowed());
        assertSame(CUSTOM, harness.chain.config());
    }

    @Test
    void configurationNeverEntersFollowStateNbt() {
        FollowStateStore store = new FollowStateStore();
        store.put(UUID.randomUUID(), new FollowState(
                true, false, FollowState.CURRENT_SCHEMA_VERSION, 2.5, 6.5));

        String nbt = store.save(new CompoundTag()).toString();

        assertFalse(nbt.contains("sprint"));
        assertFalse(nbt.contains("catch"));
        assertFalse(nbt.contains("lost"));
        assertFalse(nbt.contains("cooldown"));
        assertFalse(nbt.contains("allow"));
    }

    private static FollowDecisions.Result decide(
            FollowConfig config, double distance, boolean following) {
        return FollowDecisions.decide(
                new FollowDecisions.Input(
                        true, false, true, true, true, true, true,
                        distance, null, null, 10L),
                following,
                FollowDecisions.NO_FAILED_COOLDOWN,
                config);
    }
}
