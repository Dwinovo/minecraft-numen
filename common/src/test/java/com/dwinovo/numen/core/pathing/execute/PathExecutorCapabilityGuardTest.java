package com.dwinovo.numen.core.pathing.execute;

import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.NavigationCapabilities;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathExecutorCapabilityGuardTest {

    @Test
    void safeFollowSuppressesBreakPlaceAndBucketInputs() {
        RecordingDelegate target = new RecordingDelegate();
        Movement.ExecutionDelegate guarded = PathExecutor.capabilityGuardedDelegate(
                target, NavigationCapabilities.SAFE_FOLLOW);

        guarded.beginBreaking(null, BlockPos.ZERO);
        guarded.applyInput(Input.CLICK_LEFT, true);
        guarded.applyInput(Input.CLICK_RIGHT, true);
        guarded.applyInput(Input.MOVE_FORWARD, true);
        guarded.applyRotation(null);
        guarded.clearInputs();

        assertEquals(0, target.breakRequests);
        assertEquals(0, target.leftClicks);
        assertEquals(0, target.rightClicks);
        assertEquals(1, target.forwardInputs);
        assertEquals(1, target.rotations);
        assertEquals(1, target.clears);
    }

    @Test
    void defaultPreservesExistingExecutionInputs() {
        RecordingDelegate target = new RecordingDelegate();
        Movement.ExecutionDelegate guarded = PathExecutor.capabilityGuardedDelegate(
                target, NavigationCapabilities.DEFAULT);

        guarded.beginBreaking(null, BlockPos.ZERO);
        guarded.applyInput(Input.CLICK_LEFT, true);
        guarded.applyInput(Input.CLICK_RIGHT, true);

        assertEquals(1, target.breakRequests);
        assertEquals(1, target.leftClicks);
        assertEquals(1, target.rightClicks);
    }

    @Test
    void nullCapabilitiesAreRejectedExplicitly() {
        assertThrows(NullPointerException.class,
                () -> PathExecutor.capabilityGuardedDelegate(new RecordingDelegate(), null));
    }

    private static final class RecordingDelegate implements Movement.ExecutionDelegate {
        int breakRequests;
        int rotations;
        int clears;
        int leftClicks;
        int rightClicks;
        int forwardInputs;

        @Override
        public void beginBreaking(MovementState state, BlockPos pos) {
            breakRequests++;
        }

        @Override
        public void applyRotation(MovementState.MovementTarget target) {
            rotations++;
        }

        @Override
        public void clearInputs() {
            clears++;
        }

        @Override
        public void applyInput(Input input, boolean held) {
            if (input == Input.CLICK_LEFT) {
                leftClicks++;
            } else if (input == Input.CLICK_RIGHT) {
                rightClicks++;
            } else if (input == Input.MOVE_FORWARD) {
                forwardInputs++;
            }
        }
    }
}
