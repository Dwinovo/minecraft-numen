package com.dwinovo.numen.core.task.base;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * The "walk within reach, then act" shape shared by every task that navigates to a
 * target and then does one bounded thing there ({@code place_block},
 * {@code break_block}, {@code interact}, a single melee_attack engagement, …). It collapses
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

    /** Build the navigation toward this task's target. Assigned to {@link #nav} on start.
     *  方块目标的动作任务返回 null——它们不再自带任何到场导航,身体必须已在
     *  工作距离内({@link #reached()}),否则直接教学失败让调用方先 goto。 */
    protected abstract PlayerNav buildNav();

    /**
     * 教学失败要点名的目标格(算距离、给 goto 坐标用)。返回 null = 无固定
     * 格目标(实体目标、原地动作),失败话术退化为通用文案。默认 null。
     */
    protected net.minecraft.core.BlockPos gotoFirstTarget() {
        return null;
    }

    /** Are we within reach to {@link #act()} this tick? */
    protected abstract boolean reached();

    /** Do the bounded thing at the target; return {@link TaskState#RUNNING} or a terminal state. */
    protected abstract TaskState act();

    @Override
    protected void onStart() {
        nav = buildNav();
    }

    /** Consecutive nav-ARRIVED ticks with {@link #reached()} still false. */
    private int dudTicks = 0;
    /** Grace before arrived-but-not-reached is declared a stance dud — landing,
     *  settling and onGround can lag goal membership by a few ticks. */
    private static final int DUD_GRACE_TICKS = 10;

    @Override
    protected final TaskState onTick() {
        if (reached()) return act();
        if (nav == null) {
            // 无到场导航的动作任务:不在工作距离内 = 教学失败,旅行归 goto
            net.minecraft.core.BlockPos t = gotoFirstTarget();
            if (t != null) {
                double dist = Math.sqrt(player.distanceToSqr(
                        t.getX() + 0.5, t.getY() + 0.5, t.getZ() + 0.5));
                fail("target " + t.getX() + "," + t.getY() + "," + t.getZ() + " is "
                        + String.format("%.1f", dist) + " blocks away — out of working reach."
                        + " goto it first (goto stops right beside a solid block), then call"
                        + " this again.", FailureType.OUT_OF_REACH);
            } else {
                fail("out of working reach and this action does not travel — goto the spot"
                        + " first, then call this again.", FailureType.OUT_OF_REACH);
            }
            return TaskState.FAILED;
        }
        return switch (nav.tick()) {
            case RUNNING -> {
                dudTicks = 0;
                yield TaskState.RUNNING;
            }
            case ARRIVED -> {
                // reached() said no above, so the nav's arrival is a stance-dud
                // candidate: the search's membership is satisfied but the work
                // still can't start from here (out of reach, no sight line).
                // Route it through the SAME recovery ladder a failed path uses —
                // it is just one more way this bounded goal failed to yield a
                // working stance. The grace window absorbs settle transients.
                if (++dudTicks < DUD_GRACE_TICKS) {
                    yield TaskState.RUNNING;
                }
                dudTicks = 0;
                stopNav();
                com.dwinovo.numen.Constants.LOG.info(
                        "[numen-task] STANCE_DUD {} feet={} — nav arrived, reached() still"
                                + " false after {} ticks; routing the recovery ladder",
                        getClass().getSimpleName(), player.blockPosition().toShortString(),
                        DUD_GRACE_TICKS);
                yield handleNavFailure(FailureType.STANCE_DUD,
                        "arrived where the route ends, but the target is still out of"
                                + " reach from there");
            }
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
