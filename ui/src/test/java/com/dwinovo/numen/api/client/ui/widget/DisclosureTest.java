package com.dwinovo.numen.api.client.ui.widget;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.dwinovo.numen.api.client.ui.widget.WidgetTestSupport.C;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 折叠节抬头:三角跟着 {@link Reveal} 转,状态不在控件自己手里。 */
class DisclosureTest {

    private static WidgetTestSupport.FakeSurface draw(Reveal reveal) {
        Disclosure d = new Disclosure("高级设置", reveal);
        d.setBounds(20, 40, 200, 18);
        WidgetTestSupport.FakeSurface s = new WidgetTestSupport.FakeSurface();
        d.render(s, C, -1, -1, 0);
        return s;
    }

    /** 三角画出来的像素点(1×1 的那些笔)。 */
    private static Set<String> arrow(WidgetTestSupport.FakeSurface s) {
        Set<String> px = new HashSet<>();
        for (int[] r : s.rects) {
            if (r[2] == 1 && r[3] == 1) px.add(r[0] + "," + r[1]);
        }
        return px;
    }

    private static int[] box(Set<String> px) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (String p : px) {
            String[] a = p.split(",");
            int x = Integer.parseInt(a[0]), y = Integer.parseInt(a[1]);
            x0 = Math.min(x0, x); x1 = Math.max(x1, x);
            y0 = Math.min(y0, y); y1 = Math.max(y1, y);
        }
        return new int[]{x0, y0, x1, y1};
    }

    @Test
    void collapsedPointsRightAndExpandedPointsDown() {
        Reveal closed = new Reveal();
        int[] right = box(arrow(draw(closed)));
        assertTrue(right[3] - right[1] > right[2] - right[0], "朝右:比宽高(底边竖着)");
        Reveal open = new Reveal();
        open.snap(true);
        int[] down = box(arrow(draw(open)));
        assertTrue(down[2] - down[0] > down[3] - down[1], "朝下:比高宽(底边横着)");
    }

    @Test
    void midwayItIsNeitherEnd() {
        Reveal r = new Reveal();
        r.setOpen(true);
        r.step(0);
        r.step(Reveal.MS / 2);
        Set<String> mid = arrow(draw(r));
        Reveal closed = new Reveal();
        Reveal open = new Reveal();
        open.snap(true);
        assertNotEquals(arrow(draw(closed)), mid, "过渡中不是收着的样子");
        assertNotEquals(arrow(draw(open)), mid, "过渡中也不是展开的样子");
    }

    @Test
    void theTitleIsFollowedByARuleThatRunsToTheRowEnd() {
        WidgetTestSupport.FakeSurface s = draw(new Reveal());
        assertTrue(s.texts.contains("高级设置"), "标题照写");
        int[] rule = s.rects.get(s.rects.size() - 1);
        assertEquals(1, rule[3], "细线只有一像素高");
        assertEquals(20 + 200, rule[0] + rule[2], "一直画到这一行的右边沿");
    }

    @Test
    void clickingFlipsTheRevealTheControlKeepsNoStateOfItsOwn() {
        Reveal r = new Reveal();
        Disclosure d = new Disclosure("高级设置", r);
        d.setBounds(20, 40, 200, 18);

        assertTrue(d.mouseClicked(25, 45, 0), "点在抬头上就算它的");
        assertTrue(r.open(), "点击翻宿主手里的开合");
        assertFalse(r.value() > 0f, "翻过去只是改了目标,高度要等按帧推进");
    }
}
