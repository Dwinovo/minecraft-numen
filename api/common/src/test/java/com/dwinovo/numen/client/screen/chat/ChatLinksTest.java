package com.dwinovo.numen.client.screen.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLinksTest {

    private static List<String> urls(String text) {
        return ChatLinks.find(text).stream().map(ChatLinks.Span::url).toList();
    }

    @Test
    void findsHttpAndHttpsWithTheirPositions() {
        String t = "看 https://minecraft.wiki/w/Iron 和 http://a.com";
        List<ChatLinks.Span> spans = ChatLinks.find(t);
        assertEquals(2, spans.size());
        assertEquals("https://minecraft.wiki/w/Iron", t.substring(spans.get(0).start(), spans.get(0).end()));
        assertEquals("http://a.com", spans.get(1).url());
    }

    @Test
    void stopsAtChinesePunctuationAndWhitespace() {
        assertEquals(List.of("https://a.com/x"), urls("链接:https://a.com/x。下一句"));
        assertEquals(List.of("https://a.com/x"), urls("https://a.com/x，还有"));
        assertEquals(List.of("https://a.com/x"), urls("https://a.com/x\n第二行"));
    }

    @Test
    void trailingSentencePunctuationIsNotPartOfTheUrl() {
        assertEquals(List.of("https://a.com/x"), urls("See https://a.com/x."));
        assertEquals(List.of("https://a.com/x?q=1"), urls("Try https://a.com/x?q=1!"));
    }

    @Test
    void closingParenIsKeptOnlyWhenBalancedInsideTheUrl() {
        assertEquals(List.of("https://a.com/x"), urls("(see https://a.com/x)"));
        assertEquals(List.of("https://en.wikipedia.org/wiki/Creeper_(mob)"),
                urls("https://en.wikipedia.org/wiki/Creeper_(mob)"));
    }

    @Test
    void otherSchemesAndBareSchemesAreNotLinks() {
        assertTrue(ChatLinks.find("ftp://a.com file:///c javascript:alert(1)").isEmpty());
        assertTrue(ChatLinks.find("https:// 什么都没有").isEmpty());
        assertTrue(ChatLinks.find("没有链接").isEmpty());
    }
}
