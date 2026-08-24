package com.dwinovo.numen.client.agent;

/**
 * Why an entity agent is currently refusing to start another turn.
 *
 * <p>A recoverable transport failure is different from an owner's explicit Stop: a later event that
 * is entitled to wake the brain (for example {@code task_finished} while a background task is active)
 * must resume the former, but must never undo the latter. Keeping that distinction here prevents a
 * long-running chain from becoming permanently latched after one transient LLM failure.
 */
enum AgentTurnPause {
    NONE,
    OWNER_INTERRUPT,
    RECOVERABLE_FAILURE,
    BLOCKED;

    boolean isPaused() {
        return this != NONE;
    }

    /** Apply an event wake-up without overriding an explicit owner/configuration pause. */
    AgentTurnPause afterWakeEvent(boolean wakeWorthy) {
        return wakeWorthy && this == RECOVERABLE_FAILURE ? NONE : this;
    }
}
