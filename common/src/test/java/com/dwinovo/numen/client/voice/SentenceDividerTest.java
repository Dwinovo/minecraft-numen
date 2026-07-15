package com.dwinovo.numen.client.voice;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SentenceDivider} 的全参数矩阵：首段逗号级切分 / 最短长度 /
 * 后续段仅句末标点 / 无标点强制切 / 小数点与缩写不误切 / 缓冲末尾等前瞻 /
 * flush 收尾。全部 headless,不碰任何 Minecraft 类。
 */
class SentenceDividerTest {

    /** 逐字符喂入,模拟最碎的流式 delta;只收集 feed 阶段的产出。 */
    private static List<String> feedCharByChar(SentenceDivider d, String text) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            out.addAll(d.feed(String.valueOf(text.charAt(i))));
        }
        return out;
    }

    /** 完整一轮:逐字符喂入 + flush,得到整条回复的全部句段。 */
    private static List<String> divide(String text) {
        SentenceDivider d = new SentenceDivider();
        List<String> out = feedCharByChar(d, text);
        out.addAll(d.flush());
        return out;
    }

    // ---- 首段逗号级切分 ----

    @Test
    void firstSegmentCutsAtFirstCommaAfterMinLength() {
        List<String> out = divide("好的主人我这就出发了,先去砍一点橡木回来。");
        assertEquals(List.of("好的主人我这就出发了,", "先去砍一点橡木回来。"), out);
    }

    @Test
    void firstSegmentSkipsCommaBeforeMinLength() {
        // 第一个逗号在第 3 字符（<10）,不切;凑够长度后在下一个句读切。
        List<String> out = divide("好的,我马上去把那片云杉林砍掉,然后回来。");
        assertEquals("好的,我马上去把那片云杉林砍掉,", out.get(0));
    }

    @Test
    void firstSegmentCutsAtColon() {
        List<String> out = divide("报告主人我现在的状态:生命值满,食物也充足。");
        assertEquals("报告主人我现在的状态:", out.get(0));
    }

    @Test
    void firstSegmentEmittedPromptlyWhileStreaming() {
        // 开口延迟的关键路径:首段必须在 feed 阶段就吐出来,不能等 flush。
        SentenceDivider d = new SentenceDivider();
        List<String> streamed = feedCharByChar(d, "好的主人我这就出发了,先去砍一点橡木回来");
        assertEquals(List.of("好的主人我这就出发了,"), streamed);
    }

    // ---- 后续段:仅句末标点 ----

    @Test
    void laterSegmentsIgnoreCommas() {
        List<String> out = divide(
                "我先把工作台放在这里,大家都能用。然后我去挖矿,顺便找找铁,晚点回来。");
        assertEquals(List.of(
                "我先把工作台放在这里,",              // 首段:逗号级
                "大家都能用。",                        // 后续:句号级
                "然后我去挖矿,顺便找找铁,晚点回来。"    // 中间逗号不再切
        ), out);
    }

    @Test
    void newlineIsAlwaysABoundary() {
        SentenceDivider d = new SentenceDivider();
        // 换行不等前瞻:feed 阶段就应吐出两段。
        List<String> out = d.feed("第一件事是收集石头够用了\n第二件事是搭一个熔炉出来\n");
        assertEquals(List.of("第一件事是收集石头够用了", "第二件事是搭一个熔炉出来"), out);
    }

    @Test
    void exclamationAndQuestionMarksEndSegments() {
        List<String> out = divide("小心身后有苦力怕快跑!要我把它引开吗?好我上了。");
        assertEquals(List.of("小心身后有苦力怕快跑!", "要我把它引开吗?", "好我上了。"), out);
    }

    // ---- 无标点保险 ----

    @Test
    void forceCutWithoutAnyPunctuation() {
        SentenceDivider d = new SentenceDivider();
        String noPunct = "一".repeat(SentenceDivider.MAX_SEGMENT_CHARS * 2);
        List<String> out = feedCharByChar(d, noPunct);
        assertEquals(2, out.size());
        assertEquals(SentenceDivider.MAX_SEGMENT_CHARS, out.get(0).length());
        assertEquals(SentenceDivider.MAX_SEGMENT_CHARS, out.get(1).length());
    }

    @Test
    void forceCutDoesNotSplitSurrogatePair() {
        SentenceDivider d = new SentenceDivider();
        // 先垫一个普通字符让强制切点落在代理对中间,验证回退一位。
        String text = "字" + "𝄞".repeat(SentenceDivider.MAX_SEGMENT_CHARS);
        List<String> out = d.feed(text);
        assertFalse(out.isEmpty());
        for (String seg : out) {
            assertFalse(Character.isHighSurrogate(seg.charAt(seg.length() - 1)),
                    "段尾不能是残缺的高代理: " + seg);
        }
    }

    // ---- 前瞻消歧 ----

    @Test
    void decimalPointIsNotABoundary() {
        List<String> out = divide("圆周率大约等于3.14159,记住了吗?");
        assertEquals("圆周率大约等于3.14159,", out.get(0));
    }

    @Test
    void timeColonIsNotABoundary() {
        List<String> out = divide("我们约在下午1:30出发,不见不散。");
        assertEquals("我们约在下午1:30出发,", out.get(0));
    }

    @Test
    void englishAbbreviationIsNotABoundary() {
        List<String> out = divide("You can ask Dr. Smith about it, he knows the cave well.");
        assertEquals("You can ask Dr. Smith about it,", out.get(0));
    }

    @Test
    void egAbbreviationIsNotABoundary() {
        List<String> out = divide("Bring some food, e.g. bread or steak, before we leave.");
        assertEquals("Bring some food,", out.get(0));
        assertEquals("e.g. bread or steak, before we leave.", out.get(1));
    }

    @Test
    void urlDotsAreNotBoundaries() {
        List<String> out = divide("文档在https://example.com/wiki/page这里,自己看。");
        assertEquals(List.of("文档在https://example.com/wiki/page这里,", "自己看。"), out);
    }

    @Test
    void asciiPeriodAtBufferEndWaitsForLookahead() {
        SentenceDivider d = new SentenceDivider();
        // '.' 是缓冲最后一个字符:流没结束,不能确定是不是 "3.14" 的前半,先不切。
        assertTrue(d.feed("The answer is 42.").isEmpty());
        // 下一个 delta 证明它是句号。
        List<String> out = d.feed(" Let's go");
        assertEquals("The answer is 42.", out.get(0));
    }

    @Test
    void cjkBoundaryAtBufferEndWaitsForClosingQuote() {
        SentenceDivider d = new SentenceDivider();
        // '。' 到达时先不切——下一个字符可能是要并入本段的闭合引号。
        assertTrue(feedCharByChar(d, "他说“我们今晚在基地集合哦。").isEmpty());
        List<String> out = d.feed("”然后就走了");
        assertEquals("他说“我们今晚在基地集合哦。”", out.get(0));
    }

    @Test
    void ellipsisCutsAtLastDot() {
        List<String> out = divide("我想想看这个问题怎么办... 先探探路吧。");
        assertEquals("我想想看这个问题怎么办...", out.get(0));
    }

    @Test
    void fullWidthEllipsisAbsorbsWholeRunWhileStreaming() {
        // "……" 逐字到达:不能在第一个 '…' 就切,整串省略号要并入本段。
        List<String> out = divide("让我数数一二三四五六七八……然后开工。");
        assertEquals(List.of("让我数数一二三四五六七八……", "然后开工。"), out);
    }

    // ---- flush 收尾 ----

    @Test
    void flushEmitsRemainder() {
        SentenceDivider d = new SentenceDivider();
        assertTrue(d.feed("最后这半句没有标点").isEmpty());
        assertEquals(List.of("最后这半句没有标点"), d.flush());
        assertEquals(0, d.pendingChars());
    }

    @Test
    void flushTreatsTrailingAsciiPunctAsBoundary() {
        SentenceDivider d = new SentenceDivider();
        assertTrue(d.feed("That is all I found in the mine.").isEmpty());   // 尾部 '.' 等前瞻
        assertEquals(List.of("That is all I found in the mine."), d.flush());
    }

    @Test
    void flushOnEmptyBufferYieldsNothing() {
        SentenceDivider d = new SentenceDivider();
        assertTrue(d.flush().isEmpty());
    }

    @Test
    void flushResetsFirstSegmentMode() {
        SentenceDivider d = new SentenceDivider();
        feedCharByChar(d, "第一轮回复的首段在这里切,后面还有。");
        d.flush();
        // 新一轮:又是首段,逗号级重新生效。
        List<String> out = feedCharByChar(d, "新一轮的回复也应当尽快开口,而不是等待句号");
        assertEquals("新一轮的回复也应当尽快开口,", out.get(0));
    }

    @Test
    void resetDropsBufferedText() {
        SentenceDivider d = new SentenceDivider();
        d.feed("这些字都会被丢掉");
        d.reset();
        assertEquals(0, d.pendingChars());
        assertTrue(d.flush().isEmpty());
    }

    // ---- 杂项 ----

    @Test
    void emptyAndNullDeltasAreNoops() {
        SentenceDivider d = new SentenceDivider();
        assertTrue(d.feed(null).isEmpty());
        assertTrue(d.feed("").isEmpty());
        assertEquals(0, d.pendingChars());
    }

    @Test
    void whitespaceBetweenSegmentsIsSwallowed() {
        List<String> out = divide("First sentence is done. \n  Second one continues.");
        assertEquals(List.of("First sentence is done.", "Second one continues."), out);
    }
}
