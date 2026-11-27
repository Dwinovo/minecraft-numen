package com.dwinovo.numen.api.agent.request;

import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import com.dwinovo.numen.api.seat.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系统提示的拼法:固定的在前,按情况拼的按顺序在后,没有的整段不出现;只用 Markdown;引擎不点名任何插件的函数。
 */
class SystemPromptComposerTest {

    private static final String BASE = NumenPrompts.FIXED + "\n\n" + NumenPrompts.MEMORY + "\n\n" + NumenPrompts.CONVERSATION;

    private static Catalog catalog(List<String> namespaces, List<String> instincts) {
        return new Catalog("v", namespaces, List.of(), instincts);
    }

    @Test
    void withNothingBoundThePromptIsOnlyTheFixedAndTheAlwaysOnParts() {
        assertEquals(BASE, SystemPromptComposer.compose(null, catalog(List.of(), List.of()), ""));
        assertEquals(BASE, SystemPromptComposer.compose("  \n", catalog(List.of(), List.of()), ""),
                "a blank persona is no persona: the section does not appear");
    }

    @Test
    void thePartsComeInTheDocumentedOrder() {
        String skills = NumenPrompts.SKILLS_INTRO + "\n\n- lumber: How trees grow.";
        String prompt = SystemPromptComposer.compose("You are Aria.\n", catalog(List.of("minecraft", "ysm"),
                List.of("you swim up for air")), skills);

        int fixed = prompt.indexOf("# Harness");
        int memory = prompt.indexOf("# Memory");
        int others = prompt.indexOf("# Others in the conversation");
        int persona = prompt.indexOf("# Persona\nYou are Aria.");
        int skillList = prompt.indexOf(NumenPrompts.SKILLS_INTRO);
        int api = prompt.indexOf("Lua API: minecraft, ysm. Read one with ApiSearch before you use it.");
        int instincts = prompt.indexOf("# Instincts\nYour body does these on its own and tells you through an event when it does:\n"
                + "- you swim up for air");
        assertTrue(0 <= fixed && fixed < memory && memory < others && others < persona && persona < skillList
                && skillList < api && api < instincts, prompt);
        assertTrue(prompt.endsWith("- you swim up for air"), "nothing follows the last part: " + prompt);
    }

    @Test
    void theTextIsMarkdownWithNoXmlSectionsAndNamesNoPluginFunction() {
        String prompt = SystemPromptComposer.compose("Persona text.", catalog(List.of("ysm"), List.of("an instinct")),
                NumenPrompts.SKILLS_INTRO + "\n\n- a: b");
        for (String tag : List.of("<persona>", "<api>", "<speaking>", "<operating_principles>", "<instincts>",
                "<memory_rules>", "<conversation_rules>", "<available_skills>")) {
            assertFalse(prompt.contains(tag), tag);
        }
        assertFalse(prompt.contains("numen"), "the engine names no plugin: " + prompt);
    }

    @Test
    void aLuaApiLineAppearsOnlyWhenThereAreNamespaces() {
        assertFalse(SystemPromptComposer.compose(null, catalog(List.of(), List.of()), "").contains("Lua API:"));
        assertTrue(SystemPromptComposer.compose(null, catalog(List.of("numen"), List.of()), "")
                .endsWith("\n\nLua API: numen. Read one with ApiSearch before you use it."));
    }
}
