package com.dwinovo.numen.core.sleep;

import org.junit.jupiter.api.Test;

public final class SleepOutcomeTest {
    @Test
    void reportsOnlyServerVerifiedSleepAsSuccess() {
        SleepOutcome sleeping = SleepOutcome.verify(null, true);
        require(sleeping.success(), "server-confirmed sleeping must report success");
        require(sleeping.message().contains("sleeping"), "success must describe the verified state");

        SleepOutcome acceptedOnly = SleepOutcome.verify(null, false);
        require(!acceptedOnly.success(), "an accepted request without sleeping must fail");
        require(
            acceptedOnly.message().contains("did not enter sleeping state"),
            "an unverified sleep must explain that the server state did not change"
        );

        String daytime = "You can sleep only at night or during thunderstorms";
        SleepOutcome rejected = SleepOutcome.verify(daytime, false);
        require(!rejected.success(), "a vanilla rejection must fail the task");
        require(rejected.message().equals(daytime), "the vanilla rejection reason must be preserved");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
