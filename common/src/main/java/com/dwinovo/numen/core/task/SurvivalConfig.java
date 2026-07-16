package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskChain;

/**
 * The single gate that keeps the autonomous survival layer (auto-eat, fight-back,
 * flee, unstuck, MLG fall-save) dormant by default. Every survival chain's
 * {@code getPriority} FIRST consults {@link #enabled()} and returns
 * {@link Float#NEGATIVE_INFINITY} when it is off — so with the default-off gate the
 * four survival chains never beat {@link TaskChain#LLM_BASE_PRIORITY} and the
 * scheduler behaves exactly as it did before the layer existed (only
 * {@link LlmTaskChain} ever wins). A later stage flips this on; nothing here does.
 *
 * <p>Deliberately a plain static flag, not a per-companion setting: the layer is
 * enabled globally for the whole build once it graduates. {@code volatile} because
 * a config/command thread may flip it while the server tick thread reads it.
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
