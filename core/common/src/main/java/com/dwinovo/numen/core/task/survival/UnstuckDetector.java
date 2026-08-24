package com.dwinovo.numen.core.task.survival;

/**
 * The PURE stuck-detection core of {@code UnstuckChain}, split out of the chain so
 * it can be unit-tested with a synthetic position stream (no Minecraft). It keeps a
 * rolling window of the body's horizontal position plus, per sample, whether the
 * body was TRYING to move that tick (nonzero locomotion input). It reports "stuck"
 * only when, across a full window, the body attempted to move for (almost) every
 * tick yet stayed inside a tiny disc — the signature of a body wedged against
 * geometry while the path executor keeps pushing. It deliberately does NOT fire on
 * a legitimately idle body (no locomotion input → those ticks don't count as
 * attempts), which is what keeps the survival chain from waking during normal idle.
 */
public final class UnstuckDetector {

    private final int window;
    private final double moveThresholdSqr;
    /** Fraction of the window that must be "trying to move" to count as stuck. */
    private final double tryingFraction;

    private final double[] xs;
    private final double[] zs;
    private final boolean[] trying;
    private int size;   // valid samples so far (caps at window)
    private int head;   // ring write cursor

    public UnstuckDetector(int window, double moveThreshold) {
        this(window, moveThreshold, 0.8);
    }

    public UnstuckDetector(int window, double moveThreshold, double tryingFraction) {
        this.window = window;
        this.moveThresholdSqr = moveThreshold * moveThreshold;
        this.tryingFraction = tryingFraction;
        this.xs = new double[window];
        this.zs = new double[window];
        this.trying = new boolean[window];
    }

    /** Record this tick's horizontal position and whether the body was trying to move. */
    public void record(double x, double z, boolean movingInput) {
        xs[head] = x;
        zs[head] = z;
        trying[head] = movingInput;
        head = (head + 1) % window;
        if (size < window) size++;
    }

    /** Forget every sample — call after a break-out attempt so the next window evaluates fresh. */
    public void reset() {
        size = 0;
        head = 0;
    }

    /**
     * True once a FULL window has accumulated in which the body tried to move for at
     * least {@code tryingFraction} of the ticks yet never left a disc of radius
     * {@code moveThreshold} around its latest position.
     */
    public boolean isStuck() {
        if (size < window) return false;
        int newest = (head - 1 + window) % window;
        double nx = xs[newest];
        double nz = zs[newest];
        int tryingCount = 0;
        double maxDistSqr = 0.0;
        for (int i = 0; i < window; i++) {
            if (trying[i]) tryingCount++;
            double dx = xs[i] - nx;
            double dz = zs[i] - nz;
            double d = dx * dx + dz * dz;
            if (d > maxDistSqr) maxDistSqr = d;
        }
        return tryingCount >= Math.ceil(window * tryingFraction)
                && maxDistSqr < moveThresholdSqr;
    }
}
