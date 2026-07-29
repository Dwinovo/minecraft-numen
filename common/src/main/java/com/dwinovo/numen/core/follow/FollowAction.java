package com.dwinovo.numen.core.follow;

import java.util.Locale;
import java.util.Optional;

/**
 * User-facing owner-follow controls shared by commands and the body-bound tool.
 */
public enum FollowAction {
    ON,
    OFF,
    PAUSE,
    RESUME,
    STATUS;

    public static Optional<FollowAction> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public String argumentValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
