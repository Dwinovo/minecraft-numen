package com.dwinovo.numen.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滑条的值映射/步进吸附与下拉的选择/弹层滚动。 */
class SliderDropdownTest {

    @Test
    void sliderMapsMouseToValueWithStep() {
        AtomicReference<Double> changed = new AtomicReference<>(Double.NaN);
        Slider s = new Slider(0, 2, 0.1, 0.7, changed::set, v -> String.format("%.1f", v));
        s.setBounds(0, 0, 100, 10);
        s.mouseClicked(50, 5, 0);            // 中点 → 1.0
        assertEquals(1.0, s.value(), 1e-9);
        assertEquals(1.0, changed.get(), 1e-9);
        s.mouseDragged(83, 5, 0, 0);         // 0.83*2=1.66 → 吸附 1.7
        assertEquals(1.7, s.value(), 1e-9);
        s.mouseDragged(999, 5, 0, 0);        // 越界夹紧
        assertEquals(2.0, s.value(), 1e-9);
        s.mouseReleased(999, 5, 0);
        assertTrue(!s.mouseDragged(10, 5, 0, 0), "松手后拖拽不再生效");
    }

    @Test
    void dropdownSelectionViaOverlayClick() {
        UiRoot root = new UiRoot();
        AtomicInteger picked = new AtomicInteger(-1);
        Dropdown dd = root.add(new Dropdown(List.of("openai", "anthropic", "deepseek"), 0, picked::set));
        dd.setBounds(0, 0, 80, 14);

        root.mouseClicked(5, 5, 0);          // 打开
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.render(s, WidgetTestSupport.C, 0, 0, 0);   // 渲染一次以缓存行高(13)

        root.mouseClicked(5, 14 + 13 + 2, 0);           // 弹层第 2 行
        assertEquals(1, picked.get());
        assertEquals("anthropic", dd.selectedItem());
        assertTrue(!dd.isOpen() && !root.hasOverlay());
    }

    @Test
    void dropdownPopupRendersAfterOtherWidgets() {
        UiRoot root = new UiRoot();
        Dropdown dd = root.add(new Dropdown(List.of("a", "b"), 0, i -> {}));
        dd.setBounds(0, 0, 80, 14);
        Label below = root.add(new Label("underneath", Label.Role.PRIMARY));
        below.setBounds(0, 20, 80, 10);

        root.mouseClicked(5, 5, 0);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.render(s, WidgetTestSupport.C, 0, 0, 0);
        // 浮层最后画:弹层里的 "a" 出现在 "underneath" 之后。
        assertTrue(s.texts.indexOf("underneath") < s.texts.lastIndexOf("a"));
    }
}
