package com.dwinovo.numen.api.describe;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回答写成一行短话:id 后面的方括号里每个回答者一段,按给的先后;字符串带双引号,数与布尔原样,列表与表在花括号里;超过三项的列表或表
 * 只写前三项、后面记还有几项,套在里面的每一层都这样;没人说话就只有 id。
 */
class AnswerTextTest {

    /** 按写的先后的一张表。 */
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    private static Map<String, Map<String, Object>> answers(String name, Map<String, Object> said) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put(name, said);
        return out;
    }

    @Test
    void noAnswersIsJustTheId() {
        assertEquals("minecraft:dirt", AnswerText.of("minecraft:dirt", Map.of()));
    }

    @Test
    void stringsAreQuotedAndNumbersAndBooleansAreBare() {
        assertEquals("minecraft:iron_sword[gt={name=\"Excalibur\", level=5, lit=true}]",
                AnswerText.of("minecraft:iron_sword",
                        answers("gt", fields("name", "Excalibur", "level", 5L, "lit", true))));
        assertEquals("minecraft:iron_sword[gt={ratio=0.5}]",
                AnswerText.of("minecraft:iron_sword", answers("gt", fields("ratio", 0.5))));
    }

    @Test
    void aQuoteOrALineBreakInAStringStaysInsideItsQuotes() {
        assertEquals("stick[gt={name=\"say \\\"hi\\\"\\nbye\"}]",
                AnswerText.of("stick", answers("gt", fields("name", "say \"hi\"\nbye"))));
    }

    @Test
    void keysAreBareEvenWhenTheyAreIds() {
        assertEquals("minecraft:diamond_sword[enchantments={minecraft:sharpness=5, minecraft:unbreaking=3}]",
                AnswerText.of("minecraft:diamond_sword", answers("enchantments",
                        fields("minecraft:sharpness", 5L, "minecraft:unbreaking", 3L))));
    }

    @Test
    void everyAnswerIsWrittenInTheOrderGiven() {
        Map<String, Map<String, Object>> said = new LinkedHashMap<>();
        said.put("d", fields("v", 1L));
        said.put("c", fields("v", 2L));
        said.put("b", fields("v", 3L));
        said.put("a", fields("v", 4L));
        assertEquals("x[d={v=1}, c={v=2}, b={v=3}, a={v=4}]", AnswerText.of("x", said));
    }

    @Test
    void listsAndTablesNestInBraces() {
        assertEquals("x[gt={layers={{pattern=\"minecraft:creeper\", color=\"red\"}}, lines={\"a\", \"b\"}}]",
                AnswerText.of("x", answers("gt", fields(
                        "layers", List.of(fields("pattern", "minecraft:creeper", "color", "red")),
                        "lines", List.of("a", "b")))));
    }

    @Test
    void threeEntriesAreAllWrittenAndAFourthIsCounted() {
        assertEquals("x[gt={a=1, b=2, c=3}]", AnswerText.of("x", answers("gt", fields("a", 1L, "b", 2L, "c", 3L))));
        assertEquals("x[gt={a=1, b=2, c=3, …+2}]",
                AnswerText.of("x", answers("gt", fields("a", 1L, "b", 2L, "c", 3L, "d", 4L, "e", 5L))));
    }

    @Test
    void theRuleHoldsAtEveryDepth() {
        assertEquals("x[gt={a=1, b={1, 2, 3, …+1}, c={p=1, q=2, r=3, …+3}, …+1}]",
                AnswerText.of("x", answers("gt", fields(
                        "a", 1L,
                        "b", List.of(1L, 2L, 3L, 4L),
                        "c", fields("p", 1L, "q", 2L, "r", 3L, "s", 4L, "t", 5L, "u", 6L),
                        "d", 4L))));
    }
}
