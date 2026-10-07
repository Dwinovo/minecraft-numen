package com.dwinovo.numen.client.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConversationFacesTest {

    @Test
    void anUnnamedGroupTakesTheFirstLetterOfTheFirstTwoMembers() {
        assertEquals("TA", ConversationFaces.initials("test、alex、bob"));
    }

    @Test
    void aNamedGroupTakesTheFirstLetterOfItsFirstTwoWords() {
        assertEquals("采矿", ConversationFaces.initials("采 矿小队"));
        assertEquals("M", ConversationFaces.initials("mining"));
    }

    @Test
    void aBlankNameStillDrawsSomething() {
        assertEquals("?", ConversationFaces.initials(""));
    }
}
