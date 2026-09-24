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
}
