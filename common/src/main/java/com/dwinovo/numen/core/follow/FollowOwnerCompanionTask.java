package com.dwinovo.numen.core.follow;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;

public final class FollowOwnerCompanionTask extends AbstractCompanionTask<FollowOwnerTaskRecord> {
    private static final double PATH_GOAL_RADIUS = 1.0D;
    private static final double WALK_SPEED = 1.0D;

    private ServerPlayer owner;

    public FollowOwnerCompanionTask(NumenPlayer player, FollowOwnerTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        owner = player.resolveOwnerPlayer();
        if (!ownerAvailable()) {
            fail("owner is offline", FailureType.TARGET_LOST);
            return;
        }
        if (!sameDimension()) {
            fail("owner is in another dimension", FailureType.TARGET_LOST);
            return;
        }
        if (hasArrived()) {
            succeed();
            return;
        }
        nav = createNav();
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }

        owner = player.resolveOwnerPlayer();
        if (!ownerAvailable()) {
            fail("owner went offline while travelling", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (!sameDimension()) {
            fail("owner moved to another dimension", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (hasArrived()) {
            stopNav();
            return TaskState.SUCCESS;
        }

        if (nav == null) {
            nav = createNav();
        }
        PlayerNav.Status status = nav.tick();
        if (status == PlayerNav.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        if (status == PlayerNav.Status.ARRIVED && hasArrived()) {
            stopNav();
            return TaskState.SUCCESS;
        }
        if (status == PlayerNav.Status.ARRIVED) {
            stopNav();
            nav = createNav();
            return TaskState.RUNNING;
        }

        String reason = nav.failReason();
        FailureType type = nav.failType();
        stopNav();
        fail(reason == null || reason.isBlank() ? "could not reach owner" : reason, type);
        return TaskState.FAILED;
    }

    private PlayerNav createNav() {
        return PlayerNav.followEntity(
            player,
            this::validOwner,
            PATH_GOAL_RADIUS,
            WALK_SPEED,
            this::hasArrived
        );
    }

    private ServerPlayer validOwner() {
        ServerPlayer current = player.resolveOwnerPlayer();
        if (current == null || current.isRemoved() || !current.isAlive() || current.level() != player.level()) {
            return null;
        }
        owner = current;
        return current;
    }

    private boolean ownerAvailable() {
        return owner != null && !owner.isRemoved() && owner.isAlive();
    }

    private boolean sameDimension() {
        return owner != null && owner.level() == player.level();
    }

    private boolean hasArrived() {
        ServerPlayer current = validOwner();
        if (current == null) {
            return false;
        }
        return FollowOwnerArrival.hasArrived(
            player.getX(),
            player.blockPosition().getY(),
            player.getZ(),
            current.getX(),
            current.blockPosition().getY(),
            current.getZ()
        );
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("radius", FollowOwnerArrival.HORIZONTAL_RADIUS);
        data.put("same_floor_max_y_difference", FollowOwnerArrival.MAX_FEET_Y_DIFFERENCE);
        data.put("position", position(player));
        ServerPlayer current = player.resolveOwnerPlayer();
        if (current != null) {
            data.put("owner", current.getScoreboardName());
            data.put("owner_position", position(current));
        }
        return data;
    }

    private static Map<String, Object> position(ServerPlayer entity) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", entity.getX());
        position.put("y", entity.getY());
        position.put("z", entity.getZ());
        return position;
    }

    @Override
    protected String successMessage() {
        return "arrived near owner within horizontal radius 4 on the same floor";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out while following owner's live position";
    }

    @Override
    protected String cancelledMessage() {
        return "stopped following owner";
    }
}
