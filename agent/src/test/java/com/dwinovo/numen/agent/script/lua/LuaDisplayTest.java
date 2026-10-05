package com.dwinovo.numen.agent.script.lua;

import com.dwinovo.numen.agent.script.ScriptLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 给模型读的值:小的原样,大的缩略,缩略处写明总数与怎么看更多,从不静默。 */
class LuaDisplayTest {

    private static List<Object> numbers(int n) {
        return IntStream.rangeClosed(1, n).<Object>mapToObj(Long::valueOf).toList();
    }

    @Test
    void smallValuesAreWrittenAsTheyAre() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("block", "minecraft:iron_ore");
        Map<String, Object> pos = new LinkedHashMap<>();
        pos.put("x", 1L);
        pos.put("y", 2L);
        pos.put("z", 3L);
        block.put("pos", pos);
        block.put("lit", false);
        block.put("hardness", 3.5);
        block.put("owner", null);
        String shown = LuaDisplay.of(List.of(block, 7L));
        assertEquals("{{block = \"minecraft:iron_ore\", pos = {x = 1, y = 2, z = 3}, lit = false, hardness = 3.5, "
                + "owner = nil}, 7}", shown);
        assertEquals(LuaDisplay.of(numbers(ScriptLimits.DISPLAY_THRESHOLD)),
                "{" + String.join(", ", numbers(ScriptLimits.DISPLAY_THRESHOLD).stream().map(String::valueOf).toList())
                        + "}", "a list at the threshold is shown whole");
    }

    @Test
    void aLongListShowsItsEdgesAndSaysHowManyThereAre() {
        String shown = LuaDisplay.of(numbers(950));
        assertEquals("{1, 2, 3, …(950 items in all; index one with t[i], or filter in the program before you print), "
                + "948, 949, 950}", shown);
    }

    @Test
    void theEdgesOfALongListOfTablesAreWrittenInFull() {
        List<Object> blocks = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            blocks.add(Map.of("x", (long) i));
        }
        String shown = LuaDisplay.of(blocks);
        assertTrue(shown.startsWith("{{x = 1}, {x = 2}, {x = 3}, …(40 items in all;"), shown);
        assertTrue(shown.endsWith("{x = 38}, {x = 39}, {x = 40}}"), shown);
        assertFalse(shown.contains("{x = 4}"), shown);
    }

    @Test
    void aTableWithManyFieldsShowsItsEdgesToo() {
        Map<String, Object> inventory = new LinkedHashMap<>();
        for (int i = 1; i <= 30; i++) {
            inventory.put("item" + i, (long) i);
        }
        String shown = LuaDisplay.of(inventory);
        assertEquals("{item1 = 1, item2 = 2, item3 = 3, …(30 fields in all; read one by its name, or loop over the "
                + "table and filter before you print), item28 = 28, item29 = 29, item30 = 30}", shown);
    }

    @Test
    void tablesNestedTooDeepShowAsBracesAndTheTextSaysSo() {
        Object value = 1L;
        for (int i = 0; i < ScriptLimits.DISPLAY_DEPTH + 2; i++) {
            value = Map.of("t", value);
        }
        String shown = LuaDisplay.of(value);
        assertTrue(shown.startsWith("{t = {t = {t = {t = {t = {...}}}}}}"), shown);
        assertTrue(shown.contains("[tables nested deeper than " + ScriptLimits.DISPLAY_DEPTH + " levels show as {...}"),
                shown);
        assertFalse(LuaDisplay.of(Map.of("t", Map.of("t", 1L))).contains("{...}"));
    }

    @Test
    void aLongStringInsideATableKeepsItsStartAndSaysHowLongItWas() {
        String long_ = "a".repeat(ScriptLimits.DISPLAY_STRING_CHARS) + "TAIL";
        String shown = LuaDisplay.of(Map.of("help", long_));
        assertEquals("{help = \"" + "a".repeat(ScriptLimits.DISPLAY_STRING_CHARS) + "\"…("
                + (ScriptLimits.DISPLAY_STRING_CHARS + 4) + " characters in all)}", shown);
        assertEquals("{help = \"" + "b".repeat(ScriptLimits.DISPLAY_STRING_CHARS) + "\"}",
                LuaDisplay.of(Map.of("help", "b".repeat(ScriptLimits.DISPLAY_STRING_CHARS))), "at the limit it is whole");
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        String s = "x".repeat(ScriptLimits.DISPLAY_STRING_CHARS - 1) + "😀" + "tail";
        String shown = LuaDisplay.of(List.of(s));
        assertFalse(shown.contains("\uD83D\""), "no lone high surrogate before the closing quote: " + shown);
    }
}
