package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.Animation;

/**
 * 折叠节开合的进度:开了还是关了,以及从关到开(或反过来)走到哪一步。状态放在宿主面板手里,
 * 整张表单重建它也还在——重建不会把正在走的过渡打断。时间由调用方按帧给({@link #step}),本类不读钟。
 *
 * <p>{@link Disclosure} 拿它转三角,宿主拿 {@link #value()} 定"高级"那一截露出多高({@link ScrollBox#resize})。
 * {@link #slide()} 是对话框里那一种:{@link SlideWrap} 与 {@link ExpandRow} 用它,时长与曲线照 tdesktop 的 {@code Ui::SlideWrap}。
 */
public final class Reveal {

    /** 一次开合的时长:状态转换的量级(150~250ms)。 */
    static final long MS = 200;
    /** tdesktop 的 slideWrapDuration(lib_ui basic.style)。 */
    static final long SLIDE_MS = 150;

    private final long ms;
    /** 走线性({@code Ui::SlideWrap} 的 anim::linear),否则 easeOutCubic。 */
    private final boolean linear;
    private boolean open;
    /** 线性进度 0(收)~1(开)。 */
    private float p;
    private long lastMs = -1;

    /** 设置页折叠节那一种:{@link #MS},easeOutCubic。 */
    public Reveal() {
        this(MS, false);
    }

    private Reveal(long ms, boolean linear) {
        this.ms = ms;
        this.linear = linear;
    }

    /** 对话框里展开一截的那一种:照 tdesktop {@code Ui::SlideWrap},150ms、线性,高度跟着进度一起走。 */
    public static Reveal slide() {
        return new Reveal(SLIDE_MS, true);
    }

    public boolean open() {
        return open;
    }

    public void toggle() {
        open = !open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    /** 不带过渡地落到开或关(换了一份内容,上一份开没开与这份无关)。 */
    public void snap(boolean open) {
        this.open = open;
        this.p = open ? 1f : 0f;
        this.lastMs = -1;
    }

    /** 推进到 {@code nowMs}。返回还在走(这一帧的 {@link #value()} 与上一帧不同)。 */
    public boolean step(long nowMs) {
        long dt = lastMs < 0 ? 0 : Math.max(0, nowMs - lastMs);
        lastMs = nowMs;
        float target = open ? 1f : 0f;
        if (p == target) {
            return false;
        }
        float d = dt / (float) ms;
        p = open ? Math.min(1f, p + d) : Math.max(0f, p - d);
        return true;
    }

    /** 露出多少:0 全收,1 全开;按这一种的曲线走过。 */
    public float value() {
        return linear ? p : Animation.easeOutCubic(p);
    }
}
