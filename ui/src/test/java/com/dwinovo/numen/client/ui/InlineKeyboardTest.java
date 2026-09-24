package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内联键盘:一行等宽、放不下换行、各行平摊、缝不越排越歪。 */
class InlineKeyboardTest {

    /** 四个键,最宽的字 70;留白 8、缝 2 → 每个键至少 86 宽。 */
    private static final int[] LABELS = {30, 60, 30, 70};

    @Test
    void wideEnoughPutsEveryKeyInOneEqualRow() {
        List<InlineKeyboard.Key> keys = InlineKeyboard.layout(LABELS, 400, 8, 2, 16);
        assertEquals(4, keys.size());
        for (InlineKeyboard.Key k : keys) {
            assertEquals(0, k.y());
            assertTrue(Math.abs(k.w() - keys.get(0).w()) <= 1, "一行里的键等宽: " + keys);
        }
        InlineKeyboard.Key last = keys.get(3);
        assertEquals(400, last.x() + last.w(), "最后一个键贴着右缘");
        assertEquals(16, InlineKeyboard.height(keys));
    }

    @Test
    void naturalWidthIsOneRowOfWidestKeys() {
        assertEquals(4 * 86 + 3 * 2, InlineKeyboard.naturalWidth(LABELS, 8, 2));
        assertEquals(4, InlineKeyboard.layout(LABELS, InlineKeyboard.naturalWidth(LABELS, 8, 2), 8, 2, 16)
                .stream().filter(k -> k.y() == 0).count(), "正好这么宽时一行放得下");
    }

    @Test
    void threeFitButRowsAreBalancedTwoAndTwo() {
        List<InlineKeyboard.Key> keys = InlineKeyboard.layout(LABELS, 270, 8, 2, 16);
        assertEquals(0, keys.get(1).y());
        assertEquals(18, keys.get(2).y(), "第三个键换到第二行");
        assertEquals(18, keys.get(3).y());
        assertEquals(34, InlineKeyboard.height(keys));
        assertEquals(keys.get(0).w(), keys.get(2).w(), "两行一样宽");
    }

    @Test
    void tooNarrowStacksOnePerRow() {
        List<InlineKeyboard.Key> keys = InlineKeyboard.layout(LABELS, 80, 8, 2, 16);
        for (int i = 0; i < 4; i++) {
            assertEquals(i * 18, keys.get(i).y());
            assertEquals(80, keys.get(i).w());
        }
    }

    @Test
    void keyHitTestIsHalfOpen() {
        InlineKeyboard.Key k = new InlineKeyboard.Key(10, 0, 20, 16);
        assertTrue(k.contains(10, 0));
        assertTrue(!k.contains(30, 5), "右缘不算");
        assertTrue(!k.contains(15, 16), "底边不算");
    }
}
