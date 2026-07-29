package com.dwinovo.numen.core.follow;

/** Transient reason why owner following is not currently eligible to run. */
public enum FollowWaitingReason {
    NONE,
    OWNER_OFFLINE,
    OWNER_OTHER_DIMENSION,
    OWNER_TOO_FAR,
    OWNER_INVALID,
    COMPANION_NOT_ACTIVE
}
