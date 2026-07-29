package com.dwinovo.numen.core.follow;

/**
 * Transient, per-companion owner-follow execution state.
 *
 * <p>This state is intentionally not part of {@link FollowState} and is never
 * written to saved data.
 */
public enum FollowRuntimeState {
    DISABLED,
    MANUALLY_PAUSED,
    WAITING_FOR_OWNER,
    IDLE_NEAR_OWNER,
    FOLLOWING,
    FAILED_COOLDOWN
}
