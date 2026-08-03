package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.KeyCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 根节点路由契约:焦点转移、后加者在上、浮层优先与外点关闭、ESC。 */
class UiRootTest {

    @Test
    void clickMovesFocusBetweenFieldsAndBlankClears() {
        UiRoot root = new UiRoot();
        TextField a = root.add(new TextField("", s -> {}));
        a.setBounds(0, 0, 50, 14);
        TextField b = root.add(new TextField("", s -> {}));
        b.setBounds(0, 20, 50, 14);

        root.mouseClicked(5, 5, 0);
        assertTrue(a.isFocused());
        root.mouseClicked(5, 25, 0);
        assertFalse(a.isFocused());
        assertTrue(b.isFocused());
        root.mouseClicked(200, 200, 0);
        assertNull(root.focusedWidget());
    }

    @Test
    void keysRouteOnlyToFocusedWidget() {
        UiRoot root = new UiRoot();
        TextField a = root.add(new TextField("a", s -> {}));
        a.setBounds(0, 0, 50, 14);
        TextField b = root.add(new TextField("b", s -> {}));
        b.setBounds(0, 20, 50, 14);
        root.mouseClicked(5, 5, 0);

        root.charTyped('x');
        assertEquals("ax", a.value());
        assertEquals("b", b.value());
    }

    @Test
    void laterWidgetIsOnTopForOverlappingClicks() {
        UiRoot root = new UiRoot();
        AtomicInteger under = new AtomicInteger(), over = new AtomicInteger();
        Button bottom = root.add(new Button("under", Button.Style.NORMAL, under::incrementAndGet));
        bottom.setBounds(0, 0, 50, 14);
        Button top = root.add(new Button("over", Button.Style.NORMAL, over::incrementAndGet));
        top.setBounds(0, 0, 50, 14);

        root.mouseClicked(5, 5, 0);
        assertEquals(0, under.get());
        assertEquals(1, over.get());
    }

    @Test
    void overlayEatsOutsideClickAndCloses() {
        UiRoot root = new UiRoot();
        Dropdown dd = root.add(new Dropdown(List.of("x", "y"), 0, i -> {}));
        dd.setBounds(0, 0, 60, 14);
        AtomicInteger clicked = new AtomicInteger();
        Button other = root.add(new Button("btn", Button.Style.NORMAL, clicked::incrementAndGet));
        other.setBounds(0, 100, 50, 14);

        root.mouseClicked(5, 5, 0);          // 打开下拉
        assertTrue(dd.isOpen());
        assertTrue(root.hasOverlay());

        root.mouseClicked(5, 105, 0);        // 点浮层外:只关浮层,按钮不触发
        assertFalse(dd.isOpen());
        assertFalse(root.hasOverlay());
        assertEquals(0, clicked.get());

        root.mouseClicked(5, 105, 0);        // 浮层已关:按钮正常触发
        assertEquals(1, clicked.get());
    }

    @Test
    void escapeClosesOverlay() {
        UiRoot root = new UiRoot();
        Dropdown dd = root.add(new Dropdown(List.of("x"), 0, i -> {}));
        dd.setBounds(0, 0, 60, 14);
        root.mouseClicked(5, 5, 0);
        assertTrue(root.hasOverlay());
        assertTrue(root.keyPressed(KeyCodes.ESCAPE, 0));
        assertFalse(root.hasOverlay());
        assertFalse(dd.isOpen());
    }

    @Test
    void focusLostFiresOnBlur() {
        UiRoot root = new UiRoot();
        AtomicInteger lost = new AtomicInteger();
        Widget a = root.add(new Widget() {
            @Override public boolean focusable() { return true; }
            @Override public void onFocusLost() { lost.incrementAndGet(); }
            @Override public void render(com.dwinovo.numen.client.ui.IDrawSurface s,
                    com.dwinovo.numen.client.ui.NumenTheme.Colors c,
                    int mouseX, int mouseY, long nowMs) {}
        });
        a.setBounds(0, 0, 50, 14);
        root.mouseClicked(5, 5, 0);
        root.mouseClicked(200, 200, 0);
        assertEquals(1, lost.get());
    }
}
