package com.dwinovo.numen.core.task;

import net.minecraft.world.phys.Vec3;

/** Tracks coarse task progress so the dispatcher can detect no-progress stalls. */
final class TaskProgressWatchdog {

    private static final double MIN_MOVE_SQR = 0.20 * 0.20;
    private static final int DEFAULT_STUCK_TICKS = 20 * 20;
    private static final int DEFAULT_MAX_RECOVERIES = 2;

    private Vec3 lastPosition;
    private long lastProgressGameTime;
    private int recoveryAttempts;

    void start(Vec3 position, long gameTime) {
        lastPosition = position;
        lastProgressGameTime = gameTime;
        recoveryAttempts = 0;
    }

    boolean recordPosition(Vec3 position, long gameTime) {
        if (lastPosition == null) {
            start(position, gameTime);
            return true;
        }
        if (position.distanceToSqr(lastPosition) >= MIN_MOVE_SQR) {
            lastPosition = position;
            lastProgressGameTime = gameTime;
            return true;
        }
        return false;
    }

    boolean isStuck(long gameTime) {
        return lastPosition != null && gameTime - lastProgressGameTime >= DEFAULT_STUCK_TICKS;
    }

    boolean canRecover() {
        return recoveryAttempts < DEFAULT_MAX_RECOVERIES;
    }

    int markRecovery(Vec3 position, long gameTime) {
        recoveryAttempts++;
        lastPosition = position;
        lastProgressGameTime = gameTime;
        return recoveryAttempts;
    }

    int recoveryAttempts() {
        return recoveryAttempts;
    }

    long idleTicks(long gameTime) {
        return Math.max(0L, gameTime - lastProgressGameTime);
    }
}
