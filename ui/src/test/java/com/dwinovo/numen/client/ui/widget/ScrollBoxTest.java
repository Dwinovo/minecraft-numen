package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滚动只挪 y:布局账算一次,基线不动,超出两端不再走。 */
class ScrollBoxTest {

    private static final class Dot extends Widget {
        @Override public void render(IDrawSurface s, NumenTheme.Colors c, int mx, int my, long t) {}
    }

    private static UiRoot rootWith(int... ys) {
        UiRoot ui = new UiRoot();
        for (int y : ys) {
            ui.add(new Dot()).setBounds(10, y, 100, 18);
        }
        return ui;
    }

    @Test
    void contentThatFitsNeitherScrollsNorTakesTheWheel() {
        UiRoot ui = rootWith(20, 40);
        ScrollBox box = new ScrollBox();
        box.measure(ui, 10, 20, 100, 80, 38);

        assertEquals(0, box.max(), "装得下就没得滚");
        assertFalse(box.scrolled(30, -1), "装得下时滚轮不归它,留给外面");
        assertEquals(20, ui.widgetsView().get(0).y(), "行留在原处");
    }

    @Test
    void wheelMovesEveryRowByTheSameOffsetAndStopsAtBothEnds() {
        UiRoot ui = rootWith(20, 60, 100);
        ScrollBox box = new ScrollBox();
        box.measure(ui, 10, 20, 100, 50, 98);   // 内容 98,视口 50 → 最多滚 48

        assertEquals(48, box.max());
        assertTrue(box.scrolled(30, -1), "落在视口里且滚得动");
        assertEquals(14, box.offset(), "一格滚轮走一个固定步长");
        assertEquals(20 - 14, ui.widgetsView().get(0).y());
        assertEquals(100 - 14, ui.widgetsView().get(2).y(), "每一行挪的是同一个量");

        for (int i = 0; i < 10; i++) box.scrolled(30, -1);
        assertEquals(48, box.offset(), "到底了不再往下走");

        for (int i = 0; i < 10; i++) box.scrolled(30, 1);
        assertEquals(0, box.offset(), "到顶了不再往上走");
        assertEquals(20, ui.widgetsView().get(0).y(), "回到基线,不是回到某个近似值");
    }

    @Test
    void theWheelOnlyCountsInsideTheViewport() {
        UiRoot ui = rootWith(20, 60, 100);
        ScrollBox box = new ScrollBox();
        box.measure(ui, 10, 20, 100, 50, 98);

        assertFalse(box.inside(19));
        assertTrue(box.inside(20));
        assertTrue(box.inside(69));
        assertFalse(box.inside(70));
        assertFalse(box.scrolled(80, -1), "视口外的滚轮不归它");
        assertEquals(0, box.offset());
    }

    @Test
    void remeasuringShorterContentPullsTheOffsetBackInRange() {
        UiRoot ui = rootWith(20, 60, 100);
        ScrollBox box = new ScrollBox();
        box.measure(ui, 10, 20, 100, 50, 98);
        for (int i = 0; i < 10; i++) box.scrolled(30, -1);
        assertEquals(48, box.offset());

        // 折起一节:内容变矮,原来的偏移量已经越界——重新量的时候就得收回来
        UiRoot shorter = rootWith(20, 60);
        box.measure(shorter, 10, 20, 100, 50, 58);
        assertEquals(8, box.max());
        assertEquals(8, box.offset(), "偏移量按新内容收口");
        assertEquals(20 - 8, shorter.widgetsView().get(0).y());
    }
}
