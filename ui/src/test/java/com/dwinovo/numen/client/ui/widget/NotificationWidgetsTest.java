package com.dwinovo.numen.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示栈组件:TextField 内联错误(设错/输入即清)、InlineAlert 驻留语义、
 * ConfirmDialog 模态契约(卡外吞点击/确认执行/取消不执行/ESC=取消)。
 */
class NotificationWidgetsTest {

    // ---- TextField 内联错误 ----

    @Test
    void errorShowsInlineAndClearsOnTyping() {
        UiRoot root = new UiRoot();
        TextField f = root.add(new TextField("", s -> {}));
        f.setBounds(0, 20, 100, 13);
        root.mouseClicked(5, 25, 0);

        f.setError("必填");
        assertTrue(f.hasError());
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        f.render(s, WidgetTestSupport.C, 0, 0, 0);
        assertTrue(s.texts.contains("必填"), "错误文案应内联渲染");

        f.charTyped('a');   // 用户开始修改 → 错误撤下
        assertFalse(f.hasError());
    }

    @Test
    void setValueDoesNotClearError() {
        // 程序性赋值不算"用户开始修改"。
        TextField f = new TextField("", s -> {});
        f.setError("x");
        f.setValue("prog");
        assertTrue(f.hasError());
    }

    // ---- InlineAlert 驻留 ----

    @Test
    void alertPersistsUntilReplacedOrCleared() {
        InlineAlert alert = new InlineAlert();
        alert.setBounds(0, 0, 120, 30);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();

        assertFalse(alert.isShowing());
        alert.show(InlineAlert.Severity.ERROR, "密钥无效");
        for (int t = 0; t < 100_000; t += 5_000) {   // 远超任何 toast 时长
            s.reset();
            alert.render(s, WidgetTestSupport.C, 0, 0, t);
        }
        assertTrue(s.texts.contains("密钥无效"), "驻留条不许自动消失");

        alert.show(InlineAlert.Severity.SUCCESS, "连接成功");
        s.reset();
        alert.render(s, WidgetTestSupport.C, 0, 0, 200_000);
        s.reset();
        alert.render(s, WidgetTestSupport.C, 0, 0, 200_500);
        assertTrue(s.texts.contains("连接成功"));
        assertFalse(s.texts.contains("密钥无效"), "新结果替换旧结果");

        alert.clear();
        s.reset();
        alert.render(s, WidgetTestSupport.C, 0, 0, 300_000);
        assertTrue(s.texts.isEmpty());
    }

    @Test
    void successWithAutoDismissFadesOutAndClears() {
        InlineAlert alert = new InlineAlert();
        alert.setBounds(0, 0, 120, 24);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        alert.show(InlineAlert.Severity.SUCCESS, "连接成功", 2_500);
        alert.render(s, WidgetTestSupport.C, 0, 0, 0);          // 首帧定排版
        s.reset();
        alert.render(s, WidgetTestSupport.C, 0, 0, 1_000);       // 显示期
        assertTrue(s.texts.contains("连接成功"));
        alert.render(s, WidgetTestSupport.C, 0, 0, 2_500 + 300); // 淡出结束
        assertFalse(alert.isShowing(), "到时自动清场");
    }

    // ---- ConfirmDialog 模态契约 ----

    private static class Fixture {
        final UiRoot root = new UiRoot();
        final ConfirmDialog dialog = new ConfirmDialog();
        final AtomicInteger confirmed = new AtomicInteger();
        final AtomicInteger background = new AtomicInteger();

        Fixture() {
            Button bg = root.add(new Button("bg", Button.Style.NORMAL, background::incrementAndGet));
            bg.setBounds(0, 0, 40, 14);
            dialog.open(root, 0, 0, 200, 150, "删除这份配置?", "取消", "删除",
                    confirmed::incrementAndGet);
            // 渲染一帧,缓存按钮几何供命中判定
            root.render(new WidgetTestSupport.FakeSurface(), WidgetTestSupport.C, 0, 0, 0);
        }
    }

    @Test
    void outsideClickIsSwallowedNotClosedNotPassedThrough() {
        Fixture fx = new Fixture();
        assertTrue(fx.root.mouseClicked(5, 5, 0));      // 落在背景按钮上
        assertEquals(0, fx.background.get(), "模态下背景不可触");
        assertTrue(fx.root.hasOverlay(), "卡外点击不关闭(危险操作不给误触留门)");
    }

    // 定点几何(与 ConfirmDialog 布局常数同步):dim 200×150,卡 190 宽居中
    // → cardX=5;单行文案 cardH=44 → cardY=53,buttonY=77;
    // confirmX=137,cancelX=79。常数变了这里跟着变——测试即几何文档。
    private static final int BTN_ROW_Y = 82;
    private static final int CONFIRM_CX = 142;
    private static final int CANCEL_CX = 84;

    @Test
    void confirmRunsActionAndCloses() {
        Fixture fx = new Fixture();
        assertTrue(fx.root.mouseClicked(CONFIRM_CX, BTN_ROW_Y, 0));
        assertFalse(fx.root.hasOverlay(), "确认后关闭");
        assertEquals(1, fx.confirmed.get());
    }

    @Test
    void cancelClosesWithoutAction() {
        Fixture fx = new Fixture();
        assertTrue(fx.root.mouseClicked(CANCEL_CX, BTN_ROW_Y, 0));
        assertFalse(fx.root.hasOverlay());
        assertEquals(0, fx.confirmed.get(), "取消不执行动作");
    }

    @Test
    void escapeCancelsWithoutAction() {
        Fixture fx = new Fixture();
        assertTrue(fx.root.keyPressed(com.dwinovo.numen.client.ui.KeyCodes.ESCAPE, 0));
        assertFalse(fx.root.hasOverlay());
        assertEquals(0, fx.confirmed.get(), "ESC=取消,不执行动作");
    }
}
