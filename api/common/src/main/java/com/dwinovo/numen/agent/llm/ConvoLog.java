package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only JSONL persistence for one entity's conversation —
 * {@code <gameDir>/config/numen/conversations/<entity-uuid>.jsonl}, one record
 * per line. The entity UUID is globally unique and dimension-stable, so the same
 * file follows the companion for its whole life.
 *
 * <h2>Record model (v2)</h2>
 * Every line is one JSON <em>record</em>, discriminated like Claude Code's transcript:
 * <ul>
 *   <li><b>Header</b> — {@code {"type":"header","v":2,...}} — always line 1 of a v2 file.</li>
 *   <li><b>Messages</b> — the v1 shape, keyed by {@code role} = {@code user}/{@code assistant}/{@code tool},
 *       plus optional additive metadata ({@code ts}, and reserved {@code model}/{@code usage}).</li>
 *   <li><b>Events</b> — keyed by {@code type}, no {@code role}: {@code compact}, {@code persona-change},
 *       and the reserved {@code goal}. Events carry state, not conversation.</li>
 * </ul>
 * <b>Discriminator rule:</b> a record with a {@code type} field is an event; otherwise it's a message
 * dispatched on {@code role}. The legacy v1 {@code role:"compact"} line is read as a {@code compact} event.
 *
 * <h2>Two derived views</h2>
 * {@link #load} is the LLM context (compaction restarts the replay from its summary); {@link #loadDisplay}
 * is the physical transcript the GUI renders (compaction/persona-change become sentinel divider lines, no
 * history is lost). Events are absent from the LLM view and appear only as dividers in the display view.
 *
 * <h2>Forward compatibility</h2>
 * A valid-JSON record with an unknown {@code type}/{@code role} is <em>skipped from both in-memory views
 * but kept on disk</em> (append-only) — a newer numen can add record kinds without an older one losing them.
 *
 * <h2>Migration (v1 → v2)</h2>
 * {@link #migrateIfNeeded} rewrites a headerless v1 file to v2 atomically (temp → {@code ATOMIC_MOVE}),
 * keeping a {@code .v1.bak}. The reader permanently understands the v1 shape too, so migration is an
 * optimization — never a correctness dependency, and never able to lose records (the original file is
 * only ever replaced by a fully-written temp; on any failure it is left untouched).
 *
 * <h2>Best-effort by design</h2>
 * IO failures log a warning and the chat carries on in memory — persistence must never take the
 * companion offline.
 */
public final class ConvoLog {

    /** On-disk format version stamped in the header record; bumped when the record model changes. */
    public static final int FORMAT_VERSION = 2;

    /** Soft cap on messages replayed into context (the file itself is unbounded). */
    public static final int DEFAULT_LOAD_LIMIT = 200;

    /** Compaction-boundary sentinel in the DISPLAY view — the GUI draws a thin divider. Never sent to the LLM. */
    public static final String COMPACT_DIVIDER = "[numen:compact-divider]";
    /** Persona-change sentinel in the DISPLAY view — drawn as a "人设已切换" divider. Never sent to the LLM. */
    public static final String PERSONA_DIVIDER = "[numen:persona-divider]";

    // Event type discriminators.
    private static final String EV_HEADER = "header";
    private static final String EV_COMPACT = "compact";
    private static final String EV_PERSONA = "persona-change";
    private static final String EV_GOAL = "goal";   // reserved (recognized, no behavior yet)

    private final Path file;

    private ConvoLog(Path file) {
        this.file = file;
    }

    /** The log for one entity under {@code conversationsDir}. Creates nothing until the first append. */
    public static ConvoLog forEntity(Path conversationsDir, UUID entityUuid) {
        return new ConvoLog(conversationsDir.resolve(entityUuid + ".jsonl"));
    }

    public Path file() {
        return file;
    }

    /** A companion's current persona, recovered from the last {@code persona-change} event. */
    public record PersonaState(String id, String text, String name) {}

    // ---- write ----

    /** Append one message as a single JSONL line (with a {@code ts}). Best-effort: failures only warn. */
    public void append(ConvoState.Msg msg) {
        JsonObject o = encode(msg);
        o.addProperty("ts", System.currentTimeMillis());
        writeLine(o);
    }

    /**
     * Append a compaction boundary event: a {@code type:"compact"} line whose content is the (already
     * wrapped) summary that replaces everything before it, plus a {@code preserved} whitelist of messages
     * carried across the boundary VERBATIM. The file stays append-only — the full pre-compaction history
     * remains on disk as an archive, but {@link #load} starts fresh from the latest boundary.
     */
    public void appendCompactSummary(String wrappedSummary, List<ConvoState.Msg> preserved,
                                     JsonObject meta) {
        JsonObject o = new JsonObject();
        o.addProperty("type", EV_COMPACT);
        o.addProperty("content", wrappedSummary);
        if (!preserved.isEmpty()) {
            JsonArray kept = new JsonArray();
            for (ConvoState.Msg m : preserved) kept.add(encode(m));
            o.add("preserved", kept);
        }
        // Accounting only (trigger, preTokens, summaryTokens, droppedMessages, durationMs) — replay ignores it.
        if (meta != null && !meta.entrySet().isEmpty()) {
            o.add("meta", meta);
        }
        o.addProperty("ts", System.currentTimeMillis());
        writeLine(o);
    }

    /**
     * Append a {@code persona-change} event: the companion's new persona text + display name. Event-sourced
     * (last-wins) — {@link #loadCurrentPersona} recovers the current persona from the latest such event, and
     * {@link #loadDisplay} renders it as a {@link #PERSONA_DIVIDER}. The LLM-facing reconciliation ("从现在起
     * 你是…") is a separate ordinary user message injected by the loop, not this event.
     */
    public void appendPersonaChange(String id, String text, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("type", EV_PERSONA);
        if (id != null && !id.isBlank()) o.addProperty("id", id);
        o.addProperty("content", text == null ? "" : text);
        if (name != null && !name.isBlank()) o.addProperty("name", name);
        o.addProperty("ts", System.currentTimeMillis());
        writeLine(o);
    }

    /** Write one record line, prefixing a header on a brand-new file. Best-effort. */
    private void writeLine(JsonObject record) {
        try {
            ensureHeader();
            Files.writeString(file, record + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-convo] failed to append to {}: {}", file, ex.toString());
        }
    }

    /** Ensure a fresh file opens with a v2 header record. */
    private void ensureHeader() throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) return;
        JsonObject h = new JsonObject();
        h.addProperty("type", EV_HEADER);
        h.addProperty("v", FORMAT_VERSION);
        h.addProperty("created", System.currentTimeMillis());
        Files.writeString(file, h + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** Remove the file (conversation reset). */
    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-convo] failed to delete {}: {}", file, ex.toString());
        }
    }

    // ---- migration ----

    /**
     * Rewrite a headerless v1 file to v2 (idempotent, crash-safe). Does nothing to an already-v2 file, a
     * missing file, or (on failure) the original — which the reader still understands as v1. Never deletes
     * or half-writes the original: it is only ever replaced by a fully-written temp via an atomic move.
     */
    public void migrateIfNeeded() {
        if (!Files.isRegularFile(file)) return;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String first = firstNonBlank(lines);
            if (first == null) return;                          // empty file — leave it
            JsonObject head = tryParse(first);
            if (head != null && EV_HEADER.equals(str(head.get("type")))) return;   // already v2

            // v1 → v2: keep a one-time backup, then rebuild into a temp and atomically swap in.
            Path bak = file.resolveSibling(file.getFileName() + ".v1.bak");
            if (!Files.exists(bak)) Files.copy(file, bak);

            long ts = fileAnchorMillis();
            StringBuilder sb = new StringBuilder(lines.size() * 64 + 64);
            JsonObject h = new JsonObject();
            h.addProperty("type", EV_HEADER);
            h.addProperty("v", FORMAT_VERSION);
            h.addProperty("created", ts);
            h.addProperty("migrated", true);
            sb.append(h).append('\n');
            for (String line : lines) {
                if (line.isBlank()) continue;
                JsonObject rec = tryParse(line);
                if (rec == null) continue;                      // torn/damaged v1 line — drop (was unloadable anyway)
                if (EV_COMPACT.equals(str(rec.get("role")))) {  // legacy role:"compact" → type:"compact"
                    rec.remove("role");
                    rec.addProperty("type", EV_COMPACT);
                }
                if (!rec.has("ts")) rec.addProperty("ts", ts++);
                sb.append(rec).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            moveInPlace(tmp, file);
            Constants.LOG.info("[numen-convo] migrated {} to format v{}", file.getFileName(), FORMAT_VERSION);
        } catch (Exception ex) {
            // Leave the original v1 file untouched — the reader still loads it. Retry next launch.
            Constants.LOG.warn("[numen-convo] migration of {} failed, keeping v1 as-is: {}",
                    file.getFileName(), ex.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void moveInPlace(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);   // fall back: still temp→dest, no in-place edit
        }
    }

    private long fileAnchorMillis() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            return System.currentTimeMillis();
        }
    }

    // ---- read ----

    /**
     * Load the protocol-valid tail of the conversation for the LLM: messages only (events skipped), the
     * last {@code limit} extended backwards to the nearest {@code user} message so the slice starts at a
     * turn boundary. A {@code compact} event restarts the replay from its summary + preserved tail.
     */
    public List<ConvoState.Msg> load(int limit) {
        if (!Files.isRegularFile(file)) return List.of();

        List<ConvoState.Msg> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o = tryParse(line);
                if (o == null) {
                    Constants.LOG.warn("[numen-convo] skipping unparsable line in {}", file.getFileName());
                    continue;
                }
                String type = eventType(o);
                if (type != null) {                              // event record
                    if (EV_COMPACT.equals(type)) {
                        all.clear();
                        all.add(new ConvoState.Msg.User(str(o.get("content"))));
                        if (o.has("preserved") && o.get("preserved").isJsonArray()) {
                            for (JsonElement el : o.getAsJsonArray("preserved")) {
                                ConvoState.Msg m = decodeMessage(el.getAsJsonObject());
                                if (m != null) all.add(m);
                            }
                        }
                    }
                    // header / persona-change / goal / unknown → not part of the LLM context
                    continue;
                }
                ConvoState.Msg m = decodeMessage(o);             // message record
                if (m != null) all.add(m);                       // unknown role → skip (forward-compat)
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-convo] failed to read {}: {}", file, ex.toString());
            return List.of();
        }
        if (all.size() <= limit) return all;

        // Tail-trim, then walk back to the nearest user message (a slice opening on a tool result or
        // mid-chain assistant turn is rejected by the API; a conversation always begins with user).
        int start = all.size() - limit;
        while (start > 0 && !(all.get(start) instanceof ConvoState.Msg.User)) {
            start--;
        }
        List<ConvoState.Msg> tail = all.subList(start, all.size());
        Constants.LOG.info("[numen-convo] loaded {}/{} msgs from {}",
                tail.size(), all.size(), file.getFileName());
        return new ArrayList<>(tail);
    }

    /**
     * Load the PHYSICAL tail for the chat GUI: messages in file order; {@code compact} and
     * {@code persona-change} events become sentinel divider messages instead of restarting or vanishing.
     * This is the "what actually happened" view — history is never lost to compaction.
     */
    public List<ConvoState.Msg> loadDisplay(int limit) {
        if (!Files.isRegularFile(file)) return List.of();

        List<ConvoState.Msg> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o = tryParse(line);
                if (o == null) {
                    Constants.LOG.warn("[numen-convo] skipping unparsable line in {}", file.getFileName());
                    continue;
                }
                String type = eventType(o);
                if (type != null) {                              // event record
                    if (EV_COMPACT.equals(type)) all.add(new ConvoState.Msg.User(COMPACT_DIVIDER));
                    else if (EV_PERSONA.equals(type)) all.add(new ConvoState.Msg.User(PERSONA_DIVIDER));
                    // header / goal / unknown → not shown
                    continue;
                }
                ConvoState.Msg m = decodeMessage(o);
                if (m != null) all.add(m);
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-convo] failed to read {}: {}", file, ex.toString());
            return List.of();
        }
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /** The companion's current persona from the latest {@code persona-change} event, or {@code null} if none. */
    public PersonaState loadCurrentPersona() {
        if (!Files.isRegularFile(file)) return null;
        PersonaState current = null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o = tryParse(line);
                if (o != null && EV_PERSONA.equals(eventType(o))) {
                    current = new PersonaState(str(o.get("id")), str(o.get("content")), str(o.get("name")));
                }
            }
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-convo] failed to read persona from {}: {}", file, ex.toString());
        }
        return current;
    }

    /**
     * Tool-call ids in {@code history} that have no matching tool result — the signature of a session
     * killed mid-task. The caller synthesizes "interrupted" results for these or the next request 400s.
     */
    public static List<String> unansweredToolCallIds(List<ConvoState.Msg> history) {
        Set<String> answered = new HashSet<>();
        for (ConvoState.Msg msg : history) {
            if (msg instanceof ConvoState.Msg.Tool t) answered.add(t.toolCallId());
        }
        List<String> unanswered = new ArrayList<>();
        for (ConvoState.Msg msg : history) {
            if (msg instanceof ConvoState.Msg.Assistant a) {
                for (LlmToolCall tc : a.turn().toolCalls()) {
                    if (!answered.contains(tc.id())) unanswered.add(tc.id());
                }
            }
        }
        return unanswered;
    }

    // ---- codec ----

    /**
     * The event type of a record, or {@code null} if it is a message. A {@code type} field means event;
     * the legacy v1 {@code role:"compact"} line is aliased to the {@code compact} event.
     */
    private static String eventType(JsonObject o) {
        if (o.has("type")) return str(o.get("type"));
        if (EV_COMPACT.equals(str(o.get("role")))) return EV_COMPACT;
        return null;
    }

    private static JsonObject encode(ConvoState.Msg msg) {
        JsonObject o = new JsonObject();
        switch (msg) {
            case ConvoState.Msg.User u -> {
                o.addProperty("role", "user");
                o.addProperty("content", u.content());
            }
            case ConvoState.Msg.Assistant a -> {
                o.addProperty("role", "assistant");
                o.addProperty("content", a.turn().content());
                if (a.turn().hasToolCalls()) {
                    JsonArray calls = new JsonArray();
                    for (LlmToolCall tc : a.turn().toolCalls()) {
                        JsonObject c = new JsonObject();
                        c.addProperty("id", tc.id());
                        c.addProperty("name", tc.name());
                        c.addProperty("arguments", tc.arguments());
                        calls.add(c);
                    }
                    o.add("tool_calls", calls);
                }
                // Provider extras (e.g. DeepSeek reasoning_content) must survive the round-trip.
                if (!a.turn().extras().entrySet().isEmpty()) {
                    o.add("extras", a.turn().extras());
                }
            }
            case ConvoState.Msg.Tool t -> {
                o.addProperty("role", "tool");
                o.addProperty("tool_call_id", t.toolCallId());
                o.addProperty("content", t.content());
            }
        }
        return o;
    }

    /** Decode a message record, or {@code null} for an unknown role (forward-compat: skip, don't crash). */
    private static ConvoState.Msg decodeMessage(JsonObject o) {
        String role = str(o.get("role"));
        return switch (role) {
            case "user" -> new ConvoState.Msg.User(str(o.get("content")));
            case "tool" -> new ConvoState.Msg.Tool(str(o.get("tool_call_id")), str(o.get("content")));
            case "assistant" -> {
                List<LlmToolCall> calls = new ArrayList<>();
                if (o.has("tool_calls") && o.get("tool_calls").isJsonArray()) {
                    for (JsonElement el : o.getAsJsonArray("tool_calls")) {
                        JsonObject c = el.getAsJsonObject();
                        calls.add(new LlmToolCall(
                                str(c.get("id")), str(c.get("name")), str(c.get("arguments"))));
                    }
                }
                JsonObject extras = o.has("extras") && o.get("extras").isJsonObject()
                        ? o.getAsJsonObject("extras") : null;
                yield new ConvoState.Msg.Assistant(new AssistantTurn(str(o.get("content")), calls, extras));
            }
            default -> null;   // unknown role → forward-compat skip
        };
    }

    private static JsonObject tryParse(String line) {
        try {
            JsonElement el = JsonParser.parseString(line);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String firstNonBlank(List<String> lines) {
        for (String s : lines) {
            if (!s.isBlank()) return s;
        }
        return null;
    }

    private static String str(JsonElement el) {
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }
}
