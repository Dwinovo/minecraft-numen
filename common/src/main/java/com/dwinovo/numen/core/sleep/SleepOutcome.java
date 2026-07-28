package com.dwinovo.numen.core.sleep;

/** Verified result of a vanilla sleep request. */
public record SleepOutcome(boolean success, String message) {
    public static SleepOutcome verify(String rejectionReason, boolean sleeping) {
        if (rejectionReason != null && !rejectionReason.isBlank()) {
            return new SleepOutcome(false, rejectionReason);
        }
        if (!sleeping) {
            return new SleepOutcome(false, "sleep request was accepted but the player did not enter sleeping state");
        }
        return new SleepOutcome(true, "sleeping in bed (server verified)");
    }
}
