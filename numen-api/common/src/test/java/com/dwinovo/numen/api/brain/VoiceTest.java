package com.dwinovo.numen.api.brain;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoiceTest {

    @Test
    void aWhisperAnswersInTheShapeOfAWhisper() {
        Component line = Voice.line(Venue.WHISPER, "小柚", null, "好的");
        TranslatableContents contents = (TranslatableContents) line.getContents();
        assertEquals("commands.message.display.incoming", contents.getKey());
        assertEquals("小柚", contents.getArgs()[0]);
        assertEquals("好的", contents.getArgs()[1]);
    }

    @Test
    void aConversationAnswersUnderItsName() {
        assertEquals("[小柚、阿岚] <阿岚> 好的", Voice.line(Venue.CHAT, "阿岚", "小柚、阿岚", "好的").getString());
    }

    @Test
    void whenThereIsNoPanelTheAnswerIsAPlainLine() {
        assertEquals("<小柚> 好的", Voice.line(Venue.PANEL, "小柚", null, "好的").getString());
    }
}
