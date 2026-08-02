package com.dwinovo.numen.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 列表几何:滚动夹紧、可视区间、行命中、只画可视行、选择回调。 */
class ListViewTest {

    private static List<String> items(int n) {
        return IntStream.range(0, n).mapToObj(i -> "item" + i).toList();
    }

    private static ListView<String> list(int n, AtomicInteger selected) {
        ListView<String> lv = new ListView<>(items(n), 10,
                (s, c, item, index, rx, ry, rw, rh, sel, hov) -> s.drawText(item, rx, ry, 0, false),
                selected::set);
        lv.setBounds(0, 0, 100, 50);   // 视口 5 行
        return lv;
    }

    @Test
    void scrollClampsAtBothEnds() {
        ListView<String> lv = list(20, new AtomicInteger());
        assertEquals(150, lv.maxScroll());   // 20*10 - 50
        lv.mouseScrolled(5, 5, 1);           // 向上滚在顶端:不越界
        assertEquals(0, lv.scrollY());
        for (int i = 0; i < 100; i++) lv.mouseScrolled(5, 5, -1);
        assertEquals(150, lv.scrollY());
    }

    @Test
    void onlyVisibleRowsAreRendered() {
        ListView<String> lv = list(100, new AtomicInteger());
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        lv.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.size() <= 6, "画了 " + s.texts.size() + " 行(视口只有 5 行)");
        assertEquals(0, s.scissorDepth, "scissor 未配对弹出");
        assertEquals(1, s.maxScissorDepth);
    }

    @Test
    void visibleRangeFollowsScroll() {
        ListView<String> lv = list(20, new AtomicInteger());
        for (int i = 0; i < 3; i++) lv.mouseScrolled(5, 5, -1);   // scrollY=30
        int[] range = lv.visibleRange();
        assertEquals(3, range[0]);
        assertEquals(7, range[1]);
    }

    @Test
    void clickSelectsTheRowUnderMouseAcrossScroll() {
        AtomicInteger selected = new AtomicInteger(-1);
        ListView<String> lv = list(20, selected);
        for (int i = 0; i < 3; i++) lv.mouseScrolled(5, 5, -1);
        lv.mouseClicked(5, 12, 0);   // 视口第 2 行 → 全局第 4 行(下标 4)
        assertEquals(4, selected.get());
        assertEquals(4, lv.selectedIndex());
    }

    @Test
    void setItemsShrinkingClampsScrollAndSelection() {
        ListView<String> lv = list(20, new AtomicInteger());
        lv.select(15);
        for (int i = 0; i < 100; i++) lv.mouseScrolled(5, 5, -1);
        lv.setItems(items(3));
        assertEquals(0, lv.maxScroll());
        assertTrue(lv.scrollY() <= lv.maxScroll());
        assertEquals(-1, lv.selectedIndex());
    }

    @Test
    void shortListHasNoScrollbarAndIgnoresScroll() {
        ListView<String> lv = list(3, new AtomicInteger());
        assertFalse(lv.mouseScrolled(5, 5, -1));
        assertEquals(0, lv.scrollY());
    }
}
