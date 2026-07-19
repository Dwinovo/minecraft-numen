package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.base.GoToThenDoTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code break_block} on the player body: walk within reach of one exact cell and
 * break it as a left-click ({@link Interaction#attackBlock}) — the same native
 * timed break the pathfinder/auto-mine use (creative insta, survival by real
 * hardness, best tool auto-selected). Player-body twin of BreakBlockTaskGoal
 * (construction surgery — clear a frame cell, undo a misplace).
 *
 * <p>A "navigate to a target then do one bounded thing" task, so it grows on
 * {@link GoToThenDoTask}: {@link #buildNav()} walks to the cell, {@link #reached()}
 * gates the break, {@link #act()} runs the timed attack.
 */
public final class BreakBlockCompanionTask extends GoToThenDoTask<BreakBlockTaskRecord> {

    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double WALK_SPEED = 1.0;
    /** Recovery rung: the loosened approach goal — stand ANYWHERE within this of the
     *  target (well inside swing range) instead of the resolver's tight default. */
    private static final double LOOSE_APPROACH_RADIUS = 4.0;

    /** The one reposition rung has been consumed (ladder state — survives suspend). */
    private boolean navRetried;

    private Interaction breaking;
    private String brokenBlock = "?";
    /** Success copy captured at the break — recorded here so the "already gone" and
     *  "broke X" branches keep their exact message through the base's templated result. */
    private String successMsg = "done";

    public BreakBlockCompanionTask(NumenPlayer player, BreakBlockTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        return List.of(
                // First gate also captures the block's name (as the old start() did at its top),
                // so the later gates and the result data can report it.
                () -> {
                    BlockState state = player.level().getBlockState(r.target);
                    brokenBlock = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    if (state.isAir()) {
                        return new Precondition.Failure(
                                "nothing to break at " + posLabel() + " — it's already air",
                                FailureType.TARGET_LOST);
                    }
                    return null;
                },
                () -> {
                    String hazard = BlockMiningProgress.fluidBreakHazard(player.level(), r.target);
                    return hazard != null ? new Precondition.Failure(hazard, FailureType.HAZARD) : null;
                },
                // Fail fast instead of grinding a block the current tools can't HARVEST: breaking a
                // requiresCorrectToolForDrops block (stone, ore, …) bare-handed destroys it for no drop
                // (or, for hard blocks, just times out). Same gate the pathfinder/auto-mine cost model
                // uses (COST_INF) — teach the model to equip a tool rather than waste the block.
                () -> {
                    BlockState state = player.level().getBlockState(r.target);
                    if (!BlockHelper.canHarvest(player.getInventory(), state)) {
                        return new Precondition.Failure("can't usefully break " + brokenBlock + " at " + posLabel()
                                + " — the hotbar has no tool that harvests it, so breaking it would destroy it"
                                + " without any drop. Equip the right tool (e.g. a pickaxe) to the hotbar first.",
                                FailureType.WRONG_TOOL);
                    }
                    return null;
                });
    }

    @Override
    protected PlayerNav buildNav() {
        return new PlayerNav(player, r.target, WALK_SPEED, this::withinReach);
    }

    @Override
    protected net.minecraft.core.BlockPos gotoFirstTarget() {
        return r.target;
    }

    @Override
    protected boolean reached() {
        return withinReach();
    }

    @Override
    protected TaskState act() {
        if (player.level().getBlockState(r.target).isAir()) {
            successMsg = "the block at " + posLabel() + " is already gone";
            return TaskState.SUCCESS;
        }
        if (breaking == null) breaking = Interaction.attackBlock(player, r.target);
        return switch (breaking.tick()) {
            case DONE -> {
                successMsg = "broke " + brokenBlock + " at " + posLabel();
                yield TaskState.SUCCESS;
            }
            case FAILED -> {
                fail("couldn't break " + posLabel() + ": " + breaking.failReason(), breaking.failType());
                yield TaskState.FAILED;
            }
            case RUNNING -> TaskState.RUNNING;   // mid-break (survival hardness timing)
        };
    }

    /**
     * Recovery ladder — ONE reposition rung. A {@code NO_PATH}/{@code BOXED_IN} to the
     * exact approach becomes one retry with a looser goal ({@code near(target, 4)}, still
     * within swing range — the SAME bounded break, a different stance), so "stand exactly
     * here" failures become "stand anywhere I can swing from"; the existing dig via
     * {@link Interaction#attackBlock} already handles occluders once in reach. A second
     * failure (or any other cause, e.g. target lost) gives up with the original wrapped
     * message plus what was tried.
     */
    @Override
    protected TaskState handleNavFailure(FailureType type, String reason) {
        if (!navRetried && (type == FailureType.NO_PATH || type == FailureType.BOXED_IN
                || type == FailureType.STANCE_DUD)) {
            navRetried = true;
            stopNav();
            NavGoal loose = NavGoal.near(r.target, LOOSE_APPROACH_RADIUS);
            nav = PlayerNav.toGoal(player, () -> loose, WALK_SPEED, this::withinReach);
            return TaskState.RUNNING;
        }
        String also = navRetried
                ? " (also tried approaching anywhere within " + (int) LOOSE_APPROACH_RADIUS
                        + " blocks of it — no path either)"
                : "";
        fail("can't reach " + posLabel() + ": " + reason + also, type);
        return TaskState.FAILED;
    }

    private boolean withinReach() {
        return player.onGround()
                && player.distanceToSqr(Vec3.atCenterOf(r.target)) <= REACH_SQR;
    }

    private String posLabel() {
        return r.target.getX() + "," + r.target.getY() + "," + r.target.getZ();
    }

    @Override
    protected void cleanup() {
        super.cleanup();   // stopNav + clear the path overlay
        if (breaking != null) breaking.stop();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("x", r.target.getX());
        data.put("y", r.target.getY());
        data.put("z", r.target.getZ());
        data.put("block", brokenBlock);
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out before breaking " + posLabel();
    }

    @Override
    protected String cancelledMessage() {
        return "break_block interrupted";
    }
}
