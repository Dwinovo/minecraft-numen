package com.dwinovo.numen.core.follow;

import java.util.Objects;

/**
 * Immutable, Minecraft-object-free view of one companion's follow runtime.
 */
public record FollowRuntimeSnapshot(
        FollowRuntimeState runtimeState,
        FollowWaitingReason waitingReason,
        boolean following,
        boolean navigationActive,
        boolean sprintAllowed,
        boolean catchingUp,
        long failedUntilTick,
        long remainingCooldownTicks) {

    public FollowRuntimeSnapshot {
        Objects.requireNonNull(runtimeState, "runtimeState");
        Objects.requireNonNull(waitingReason, "waitingReason");
        remainingCooldownTicks = Math.max(0L, remainingCooldownTicks);
    }
}
