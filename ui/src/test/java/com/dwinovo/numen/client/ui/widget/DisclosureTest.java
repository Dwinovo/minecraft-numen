package com.dwinovo.numen.client.ui.widget;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.client.ui.widget.WidgetTestSupport.C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 折叠节抬头:三角朝向说的是"再点一下会往哪走",状态不在控件自己手里。 */
class DisclosureTest {

    private static WidgetTestSupport.FakeSurface draw(boolean expanded) {
        Disclosure d = new Disclosure("高级设置", expanded, () -> { });
        d.setBounds(20, 40, 200, 18);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        d.render(s, C, -1, -1, 0);
        return s;
    }

    @Test
    void collapsedPointsRightAndExpandedPointsDown() {
        // 朝右:四列,一列比一列矮两格;朝下:四行,一行比一行窄两格
        WidgetTestSupport.FakeSurface right = draw(false);
        for (int i = 0; i < 4; i++) {
            assertEquals(1, right.rects.get(i)[2], "朝右时每一笔宽 1");
            assertEquals(7 - 2 * i, right.rects.get(i)[3], "越往右越矮");
        }
        WidgetTestSupport.FakeSurface down = draw(true);
        for (int i = 0; i < 4; i++) {
            assertEquals(7 - 2 * i, down.rects.get(i)[2], "越往下越窄");
            assertEquals(1, down.rects.get(i)[3], "朝下时每一笔高 1");
        }
    }

    @Test
    void theTitleIsFollowedByARuleThatRunsToTheRowEnd() {
        WidgetTestSupport.FakeSurface s = draw(false);
        assertTrue(s.texts.contains("高级设置"), "标题照写");
        int[] rule = s.rects.get(s.rects.size() - 1);
        assertEquals(1, rule[3], "细线只有一像素高");
        assertEquals(20 + 200, rule[0] + rule[2], "一直画到这一行的右边沿");
    }

    @Test
    void clickingAsksTheHostToFlipItSelfStaysDumb() {
        boolean[] asked = {false};
        Disclosure d = new Disclosure("高级设置", false, () -> asked[0] = true);
        d.setBounds(20, 40, 200, 18);

        assertTrue(d.mouseClicked(25, 45, 0), "点在抬头上就算它的");
        assertTrue(asked[0], "点击只通知宿主");
        assertEquals(false, d.expanded(), "控件自己不记状态,仍照传进来的画");
    }
}
