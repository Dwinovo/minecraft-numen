package com.dwinovo.numen.api.client.ui.widget;

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
    void popupRowsLimitedByViewportWithScrollThumb() {
        UiRoot root = new UiRoot();
        root.setViewportHeight(80);   // 下拉在 y=0,h=14;底缘剩 66px → 66/13=5 行(上面没有空间,只能往下)
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) many.add("site" + i);
        // 只画浮层(字段自己也写着当前值),texts 里只剩弹层行,计数才纯净。
        Dropdown dd = root.add(new Dropdown(many, 0, i -> {}).popupWidth(80));
        dd.setBounds(0, 0, 80, 14);

        root.mouseClicked(5, 5, 0);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 0);   // 首帧定行高
        s.reset();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 0);
        long popupRows = s.texts.stream().filter(t -> t.startsWith("site")).count();
        assertEquals(5, popupRows, "弹层只露装得下的整行,不越出底缘");

        // 滚动后首行前进,仍不越界。
        root.mouseScrolled(5, 20, -1);
        s.reset();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("site1") && !s.texts.contains("site0"));
    }

    @Test
    void nearTheBottomItOpensUpwardAndRowsStillHitTheRightItem() {
        UiRoot root = new UiRoot();
        root.setViewport(0, 0, 200, 100);
        AtomicInteger picked = new AtomicInteger(-1);
        Dropdown dd = root.add(new Dropdown(List.of("a", "b", "c", "d"), 0, picked::set));
        dd.setBounds(10, 80, 80, 14);   // 下面只剩 6px,上面 80px

        root.mouseClicked(15, 85, 0);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.render(s, WidgetTestSupport.C, -10, -10, 0);   // 首帧定行高(13)
        // 4 行 52px 贴着字段上沿:28~80。第 3 行(c)在 54~67
        root.mouseClicked(15, 60, 0);
        assertEquals(2, picked.get());
    }

    @Test
    void shortDropdownNeverReachesOutsideTheViewportEvenWithFewRoomBelow() {
        UiRoot root = new UiRoot();
        root.setViewport(0, 0, 200, 100);
        Dropdown dd = root.add(new Dropdown(List.of("a", "b", "c"), 0, i -> {}).popupWidth(80));
        dd.setBounds(100, 60, 80, 14);   // 下面 26px(2 行),上面 60px(3 行装得下)
        root.mouseClicked(105, 65, 0);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 0);
        s.reset();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 0);
        // 3 行装不进下面的 26px,上面装得下:整张弹层在字段上方,没有一行落在字段下面
        assertTrue(s.rects.stream().allMatch(r -> r[1] + r[3] <= 100));
        assertEquals(3, s.texts.stream().filter(t -> List.of("a", "b", "c").contains(t)).count());
    }

    @Test
    void popupShowsAGrayDetailBesideEachItem() {
        UiRoot root = new UiRoot();
        Dropdown dd = root.add(new Dropdown(List.of("自动", "低"), 0, i -> {}).details(List.of("不发参数", "档位 low")));
        dd.setBounds(0, 0, 200, 14);
        root.mouseClicked(5, 5, 0);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("不发参数") && s.texts.contains("档位 low"));
    }

    @Test
    void closedPopupKeepsDrawingWhileItRetractsThenGoes() {
        UiRoot root = new UiRoot();
        Dropdown dd = root.add(new Dropdown(List.of("a", "b"), 0, i -> {}).popupWidth(80));
        dd.setBounds(0, 0, 80, 14);
        root.mouseClicked(5, 5, 0);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.render(s, WidgetTestSupport.C, -10, -10, 0);
        root.mouseClicked(5, 14 + 2, 0);   // 选第一项,弹层收起
        assertTrue(!root.hasOverlay());

        s.reset();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 50);   // 收回途中:弹层还在画,不是硬切
        assertTrue(s.texts.contains("a"));
        s.reset();
        root.renderOverlayLayer(s, WidgetTestSupport.C, -10, -10, 50 + 1000);   // 收完了
        assertTrue(s.texts.isEmpty());
    }

    @Test
    void anEmptyRowSaysWhereToGoAndClickingGoesThere() {
        UiRoot root = new UiRoot();
        AtomicInteger went = new AtomicInteger();
        Dropdown dd = root.add(new Dropdown(List.of(), 0, i -> {}).row("模型", 4).whenEmpty("没有", went::incrementAndGet));
        dd.setBounds(0, 0, 200, 20);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        root.render(s, WidgetTestSupport.C, -10, -10, 0);
        assertTrue(s.texts.contains("没有"));
        assertTrue(root.mouseClicked(5, 5, 0));
        assertEquals(1, went.get());
        assertTrue(!dd.isOpen() && !root.hasOverlay(), "没有可选的:不开弹层,只跳转");
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
