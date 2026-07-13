package com.dwinovo.numen.core.task;

/**
 * Stable machine-readable failure categories returned in task result data.
 *
 * <p>The human message remains the primary prompt for the LLM, while these
 * codes let later planning, UI and retry logic reason about failures without
 * parsing prose.
 */
public enum TaskFailureCode {
    TIMEOUT("timeout"),
    STUCK("stuck"),
    UNREACHABLE("unreachable"),
    MISSING_TOOL("missing_tool"),
    MISSING_ITEM("missing_item"),
    INVALID_TARGET("invalid_target"),
    CANCELLED("cancelled"),
    ENTITY_UNAVAILABLE("entity_unavailable"),
    INTERNAL_ERROR("internal_error");

    private final String code;

    TaskFailureCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
