package com.dwinovo.numen.api.client.ui.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正文里没写成 Markdown 链接的 http/https 网址在哪(Telegram 把正文里的网址自动认成链接)。
 *
 * <p>网址到空白、引号尖括号、中文标点为止;末尾的英文句读(句号、逗号、问号……)算句子的不算网址,
 * 右括号只有网址里有对应的左括号才留下——"(见 https://a.com/x)"里的右括号是句子的。
 *
 * <p>纯逻辑,不碰 Minecraft;CommonMark 本身不认裸网址,所以这一步归适配器({@link Markdown}),
 * 它是正文里认"这是链接"的唯一一处裸网址判据。
 */
final class BareLinks {

    /** 一段链接:{@code [start, end)} 是它在原文里的位置,{@code url} 就是那一段。 */
    record Span(int start, int end, String url) {}

    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s<>\"'`，。、；：！？（）《》「」『』【】…]+", Pattern.CASE_INSENSITIVE);

    private BareLinks() {}

    static List<Span> find(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = URL.matcher(text);
        while (m.find()) {
            int end = trimmedEnd(text, m.start(), m.end());
            // 只剩"https://"本身不是网址
            if (text.indexOf("://", m.start()) + 3 < end) out.add(new Span(m.start(), end, text.substring(m.start(), end)));
        }
        return out;
    }

    /** 这个目的地能不能当链接点开:只认 http/https。 */
    static boolean opens(String destination) {
        return destination != null && URL.matcher(destination).lookingAt();
    }

    private static int trimmedEnd(String text, int start, int end) {
        while (end > start) {
            char c = text.charAt(end - 1);
            if (".,;:!?".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && count(text, start, end, '(') < count(text, start, end, ')')) {
                end--;
            } else if (c == ']' && count(text, start, end, '[') < count(text, start, end, ']')) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(String s, int from, int to, char c) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }
}
