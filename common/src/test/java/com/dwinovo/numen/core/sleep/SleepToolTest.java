package com.dwinovo.numen.core.sleep;

import org.junit.jupiter.api.Test;

public final class SleepToolTest {
    @Test
    void exposesVerifiedSelfContainedSleep() {
        SleepTool tool = new SleepTool();
        String description = tool.description();

        require(tool.name().equals("sleep"), "the public tool name must be sleep");
        require(description.contains("nearby bed"), "sleep must own nearby-bed discovery");
        require(
            description.contains("vanilla server") && description.contains("actually sleeping"),
            "sleep must promise a verified server-side postcondition"
        );
        require(
            description.contains("do not call") && description.contains("interact_at"),
            "raw bed interaction must not substitute for verified sleep"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
