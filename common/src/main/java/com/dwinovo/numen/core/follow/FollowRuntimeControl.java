package com.dwinovo.numen.core.follow;

import java.util.UUID;

/**
 * Transient control surface for one live companion follow runtime.
 */
public interface FollowRuntimeControl {

    UUID companionUuid();

    void release(FollowReleaseReason reason);

    FollowRuntimeSnapshot snapshot(long currentGameTime);
}
