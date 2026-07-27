package com.dwinovo.numen.core.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowOwnerToolTest {

    @Test
    void descriptionRecognizesChineseComeHereRequests() {
        String description = new FollowOwnerTool().description();

        assertTrue(description.contains("\u8fc7\u6765"));
        assertTrue(description.contains("\u56de\u5230\u6211\u8eab\u8fb9"));
        assertTrue(description.contains("\u5230\u6211\u8fd9\u91cc"));
    }
}
