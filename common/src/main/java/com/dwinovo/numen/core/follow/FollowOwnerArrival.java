package com.dwinovo.numen.core.follow;

public final class FollowOwnerArrival {
    public static final double HORIZONTAL_RADIUS = 4.0D;
    public static final int MAX_FEET_Y_DIFFERENCE = 1;

    private FollowOwnerArrival() {
    }

    public static boolean hasArrived(
        double playerX,
        int playerFeetY,
        double playerZ,
        double ownerX,
        int ownerFeetY,
        double ownerZ
    ) {
        double dx = playerX - ownerX;
        double dz = playerZ - ownerZ;
        int feetYDifference = Math.abs(playerFeetY - ownerFeetY);
        return dx * dx + dz * dz <= HORIZONTAL_RADIUS * HORIZONTAL_RADIUS
            && feetYDifference <= MAX_FEET_Y_DIFFERENCE;
    }
}
