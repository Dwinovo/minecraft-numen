package com.dwinovo.numen.core.chat;

import java.util.Locale;

/** Matches ordinary chat messages that explicitly address a named companion. */
public final class AddressedChatMatcher {
    private AddressedChatMatcher() {
    }

    public static boolean isAddressed(String message, String companionName) {
        if (message == null || companionName == null) {
            return false;
        }

        String name = companionName.trim();
        if (name.isEmpty()) {
            return false;
        }

        String foldedMessage = message.toLowerCase(Locale.ROOT);
        String foldedName = name.toLowerCase(Locale.ROOT);
        int fromIndex = 0;
        while (fromIndex <= foldedMessage.length() - foldedName.length()) {
            int start = foldedMessage.indexOf(foldedName, fromIndex);
            if (start < 0) {
                return false;
            }

            int end = start + foldedName.length();
            boolean leftBoundary = !isAsciiWord(foldedName.charAt(0))
                    || start == 0
                    || !isAsciiWord(foldedMessage.charAt(start - 1));
            boolean rightBoundary = !isAsciiWord(foldedName.charAt(foldedName.length() - 1))
                    || end == foldedMessage.length()
                    || !isAsciiWord(foldedMessage.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }

            fromIndex = start + 1;
        }
        return false;
    }

    private static boolean isAsciiWord(char value) {
        return value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '_';
    }
}
