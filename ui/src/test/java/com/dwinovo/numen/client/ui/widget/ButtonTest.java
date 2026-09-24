package com.dwinovo.numen.client.ui.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ButtonTest {

    @Test
    void aLinkButtonIsJustItsLabelUntilHovered() {
        UiRoot root = new UiRoot();
        Button b = root.add(new Button("保存", Button.Style.LINK, () -> { }));
        b.setBounds(0, 0, 60, 16);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        b.render(s, WidgetTestSupport.C, -100, -100, 0);
        assertEquals(0, s.rects.size(), "对话框的纯字钮:不悬停时不画底");
        assertTrue(s.texts.contains("保存"));
    }

    @Test
    void aGhostButtonWithAnIconDrawsTheIconNotItsLabel() {
        UiRoot root = new UiRoot();
        int[] drawn = new int[4];
        Button b = root.add(new Button("复制", Button.Style.GHOST, () -> { })
                .icon(12, (surface, x, y, size, argb) -> {
                    drawn[0] = x;
                    drawn[1] = y;
                    drawn[2] = size;
                    drawn[3]++;
                }));
        b.setBounds(10, 20, 14, 14);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        b.render(s, WidgetTestSupport.C, -100, -100, 0);
        assertEquals(1, drawn[3], "设了图标的幽灵钮要画图标");
        assertEquals(11, drawn[0], "图标在钮里水平居中");
        assertEquals(21, drawn[1], "图标在钮里垂直居中");
        assertEquals(12, drawn[2]);
        assertTrue(s.texts.isEmpty(), "图标钮不画 label,label 只留给悬停提示");
    }
}
