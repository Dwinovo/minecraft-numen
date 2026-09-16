package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图标是像素稿:该实的实,实的外沿补一圈暗边,别处一个像素都不画。 */
class NumenIconsTest {

    /** 记下每一格画成了什么颜色。 */
    private static final class Pixels implements IDrawSurface {
        final Map<Long, Integer> at = new HashMap<>();

        @Override public void fillRect(int x, int y, int w, int h, int argb) {
            assertEquals(1, w, "图标一次只画一格");
            assertEquals(1, h, "图标一次只画一格");
            at.put(((long) x << 32) | (y & 0xFFFFFFFFL), argb);
        }
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) {}
        @Override public int textWidth(String t) { return 0; }
        @Override public int lineHeight() { return 9; }
        @Override public void pushScissor(int x, int y, int w, int h) {}
        @Override public void popScissor() {}

        Integer at(int x, int y) { return at.get(((long) x << 32) | (y & 0xFFFFFFFFL)); }
    }

    @Test
    void everyIconIsSquareSoOneNumberPlacesIt() {
        for (boolean[][] icon : new boolean[][][]{
                NumenIcons.PENCIL, NumenIcons.TRASH, NumenIcons.COPY}) {
            int n = NumenIcons.size(icon);
            assertEquals(n, icon.length);
            for (boolean[] column : icon) {
                assertEquals(n, column.length, "稿子得是正方的,不然摆不准");
            }
        }
    }

    @Test
    void theShapeTakesTheGivenColourAndItsRimTakesTheDarkOne() {
        Pixels p = new Pixels();
        NumenIcons.draw(p, NumenIcons.COPY, 100, 200, 0xFFAABBCC);

        // 复制图标:前一张纸的左上角(稿子里 x5,y5)是实的
        assertEquals(0xFFAABBCC, p.at(105, 205), "实格用调用者给的颜色");
        // 两张纸中间那一列(x4,y5)是空的,但挨着实格 → 暗边
        Integer seam = p.at(104, 205);
        assertTrue(seam != null && seam != 0xFFAABBCC, "纸与纸之间那一格是暗边,不是图形色");
    }

    @Test
    void nothingIsPaintedWhereTheShapeIsFarAway() {
        Pixels p = new Pixels();
        NumenIcons.draw(p, NumenIcons.COPY, 0, 0, 0xFFFFFFFF);

        int n = NumenIcons.size(NumenIcons.COPY);
        assertNull(p.at(0, n - 1), "左下角离图形两格远,一个像素都不画");
        assertNull(p.at(n - 1, 0), "右上角同理");
        for (var e : p.at.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int y = (int) (long) e.getKey();
            assertTrue(x >= 0 && x < n && y >= 0 && y < n, "画出来的格子都落在稿子那一方里");
        }
    }
}
