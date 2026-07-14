package com.dwinovo.numen.core.pathing.engine;

/**
 * Two-phase node budget — Baritone's primary/failure TIME semantics translated
 * into deterministic expansion counts (deterministic → reproducible unit tests;
 * the search runs off-thread, so frame pacing is irrelevant):
 *
 * <ul>
 *   <li>past {@code primaryExpansions}: stop as soon as a committable partial
 *       candidate exists (a mid-journey segment returns early — lower segment-
 *       boundary latency);</li>
 *   <li>otherwise keep expanding to {@code failureExpansions} (a "failing"
 *       search — nothing committable yet — earns the full desperate budget);</li>
 *   <li>reaching the goal terminates at any point.</li>
 * </ul>
 */
public record SearchBudget(int primaryExpansions, int failureExpansions) {

    public SearchBudget {
        if (primaryExpansions <= 0 || failureExpansions < primaryExpansions) {
            throw new IllegalArgumentException(
                    "budget must satisfy 0 < primary <= failure: "
                            + primaryExpansions + "/" + failureExpansions);
        }
    }

    public static SearchBudget of(int primary, int failure) {
        return new SearchBudget(primary, failure);
    }

    /**
     * The caller policy PlayerNav uses: failure budget scales with the straight-
     * line distance to the goal (tunnelling through uniform rock branches
     * enormously — a 112-block climb starves at the near-goal default), primary
     * is a quarter of it (mirrors Baritone's 500ms:2000ms ratio).
     * Values carried over verbatim from the previously-verified policy.
     */
    public static SearchBudget scaled(double straightLineDist) {
        int failure = (int) Math.min(200_000, 10_000 + 1_700 * straightLineDist);
        int primary = Math.max(10_000, failure / 4);
        return new SearchBudget(Math.min(primary, failure), failure);
    }
}
