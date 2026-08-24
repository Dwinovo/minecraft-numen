package com.dwinovo.numen.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 贪心折行:优先在空格断(拉丁词),无空格可断时按字符断(CJK 与超长词)。
 * 纯 JVM——宽度函数由调用方注入(游戏里是字体测量,测试里是假度量)。
 */
public final class TextWrap {

    private TextWrap() {}

    /**
     * @param maxLines 超出的行数丢弃,末行加省略号;≤0 = 不限行数
     */
    public static List<String> wrap(String text, int maxWidth,
                                    ToIntFunction<String> widthFn, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(paragraph, maxWidth, widthFn, out);
        }
        if (maxLines > 0 && out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            String last = cut.get(maxLines - 1);
            while (!last.isEmpty() && widthFn.applyAsInt(last + "...") > maxWidth) {
                last = last.substring(0, last.length() - 1);
            }
            cut.set(maxLines - 1, last + "...");
            return cut;
        }
        return out;
    }

    private static void wrapParagraph(String text, int maxWidth,
                                      ToIntFunction<String> widthFn, List<String> out) {
        int lineStart = 0;
        int lastSpace = -1;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == ' ') lastSpace = i;
            String candidate = text.substring(lineStart, i + 1);
            if (widthFn.applyAsInt(candidate) > maxWidth && i > lineStart) {
                if (lastSpace > lineStart) {
                    out.add(text.substring(lineStart, lastSpace));
                    lineStart = lastSpace + 1;   // 吃掉断行空格
                } else {
                    out.add(text.substring(lineStart, i)); // 无空格:字符级硬断(CJK/长词)
                    lineStart = i;
                }
                lastSpace = -1;
                continue;   // 当前字符归入下一行重新度量
            }
            i++;
        }
        if (lineStart < text.length()) {
            out.add(text.substring(lineStart));
        } else if (text.isEmpty()) {
            out.add("");
        }
    }
}
