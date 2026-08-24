package com.dwinovo.numen.client.ui;

/**
 * Tiny easing toolkit for the GUI — pure functions, no state. Used for smooth
 * transcript scrolling and slide/fade transitions.
 */
public final class Anim {

    private Anim() {}

    /** Cubic ease-out: fast start, gentle settle. t in [0,1]. */
    public static float easeOutCubic(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    /**
     * Frame-rate-independent exponential approach: move {@code current} toward
     * {@code target} by a {@code rate} decay over {@code dtSeconds}, snapping when
     * within half a pixel (so the value actually ARRIVES instead of crawling forever).
     */
    public static float approach(float current, float target, float rate, float dtSeconds) {
        float next = target + (current - target) * (float) Math.exp(-rate * dtSeconds);
        return Math.abs(next - target) < 0.5f ? target : next;
    }

    public static float clamp01(float t) {
        return t < 0f ? 0f : Math.min(t, 1f);
    }
}
