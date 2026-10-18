package com.dwinovo.numen.api.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@code /numen key} 回执里露出来的:前三位、末两位;短的一位也不露。 */
class KeyCommandTest {

    @Test
    void onlyTheEndsOfALongKeyAreShown() {
        assertEquals("sk-…op", KeyCommand.preview("sk-abcdefghijklmnop"));
    }

    @Test
    void aShortKeyIsHiddenWhole() {
        assertEquals("…", KeyCommand.preview("sk-ab"));
        assertEquals("…", KeyCommand.preview("123456"));
    }
}
