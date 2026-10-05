package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内联键盘:行由发消息的一方定、一行等宽贴满、行间隔缝、缝不越排越歪。 */
class InlineKeyboardTest {

    /** 两行两个;最宽的字 70,留白 8、缝 2。 */
    private static final int[][] ROWS = {{30, 60}, {30, 70}};

    @Test
    void keysInARowShareItsWidthEquallyAndReachTheRightEdge() {
        List<InlineKeyboard.Key> keys = InlineKeyboard.layout(ROWS, 201, 2, 16);
        assertEquals(4, keys.size());
        for (int row = 0; row < 2; row++) {
            InlineKeyboard.Key a = keys.get(row * 2), b = keys.get(row * 2 + 1);
            assertEquals(a.y(), b.y(), "同一行");
            assertTrue(Math.abs(a.w() - b.w()) <= 1, "一行里的键等宽: " + keys);
            assertEquals(0, a.x());
            assertEquals(201, b.x() + b.w(), "最后一个键贴着右缘");
            assertEquals(2, b.x() - (a.x() + a.w()), "键间一道缝");
        }
    }

    @Test
    void rowsStackWithAGapBetween() {
        List<InlineKeyboard.Key> keys = InlineKeyboard.layout(ROWS, 200, 2, 16);
        assertEquals(0, keys.get(0).y());
        assertEquals(18, keys.get(2).y(), "第二行在第一行下面隔一道缝");
        assertEquals(34, InlineKeyboard.height(keys));
    }

    @Test
    void naturalWidthIsTheWidestRowOfEqualKeys() {
        assertEquals(2 * 86 + 2, InlineKeyboard.naturalWidth(ROWS, 8, 2));
        assertEquals(3 * 26 + 2 * 2, InlineKeyboard.naturalWidth(new int[][] {{10}, {5, 10, 5}}, 8, 2),
                "按最挤的那一行算");
    }

    @Test
    void aRowOfOneTakesTheWholeWidth() {
        List<InlineKeyboard.Key> keys = InlineKeyboard.layout(new int[][] {{40}}, 120, 2, 16);
        assertEquals(new InlineKeyboard.Key(0, 0, 120, 16), keys.get(0));
    }

    @Test
    void keyHitTestIsHalfOpen() {
        InlineKeyboard.Key k = new InlineKeyboard.Key(10, 0, 20, 16);
        assertTrue(k.contains(10, 0));
        assertTrue(!k.contains(30, 5), "右缘不算");
        assertTrue(!k.contains(15, 16), "底边不算");
    }
}
