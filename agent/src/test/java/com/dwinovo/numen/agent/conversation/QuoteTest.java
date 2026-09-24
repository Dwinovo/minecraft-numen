package com.dwinovo.numen.agent.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteTest {

    @Test
    void composedTextParsesBackIntoItsParts() {
        Quote q = Quote.parse(Quote.compose("TEST", "我在这儿,随时听你差遣。", "那你去砍树"));
        assertTrue(q.quoted());
        assertEquals("TEST", q.who());
        assertEquals("我在这儿,随时听你差遣。", q.snippet());
        assertEquals("那你去砍树", q.body());
    }

    @Test
    void plainTextIsAllBody() {
        Quote q = Quote.parse("@TEST 去砍树");
        assertFalse(q.quoted());
        assertNull(q.who());
        assertEquals("@TEST 去砍树", q.body());
    }

    @Test
    void aQuoteLineWithNothingUnderItIsNotAQuote() {
        Quote q = Quote.parse("> TEST: 你好");
        assertFalse(q.quoted());
        assertEquals("> TEST: 你好", q.body());
    }

    @Test
    void aQuoteLineWithoutASpeakerIsNotAQuote() {
        Quote q = Quote.parse("> 随便写的一行\n下面");
        assertFalse(q.quoted());
    }

    @Test
    void theQuotedLineIsFlattenedAndShortened() {
        String longLine = "第一行\n第二行" + "字".repeat(100);
        Quote q = Quote.parse(Quote.compose("TEST", longLine, "好"));
        assertFalse(q.snippet().contains("\n"), "引的那句压成一行");
        assertEquals(Quote.SNIPPET_MAX + 1, q.snippet().length(), "截到上限再补一个省略号");
        assertTrue(q.snippet().endsWith("…"));
        assertEquals("好", q.body());
    }

    @Test
    void theBodyKeepsItsOwnLineBreaks() {
        Quote q = Quote.parse(Quote.compose("TEST", "那句", "第一行\n第二行"));
        assertEquals("第一行\n第二行", q.body());
    }
}
