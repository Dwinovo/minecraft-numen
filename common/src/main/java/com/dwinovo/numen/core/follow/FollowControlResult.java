package com.dwinovo.numen.core.follow;

import java.util.Objects;

/**
 * Immutable result envelope shared by the command and body-bound tool.
 */
public record FollowControlResult(
        FollowAction action,
        boolean success,
        boolean changed,
        String code,
        String message,
        FollowStatus status) {

    public FollowControlResult {
        Objects.requireNonNull(action, "action");
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        Objects.requireNonNull(status, "status");
    }
}
