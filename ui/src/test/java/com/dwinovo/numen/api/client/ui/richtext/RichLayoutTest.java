package com.dwinovo.numen.api.client.ui.richtext;

import com.dwinovo.numen.api.client.ui.richtext.Doc.Para;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Run;
import com.dwinovo.numen.api.client.ui.richtext.RichLayout.Frag;
import com.dwinovo.numen.api.client.ui.richtext.RichLayout.Laid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichLayoutTest {

    /** 每个字 6 宽,粗体每个字多 1。 */
    private static final RichLayout.Metrics M =
            (text, style) -> text.length() * 6 + ((style & Doc.BOLD) != 0 ? text.length() : 0);

    private static Doc para(Run... runs) {
        return new Doc(List.of(Para.of(List.of(runs))));
    }

    private static List<String> rowTexts(Laid laid) {
        return laid.rows().stream()
                .map(r -> r.frags().stream().map(Frag::text).reduce("", String::concat))
                .toList();
    }

    @Test
    void wrapsAtSpacesAndNeverSplitsAWord() {
        Laid l = RichLayout.layout(para(Run.of("aaa bbb ccc")), 40, M);   // 一行放 6 个字符
        assertEquals(List.of("aaa ", "bbb ", "ccc"), rowTexts(l));
    }

    @Test
    void aTrailingSpaceDoesNotPushAWordDown() {
        Laid l = RichLayout.layout(para(Run.of("aaaaa bbb")), 30, M);   // "aaaaa " 是 36,但行尾的空格不占位
        assertEquals(List.of("aaaaa ", "bbb"), rowTexts(l));
        assertEquals(30, l.rows().get(0).end());
    }

    @Test
    void cjkBreaksBetweenAnyTwoCharacters() {
        Laid l = RichLayout.layout(para(Run.of("一二三四五六七")), 24, M);   // 一行 4 个字
        assertEquals(List.of("一二三四", "五六七"), rowTexts(l));
    }

    @Test
    void aWordWiderThanTheLineBreaksPerCharacter() {
        Laid l = RichLayout.layout(para(Run.of("abcdefghij")), 24, M);
        assertEquals(List.of("abcd", "efgh", "ij"), rowTexts(l));
    }

    @Test
    void styleChangesMidLineKeepPositionsContiguous() {
        Laid l = RichLayout.layout(para(Run.of("ab"), new Run("cd", Doc.BOLD, null), Run.of("ef")), 0, M);
        List<Frag> f = l.rows().get(0).frags();
        assertEquals(3, f.size());
        assertEquals(12, f.get(1).x());
        assertEquals(14, f.get(1).w());   // 粗体量宽
        assertEquals(26, f.get(2).x());
    }

    @Test
    void newlinesBreakRowsAndBlocksFollowEachOtherWithoutBlankRows() {
        Doc doc = new Doc(List.of(Para.of(List.of(Run.of("a\nb"))), Para.of(List.of(Run.of("c"))),
                new Para("", false, List.of(Run.of("d")))));
        assertEquals(List.of("a", "b", "c", "d"), rowTexts(RichLayout.layout(doc, 100, M)));
    }

    @Test
    void listPrefixIndentsEveryRowAndOnlyAMarkedParaDrawsIt() {
        Doc doc = new Doc(List.of(new Para("• ", true, List.of(Run.of("aaaa bbbb"))),
                new Para("• ", false, List.of(Run.of("cc")))));
        Laid l = RichLayout.layout(doc, 36, M);   // 缩进 12,正文一行放 4 个字符
        assertEquals(List.of("• aaaa ", "bbbb", "cc"), rowTexts(l));
        assertEquals(12, l.rows().get(1).frags().get(0).x(), "折下来的行对齐到正文");
        assertEquals(12, l.rows().get(2).frags().get(0).x(), "同一项的续段也对齐");
    }

    @Test
    void codeBlocksHardBreakAndPadAndSpanTheWidth() {
        Doc doc = new Doc(List.of(new Doc.Code(List.of("abcdefgh", "", "x"))));
        Laid l = RichLayout.layout(doc, 36, M);   // 左右各留 3,一行放 5 个字符
        assertEquals(List.of("abcde", "fgh", "", "x"), rowTexts(l));
        assertTrue(l.rows().stream().allMatch(RichLayout.Row::code));
        assertEquals(3, l.rows().get(0).frags().get(0).x());
        assertEquals(36, l.width());
    }

    @Test
    void linkAtFindsTheLinkUnderThePointer() {
        Laid l = RichLayout.layout(para(Run.of("ab"), new Run("cd", 0, "https://a.com"), Run.of("ef")), 0, M);
        assertNull(l.linkAt(0, 11));
        assertEquals("https://a.com", l.linkAt(0, 12));
        assertEquals("https://a.com", l.linkAt(0, 23));
        assertNull(l.linkAt(0, 24));
        assertNull(l.linkAt(1, 12), "没有这一行");
    }

    @Test
    void aLinkThatWrapsIsClickableOnBothRows() {
        Laid l = RichLayout.layout(para(new Run("一二三四五六", 0, "https://a.com")), 24, M);
        assertEquals("https://a.com", l.linkAt(0, 0));
        assertEquals("https://a.com", l.linkAt(1, 0));
    }

    @Test
    void paintLaysBackdropsFirstThenText() {
        Doc doc = para(Run.of("ab"), new Run("cd", Doc.CODE, null));
        List<String> calls = new ArrayList<>();
        RichLayout.layout(doc, 0, M).paint(new RichLayout.Canvas() {
            @Override
            public void backdrop(int x, int y, int w, int h) {
                calls.add("bg " + x + "," + y + " " + w + "x" + h);
            }

            @Override
            public void text(Frag frag, int x, int y) {
                calls.add("t " + frag.text() + " " + x + "," + y);
            }
        }, 100, 50, 10);
        assertEquals(List.of("bg 111,50 14x9", "t ab 100,50", "t cd 112,50"), calls);
    }

    @Test
    void aCodeBlockBackdropReachesAboveItsFirstRowForThePadding() {
        List<String> calls = new ArrayList<>();
        RichLayout.layout(new Doc(List.of(new Doc.Code(List.of("ab", "c")))), 0, M).paint(new RichLayout.Canvas() {
            @Override
            public void backdrop(int x, int y, int w, int h) {
                calls.add(x + "," + y + " " + w + "x" + h);
            }

            @Override
            public void text(Frag frag, int x, int y) {}
        }, 10, 50, 10);
        assertEquals(List.of("10,48 18x10", "10,58 18x10"), calls, "每行的底都上移 2,连起来就是整块,上下各有 2 的边距");
    }

    @Test
    void accentedMarksOnlyTheGivenRangesAndSkipsCode() {
        Doc doc = new Doc(List.of(Para.of(List.of(Run.of("hi @小明 ok "), new Run("@小明", Doc.CODE, null)))));
        Doc out = doc.accented(List.of(new Doc.Range(3, 6)));
        List<Run> runs = ((Para) out.blocks().get(0)).runs();
        assertEquals(List.of(Run.of("hi "), new Run("@小明", Doc.ACCENT, null), Run.of(" ok "),
                new Run("@小明", Doc.CODE, null)), runs);
    }

    @Test
    void accentedRangesCountAcrossParagraphs() {
        Doc doc = new Doc(List.of(Para.of(List.of(Run.of("ab"))), Para.of(List.of(Run.of("cd")))));
        Doc out = doc.accented(List.of(new Doc.Range(3, 4)));   // plainText 是 "ab\ncd",3 是 c
        assertEquals(List.of(new Run("c", Doc.ACCENT, null), Run.of("d")), ((Para) out.blocks().get(1)).runs());
    }

    @Test
    void withTailAppendsToTheLastParagraph() {
        Doc out = para(Run.of("hi")).withTail("_");
        assertEquals("hi_", out.plainText());
        assertEquals(1, out.blocks().size());
    }
}
