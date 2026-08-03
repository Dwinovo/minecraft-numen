package com.dwinovo.numen.client.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * toast 状态机的时序契约(时间全注入,零真实时钟):滑入→停留→滑出→下一条、
 * 排版只发生一次、ERROR 停留下限、滑动期位移在屏内外之间。
 */
class NumenToastsTest {

    /** 假画布:记录每帧的绘制与测量,是断言的观察窗。 */
    private static final class FakeSurface implements IDrawSurface {
        int widthCalls;
        final List<int[]> rects = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

        @Override public void fillRect(int x, int y, int w, int h, int argb) {
            rects.add(new int[]{x, y, w, h});
        }
        @Override public void drawText(String t, int x, int y, int argb, boolean shadow) {
            texts.add(t);
        }
        @Override public int textWidth(String t) { widthCalls++; return t.length() * 6; }
        @Override public int lineHeight() { return 10; }
        @Override public void pushScissor(int x, int y, int w, int h) {}
        @Override public void popScissor() {}

        void resetFrame() { rects.clear(); texts.clear(); }
    }

    private static final NumenTheme.Colors C = NumenTheme.DARK.colors();
    private static final int SCREEN_W = 320;

    @Test
    void lifecycleSlideInVisibleSlideOutThenNext() {
        NumenToasts toasts = new NumenToasts();
        FakeSurface s = new FakeSurface();
        toasts.push(NumenToasts.Severity.INFO, "first");
        toasts.push(NumenToasts.Severity.INFO, "second");

        // 状态从进入时刻起算满时长(跳表不快进)——按级联时刻逐帧推进。
        // 入场首帧 alpha=0 不画文字(渐显),文字断言放在停留期。
        toasts.render(s, SCREEN_W, C, 0);                       // 滑入首帧
        long tVisible = NumenToasts.SLIDE_MS + 1;
        s.resetFrame();
        toasts.render(s, SCREEN_W, C, tVisible);                 // 进入停留(计时起点 tVisible)
        assertEquals("first", s.texts.get(0));

        long tSlideOut = tVisible + NumenToasts.VISIBLE_MAX_MS + 1;
        toasts.render(s, SCREEN_W, C, tSlideOut);                // 进入滑出(计时起点 tSlideOut)
        toasts.render(s, SCREEN_W, C, tSlideOut + NumenToasts.SLIDE_MS + 1); // 滑出完成,current 清空
        long tSecond = tSlideOut + NumenToasts.SLIDE_MS + 2;
        toasts.render(s, SCREEN_W, C, tSecond);                  // 下一条滑入首帧
        s.resetFrame();
        toasts.render(s, SCREEN_W, C, tSecond + NumenToasts.SLIDE_MS + 1);   // 下一条停留
        assertEquals("second", s.texts.get(0));
    }

    @Test
    void layoutHappensExactlyOnce() {
        NumenToasts toasts = new NumenToasts();
        FakeSurface s = new FakeSurface();
        toasts.push(NumenToasts.Severity.INFO, "一条会折行的比较长的通知文本内容");
        toasts.render(s, SCREEN_W, C, 0);
        int callsAfterFirstFrame = s.widthCalls;
        assertTrue(callsAfterFirstFrame > 0);
        for (int frame = 1; frame <= 50; frame++) {
            toasts.render(s, SCREEN_W, C, frame * 16L);
        }
        assertEquals(callsAfterFirstFrame, s.widthCalls, "渲染帧内发生了重新排版");
    }

    @Test
    void visibleToastSitsInsideScreenSlidingStartsOutside() {
        NumenToasts toasts = new NumenToasts();
        FakeSurface s = new FakeSurface();
        toasts.push(NumenToasts.Severity.INFO, "hi");

        toasts.render(s, SCREEN_W, C, 0);                        // t=0 完全收起
        int[] first = s.rects.get(0);
        assertTrue(first[0] >= SCREEN_W - NumenToasts.MARGIN, "滑入起点应在屏幕右缘外");

        s.resetFrame();
        toasts.render(s, SCREEN_W, C, NumenToasts.SLIDE_MS + 10); // 停留期
        int[] settled = s.rects.get(0);
        assertEquals(SCREEN_W - NumenToasts.MARGIN - settled[2], settled[0], "停留期应贴右缘且全部在屏内");
    }

    @Test
    void errorStaysLongerThanShortInfo() {
        NumenToasts info = new NumenToasts();
        NumenToasts error = new NumenToasts();
        FakeSurface s = new FakeSurface();
        info.push(NumenToasts.Severity.INFO, "ok");
        error.push(NumenToasts.Severity.ERROR, "ok");
        info.render(s, SCREEN_W, C, 0);
        error.render(s, SCREEN_W, C, 0);

        long probe = NumenToasts.SLIDE_MS + NumenToasts.VISIBLE_MIN_MS + NumenToasts.PER_CHAR_MS * 2 + 100;
        // INFO 在 probe 时刻已进入滑出乃至结束;ERROR 因停留下限仍在停留期。
        for (long t = 100; t <= probe; t += 100) {
            s.resetFrame();
            info.render(s, SCREEN_W, C, t);
            error.render(s, SCREEN_W, C, t);
        }
        s.resetFrame();
        error.render(s, SCREEN_W, C, probe);
        assertEquals(1, s.texts.size(), "ERROR 应仍在展示");
    }

    @Test
    void pushIsSafeBeforeAnyRenderAndQueueDrainsToIdle() {
        NumenToasts toasts = new NumenToasts();
        toasts.push(NumenToasts.Severity.WARN, "w");
        assertTrue(!toasts.isIdle());
        FakeSurface s = new FakeSurface();
        long t = 0;
        for (int i = 0; i < 500 && !toasts.isIdle(); i++) {
            toasts.render(s, SCREEN_W, C, t += 100);
        }
        assertTrue(toasts.isIdle(), "队列应最终排空");
    }
}
