package com.dwinovo.numen.core.pathing.engine;

/**
 * Two-phase search budget — expansion counts, optionally paired with wall-clock
 * deadlines:
 *
 * <ul>
 *   <li>past the PRIMARY threshold (expansions or elapsed time): stop as soon
 *       as a committable partial candidate exists (a mid-journey segment
 *       returns early — lower segment-boundary latency);</li>
 *   <li>otherwise keep expanding to the FAILURE threshold (a "failing" search —
 *       nothing committable yet — earns the full desperate budget);</li>
 *   <li>reaching the goal terminates at any point.</li>
 * </ul>
 *
 * <p>Two flavours:
 * <ul>
 *   <li>{@link #of} — expansion counts only ({@code *Nanos == 0}). Fully
 *       deterministic; what unit tests use.</li>
 *   <li>{@link #timed} — wall-clock deadlines with an expansion FUSE. This is
 *       the live policy: the same time budget buys whatever a given machine can
 *       explore (no per-machine throughput tuning), at the price that a
 *       budget-cut partial may differ across machines. Completed searches are
 *       machine-independent either way — the cut only moves where the search
 *       STOPS, never what a finished search found. The fuse bounds memory and
 *       runaway loops, orders of magnitude above depression-class terrain.</li>
 * </ul>
 */
public record SearchBudget(int primaryExpansions, int failureExpansions,
                           long primaryNanos, long failureNanos) {

    public SearchBudget {
        if (primaryExpansions <= 0 || failureExpansions < primaryExpansions) {
            throw new IllegalArgumentException(
                    "budget must satisfy 0 < primary <= failure: "
                            + primaryExpansions + "/" + failureExpansions);
        }
        if (primaryNanos < 0 || failureNanos < primaryNanos) {
            throw new IllegalArgumentException(
                    "deadlines must satisfy 0 <= primary <= failure: "
                            + primaryNanos + "/" + failureNanos);
        }
    }

    /** Deterministic expansion-count budget (no deadlines) — the test flavour. */
    public static SearchBudget of(int primary, int failure) {
        return new SearchBudget(primary, failure, 0L, 0L);
    }

    /**
     * Wall-clock budget with an expansion fuse — the live flavour. Both phases
     * share the fuse as their expansion cap; the primary/failure split is
     * carried by the deadlines alone.
     */
    public static SearchBudget timed(long primaryMs, long failureMs, int expansionFuse) {
        return new SearchBudget(expansionFuse, expansionFuse,
                primaryMs * 1_000_000L, failureMs * 1_000_000L);
    }

    /** Whether this budget carries wall-clock deadlines at all. */
    public boolean timedFlavour() {
        return failureNanos > 0;
    }
}
