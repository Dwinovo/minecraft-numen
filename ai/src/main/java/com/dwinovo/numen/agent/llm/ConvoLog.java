package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.ai.AiLog;
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
import java.util.List;
import java.util.UUID;

/**
 * Append-only JSONL persistence for one entity's conversation —
 * {@code <gameDir>/config/numen/conversations/<entity-uuid>.jsonl}, one record
 * per line. The entity UUID is globally unique and dimension-stable, so the same
 * file follows the companion for its whole life.
 *
 * <h2>Record model (v3)</h2>
 * Every line is one JSON <em>record</em>, discriminated like Claude Code's transcript:
 * <ul>
 *   <li><b>Header</b> — {@code {"type":"header","v":3,...}} — always line 1 of a v2+ file.</li>
 *   <li><b>Messages</b> — the v1 shape, keyed by {@code role} = {@code user}/{@code assistant}/{@code tool},
 *       plus optional additive metadata ({@code ts}, and reserved {@code model}/{@code usage}).</li>
 *   <li><b>Events</b> — keyed by {@code type}, no {@code role}: {@code compact}, {@code clear},
 *       {@code persona-change}, {@code halt} (a turn cut off here, with its {@code reason}), and the reserved
 *       {@code goal}. Events record <em>what happened, and when</em> — never a copy of configuration
 *       (bindings live in the companion's {@code binding.json}, persona text in the library).</li>
 * </ul>
 * <b>Discriminator rule:</b> a record with a {@code type} field is an event; otherwise it's a message
 * dispatched on {@code role}. The legacy v1 {@code role:"compact"} line is read as a {@code compact} event.
 *
 * <h2>Two derived views</h2>
 * {@link #load} is the LLM context (compaction restarts the replay from its summary); {@link #loadDisplay}
 * is the physical transcript the GUI renders (compaction/clear/persona-change become sentinel divider lines, no
 * history is lost). A {@code halt} is part of what happened in both views: it replays as a
 * {@link ConvoState.Msg.Halt} — the LLM view leaves it to {@link ProtocolView}, the display view draws it as
 * an interruption divider. The other events are absent from the LLM view and appear only as dividers.
 *
 * <h2>Forward compatibility</h2>
 * A valid-JSON record with an unknown {@code type}/{@code role} is <em>skipped from both in-memory views
 * but kept on disk</em> (append-only) — a newer numen can add record kinds without an older one losing them.
 *
 * <h2>Migration (v1 → v2 → v3)</h2>
 * {@link #migrateIfNeeded} rewrites an older file to the current version atomically (temp →
 * {@code ATOMIC_MOVE}), keeping a {@code .v<N>.bak}. The reader still understands the v1 shape, and the
 * rewrite can never lose records (the original file is only ever replaced by a fully-written temp; on any
 * failure it is left untouched and the migration retries next launch).
 *
 * <h2>Best-effort by design</h2>
 * IO failures log a warning and the chat carries on in memory — persistence must never take the
 * companion offline.
 */
public final class ConvoLog {

    /** On-disk format version stamped in the header record; bumped when the record model changes. */
    public static final int FORMAT_VERSION = 3;

    /** Soft cap on messages replayed into context (the file itself is unbounded). */
    public static final int DEFAULT_LOAD_LIMIT = 200;

    /** Compaction-boundary sentinel in the DISPLAY view — the GUI draws a thin divider. Never sent to the LLM. */
    public static final String COMPACT_DIVIDER = "[numen:compact-divider]";
    /** Persona-change sentinel in the DISPLAY view — drawn as a "人设已切换" divider. Never sent to the LLM. */
    public static final String PERSONA_DIVIDER = "[numen:persona-divider]";
    /** Context-clear sentinel in the DISPLAY view — drawn as a "上下文已清除" divider. Never sent to the LLM. */
    public static final String CLEAR_DIVIDER = "[numen:clear-divider]";

    // Event type discriminators.
    private static final String EV_HEADER = "header";
    private static final String EV_COMPACT = "compact";
    private static final String EV_CLEAR = "clear";
    private static final String EV_PERSONA = "persona-change";
    private static final String EV_HALT = "halt";
    private static final String EV_GOAL = "goal";   // reserved (recognized, no behavior yet)

