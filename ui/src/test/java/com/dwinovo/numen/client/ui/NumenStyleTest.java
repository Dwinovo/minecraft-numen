package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 框只有一种画法:方角,一圈 1 像素描边在下,内衬底压在上面。 */
class NumenStyleTest {

    private static final class Recorder implements IDrawSurface {
        final List<int[]> rects = new ArrayList<>();

        @Override public void fillRect(int x, int y, int w, int h, int argb) {
            rects.add(new int[]{x, y, w, h, argb});
        }
        @Override public void drawText(String text, int x, int y, int argb, boolean shadow) {}
        @Override public int textWidth(String text) { return 0; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) {}
        @Override public void popScissor() {}
    }

    @Test
    void aBoxIsASquareRingUnderItsFill() {
        Recorder s = new Recorder();
        NumenStyle.box(s, 10, 20, 100, 18, 0xFF111111, 0xFFEEEEEE);

        assertEquals(2, s.rects.size(), "描边一笔、内衬一笔,没有别的形状");
        assertArrayEquals(new int[]{10, 20, 100, 18, 0xFFEEEEEE}, s.rects.get(0), "先铺满描边色");
        assertArrayEquals(new int[]{11, 21, 98, 16, 0xFF111111}, s.rects.get(1), "内缩 1 像素压上底色,露出一圈描边");
    }
}
