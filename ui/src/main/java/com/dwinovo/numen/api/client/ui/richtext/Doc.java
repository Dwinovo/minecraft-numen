package com.dwinovo.numen.api.client.ui.richtext;

import java.util.ArrayList;
import java.util.List;

/**
 * 一段带样式的文字:块的序列,每块是若干{@link Run}(同一种样式的一小段字)。它只管"哪些字是粗的、斜的、
 * 划掉的、等宽的、点得开的",不认识 Markdown,也不知道像素和颜色——解析(Markdown → 它)在 {@code markdown} 包,
 * 折行与点中在 {@link RichLayout},落到画布上由调用方的 {@link RichLayout.Canvas} 决定颜色与字体。
 *
 * <p>纯 JVM;值都是不可变的,可以当缓存的键。
 */
public record Doc(List<Block> blocks) {

    public Doc {
        blocks = List.copyOf(blocks);
    }

    /** 样式位。 */
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int STRIKE = 4;
    /** 等宽一档:行内代码与代码块。排版量字时当作特殊样式,落笔时画底。 */
    public static final int CODE = 8;
    /** 强调色(@ 到的名字);链接另有 {@link Run#link},颜色和下划线由调用方画。 */
    public static final int ACCENT = 16;

    /**
     * 一小段同样式的字。{@code text} 里的 {@code '\n'} 是强制换行;{@code link} 非空 = 点它打开这个网址。
     */
    public record Run(String text, int style, String link) {
        public static Run of(String text) {
            return new Run(text, 0, null);
        }

        Run with(String newText) {
            return new Run(newText, style, link);
        }
    }

    public sealed interface Block permits Para, Code {}

    /**
     * 一段话。{@code prefix} 是前缀(列表的"• "、"1. "和嵌套的缩进空格):整段的左缩进就是它的宽度,
     * {@code marked} 为真时第一行在缩进处画出它(列表项的第一段),为假时只占位(同一项的续段)。
     */
    public record Para(String prefix, boolean marked, List<Run> runs) implements Block {
        public Para {
            runs = List.copyOf(runs);
        }

        public static Para of(List<Run> runs) {
            return new Para("", false, runs);
        }
    }

    /** 代码块:每个元素是一行,不折词、不解析,超宽按字硬断。 */
    public record Code(List<String> lines) implements Block {
        public Code {
            lines = List.copyOf(lines);
        }
    }

    /** 一段原样的字:每个换行符分一行,不空行、不解析样式。 */
    public static Doc plain(String text) {
        List<Block> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            out.add(new Para("", false, List.of(Run.of(line))));
        }
        return new Doc(out);
    }

    /** 所有段落的字按顺序连起来,段落之间一个 {@code '\n'};代码块不算(那里面的字不当正文认)。 */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof Para p) {
                if (sb.length() > 0) sb.append('\n');
                for (Run r : p.runs()) sb.append(r.text());
            }
        }
        return sb.toString();
    }

    /** {@code [start, end)} 是 {@link #plainText()} 里的下标。 */
    public record Range(int start, int end) {}

    /** 把 {@link #plainText()} 里这些范围的字标成强调色(行内代码里的字不动);范围外原样。 */
    public Doc accented(List<Range> ranges) {
        if (ranges.isEmpty()) return this;
        List<Block> out = new ArrayList<>(blocks.size());
        int at = 0;
        boolean first = true;
        for (Block b : blocks) {
            if (!(b instanceof Para p)) {
                out.add(b);
                continue;
            }
            if (!first) at++;   // 段落之间的 '\n'
            first = false;
            List<Run> runs = new ArrayList<>();
            for (Run r : p.runs()) {
                if ((r.style() & CODE) != 0) {
                    runs.add(r);
                } else {
                    split(r, at, ranges, runs);
                }
                at += r.text().length();
            }
            out.add(new Para(p.prefix(), p.marked(), runs));
        }
        return new Doc(out);
    }

    private static void split(Run r, int base, List<Range> ranges, List<Run> out) {
        String t = r.text();
        int from = 0;
        for (Range g : ranges) {
            int s = Math.max(g.start() - base, from);
            int e = Math.min(g.end() - base, t.length());
            if (s >= e) continue;
            if (s > from) out.add(r.with(t.substring(from, s)));
            out.add(new Run(t.substring(s, e), r.style() | ACCENT, r.link()));
            from = e;
        }
        if (from < t.length()) out.add(r.with(t.substring(from)));
    }

    /** 最后一段末尾接一小段原样的字(打字机的光标);没有段落就自己成一段。 */
    public Doc withTail(String tail) {
        List<Block> out = new ArrayList<>(blocks);
        int last = out.size() - 1;
        if (last >= 0 && out.get(last) instanceof Para p) {
            List<Run> runs = new ArrayList<>(p.runs());
            runs.add(Run.of(tail));
            out.set(last, new Para(p.prefix(), p.marked(), runs));
        } else {
            out.add(new Para("", false, List.of(Run.of(tail))));
        }
        return new Doc(out);
    }
}
