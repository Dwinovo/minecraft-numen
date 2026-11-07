package com.dwinovo.numen.api.client.ui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 下拉弹层的摆法:往哪边开、多高、多宽。 */
class PopupPlacementTest {

    private static final int ROW = 13;
    private static final UiRoot.Viewport VIEW = new UiRoot.Viewport(0, 0, 300, 200);

    @Test
    void opensDownWhenItFitsBelow() {
        PopupPlacement p = PopupPlacement.place(20, 18, 10, 80, 4 * ROW, ROW, VIEW);
        assertFalse(p.up());
        assertEquals(38, p.y());
        assertEquals(4 * ROW, p.h());
    }

    @Test
    void opensUpWhenBelowIsShortButAboveFits() {
        PopupPlacement p = PopupPlacement.place(150, 18, 10, 80, 8 * ROW, ROW, VIEW);
        assertTrue(p.up());
        assertEquals(8 * ROW, p.h());
        assertEquals(150 - 8 * ROW, p.y(), "贴着字段上沿,底边对着字段顶边");
    }

    @Test
    void whenNeitherSideFitsTakesTheRoomierSideAndScrollsInside() {
        UiRoot.Viewport v = new UiRoot.Viewport(0, 0, 300, 100);
        // 下面 42、上面 40:下面更宽敞,3 整行(39)
        PopupPlacement down = PopupPlacement.place(40, 18, 10, 80, 8 * ROW, ROW, v);
        assertFalse(down.up());
        assertEquals(3 * ROW, down.h());
        // 下面 22、上面 60:往上,4 整行(52)
        PopupPlacement up = PopupPlacement.place(60, 18, 10, 80, 8 * ROW, ROW, v);
        assertTrue(up.up());
        assertEquals(4 * ROW, up.h());
        assertTrue(up.y() >= 0 && down.y() + down.h() <= 100);
    }

    @Test
    void neverTallerThanTheRoomItTakes() {
        UiRoot.Viewport v = new UiRoot.Viewport(0, 30, 300, 100);
        PopupPlacement p = PopupPlacement.place(50, 18, 10, 80, 8 * ROW, ROW, v);
        assertTrue(p.y() >= 30 && p.y() + p.h() <= 100);
        // 一行都放不下时只露得下的那一截,不越界
        PopupPlacement tight = PopupPlacement.place(92, 4, 10, 80, 8 * ROW, ROW, new UiRoot.Viewport(0, 90, 300, 100));
        assertTrue(tight.y() + tight.h() <= 100 && tight.h() <= 4);
    }

    @Test
    void narrowWindowClampsWidthAndPushesLeftEdgeBackIn() {
        UiRoot.Viewport narrow = new UiRoot.Viewport(0, 0, 100, 200);
        PopupPlacement wide = PopupPlacement.place(20, 18, 40, 160, 2 * ROW, ROW, narrow);
        assertEquals(100, wide.w());
        assertEquals(0, wide.x());
        PopupPlacement shifted = PopupPlacement.place(20, 18, 80, 60, 2 * ROW, ROW, narrow);
        assertEquals(60, shifted.w());
        assertEquals(40, shifted.x(), "右缘贴着窗口右缘");
        PopupPlacement offset = PopupPlacement.place(20, 18, 10, 60, 2 * ROW, ROW, new UiRoot.Viewport(50, 0, 300, 200));
        assertEquals(50, offset.x());
    }

    @Test
    void viewportClipIsThePanelWhereItLiesInsideTheScreen() {
        assertEquals(new UiRoot.Viewport(10, 20, 110, 220), UiRoot.Viewport.clip(10, 20, 100, 200, 400, 300));
    }

    @Test
    void viewportClipStopsAtTheScreenEdgesWhenThePanelOverhangs() {
        // 窗口缩得比面板小:左、上从屏幕边起,右、下止于屏幕边
        assertEquals(new UiRoot.Viewport(0, 0, 284, 160), UiRoot.Viewport.clip(-20, -36, 380, 232, 284, 160));
    }

    @Test
    void unboundedViewportKeepsTheWantedBox() {
        PopupPlacement p = PopupPlacement.place(500, 18, 10, 80, 8 * ROW, ROW, UiRoot.Viewport.UNBOUNDED);
        assertFalse(p.up());
        assertEquals(8 * ROW, p.h());
        assertEquals(80, p.w());
    }
}
