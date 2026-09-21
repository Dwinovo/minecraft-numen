package com.dwinovo.numen.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多选卡契约:勾行、确认给出勾上的下标、没勾时确认不动、取消/卡外/ESC 都不执行。 */
class PickDialogTest {

    private static class Fixture {
        final UiRoot root = new UiRoot();
        final PickDialog dialog = new PickDialog();
        final AtomicReference<List<Integer>> confirmed = new AtomicReference<>();

        Fixture() {
            dialog.open(root, 0, 0, 200, 150, "拉进这个会话", List.of("阿岚", "小满"),
                    "取消", "拉进来", confirmed::set);
            // 渲染一帧,缓存行与按钮几何供命中判定
            root.render(new WidgetTestSupport.FakeSurface(), WidgetTestSupport.C, 0, 0, 0);
        }
    }

    // 定点几何(与 PickDialog 布局常数同步):dim 200×150,卡 190 宽居中 → cardX=5;
    // 两行时 cardH=74 → cardY=38;行起点 rowsY=57、行高 13;buttonY=92;confirmX=137,cancelX=79。
    // 常数变了这里跟着变——测试即几何文档。
    private static final int ROW0_Y = 60;
    private static final int ROW1_Y = 73;
    private static final int ROW_X = 20;
    private static final int BTN_ROW_Y = 97;
    private static final int CONFIRM_CX = 142;
    private static final int CANCEL_CX = 84;

    @Test
    void clickingRowsTogglesThem() {
        Fixture fx = new Fixture();
        assertTrue(fx.root.mouseClicked(ROW_X, ROW0_Y, 0));
        assertEquals(List.of(0), fx.dialog.pickedIndices());
        assertTrue(fx.root.mouseClicked(ROW_X, ROW1_Y, 0));
        assertEquals(List.of(0, 1), fx.dialog.pickedIndices());
        assertTrue(fx.root.mouseClicked(ROW_X, ROW0_Y, 0));
        assertEquals(List.of(1), fx.dialog.pickedIndices(), "再点一次取消勾选");
    }

    @Test
    void confirmHandsOverThePickedIndicesAndCloses() {
        Fixture fx = new Fixture();
        fx.root.mouseClicked(ROW_X, ROW1_Y, 0);
        assertTrue(fx.root.mouseClicked(CONFIRM_CX, BTN_ROW_Y, 0));
        assertFalse(fx.root.hasOverlay(), "确认后关闭");
        assertEquals(List.of(1), fx.confirmed.get());
    }

    @Test
    void confirmWithNothingPickedDoesNothing() {
        Fixture fx = new Fixture();
        assertTrue(fx.root.mouseClicked(CONFIRM_CX, BTN_ROW_Y, 0));
        assertTrue(fx.root.hasOverlay(), "没东西可确认:卡还开着");
        assertNull(fx.confirmed.get());
    }

    @Test
    void cancelClosesWithoutAction() {
        Fixture fx = new Fixture();
        fx.root.mouseClicked(ROW_X, ROW0_Y, 0);
        assertTrue(fx.root.mouseClicked(CANCEL_CX, BTN_ROW_Y, 0));
        assertFalse(fx.root.hasOverlay());
        assertNull(fx.confirmed.get());
    }

    @Test
    void outsideClickClosesWithoutAction() {
        Fixture fx = new Fixture();
        fx.root.mouseClicked(ROW_X, ROW0_Y, 0);
        assertTrue(fx.root.mouseClicked(2, 2, 0), "卡外点击只负责关卡,不下传");
        assertFalse(fx.root.hasOverlay(), "不是危险操作:卡外点击 = 关掉");
        assertNull(fx.confirmed.get());
    }

    @Test
    void escapeClosesWithoutAction() {
        Fixture fx = new Fixture();
        fx.root.mouseClicked(ROW_X, ROW0_Y, 0);
        assertTrue(fx.root.keyPressed(com.dwinovo.numen.client.ui.KeyCodes.ESCAPE, 0));
        assertFalse(fx.root.hasOverlay());
        assertNull(fx.confirmed.get());
    }
}
