package com.dwinovo.numen.core.task;


/**
 * The single gate for the autonomous survival layer (auto-eat, fight-back,
 * flee, unstuck, MLG fall-save). Every survival chain's {@code getPriority}
 * FIRST consults {@link #enabled()} and returns
 * {@link Float#NEGATIVE_INFINITY} when it is off — so with the gate off the
 * survival chains never beat {@link 反射#LLM_BASE_PRIORITY} and the
 * scheduler behaves as if the layer didn't exist. The field's own default is
 * OFF (the safe state a bare library build ships with); the tool pack flips it
 * on explicitly in {@code NumenCore.init()}.
 *
 * <p>Deliberately a plain static flag, not a per-companion setting: the layer is
 * enabled globally for the whole build. {@code volatile} because a
 * config/command thread may flip it while the server tick thread reads it.
 */
public final class SurvivalConfig {

    private SurvivalConfig() {}

    private static volatile boolean enabled = false;

    /** Is the autonomous survival layer live? Off by default. */
    public static boolean enabled() {
        return enabled;
    }

    /** Flip the survival layer on/off (a later stage / a debug command calls this). */
    public static void setEnabled(boolean value) {
        enabled = value;
    }
}
