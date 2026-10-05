package com.dwinovo.numen.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 一段可编辑文字折成几行的几何:每一行是原文里的一段 {@code [start, end)}(不含换行符),下标与原文一一对应,
 * 光标、点击、上下移动都照它换算。多行输入框({@code MultilineTextField})与聊天输入框({@code TextField}
 * 的多行模式)用的是同一份。
 *
 * <p>折法:换行符是硬行;一个硬行按宽度切成软行,空格优先断(压线的空格随行吞掉,词不劈两半),整行没有空格
 * (CJK、长词)逐字断。与 {@link TextWrap} 的区别:那个只出显示用的字串,这个要保留下标,所以断行的空格留在行里。
 * 纯 JVM——宽度函数由调用方注入;不给宽度函数(还没有画布可量)就只按换行符分行。
 */
public final class WrappedText {

    private final CharSequence text;
    private final ToIntFunction<String> width;
    private final List<int[]> lines;

    private WrappedText(CharSequence text, ToIntFunction<String> width, List<int[]> lines) {
        this.text = text;
        this.width = width;
        this.lines = lines;
    }

    /**
     * @param maxWidth 一行最多多宽;{@code <= 0} 或 {@code width == null} = 不折软行
     */
    public static WrappedText of(CharSequence text, int maxWidth, ToIntFunction<String> width) {
        List<int[]> out = new ArrayList<>();
        int len = text.length();
        int hardStart = 0;
        for (int i = 0; i <= len; i++) {
            if (i == len || text.charAt(i) == '\n') {
                wrapHardLine(text, hardStart, i, maxWidth, width, out);
                hardStart = i + 1;
            }
        }
        return new WrappedText(text, width, out);
    }

    /** 一个硬行(无换行符)按宽度切成若干软行:空格优先断行,CJK 逐字断。 */
    private static void wrapHardLine(CharSequence text, int start, int end, int maxWidth,
                                     ToIntFunction<String> width, List<int[]> out) {
        if (width == null || maxWidth <= 0 || start >= end) {
            out.add(new int[]{start, end});
            return;
        }
        int lineStart = start;
        int used = 0;
        int lastSpace = -1;
        int i = start;
        while (i < end) {
            char ch = text.charAt(i);
            int cw = width.applyAsInt(String.valueOf(ch));
            if (used + cw > maxWidth && i > lineStart) {
                int cut;
                if (ch == ' ') {
                    cut = i + 1;              // 压线的空格随行吞掉(行尾空格无需显示宽度)
                    i = cut;
                } else if (lastSpace > lineStart) {
                    cut = lastSpace + 1;      // 回退到最近空格后断,词不劈两半
                    i = cut;
                } else {
                    cut = i;                  // 整行无空格(CJK/长词):逐字断
                }
                out.add(new int[]{lineStart, cut});
                lineStart = cut;
                used = 0;
                lastSpace = -1;
                continue;                     // 回退过的字符从零重新累计宽度
            }
            if (ch == ' ') lastSpace = i;
            used += cw;
            i++;
        }
        out.add(new int[]{lineStart, end});
    }

    /** 几行;永远至少一行。 */
    public int lineCount() {
        return lines.size();
    }

    public int start(int line) {
        return lines.get(line)[0];
    }

    public int end(int line) {
        return lines.get(line)[1];
    }

    /** 这一行的字。 */
    public String line(int line) {
        return text.subSequence(start(line), end(line)).toString();
    }

    /** 行尾是硬换行(其后是 \n)? */
    public boolean endsHard(int line) {
        int end = end(line);
        return end < text.length() && text.charAt(end) == '\n';
    }

    /** 行尾是折出来的(下一行接着同一个硬行)? */
    public boolean endsSoft(int line) {
        return line < lines.size() - 1 && !endsHard(line);
    }

    /** 下标在第几行:软换行边界上的光标归下一行行首(通用编辑器行为)。 */
    public int lineOf(int index) {
        for (int i = 0; i < lines.size(); i++) {
            int[] span = lines.get(i);
            if (index < span[1]) return i;
            if (index == span[1] && (i == lines.size() - 1 || endsHard(i))) return i;
        }
        return lines.size() - 1;
    }

    /** 原文 {@code [from, to)} 画出来多宽;没有宽度函数时是 0。 */
    public int widthOf(int from, int to) {
        if (width == null || from >= to) return 0;
        return width.applyAsInt(text.subSequence(from, to).toString());
    }

    /** 下标离它那一行行首多远(px)。 */
    public int xOf(int index) {
        return widthOf(start(lineOf(index)), index);
    }

    /** 行内横坐标(px,相对行首)→ 下标:落在字符前半归左,后半归右;没有宽度函数时落在行尾。 */
    public int indexAtX(int line, int px) {
        int[] span = lines.get(line);
        if (width == null) return span[1];
        int used = 0;
        for (int i = span[0]; i < span[1]; i++) {
            int cw = width.applyAsInt(String.valueOf(text.charAt(i)));
            if (px < used + cw / 2) return i;
            used += cw;
        }
        return span[1];
    }
}
