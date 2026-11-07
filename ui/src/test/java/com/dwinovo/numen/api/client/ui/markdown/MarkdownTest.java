package com.dwinovo.numen.api.client.ui.markdown;

import com.dwinovo.numen.api.client.ui.richtext.Doc;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Code;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Para;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Run;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTest {

    private static List<Run> runs(String md) {
        Doc doc = Markdown.parse(md);
        assertEquals(1, doc.blocks().size(), md);
        return ((Para) doc.blocks().get(0)).runs();
    }

    @Test
    void inlineStylesBecomeStyleBits() {
        assertEquals(List.of(Run.of("a "), new Run("粗", Doc.BOLD, null), Run.of(" "), new Run("斜", Doc.ITALIC, null),
                        Run.of(" "), new Run("划", Doc.STRIKE, null), Run.of(" "), new Run("码", Doc.CODE, null)),
                runs("a **粗** *斜* ~~划~~ `码`"));
    }

    @Test
    void stylesNestAndBitsCombine() {
        assertEquals(List.of(new Run("两者", Doc.BOLD | Doc.ITALIC, null)), runs("***两者***"));
    }

    @Test
    void aSingleTildeIsNotStrikethrough() {
        assertEquals("~/x ~ y~", plain(runs("~/x ~ y~")));
    }

    @Test
    void markdownLinksCarryTheirDestinationAndKeepTheirText() {
        assertEquals(List.of(Run.of("看 "), new Run("维基", 0, "https://minecraft.wiki"), Run.of("。")),
                runs("看 [维基](https://minecraft.wiki)。"));
        assertEquals(List.of(new Run("https://a.com", 0, "https://a.com")), runs("<https://a.com>"));
    }

    @Test
    void bareUrlsBecomeLinksAndLinkTextIsNotLinkedAgain() {
        List<Run> r = runs("见 https://a.com/x。");
        assertEquals(new Run("https://a.com/x", 0, "https://a.com/x"), r.get(1));
        assertEquals(List.of(new Run("https://b.com", 0, "https://a.com")), runs("[https://b.com](https://a.com)"));
    }

    @Test
    void aDestinationThatCannotOpenKeepsTheTextWithoutALink() {
        List<Run> r = runs("[点我](javascript:alert(1))");
        assertEquals("点我", plain(r));
        assertNull(r.get(0).link());
    }

    @Test
    void imagesShowTheirAltTextAsALink() {
        assertEquals(List.of(new Run("图", 0, "https://a.com/i.png")), runs("![图](https://a.com/i.png)"));
    }

    @Test
    void lineBreaksStayBreaks() {
        assertEquals("a\nb\nc", plain(runs("a\nb  \nc")));
    }

    @Test
    void headingsBecomeBoldParagraphsWithoutTheirMarkers() {
        Doc doc = Markdown.parse("# 标题\n正文");
        assertEquals(2, doc.blocks().size());
        assertEquals(List.of(new Run("标题", Doc.BOLD, null)), ((Para) doc.blocks().get(0)).runs());
    }

    @Test
    void fencedAndIndentedCodeBlocksKeepEveryLine() {
        Doc doc = Markdown.parse("```java\nint a;\n\nint b;\n```");
        assertEquals(List.of(new Code(List.of("int a;", "", "int b;"))), doc.blocks());
        assertEquals(List.of(new Code(List.of("x = 1"))), Markdown.parse("    x = 1").blocks());
    }

    @Test
    void markdownInsideCodeIsNotParsed() {
        assertEquals(List.of(new Code(List.of("**not bold** https://a.com"))),
                Markdown.parse("```\n**not bold** https://a.com\n```").blocks());
        assertEquals(List.of(new Run("**x**", Doc.CODE, null)), runs("`**x**`"));
    }

    @Test
    void listsKeepTheirMarkersAndNesting() {
        Doc doc = Markdown.parse("- 甲\n- 乙\n  1. 丙\n  2. 丁");
        List<Para> ps = doc.blocks().stream().map(b -> (Para) b).toList();
        assertEquals(List.of("• ", "• ", "   1. ", "   2. "), ps.stream().map(Para::prefix).toList());
        assertTrue(ps.stream().allMatch(Para::marked));
    }

    @Test
    void orderedListsKeepTheirStartNumber() {
        Doc doc = Markdown.parse("3. 三\n4. 四");
        assertEquals(List.of("3. ", "4. "), doc.blocks().stream().map(b -> ((Para) b).prefix()).toList());
    }

    @Test
    void blocksWeDoNotRenderShowTheirSourceLineByLine() {
        Doc doc = Markdown.parse("> 引用一\n> 引用二\n\n---\n\n<div>x</div>");
        assertEquals(List.of("> 引用一", "> 引用二", "---", "<div>x</div>"),
                doc.blocks().stream().map(b -> plain(((Para) b).runs())).toList());
    }

    @Test
    void aParagraphAfterAnUnrenderedBlockIsStillParsed() {
        Doc doc = Markdown.parse("> 引用\n\n看 **粗** 和 [文档](https://a.com)");
        assertEquals(2, doc.blocks().size());
        assertEquals(List.of(Run.of("看 "), new Run("粗", Doc.BOLD, null), Run.of(" 和 "),
                new Run("文档", 0, "https://a.com")), ((Para) doc.blocks().get(1)).runs());
    }

    @Test
    void unmatchedMarkersStayLiteral() {
        assertEquals("**没闭合 和 a*b 和 _x", plain(runs("**没闭合 和 a*b 和 _x")));
    }

    @Test
    void inlineHtmlIsShownAsText() {
        assertEquals("a <b>c</b>", plain(runs("a <b>c</b>")));
    }

    private static String plain(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs) sb.append(r.text());
        return sb.toString();
    }
}
