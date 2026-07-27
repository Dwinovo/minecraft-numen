package com.dwinovo.numen.core.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressedChatMatcherTest {

    @Test
    void onlyMessagesThatExplicitlyNameTheCompanionAreAddressed() {
        assertTrue(AddressedChatMatcher.isAddressed(
            "\u5c0f\u8bfa\uff0c\u4f60\u597d",
            "\u5c0f\u8bfa"
        ));
        assertTrue(AddressedChatMatcher.isAddressed("BOB, hello", "Bob"));
        assertFalse(AddressedChatMatcher.isAddressed(
            "\u5927\u5bb6\u597d",
            "\u5c0f\u8bfa"
        ));
        assertFalse(AddressedChatMatcher.isAddressed("Bobby, hello", "Bob"));
    }
}
