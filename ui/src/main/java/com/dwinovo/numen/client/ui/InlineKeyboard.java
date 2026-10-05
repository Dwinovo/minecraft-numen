package com.dwinovo.numen.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 内联键盘的排法(Telegram 消息下面挂的那排按钮,{@code ReplyKeyboard::resize}):哪几个键同一行由发消息的一方定,
 * 一行里的键平分这一行的宽、键与键隔一道缝,行与行也隔一道缝;键盘与消息同宽,字放不下由宿主截短。
 * 纯几何:宿主给每一行各个键上那行字的宽与键盘的宽,回每个键的矩形(相对键盘左上角,按行、按行里的次序排),
 * 画和点都照它。
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
     * 键盘不截字要多宽:最挤的那一行里,每个键都按这一行最宽的字加两边留白算(一行等宽),再加键间的缝。
     *
     * @param rows 每一行各个键上那行字的宽
     * @param pad  字到键边的留白(一边)
     * @param skip 键与键、行与行之间的缝
     */
    public static int naturalWidth(int[][] rows, int pad, int skip) {
        int w = 0;
        for (int[] row : rows) {
            if (row.length == 0) continue;
            w = Math.max(w, row.length * (widest(row) + pad * 2) + (row.length - 1) * skip);
        }
        return w;
    }

    /** 每个键在哪:一行里的键平分这一行的宽(Telegram 按浮点累加再取整,缝不会越排越歪)。 */
    public static List<Key> layout(int[][] rows, int width, int skip, int keyH) {
        List<Key> out = new ArrayList<>();
        int y = 0;
        for (int[] row : rows) {
            int k = row.length;
            if (k == 0) continue;
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
