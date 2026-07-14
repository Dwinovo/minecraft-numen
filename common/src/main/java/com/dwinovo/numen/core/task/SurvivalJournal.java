package com.dwinovo.numen.core.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A companion body's short survival diary: one line per autonomous survival episode
 * (fought off a mob, ate, broke a fall), written by the survival chains at their
 * completion edges and drained into the NEXT LLM tool result's message — so the
 * model learns what its body did on its own, at exactly the moment it reads the
 * task outcome, with zero extra LLM calls and zero protocol change. The survival
 * layer stays non-consultative (the model is informed, never asked).
 *
 * <p>Bounded ring: quiet idle stretches accumulate at most {@link #MAX_ENTRIES}
 * lines (oldest dropped), so a result message can never be flooded.
 *
 * <p>Tick-thread only, like all task-layer state.
 */
public final class SurvivalJournal {

    private static final int MAX_ENTRIES = 6;

    private final ArrayDeque<String> entries = new ArrayDeque<>();

    /** Record one completed survival episode (one line, no trailing punctuation). */
    public void note(String line) {
        if (line == null || line.isBlank()) return;
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(line);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** All entries in order, clearing the journal. */
    public List<String> drain() {
        List<String> out = new ArrayList<>(entries);
        entries.clear();
        return out;
    }
}
