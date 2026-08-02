package com.dwinovo.numen.client.ui;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.ToIntFunction;

/**
 * NumenUI 的横幅通知(toast):右上角滑入 → 停留 → 滑出,一次一条,队列排队。
 *
 * <h2>性能形态</h2>
 * 折行/测宽只在首帧接触画布时做一次并缓存进条目;此后每帧渲染是纯绘制,
 * 零排版零分配。滑动用时间驱动的缓动(不积帧),入队线程安全——LLM 异步
 * 回调线程可以直接 {@link #push},排版推迟到渲染线程首帧。
 *
 * <h2>为什么不用原版 toast</h2>
 * 原版 ToastManager 样式锁死成就风、不吃我们的主题,而且其 API 恰好逐版本
 * 变动——自建后整套行为在纯 JVM 层,十一个分支零移植。
 *
 * <h2>文案纪律</h2>
 * 说人话 + 说下一步("密钥无效——检查 API Key 是否复制完整"),不甩堆栈;
 * 堆栈的去处是游戏日志(错误四去处口径)。
 */
public final class NumenToasts {

    public enum Severity { INFO, WARN, ERROR }

    // ---- 时序参数(毫秒) ----
    static final long SLIDE_MS = 200;
    static final long VISIBLE_MIN_MS = 2_500;
    static final long VISIBLE_MAX_MS = 6_000;
    /** ERROR 至少停留这么久——报错看不清等于没报。 */
    static final long ERROR_VISIBLE_MIN_MS = 4_500;
    /** 每字符追加的停留时长(阅读速度补偿)。 */
    static final long PER_CHAR_MS = 35;

    // ---- 版式参数(像素,GUI 缩放坐标) ----
    static final int MARGIN = 6;
    static final int PAD = NumenStyle.PAD;
    static final int MAX_TEXT_WIDTH = 200;
    static final int MAX_LINES = 3;

    private enum State { SLIDE_IN, VISIBLE, SLIDE_OUT }

    private static final class Toast {
        final Severity severity;
        final String message;
        // 首帧排版缓存
        java.util.List<String> lines;
        int w, h;
        long visibleMs;

        Toast(Severity severity, String message) {
            this.severity = severity;
            this.message = message == null ? "" : message;
        }
    }

    private final Queue<Toast> queue = new ConcurrentLinkedQueue<>();
    private Toast current;
    private State state;
    private long stateStartMs;

    /** 线程安全;可从任意线程调用(异步 LLM 回调直接用)。 */
    public void push(Severity severity, String message) {
        queue.add(new Toast(severity, message));
    }

    public boolean isIdle() {
        return current == null && queue.isEmpty();
    }

    /**
     * 每帧调用(渲染线程)。时间由调用方注入,本类不读钟——时序全部可测。
     *
     * @param screenW GUI 缩放后的屏幕宽
     */
    public void render(IDrawSurface s, int screenW, NumenTheme.Colors c, long nowMs) {
        if (current == null) {
            current = queue.poll();
            if (current == null) return;
            state = State.SLIDE_IN;
            stateStartMs = nowMs;
        }
        if (current.lines == null) {
            layout(current, s::textWidth, s.lineHeight());
        }

        long elapsed = nowMs - stateStartMs;
        if (state == State.SLIDE_IN && elapsed >= SLIDE_MS) {
            state = State.VISIBLE;
            stateStartMs = nowMs;
            elapsed = 0;
        }
        if (state == State.VISIBLE && elapsed >= current.visibleMs) {
            state = State.SLIDE_OUT;
            stateStartMs = nowMs;
            elapsed = 0;
        }
        if (state == State.SLIDE_OUT && elapsed >= SLIDE_MS) {
            current = null;   // 下一帧从队列取下一条
            return;
        }

        // 滑动位移:完全收起 = 移出屏幕右缘(w + MARGIN)。
        int hiddenOffset = current.w + MARGIN;
        int offset = switch (state) {
            case SLIDE_IN -> Math.round(hiddenOffset * (1 - Animation.easeOutCubic(
                    Animation.progress(elapsed, SLIDE_MS))));
            case VISIBLE -> 0;
            case SLIDE_OUT -> Math.round(hiddenOffset * Animation.easeOutCubic(
                    Animation.progress(elapsed, SLIDE_MS)));
        };

        int x = screenW - MARGIN - current.w + offset;
        int y = MARGIN;
        int bg = switch (current.severity) {
            case INFO -> c.toastInfoBg();
            case WARN -> c.toastWarnBg();
            case ERROR -> c.toastErrorBg();
        };
        s.fillRoundRect(x, y, current.w, current.h, NumenStyle.RADIUS_PANEL, bg);
        int ty = y + PAD;
        for (String line : current.lines) {
            s.drawText(line, x + PAD, ty, c.toastText(), false);
            ty += s.lineHeight();
        }
    }

    /** 首帧排版一次:折行、量宽、按字数定停留时长,全部缓存进条目。 */
    private void layout(Toast t, ToIntFunction<String> widthFn, int lineHeight) {
        t.lines = TextWrap.wrap(t.message, MAX_TEXT_WIDTH, widthFn, MAX_LINES);
        int textW = 0;
        for (String line : t.lines) textW = Math.max(textW, widthFn.applyAsInt(line));
        t.w = textW + PAD * 2;
        t.h = Math.max(1, t.lines.size()) * lineHeight + PAD * 2;
        long dur = VISIBLE_MIN_MS + (long) t.message.length() * PER_CHAR_MS;
        if (t.severity == Severity.ERROR) dur = Math.max(dur, ERROR_VISIBLE_MIN_MS);
        t.visibleMs = Math.min(dur, VISIBLE_MAX_MS);
    }
}
