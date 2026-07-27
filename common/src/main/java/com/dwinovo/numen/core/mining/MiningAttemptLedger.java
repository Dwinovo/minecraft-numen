package com.dwinovo.numen.core.mining;

import com.dwinovo.numen.core.mining.MiningGeometry.Point;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class MiningAttemptLedger {
    public enum Decision {
        DEFER,
        FINAL_FAILURE
    }

    private final int maxAttempts;
    private final Map<Point, Integer> attempts = new HashMap<>();
    private final Set<Point> deferred = new HashSet<>();
    private final LinkedHashMap<Point, String> failures = new LinkedHashMap<>();

    public MiningAttemptLedger(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    public Decision recordFailure(Point target, String reason) {
        int attempt = attempts.merge(target, 1, Integer::sum);
        if (attempt >= maxAttempts) {
            deferred.remove(target);
            failures.put(target, normalizeReason(reason));
            return Decision.FINAL_FAILURE;
        }
        deferred.add(target);
        return Decision.DEFER;
    }

    public boolean isDeferred(Point target) {
        return deferred.contains(target);
    }

    public void startNextRound() {
        deferred.clear();
    }

    public void recordSuccess(Point target) {
        attempts.remove(target);
        deferred.remove(target);
        failures.remove(target);
    }

    public Map<Point, String> failures() {
        return Map.copyOf(failures);
    }

    public int attempts(Point target) {
        return attempts.getOrDefault(target, 0);
    }

    public static boolean isComplete(int completed, int requested) {
        return requested >= 0 && completed >= requested;
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? "unknown failure" : reason;
    }
}
