package com.dwinovo.numen.api.client.ui.richtext;

import com.dwinovo.numen.api.client.ui.richtext.Doc.Block;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Code;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Para;
import com.dwinovo.numen.api.client.ui.richtext.Doc.Run;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Doc} 折成行:每一行是若干{@link Frag}(一段同样式的字和它的位置),整份排版就是行的列表。
 * 量字、落笔都由调用方给({@link Metrics}、{@link Canvas}),所以这里没有字体、颜色和像素的约定;
 * 点中({@link Laid#linkAt})用的是排版时记下的位置,和画出来的是同一份。
 *
 * <p>折法:空格后断,词不劈两半;CJK 等一个字一个落点,可以在任意两个字之间断;一个词(或代码行)
 * 单独比一行还宽就逐字硬断。块与块之间不空行(聊天面板寸土寸金)。
 */
public final class RichLayout {

    /** 代码块里字离底色左右缘的边距(像素,Telegram 的 pre 块);上下的边距见 {@link Laid#paint}。 */
    static final int CODE_PAD_X = 3;
    static final int CODE_PAD_Y = 2;

    private RichLayout() {}

    /** 一段字在某种样式下多宽。 */
    @FunctionalInterface
    public interface Metrics {
        int width(String text, int style);
    }

    /** 落笔的地方:{@link Laid#paint} 照排好的位置逐段调它。 */
    public interface Canvas {
        /** 一块底(代码块整行、行内代码)。 */
        void backdrop(int x, int y, int w, int h);

        /** 一段字,{@code (x, y)} 是左上角。 */
        void text(Frag frag, int x, int y);
    }

    /** 一行里的一段字:{@code x} 从行首起算。 */
    public record Frag(String text, int style, String link, int x, int w) {}

    /** {@code code} = 这一行属于代码块,整行铺底;{@code end} = 这一行的右缘(行尾的空格不算,代码行带右边的留白),空行是 0。 */
    public record Row(List<Frag> frags, boolean code, int end) {}

    /** 排好的整份:行、和最宽那一行的宽度(代码块的底铺到这么宽)。 */
    public record Laid(List<Row> rows, int width) {
        public Laid {
            rows = List.copyOf(rows);
        }

        /** 最后一行的右缘;没有行是 0。 */
        public int lastEnd() {
            return rows.isEmpty() ? 0 : rows.get(rows.size() - 1).end();
        }

        /** 第 {@code row} 行 {@code x} 处的链接;不在链接上(或越界)是 null。 */
        public String linkAt(int row, int x) {
            if (row < 0 || row >= rows.size()) return null;
            for (Frag f : rows.get(row).frags()) {
                if (f.link() != null && x >= f.x() && x < f.x() + f.w()) return f.link();
            }
            return null;
        }

        /** 把整份画到 {@code (x, y)} 起,每行 {@code rowH} 高。先铺底、再落字,字不会被自己的底盖住。 */
        public void paint(Canvas canvas, int x, int y, int rowH) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                int ry = y + i * rowH;
                if (row.code()) {
                    // 底往上多铺 CODE_PAD_Y:字在行里贴着行顶,上下各留这么多边距(上面的借的是上一行字下面的空隙)
                    canvas.backdrop(x, ry - CODE_PAD_Y, width, rowH);
                } else {
                    for (Frag f : row.frags()) {
                        if ((f.style() & Doc.CODE) != 0) canvas.backdrop(x + f.x() - 1, ry, f.w() + 2, rowH - 1);
                    }
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                for (Frag f : rows.get(i).frags()) canvas.text(f, x + f.x(), y + i * rowH);
            }
        }
    }

    /**
     * @param maxW 一行最多多宽(含缩进);{@code <= 0} 不折软行,只按换行符分行
     */
    public static Laid layout(Doc doc, int maxW, Metrics metrics) {
        List<Row> rows = new ArrayList<>();
        for (Block block : doc.blocks()) {
            if (block instanceof Para p) {
                para(p, maxW, metrics, rows);
            } else if (block instanceof Code c) {
                code(c, maxW, metrics, rows);
            }
        }
        int width = 0;
        for (Row r : rows) width = Math.max(width, r.end());
        return new Laid(rows, width);
    }

    // ---- 段落 ----

    private static void para(Para p, int maxW, Metrics m, List<Row> rows) {
        int indent = p.prefix().isEmpty() ? 0 : m.width(p.prefix(), 0);
        Line line = new Line(indent, m);
        if (p.marked() && !p.prefix().isEmpty()) line.frags.add(new Frag(p.prefix(), 0, null, 0, indent));
        for (Run run : p.runs()) {
            for (String piece : pieces(run.text())) {
                if (piece.equals("\n")) {
                    rows.add(line.row());
                    line = new Line(indent, m);
                    continue;
                }
                put(piece, run, line, indent, maxW, m, rows);
            }
        }
        rows.add(line.row());
    }

    /** 放一个落点:这一行放不下就换行;一个落点自己比整行还宽就逐字拆。 */
    private static void put(String piece, Run run, Line line, int indent, int maxW, Metrics m, List<Row> rows) {
        int w = m.width(piece, run.style());
        // 行尾的空格不占位:压线的空格不该把词挤到下一行
        int ink = m.width(stripEnd(piece), run.style());
        if (maxW > 0 && line.x + ink > maxW && line.x > indent) {
            rows.add(line.row());
            line.restart(indent);
        }
        if (maxW > 0 && line.x + ink > maxW && piece.codePointCount(0, piece.length()) > 1) {
            // 一个词自己就比一行宽:逐字落,放不下的字换行
            for (int i = 0; i < piece.length(); ) {
                int cp = piece.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                i += ch.length();
                int cw = m.width(ch, run.style());
                if (line.x + cw > maxW && line.x > indent) {
                    rows.add(line.row());
                    line.restart(indent);
                }
                line.add(ch, run, cw);
            }
            return;
        }
        line.add(piece, run, w);
    }

    private static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') e--;
        return s.substring(0, e);
    }

    /**
     * 把一段字切成落点:一个词连着它后面的空格是一个,CJK 等一个字一个,换行符单独一个。
     * 落点之间才断行,所以词不会被劈开。
     */
    static List<String> pieces(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean trailingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') {
                flush(word, out);
                trailingSpace = false;
                out.add("\n");
            } else if (isIdeograph(cp)) {
                flush(word, out);
                trailingSpace = false;
                out.add(new String(Character.toChars(cp)));
            } else if (cp == ' ') {
                word.append(' ');
                trailingSpace = true;
            } else {
                if (trailingSpace) {
                    flush(word, out);
                    trailingSpace = false;
                }
                word.appendCodePoint(cp);
            }
        }
        flush(word, out);
        return out;
    }

    private static void flush(StringBuilder word, List<String> out) {
        if (word.length() > 0) {
            out.add(word.toString());
            word.setLength(0);
        }
    }

    /** 可以在它前后任意处断开的字:CJK 表意文字、假名、谚文、全角标点。 */
    private static boolean isIdeograph(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFF00 && cp <= 0xFFEF)
                || (cp >= 0x20000 && cp <= 0x3FFFF);
    }

    // ---- 代码块 ----

    private static void code(Code c, int maxW, Metrics m, List<Row> rows) {
        int pad = CODE_PAD_X;
        Run run = new Run("", Doc.CODE, null);
        for (String src : c.lines()) {
            Line line = new Line(pad, m);
            line.code = true;
            if (src.isEmpty()) {
                rows.add(line.row());
                continue;
            }
            for (int i = 0; i < src.length(); ) {
                int cp = src.codePointAt(i);
                String ch = new String(Character.toChars(cp));
                i += ch.length();
                int cw = m.width(ch, Doc.CODE);
                if (maxW > 0 && line.x + cw + pad > maxW && line.x > pad) {
                    rows.add(line.row());
                    line = new Line(pad, m);
                    line.code = true;
                }
                line.add(ch, run, cw);
            }
            rows.add(line.row());
        }
    }

    /** 正在排的这一行。相邻同样式同链接的字并成一段。 */
    private static final class Line {
        final List<Frag> frags = new ArrayList<>();
        int x;
        boolean code;
        final Metrics m;

        Line(int indent, Metrics m) {
            this.x = indent;
            this.m = m;
        }

        void restart(int indent) {
            frags.clear();
            x = indent;
        }

        void add(String text, Run run, int w) {
            if (!frags.isEmpty()) {
                Frag last = frags.get(frags.size() - 1);
                if (last.style() == run.style() && java.util.Objects.equals(last.link(), run.link())
                        && last.x() + last.w() == x) {
                    frags.set(frags.size() - 1, new Frag(last.text() + text, last.style(), last.link(), last.x(),
                            last.w() + w));
                    x += w;
                    return;
                }
            }
            frags.add(new Frag(text, run.style(), run.link(), x, w));
            x += w;
        }

        Row row() {
            if (frags.isEmpty()) return new Row(List.of(), code, 0);
            Frag last = frags.get(frags.size() - 1);
            // 代码行右边留一个和左边一样的空白;其余行不算行尾的空格
            int end = code ? x + CODE_PAD_X : last.x() + m.width(stripEnd(last.text()), last.style());
            return new Row(List.copyOf(frags), code, end);
        }
    }
}
