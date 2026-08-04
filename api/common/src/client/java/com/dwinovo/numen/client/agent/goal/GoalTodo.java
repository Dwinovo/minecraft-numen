package com.dwinovo.numen.client.agent.goal;

import java.util.UUID;

/**
 * One item in a goal plan. Kept intentionally aligned with the existing
 * {@code todowrite} payload so later UI and command layers can reuse the same
 * status strings: pending, in_progress, completed, cancelled.
 */
public record GoalTodo(String id, String content, String status, long createdAtMs, long updatedAtMs) {

    public GoalTodo {
        id = id == null ? "" : id;
        content = content == null ? "" : content;
        status = status == null || status.isBlank() ? "pending" : status;
        createdAtMs = Math.max(0, createdAtMs);
        updatedAtMs = Math.max(0, updatedAtMs);
    }

    public static GoalTodo of(String content, String status, long nowMs) {
        return new GoalTodo(UUID.randomUUID().toString().substring(0, 8),
                content, status, nowMs, nowMs);
    }
}
