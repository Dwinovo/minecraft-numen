package com.dwinovo.numen.core.task.base;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A reusable "candidate targets, minus the ones we've given up on" holder.
 * Callers provide the identity key, so exclusions can be stored as block
 * positions, entity ids, or any other stable handle without this class knowing
 * about the underlying world object.
 *
 * <p>{@link #blacklist} and {@link #skip} are the same operation under two names,
 * letting each caller use the verb that fits its domain.
 *
 * @param <T> the candidate type.
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

    /** Permanently exclude {@code t}. */
    public void skip(T t) {
        excluded.add(key.apply(t));
    }

    /** Is {@code t} currently excluded? */
    public boolean isExcluded(T t) {
        return excluded.contains(key.apply(t));
    }

    /**
     * The best non-excluded candidate from {@code candidates} by {@code preference}
     * (the smallest under the comparator), or empty if all are excluded or the
     * list is empty.
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