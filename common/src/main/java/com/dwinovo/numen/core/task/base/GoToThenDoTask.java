package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * The "walk within reach, then act" shape shared by every task that navigates to a
 * target and then does one bounded thing there ({@code place_block},
 * {@code break_block}, {@code interact}, a single hunt engagement, …). It collapses
 * the identical nav-drive-then-act loop those tasks each hand-wrote onto three small
 * abstract hooks, leaving each concrete task to describe only its target, its
 * arrival test, and its action.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>{@link #onStart()} builds the nav from {@link #buildNav()}.</li>
 *   <li>each tick: if {@link #reached()} → {@link #act()}; otherwise drive the nav —
 *       {@code RUNNING}/{@code ARRIVED} keep going, {@code FAILED} routes through
 *       {@link #handleNavFailure(FailureType, String)}.</li>
 * </ul>
 *
 * <h2>Recovery hook</h2>
 * The default {@link #handleNavFailure} is today's behaviour: report the nav's
 * cause via {@link #fail} and terminate. This is the seam a later stage overrides
 * to attach a {@link RecoveryLadder} — swap "give up on a nav failure" for "try the
 * next rung" WITHOUT touching the loop or the concrete tasks.
 *
 * @param <R> the concrete {@link TaskRecord} subtype for this task.
 */
public abstract class GoToThenDoTask<R extends TaskRecord> extends AbstractCompanionTask<R> {

    protected GoToThenDoTask(NumenPlayer player, R record) {
        super(player, record);
    }

    /** Build the navigation toward this task's target. Assigned to {@link #nav} on start. */
    protected abstract PlayerNav buildNav();

    /** Are we within reach to {@link #act()} this tick? */
    protected abstract boolean reached();

    /** Do the bounded thing at the target; return {@link TaskState#RUNNING} or a terminal state. */
    protected abstract TaskState act();

    @Override
    protected void onStart() {
        nav = buildNav();
    }

    @Override
    protected final TaskState onTick() {
        if (reached()) return act();
        if (nav == null) {
            fail("navigation unavailable", FailureType.NO_PATH);   // defensive; unreachable today
            return TaskState.FAILED;
        }
        return switch (nav.tick()) {
            case RUNNING, ARRIVED -> TaskState.RUNNING;
            case FAILED -> handleNavFailure(nav.failType(), nav.failReason());
        };
    }

    /**
     * React to the nav giving up. Default: {@code fail(reason, type)} and
     * terminate FAILED. Override to interpose a {@link RecoveryLadder} that offers
     * an alternative approach to the same bounded goal before conceding.
     */
    protected TaskState handleNavFailure(FailureType type, String reason) {
        fail(reason, type);
        return TaskState.FAILED;
    }

    @Override
    protected abstract String successMessage();
}
