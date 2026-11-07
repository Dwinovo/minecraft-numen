package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.Animation;

/**
 * 折叠节开合的进度:开了还是关了,以及从关到开(或反过来)走到哪一步。状态放在宿主面板手里,
 * 整张表单重建它也还在——重建不会把正在走的过渡打断。时间由调用方按帧给({@link #step}),本类不读钟。
 *
 * <p>{@link Disclosure} 拿它转三角,宿主拿 {@link #value()} 定"高级"那一截露出多高({@link ScrollBox#resize})。
 */
public final class Reveal {

    /** 一次开合的时长:状态转换的量级(150~250ms)。 */
    static final long MS = 200;

    private boolean open;
    /** 线性进度 0(收)~1(开)。 */
    private float p;
    private long lastMs = -1;

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
        float d = dt / (float) MS;
        p = open ? Math.min(1f, p + d) : Math.max(0f, p - d);
        return true;
    }

    /** 露出多少:0 全收,1 全开;缓动过。 */
    public float value() {
        return Animation.easeOutCubic(p);
    }
}
