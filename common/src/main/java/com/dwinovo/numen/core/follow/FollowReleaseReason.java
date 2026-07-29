package com.dwinovo.numen.core.follow;

/**
 * Why transient owner-follow control is being relinquished.
 */
public enum FollowReleaseReason {
    SCHEDULER_INTERRUPT,
    FOLLOW_DISABLED,
    MANUAL_PAUSE,
    COMPANION_DEATH,
    COMPANION_REMOVED,
    SERVER_STOPPING,
    RUNTIME_REPLACED,
    INTERNAL_STATE_CHANGE
}
