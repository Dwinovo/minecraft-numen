package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.NumenIcons;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.client.ui.widget.WidgetTestSupport.C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 图标按钮不写字,所以悬停必须说得出自己是干嘛的。 */
class IconButtonTest {

    private static UiRoot rootWith(IconButton b) {
        UiRoot ui = new UiRoot();
        ui.add(b);
        b.setBounds(50, 60, 18, 18);
        return ui;
    }

    @Test
    void hoveringHandsTheWordToTheRootSoTheHostCanDrawItOnTop() {
        IconButton b = new IconButton(NumenIcons.COPY, "复制", () -> { });
        UiRoot ui = rootWith(b);

        ui.renderContent(new WidgetTestSupport.FakeSurface(), C, 55, 65, 0);
        assertEquals("复制", ui.tooltip(), "鼠标在上面就报这一句");
    }

    @Test
    void theWordIsGoneTheFrameAfterTheMouseLeaves() {
        IconButton b = new IconButton(NumenIcons.COPY, "复制", () -> { });
        UiRoot ui = rootWith(b);

        ui.renderContent(new WidgetTestSupport.FakeSurface(), C, 55, 65, 0);
        ui.renderContent(new WidgetTestSupport.FakeSurface(), C, 5, 5, 0);
        assertNull(ui.tooltip(), "移开就该没了——每帧重收,不是攒着");
    }

    @Test
    void hoveringAlsoLightsTheHitAreaSoItLooksClickable() {
        IconButton b = new IconButton(NumenIcons.COPY, "复制", () -> { });
        UiRoot ui = rootWith(b);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();

        ui.renderContent(s, C, 55, 65, 0);
        int[] first = s.rects.get(0);
        assertEquals(50, first[0]);
        assertEquals(60, first[1]);
        assertEquals(18, first[2], "悬停底铺满整个热区");
        assertEquals(18, first[3]);
    }

    @Test
    void clickingRunsTheAction() {
        boolean[] ran = {false};
        IconButton b = new IconButton(NumenIcons.COPY, "复制", () -> ran[0] = true);
        UiRoot ui = rootWith(b);

        assertTrue(ui.mouseClicked(55, 65, 0));
        assertTrue(ran[0]);
    }
}
