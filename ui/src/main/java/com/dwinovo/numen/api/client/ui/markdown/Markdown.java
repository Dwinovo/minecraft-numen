package com.dwinovo.numen.api.client.ui.markdown;

import com.dwinovo.numen.api.client.ui.richtext.Doc;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Block;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Run;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Markdown → {@link Doc} 的适配器:CommonMark 的语法树在这里读完就变成富文本模型,CommonMark 的类型不出这个包。
 *
 * <p>认的:粗体、斜体、删除线({@code ~~}),行内代码,围栏与缩进代码块,链接({@code [字](网址)}、{@code <网址>},
 * 外加没写成链接的裸网址),图片(当成写着替代文字的链接),标题(变粗体),有序与无序列表(嵌套)。
 * 其余的块(引用、分隔线、HTML 块)按原文一行一行照样显示——记号留着,不吞字。
 * 聊天里换行就是换行:一个段落里的软换行也断行。
 *
 * <p>纯 JVM,无状态,线程安全({@link Parser} 解析时不留状态)。
 */
public final class Markdown {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(StrikethroughExtension.builder().requireTwoTildes(true).build()))
            // 认的块之外的原文要照样显示,靠源码位置取回
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .enabledBlockTypes(Set.of(Heading.class, FencedCodeBlock.class, IndentedCodeBlock.class,
                    org.commonmark.node.BlockQuote.class, org.commonmark.node.HtmlBlock.class,
                    org.commonmark.node.ThematicBreak.class, ListBlock.class))
            .build();

    private Markdown() {}

    public static Doc parse(String markdown) {
        String source = markdown.replace("\r\n", "\n");
        String[] lines = source.split("\n", -1);
        List<Block> out = new ArrayList<>();
        for (Node n = PARSER.parse(source).getFirstChild(); n != null; n = n.getNext()) {
            block(n, "", false, lines, out);
        }
        return new Doc(out);
    }

    // ---- 块 ----

    /**
     * @param prefix 列表里的前缀(嵌套的缩进 + 标记);{@code marked} = 这一块是列表项的第一块,画出标记
     */
    private static void block(Node n, String prefix, boolean marked, String[] lines, List<Block> out) {
        if (n instanceof Paragraph) {
            out.add(new Doc.Para(prefix, marked, inline(n, 0, null)));
        } else if (n instanceof Heading) {
            out.add(new Doc.Para(prefix, marked, inline(n, Doc.BOLD, null)));
        } else if (n instanceof FencedCodeBlock c) {
            out.add(new Doc.Code(codeLines(c.getLiteral())));
        } else if (n instanceof IndentedCodeBlock c) {
            out.add(new Doc.Code(codeLines(c.getLiteral())));
        } else if (n instanceof ListBlock list) {
            list(list, prefix, lines, out);
        } else {
            literal(n, lines, out);   // 引用、分隔线、HTML 块:原文照样显示
        }
    }

    private static void list(ListBlock list, String prefix, String[] lines, List<Block> out) {
        // 嵌套的列表缩进到外层标记之后
        String pad = " ".repeat(prefix.isEmpty() ? 0 : prefix.length() + 1);
        int number = 1;
        String delimiter = ".";
        if (list instanceof OrderedList ordered) {
            if (ordered.getMarkerStartNumber() != null) number = ordered.getMarkerStartNumber();
            delimiter = ordered.getMarkerDelimiter();
        }
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            String marker = list instanceof BulletList ? "• " : number++ + delimiter + " ";
            String itemPrefix = pad + marker;
            boolean first = true;
            for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                block(child, itemPrefix, first, lines, out);
                first = false;
            }
        }
    }

    /** 不认的块:它在原文里占的每一行,一行一段。 */
    private static void literal(Node n, String[] lines, List<Block> out) {
        for (SourceSpan span : n.getSourceSpans()) {
            String line = lines[span.getLineIndex()];
            String text = line.substring(Math.min(span.getColumnIndex(), line.length()),
                    Math.min(span.getColumnIndex() + span.getLength(), line.length()));
            out.add(new Doc.Para("", false, List.of(Run.of(text))));
        }
    }

    private static List<String> codeLines(String literal) {
        String body = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
        return List.of(body.split("\n", -1));
    }

    // ---- 行内 ----

    /** {@code style}、{@code link} 是外层已经带上的样式与链接,往里传。 */
    private static List<Run> inline(Node parent, int style, String link) {
        List<Run> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Text t) {
                text(t.getLiteral(), style, link, out);
            } else if (n instanceof Emphasis) {
                out.addAll(inline(n, style | Doc.ITALIC, link));
            } else if (n instanceof StrongEmphasis) {
                out.addAll(inline(n, style | Doc.BOLD, link));
            } else if (n instanceof Strikethrough) {
                out.addAll(inline(n, style | Doc.STRIKE, link));
            } else if (n instanceof Code c) {
                out.add(new Run(c.getLiteral(), style | Doc.CODE, link));
            } else if (n instanceof SoftLineBreak || n instanceof HardLineBreak) {
                out.add(new Run("\n", style, null));
            } else if (n instanceof HtmlInline h) {
                text(h.getLiteral(), style, link, out);
            } else if (n instanceof Link l) {
                out.addAll(inline(n, style, BareLinks.opens(l.getDestination()) ? l.getDestination() : link));
            } else if (n instanceof Image i) {
                // 图片画不了:写着替代文字的链接,点开就是那张图
                out.addAll(inline(n, style, BareLinks.opens(i.getDestination()) ? i.getDestination() : link));
            } else {
                out.addAll(inline(n, style, link));
            }
        }
        return out;
    }

    /** 一段普通文字:不在链接里的,裸网址切出来成链接。 */
    private static void text(String literal, int style, String link, List<Run> out) {
        if (link != null) {
            out.add(new Run(literal, style, link));
            return;
        }
        int from = 0;
        for (BareLinks.Span s : BareLinks.find(literal)) {
            if (s.start() > from) out.add(new Run(literal.substring(from, s.start()), style, null));
            out.add(new Run(s.url(), style, s.url()));
            from = s.end();
        }
        if (from < literal.length()) out.add(new Run(literal.substring(from), style, null));
    }
}
