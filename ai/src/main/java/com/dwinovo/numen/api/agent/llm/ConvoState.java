package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.provider.AssistantTurn;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-entity conversation history, the brain's working copy of the conversation record. Lives on the **client**
 * side (the brain runs on the owner's client), accessed solely from the client main thread — no synchronisation
 * needed. The record itself is on the server; every change here is handed to the {@link Journal}, which writes it.
 *
 * <h2>Storage type</h2>
 * Plain provider-agnostic DTOs ({@link AssistantTurn} et al.) — no SDK
 * classes leak through. The provider layer translates these to / from wire
 * JSON when building requests and parsing responses.
 *
 * <h2>No loop guard, no turn cap</h2>
 * There is intentionally no autonomous stop: a capable agent legitimately
 * chains many tasks, and retrying a timed-out task repeats the exact same
 * tool call — a signature-based loop detector kept killing that correct
 * recovery (it aborted a {@code move_to} resume after a mid-journey timeout).
 * Runaways are stopped by the owner's interrupt; {@link #turnCount} is kept
 * purely for log numbering.
 */
public final class ConvoState {

    /** Tagged union for conversation history. */
    public sealed interface Msg permits Msg.User, Msg.Assistant, Msg.Tool, Msg.Halt {
        /**
         * Someone said something, or the world did. {@code meta} marks text the owner did not type and the panel does not
         * draw (a goal's instruction to her, the evaluator's feedback); the model reads it like any other user text.
         * {@code from} is who said the words ({@code player@shortcode}); {@code null} when the text is no one's words
         * (world events, meta). One message holds the words of one speaker only.
         */
        record User(String content, boolean meta, String from) implements Msg {
            public User(String content, boolean meta) {
                this(content, meta, null);
            }

            public User(String content) {
                this(content, false, null);
            }
        }
        record Assistant(AssistantTurn turn) implements Msg {}
        record Tool(String toolCallId, String content) implements Msg {}
        /**
         * The turn was cut off here (owner stop, disconnect, death); {@code reason} is shown to
         * the model. History records only where the cut happened — dangling tool calls are not
         * answered and nothing is capped at the time; {@link ProtocolView} turns the record into a
         * provider-valid sequence right before each request.
         *
         * <p>Two voices, never spliced: {@code reason} is the sentence for the model; {@code code} + {@code args}
         * are the structured cause the panel turns into the owner's language when it draws the notice
         * ({@code code == null}: no structured cause, as in logs written before there was one: the panel then
         * shows {@code reason} as stored).
         */
        record Halt(String reason, String code, List<String> args) implements Msg {
            public Halt {
                args = args == null ? List.of() : List.copyOf(args);
            }

            /** A cut with no structured cause. */
            public Halt(String reason) {
                this(reason, null, List.of());
            }
        }
    }

    /**
     * Where the conversation is written down: every change to the history is handed to it, in order. The record is
     * append-only; compaction and clearing are recorded as what they are, not as edits.
     */
    public interface Journal {

        /** Nothing is written (tests). */
        Journal NONE = new Journal() {
            @Override
            public void append(Msg msg) { }

            @Override
            public void attach(String type, JsonObject fields) { }

            @Override
            public void compacted(String summary, int keptTail, JsonObject meta) { }

            @Override
            public void cleared() { }
        };

        /** A message was added to the history. */
        void append(Msg msg);

        /** A state or reminder that is not part of the history (a goal's status, a persona change). */
        void attach(String type, JsonObject fields);

        /**
         * The history became {@code summary} plus its last {@code keptTail} messages, kept verbatim.
         *
         * @param meta accounting only (trigger, tokens before, duration)
         */
        void compacted(String summary, int keptTail, JsonObject meta);

        /** The history was emptied: the conversation goes on in a new one, the old record stays as it was. */
        void cleared();
    }

    private final List<Msg> messages = new ArrayList<>();
    private int turnCount = 0;

    /** Told about every change — the persistence hook. */
    private final Journal journal;

    /** In-memory only (tests). */
    public ConvoState() {
        this(Journal.NONE);
    }

    public ConvoState(Journal journal) {
        this.journal = journal;
    }

    /**
     * Splice a previously recorded history in at start-up, WITHOUT telling the
     * journal — these messages are already in the record; writing them again
     * would duplicate it on every start.
     */
    public void preload(List<Msg> history) {
        messages.addAll(history);
    }

    /**
     * Swap the entire history for {@code replacement} WITHOUT telling the
     * journal — used by compaction and clearing, which the journal learns of
     * through {@link Journal#compacted} and {@link Journal#cleared} rather than as
     * ordinary appended messages.
     */
    public void replaceAll(List<Msg> replacement) {
        messages.clear();
        messages.addAll(replacement);
        resetTurnCount();
    }

    public void addUser(String content) {
        push(new Msg.User(content));
    }

    /** Someone's words: {@code from} is who said them ({@code player@shortcode}), {@code null} when it is no one's words. */
    public void addUser(String content, String from) {
        push(new Msg.User(content, false, from));
    }

    /** Text the model reads but the owner did not type and the panel does not draw. */
    public void addMeta(String content) {
        push(new Msg.User(content, true));
    }

    public void addAssistant(AssistantTurn turn) {
        push(new Msg.Assistant(turn));
    }

    public void addToolResult(String toolCallId, String content) {
        push(new Msg.Tool(toolCallId, content));
    }

    public void addHalt(String reason, String code, List<String> args) {
        push(new Msg.Halt(reason, code, args));
    }

    private void push(Msg msg) {
        messages.add(msg);
        journal.append(msg);
    }

    /** Record a state or reminder that is not part of the history. */
    public void attach(String type, JsonObject fields) {
        journal.attach(type, fields);
    }

    /** The history becomes {@code summary} plus its last {@code kept} messages. */
    public void compact(String summary, List<Msg> kept, JsonObject meta) {
        journal.compacted(summary, kept.size(), meta);
        List<Msg> next = new ArrayList<>();
        next.add(new Msg.User(summary));
        next.addAll(kept);
        replaceAll(next);
    }

    /** Empty the history; the record goes on in a new conversation. */
    public void clear() {
        journal.cleared();
        replaceAll(List.of());
    }

    public List<Msg> snapshot() {
        return List.copyOf(messages);
    }

    /** Most recent message, or {@code null} when the history is empty. */
    public Msg lastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public int turnCount() { return turnCount; }
    public void incrementTurn() { turnCount++; }

    /**
     * Called when a final text response arrives (no tool_calls). The chain
     * is settled; the next user message starts a fresh log-numbering count.
     */
    public void resetTurnCount() {
        turnCount = 0;
    }
}
