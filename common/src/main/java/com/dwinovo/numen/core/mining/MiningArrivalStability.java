package com.dwinovo.numen.core.mining;

/** Gives a just-arrived mining stance a bounded window to settle on the ground. */
public final class MiningArrivalStability {
    private final int maxWaitTicks;
    private Long targetKey;
    private int waitTicks;

    public MiningArrivalStability(int maxWaitTicks) {
        if (maxWaitTicks < 0) {
            throw new IllegalArgumentException("maxWaitTicks must not be negative");
        }
        this.maxWaitTicks = maxWaitTicks;
    }

    public boolean shouldWait(long targetKey, boolean onGround) {
        if (onGround) {
            reset();
            return false;
        }
        if (this.targetKey == null || this.targetKey.longValue() != targetKey) {
            this.targetKey = targetKey;
            this.waitTicks = 0;
        }
        if (this.waitTicks < this.maxWaitTicks) {
            this.waitTicks++;
            return true;
        }
        reset();
        return false;
    }

    public void reset() {
        this.targetKey = null;
        this.waitTicks = 0;
    }
}
