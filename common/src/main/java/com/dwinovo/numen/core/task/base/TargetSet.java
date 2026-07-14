package com.dwinovo.numen.core.task.base;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A reusable "candidate targets, minus the ones we've given up on" holder — the
 * shared shape behind {@code MineCompanionTask.blacklist} (ore cells A* couldn't
 * reach) and {@code HuntCompanionTask.skipped} (mobs it failed to close on). Both
 * were the same idea: keep an exclusion set keyed by an identity, and pick the best
 * candidate that isn't excluded.
 *
 * <p>Excluded identities are stored by a caller-supplied {@code key} rather than the
 * candidate itself, so the two callers keep their existing identity model — a
 * {@code BlockPos} for ores, an entity id for mobs — without this class knowing
 * anything about Minecraft. Pure and unit-testable.
 *
 * <p>{@link #blacklist} and {@link #skip} are the same operation under two names, so
 * each call site migrates to the verb it already uses (mine "blacklists", hunt
 * "skips").
 *
 * @param <T> the candidate type (an ore position, a mob, …).
 */
public final class TargetSet<T> {

    private final Function<T, Object> key;
    private final Set<Object> excluded = new HashSet<>();

    public TargetSet(Function<T, Object> key) {
        this.key = key;
    }

    /** Permanently exclude {@code t} (mine's "unreachable ore" sense). */
    public void blacklist(T t) {
        excluded.add(key.apply(t));
    }

    /** Permanently exclude {@code t} (hunt's "couldn't engage this mob" sense). */
    public void skip(T t) {
        excluded.add(key.apply(t));
    }

    /** Is {@code t} currently excluded? */
    public boolean isExcluded(T t) {
        return excluded.contains(key.apply(t));
    }

    /**
     * The best non-excluded candidate from {@code candidates} by {@code preference}
     * (the smallest under the comparator — e.g. nearest first), or empty if all are
     * excluded / the list is empty.
     */
    public Optional<T> pick(List<T> candidates, Comparator<T> preference) {
        return candidates.stream()
                .filter(c -> !isExcluded(c))
                .min(preference);
    }

    /** Forget every exclusion. */
    public void reset() {
        excluded.clear();
    }
}