    /** The runtime-state block older builds persisted at the head of user messages (stripped by the v3 migration). */
    private static final String LEGACY_TASK_OPEN = "<current_task>";
    private static final String LEGACY_TASK_CLOSE = "</current_task>";

    private final Path file;

    private ConvoLog(Path file) {
        this.file = file;
    }

    /** The log for one entity under {@code conversationsDir}. Creates nothing until the first append. */
    /** 会话日志的落点由宿主给全路径(客户端按同伴归拢在 companions/&lt;uuid&gt;/)。 */
    public static ConvoLog atFile(Path file) {
        return new ConvoLog(file);
    }

    public Path file() {
        return file;
    }

    // ---- write ----

    /**
     * Append one message as a single JSONL line (with a {@code ts}); a {@link ConvoState.Msg.Halt} is written as
     * a {@code halt} event. Best-effort: failures only warn.
     */
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
     * 记一条"这里清空过上下文"的边界。{@link #load} 从它之后从零重放——对模型是真空白;
     * {@link #loadDisplay} 渲染成 {@link #CLEAR_DIVIDER}。文件 append-only,清空不删任何历史,
     * 之前的对话全部留档、界面照常能翻。
     */
    public void appendClearBoundary() {
        JsonObject o = new JsonObject();
        o.addProperty("type", EV_CLEAR);
        o.addProperty("ts", System.currentTimeMillis());
        writeLine(o);
    }

    /**
     * 记一条"这里换过人设"的分隔——只记<b>何时发生</b>,不记换成了什么:
     * 绑定的真源是同伴自己的 {@code binding.json},正文的真源是人设库。
     * 日志存副本就成了第二真源,改人设时必然对不上。
     * {@link #loadDisplay} 把它渲染成 {@link #PERSONA_DIVIDER};LLM 上下文里没有它。
     */
    public void appendPersonaDivider() {
        JsonObject o = new JsonObject();
        o.addProperty("type", EV_PERSONA);
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
            AiLog.LOG.warn("[numen-convo] failed to append to {}: {}", file, ex.toString());
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
            AiLog.LOG.warn("[numen-convo] failed to delete {}: {}", file, ex.toString());
        }
    }

    // ---- migration ----

    /**
     * Bring an older file up to {@link #FORMAT_VERSION} (idempotent, crash-safe). Does nothing to a current (or
     * newer) file, a missing or empty file, or (on failure) the original. Never deletes or half-writes the
     * original: after a one-time {@code .v<N>.bak} copy it is only ever replaced by a fully-written temp via an
     * atomic move.
     * <ul>
     *   <li><b>v1 → v2</b>: add the header; the legacy {@code role:"compact"} line becomes a {@code compact}
     *       event; records without {@code ts} get one.</li>
     *   <li><b>v2 → v3</b>: strip the {@code <current_task>} block older builds persisted at the head of user
     *       messages — top-level ones, compact summaries (they replay as the first user message) and the user
     *       messages preserved inside them. Runtime state is attached per request now; a stale copy left in
     *       history would contradict the live one on every request.</li>
     * </ul>
     * Runs once because the new header version lands in the same atomic swap as the rewritten records: a crash
     * before the swap leaves the old version in place and the whole rewrite simply runs again next launch.
     */
    public void migrateIfNeeded() {
        if (!Files.isRegularFile(file)) return;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String first = firstNonBlank(lines);
            if (first == null) return;                          // empty file — leave it
            JsonObject head = tryParse(first);
            boolean hasHeader = head != null && EV_HEADER.equals(str(head.get("type")));
            int from = hasHeader ? head.get("v").getAsInt() : 1;   // every header ever written carries v
            if (from >= FORMAT_VERSION) return;

            // Keep a one-time backup, then rebuild into a temp and atomically swap in.
            Path bak = file.resolveSibling(file.getFileName() + ".v" + from + ".bak");
            if (!Files.exists(bak)) Files.copy(file, bak);

            long ts = fileAnchorMillis();
            StringBuilder sb = new StringBuilder(lines.size() * 64 + 64);
            JsonObject h = hasHeader ? head.deepCopy() : new JsonObject();
            if (!hasHeader) {
                h.addProperty("type", EV_HEADER);
                h.addProperty("created", ts);
            }
            h.addProperty("v", FORMAT_VERSION);
            h.addProperty("migrated", true);
            sb.append(h).append('\n');
            boolean oldHeaderSkipped = !hasHeader;
            for (String line : lines) {
                if (line.isBlank()) continue;
                JsonObject rec = tryParse(line);
                if (rec == null) continue;                      // torn/damaged line — drop (was unloadable anyway)
                if (!oldHeaderSkipped && EV_HEADER.equals(str(rec.get("type")))) {
                    oldHeaderSkipped = true;                    // replaced by the rewritten header above
                    continue;
                }
                if (from < 2) {
                    if (EV_COMPACT.equals(str(rec.get("role")))) {  // legacy role:"compact" → type:"compact"
                        rec.remove("role");
                        rec.addProperty("type", EV_COMPACT);
                    }
                    if (!rec.has("ts")) rec.addProperty("ts", ts++);
                }
                stripLegacyCurrentTask(rec);
                sb.append(rec).append('\n');
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            moveInPlace(tmp, file);
            AiLog.LOG.info("[numen-convo] migrated {} from format v{} to v{}", file.getFileName(), from, FORMAT_VERSION);
        } catch (Exception ex) {
            // Leave the original file untouched — the reader still loads it. Retry next launch.
            AiLog.LOG.warn("[numen-convo] migration of {} failed, keeping it as-is: {}",
                    file.getFileName(), ex.toString());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** v2 → v3: strip the persisted {@code <current_task>} block from every user-message text in {@code rec}. */
    private static void stripLegacyCurrentTask(JsonObject rec) {
        if ("user".equals(str(rec.get("role")))) {
            stripContent(rec);
        } else if (EV_COMPACT.equals(str(rec.get("type")))) {
            stripContent(rec);
            if (rec.has("preserved") && rec.get("preserved").isJsonArray()) {
                for (JsonElement el : rec.getAsJsonArray("preserved")) {
                    if (el.isJsonObject() && "user".equals(str(el.getAsJsonObject().get("role")))) {
                        stripContent(el.getAsJsonObject());
                    }
                }
            }
        }
    }

    private static void stripContent(JsonObject o) {
        String content = str(o.get("content"));
        String stripped = withoutLegacyCurrentTask(content);
        if (!stripped.equals(content)) {
            o.addProperty("content", stripped);
        }
    }

    /**
     * The text without the generated {@code <current_task>} block older builds put in front of the owner's
     * words. A block that starts inside {@code <query>} is the owner's own text and is left alone.
     */
    private static String withoutLegacyCurrentTask(String content) {
        int open = content.indexOf(LEGACY_TASK_OPEN);
        int query = content.indexOf("<query>");
        if (open < 0 || (query >= 0 && open > query)) return content;
        int close = content.indexOf(LEGACY_TASK_CLOSE, open + LEGACY_TASK_OPEN.length());
        if (close < 0) return content;
        int end = close + LEGACY_TASK_CLOSE.length();
        if (end < content.length() && content.charAt(end) == '\r') end++;
        if (end < content.length() && content.charAt(end) == '\n') end++;
        return (content.substring(0, open) + content.substring(end)).strip();
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
     * Load the tail of the conversation for the LLM: messages and cut-off points (other events skipped), the
     * last {@code limit} extended backwards to the nearest {@code user} message so the slice starts at a
     * turn boundary. A {@code compact} event restarts the replay from its summary + preserved tail;
     * a {@code clear} event restarts it from nothing; a {@code halt} event replays as a
     * {@link ConvoState.Msg.Halt} where the turn was cut off.
     */
    public List<ConvoState.Msg> load(int limit) {
        if (!Files.isRegularFile(file)) return List.of();

        List<ConvoState.Msg> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o = tryParse(line);
                if (o == null) {
                    AiLog.LOG.warn("[numen-convo] skipping unparsable line in {}", file.getFileName());
                    continue;
                }
                String type = eventType(o);
                if (type != null) {                              // event record
                    if (EV_COMPACT.equals(type)) {
                        all.clear();
                        all.add(new ConvoState.Msg.User(str(o.get("content"))));
                        if (o.has("preserved") && o.get("preserved").isJsonArray()) {
                            for (JsonElement el : o.getAsJsonArray("preserved")) {
                                ConvoState.Msg m = decode(el.getAsJsonObject());
                                if (m != null) all.add(m);
                            }
                        }
                    } else if (EV_CLEAR.equals(type)) {
                        all.clear();   // 白纸重来:无摘要、无保留
                    } else if (EV_HALT.equals(type)) {
                        all.add(decodeHalt(o));
                    }
                    // header / persona-change / goal / unknown → not part of the LLM context
                    continue;
                }
                ConvoState.Msg m = decodeMessage(o);             // message record
                if (m != null) all.add(m);                       // unknown role → skip (forward-compat)
            }
        } catch (IOException ex) {
            AiLog.LOG.warn("[numen-convo] failed to read {}: {}", file, ex.toString());
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
        AiLog.LOG.info("[numen-convo] loaded {}/{} msgs from {}",
                tail.size(), all.size(), file.getFileName());
        return new ArrayList<>(tail);
    }

    /**
     * Load the PHYSICAL tail for the chat GUI: messages in file order; {@code compact}, {@code clear}
     * and {@code persona-change} events become sentinel divider messages instead of restarting or
     * vanishing, and a {@code halt} event stays the {@link ConvoState.Msg.Halt} it was appended as —
     * the GUI draws it as an interruption divider carrying its reason. This is the "what actually
     * happened" view — history is never lost to compaction or clearing.
     */
    public List<ConvoState.Msg> loadDisplay(int limit) {
        if (!Files.isRegularFile(file)) return List.of();

        List<ConvoState.Msg> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o = tryParse(line);
                if (o == null) {
                    AiLog.LOG.warn("[numen-convo] skipping unparsable line in {}", file.getFileName());
                    continue;
                }
                String type = eventType(o);
                if (type != null) {                              // event record
                    if (EV_COMPACT.equals(type)) all.add(new ConvoState.Msg.User(COMPACT_DIVIDER));
                    else if (EV_CLEAR.equals(type)) all.add(new ConvoState.Msg.User(CLEAR_DIVIDER));
                    else if (EV_PERSONA.equals(type)) all.add(new ConvoState.Msg.User(PERSONA_DIVIDER));
                    else if (EV_HALT.equals(type)) all.add(decodeHalt(o));
                    // header / goal / unknown → not shown
                    continue;
                }
                ConvoState.Msg m = decodeMessage(o);
                if (m != null) all.add(m);
            }
        } catch (IOException ex) {
            AiLog.LOG.warn("[numen-convo] failed to read {}: {}", file, ex.toString());
            return List.of();
        }
        if (all.size() <= limit) return all;
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    /**
     * <b>迁移专用</b>:旧版把人设绑定记在 {@code persona-change} 事件的 {@code id} 里
     * (事件溯源、后写胜出)。返回最后一条的 id 供 {@code binding.json} 接管;新写的分隔
     * 不带 id,所以迁移完这里恒返回 null。旧布局清空后整个方法可以删掉。
     */
    public String legacyPersonaId() {
        if (!Files.isRegularFile(file)) return null;
        String current = null;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonObject o = tryParse(line);
                if (o != null && EV_PERSONA.equals(eventType(o))) {
                    String id = str(o.get("id"));
                    current = id == null || id.isBlank() ? null : id;
                }
            }
        } catch (IOException ex) {
            AiLog.LOG.warn("[numen-convo] failed to read persona from {}: {}", file, ex.toString());
        }
        return current;
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
            case ConvoState.Msg.Halt h -> {
                o.addProperty("type", EV_HALT);
                o.addProperty("reason", h.reason());
            }
        }
        return o;
    }

    /**
     * Decode a record {@link #encode} produces — a message, or a {@code halt} event — or {@code null} for
     * anything else (forward-compat: skip, don't crash). Used where encoded records are nested: the
     * {@code preserved} list of a compact event.
     */
    private static ConvoState.Msg decode(JsonObject o) {
        return EV_HALT.equals(eventType(o)) ? decodeHalt(o) : decodeMessage(o);
    }

    private static ConvoState.Msg.Halt decodeHalt(JsonObject o) {
        return new ConvoState.Msg.Halt(str(o.get("reason")));
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
