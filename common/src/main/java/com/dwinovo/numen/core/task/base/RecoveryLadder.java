package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.task.CompanionTask;
import com.dwinovo.numen.core.task.FailureType;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * An ordered set of fallback EXECUTIONS for one bounded goal, driven by a parent
 * {@link AbstractCompanionTask}. Each {@link Rung} offers a different way to reach
 * the SAME goal (e.g. "walk to the exact face" → "approach an adjacent cell" →
 * "dig the occluder, then place"), declares the {@link FailureType}s it is willing
 * to catch, and caps how many times it may be retried.
 *
 * <h2>Recovery boundary</h2>
 * A ladder only ever offers alternative executions of the goal the parent already
 * owns; it NEVER acquires a prerequisite or widens the goal. That rule is encoded
 * in the failure routing: a rung catches only the {@link FailureType}s it lists
 * (in practice the "in-ladder" categories — {@code OCCLUDED}, {@code NO_PATH},
 * {@code OUT_OF_REACH}, {@code HAZARD}, …). A failure no rung handles — a
 * prerequisite gap like {@code NO_MATERIAL} / {@code WRONG_TOOL} — falls straight
 * through {@link #advance(FailureType)} (returns {@code false}), and the parent
 * gives up carrying that cause back to the LLM.
 *
 * <h2>Driving it</h2>
 * A parent's {@code onTick} runs the current rung's task and, on a terminal
 * failure, asks the ladder whether to continue:
 * {@snippet :
 * TaskState st = runChild(ladder.current());
 * if (st == null) return TaskState.RUNNING;          // rung still working
 * if (st != TaskState.FAILED) return st;             // rung succeeded / non-fail terminal
 * if (ladder.advance(lastFailure())) return TaskState.RUNNING;   // retry / next rung
 * fail(doneReason(), lastFailure());                 // ladder exhausted
 * return TaskState.FAILED;
 * }
 *
 * <p>Deterministic and Minecraft-free: strategies are {@link Supplier}s of
 * {@link CompanionTask}, so the advancement logic is unit-testable with fakes.
 */
public final class RecoveryLadder {

    /**
     * One fallback approach.
     *
     * @param strategy    builds a fresh task for this rung each time it is
     *                    (re)entered — a {@link Supplier} so a retry gets a clean
     *                    instance and the ladder never touches Minecraft itself.
     * @param handles     the {@link FailureType}s this rung is willing to catch;
     *                    a cause outside this set is not handled by this rung.
     * @param maxAttempts total number of executions allowed on this rung
     *                    (≥ 1); reaching it advances to the next matching rung.
     */
    public record Rung(Supplier<CompanionTask> strategy, Set<FailureType> handles, int maxAttempts) {}

    private final List<Rung> rungs;

    /** Index of the current rung; {@code == rungs.size()} once exhausted. */
    private int index;
    /** Executions committed on the current rung so far (starts at 1 when a rung is entered). */
    private int attempts = 1;
    /** The lazily-built task for the current rung; nulled on retry / advance so it is rebuilt. */
    private CompanionTask cached;

    public RecoveryLadder(List<Rung> rungs) {
        this.rungs = List.copyOf(rungs);
    }

    /** Convenience varargs factory. */
    public static RecoveryLadder of(Rung... rungs) {
        return new RecoveryLadder(List.of(rungs));
    }

    /**
     * The task for the current rung, lazily built via its {@link Rung#strategy()}
     * and cached until the ladder retries or advances (so per-tick calls reuse the
     * same instance). {@code null} once the ladder is {@link #exhausted()}.
     */
    public CompanionTask current() {
        if (index >= rungs.size()) return null;
        if (cached == null) cached = rungs.get(index).strategy().get();
        return cached;
    }

    /**
     * Decide what to do after the current rung failed with {@code lastFail}.
     * <ul>
     *   <li>If the current rung {@link Rung#handles() handles} {@code lastFail}
     *       and it has attempts left ({@code attempts < maxAttempts}): retry the
     *       SAME rung (rebuild its strategy) and return {@code true}.</li>
     *   <li>Otherwise advance to the next LATER rung whose {@code handles}
     *       contains {@code lastFail}, reset its attempt counter, and return
     *       {@code true}.</li>
     *   <li>If neither applies — no remaining rung handles the cause — the ladder
     *       is exhausted: return {@code false} (the parent gives up carrying
     *       {@code lastFail}).</li>
     * </ul>
     */
    public boolean advance(FailureType lastFail) {
        if (index < rungs.size()) {
            Rung r = rungs.get(index);
            if (r.handles().contains(lastFail) && attempts < r.maxAttempts()) {
                attempts++;
                cached = null;         // rebuild the strategy for the retry
                return true;
            }
        }
        for (int i = index + 1; i < rungs.size(); i++) {
            if (rungs.get(i).handles().contains(lastFail)) {
                index = i;
                attempts = 1;
                cached = null;
                return true;
            }
        }
        index = rungs.size();          // exhausted
        cached = null;
        return false;
    }

    /** Index of the current rung (or the rung count once exhausted). */
    public int currentRung() {
        return index;
    }

    /** Executions committed on the current rung so far (1-based). */
    public int currentAttempt() {
        return attempts;
    }

    /** True once no rung remains to run. */
    public boolean exhausted() {
        return index >= rungs.size();
    }
}
