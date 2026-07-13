package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.util.PathSettings;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * A computed route: an ordered list of {@link Movement} edges from
 * {@link #start} to {@link #end}. {@code partial} marks a best-effort path
 * that approached but did not reach the goal (A* exhausted its node budget
 * or the goal was unreachable) — the executor still walks it to make
 * progress, then a replan continues toward the goal.
 */
public final class Path {

    public final BlockPos start;
    public final BlockPos end;
    public final List<Movement> movements;
    public final boolean partial;

    public Path(BlockPos start, BlockPos end, List<Movement> movements, boolean partial) {
        this.start = start.immutable();
        this.end = end.immutable();
        this.movements = List.copyOf(movements);
        this.partial = partial;
    }

    public boolean isEmpty() {
        return movements.isEmpty();
    }

    /**
     * Baritone's {@code staticCutoff}: trim the last {@code pathCutoffFactor}
     * (10%) of a <em>partial</em> path beyond a {@code pathCutoffMinimumLength}
     * floor, so the next segment is re-planned with fresher chunk data and the
     * segments overlap cleanly. A full path that reaches the goal is never
     * trimmed; short paths are kept whole.
     */
    public Path staticCutoff() {
        if (!partial) return this;
        // Baritone counts path LENGTH in positions (= movements + 1); newLength is
        // the last position to include == the movement count to keep.
        int positions = movements.size() + 1;
        if (positions < PathSettings.PATH_CUTOFF_MINIMUM_LENGTH) return this;
        int newLength = (int) ((positions - PathSettings.PATH_CUTOFF_MINIMUM_LENGTH)
                * PathSettings.PATH_CUTOFF_FACTOR) + PathSettings.PATH_CUTOFF_MINIMUM_LENGTH - 1;
        if (newLength >= movements.size() || newLength <= 0) return this;
        List<Movement> trimmed = movements.subList(0, newLength);
        return new Path(start, trimmed.get(trimmed.size() - 1).dest, trimmed, partial);
    }
}
