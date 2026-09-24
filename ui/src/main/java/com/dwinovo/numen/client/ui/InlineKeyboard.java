package com.dwinovo.numen.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 内联键盘的排法(Telegram 消息下面挂的那排按钮,{@code ReplyKeyboard::resize}):一行里的键等宽、键与键隔一道缝,
 * 一行放不下就换行,各行的键数尽量一样多(四个键放不下一行时是两行两个,不是三个加一个)。
 * 纯几何:宿主给每个键上那行字的宽与键盘的宽,回每个键的矩形(相对键盘左上角),画和点都照它。
 */
public final class InlineKeyboard {

    private InlineKeyboard() {}

    /** 一个键占的那块;{@code x}、{@code y} 相对键盘左上角。 */
    public record Key(int x, int y, int w, int h) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /**
     * 全部键排成一行要多宽:每个键都按最宽的那行字加两边留白算(等宽),再加键间的缝。
     *
     * @param pad  字到键边的留白(一边)
     * @param skip 键与键、行与行之间的缝
     */
    public static int naturalWidth(int[] textWidths, int pad, int skip) {
        int n = textWidths.length;
        return n == 0 ? 0 : n * (widest(textWidths) + pad * 2) + (n - 1) * skip;
    }

    /**
     * 排成几行、每个键在哪。一行能放几个按最宽的字算;放不下全部就分几行,再把键平摊到各行。
     * 一行里的键平分这一行的宽(Telegram 按浮点累加再取整,缝不会越排越歪)。
     */
    public static List<Key> layout(int[] textWidths, int width, int pad, int skip, int keyH) {
        int n = textWidths.length;
        List<Key> out = new ArrayList<>(n);
        if (n == 0) return out;
        int fit = Math.max(1, Math.min(n, (width + skip) / (widest(textWidths) + pad * 2 + skip)));
        int rows = (n + fit - 1) / fit;
        int perRow = (n + rows - 1) / rows;
        int y = 0;
        for (int first = 0; first < n; first += perRow) {
            int k = Math.min(perRow, n - first);
            double each = (width - (k - 1) * skip) / (double) k;
            double x = 0;
            for (int i = 0; i < k; i++) {
                int left = (int) Math.floor(x);
                out.add(new Key(left, y, (int) Math.floor(x + each) - left, keyH));
                x += each + skip;
            }
            y += keyH + skip;
        }
        return out;
    }

    /** 排出来的键盘多高。 */
    public static int height(List<Key> keys) {
        int h = 0;
        for (Key k : keys) h = Math.max(h, k.y() + k.h());
        return h;
    }

    private static int widest(int[] textWidths) {
        int w = 1;
        for (int t : textWidths) w = Math.max(w, t);
        return w;
    }
}
