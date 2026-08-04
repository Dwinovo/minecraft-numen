package com.dwinovo.numen.client.agent.goal;

/** Lifecycle of a persistent Numen goal. */
public enum GoalStatus {
    NONE("none"),
    ACTIVE("active"),
    PAUSED("paused"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    FAILED("failed"),
    BLOCKED("blocked");

    private final String key;

    GoalStatus(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static GoalStatus parse(String raw, GoalStatus fallback) {
        if (raw == null) return fallback;
        for (GoalStatus status : values()) {
            if (status.key.equalsIgnoreCase(raw)) return status;
        }
        return fallback;
    }
}
