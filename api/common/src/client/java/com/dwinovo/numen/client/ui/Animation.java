package com.dwinovo.numen.client.ui;

/** NumenUI 缓动小库:纯函数,时间参数一律由调用方注入(可测性)。 */
public final class Animation {

    private Animation() {}

    /** t∈[0,1] → [0,1],出场收尾减速(滑入/滑出的标准曲线)。 */
    public static float easeOutCubic(float t) {
        t = clamp01(t);
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }

    /** 指数趋近 + 吸附:距目标小于 snapThreshold 时直接贴齐,避免永不到达的抖尾。 */
    public static float lerpTo(float current, float target, float speed, float snapThreshold) {
        float next = current + (target - current) * speed;
        return Math.abs(next - target) < snapThreshold ? target : next;
    }

    /** 时间进度:elapsed/duration 夹到 [0,1](duration≤0 视作已完成)。 */
    public static float progress(long elapsedMs, long durationMs) {
        if (durationMs <= 0) return 1.0f;
        return clamp01((float) elapsedMs / durationMs);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
