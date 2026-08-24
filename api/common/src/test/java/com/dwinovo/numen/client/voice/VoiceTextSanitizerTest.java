package com.dwinovo.numen.client.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link VoiceTextSanitizer} 的清洗规则矩阵。 */
class VoiceTextSanitizerTest {

    @Test
    void plainChineseTextPassesThrough() {
        assertEquals("我这就去砍树,很快回来。",
                VoiceTextSanitizer.clean("我这就去砍树,很快回来。"));
    }

    @Test
    void markdownBoldAndInlineCodeAreStripped() {
        assertEquals("一定要先搭工作台再合成",
                VoiceTextSanitizer.clean("**一定要**先搭`工作台`再合成"));
    }

    @Test
    void markdownHeadingAndQuoteMarksAreStripped() {
        assertEquals("今日计划 先挖铁", VoiceTextSanitizer.clean("## 今日计划\n> 先挖铁"));
    }

    @Test
    void parenActionsAreRemoved() {
        assertEquals("好呀 我们现在就出发吧",
                VoiceTextSanitizer.clean("好呀(笑)我们现在就出发吧（挥了挥手）"));
    }

    @Test
    void starActionsAreRemoved() {
        assertEquals("没问题 交给我", VoiceTextSanitizer.clean("*点头* 没问题,交给我".replace(",", " ").strip()));
        assertEquals("没问题,交给我", VoiceTextSanitizer.clean("*点头*没问题,交给我"));
    }

    @Test
    void urlsAreRemoved() {
        assertEquals("配方在 这个页面",
                VoiceTextSanitizer.clean("配方在 https://minecraft.wiki/w/Crafting 这个页面"));
        assertEquals("看 就懂了", VoiceTextSanitizer.clean("看 www.example.com/guide 就懂了"));
    }

    @Test
    void xmlTagResiduesAreRemoved() {
        assertEquals("我回来了", VoiceTextSanitizer.clean("<event>我回来了</event>"));
        assertEquals("收到", VoiceTextSanitizer.clean("收到<br/>"));
    }

    @Test
    void punctuationOnlySegmentBecomesEmpty() {
        assertEquals("", VoiceTextSanitizer.clean("……"));
        assertEquals("", VoiceTextSanitizer.clean("!?。"));
    }

    @Test
    void markerOnlySegmentBecomesEmpty() {
        assertEquals("", VoiceTextSanitizer.clean("***"));
        assertEquals("", VoiceTextSanitizer.clean("(叹气)"));
        assertEquals("", VoiceTextSanitizer.clean("   "));
        assertEquals("", VoiceTextSanitizer.clean(null));
    }

    @Test
    void whitespaceIsCollapsed() {
        assertEquals("第一步 挖三块 圆石",
                VoiceTextSanitizer.clean("第一步   挖三块\n\n圆石"));
    }

    @Test
    void mixedRealWorldReply() {
        String raw = "**收到!**(整理背包) 我先去 https://example.com 看看,然后带上`铁镐`出发。";
        String out = VoiceTextSanitizer.clean(raw);
        assertTrue(out.contains("收到"));
        assertTrue(out.contains("铁镐"));
        assertTrue(!out.contains("**") && !out.contains("`") && !out.contains("http"));
        assertTrue(!out.contains("整理背包"));
    }
}
