package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DoingTextTest {

    @Test
    void aKeyWithATranslationIsTranslatedWithItsWords() {
        Component text = DoingText.of(new LoopStatus.Doing("numen.activity.dig", List.of("oak_log", "3", "10")),
                Set.of("numen.activity.dig")::contains);
        TranslatableContents contents = (TranslatableContents) text.getContents();
        assertEquals("numen.activity.dig", contents.getKey());
        assertEquals(List.of("oak_log", "3", "10"), List.of(contents.getArgs()));
    }

    @Test
    void aToolWithNoTranslationIsSaidByItsOwnName() {
        Component text = DoingText.of(new LoopStatus.Doing("numen.tool.mcp_fetch", List.of("mcp_fetch")), key -> false);
        assertEquals("mcp_fetch", text.getString());
    }
}
