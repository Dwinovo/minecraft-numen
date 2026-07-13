package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.util.SafeJsonStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Per-companion long-term semantic memory: named places, storage locations,
 * owner preferences and durable notes. Stored client-side next to conversation
 * logs and injected into the system prompt each turn.
 */
public final class LongTermMemory {

    public enum Category {
        LOCATION("location"),
        STORAGE("storage"),
        PREFERENCE("preference"),
        NOTE("note");

        private final String id;
        Category(String id) { this.id = id; }
        public String id() { return id; }

        public static Category parse(String raw) {
            if (raw == null || raw.isBlank()) return NOTE;
            String n = raw.trim().toLowerCase(Locale.ROOT);
            for (Category c : values()) if (c.id.equals(n) || c.name().equalsIgnoreCase(n)) return c;
            return NOTE;
        }
    }

    public record Entry(Category category, String label, String content,
                        String dimension, Integer x, Integer y, Integer z,
                        long createdAt, long updatedAt) {}

    private static final int MAX_ENTRIES = 96;

    private final Path file;
    private final List<Entry> entries = new ArrayList<>();

    private LongTermMemory(Path file) {
        this.file = file;
        load();
    }

    public static LongTermMemory forEntity(Path memoryDir, UUID entityUuid) {
        return new LongTermMemory(memoryDir.resolve(entityUuid + ".longterm.json"));
    }

    public synchronized Entry remember(String category, String label, String content,
                                       String dimension, Integer x, Integer y, Integer z) {
        Category cat = Category.parse(category);
        String normalizedLabel = clean(label, cat.id());
        String normalizedContent = clean(content, "");
        long now = Instant.now().toEpochMilli();
        Entry replacement = new Entry(cat, normalizedLabel, normalizedContent,
                blankToNull(dimension), x, y, z, now, now);

        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (old.category == cat && old.label.equalsIgnoreCase(normalizedLabel)) {
                replacement = new Entry(cat, normalizedLabel, normalizedContent,
                        replacement.dimension, replacement.x, replacement.y, replacement.z,
                        old.createdAt, now);
                entries.set(i, replacement);
                save();
                return replacement;
            }
        }
        entries.add(replacement);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        save();
        return replacement;
    }

    public synchronized List<Entry> search(String query, String category, int limit) {
        Category cat = category == null || category.isBlank() ? null : Category.parse(category);
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        int max = Math.max(1, Math.min(32, limit <= 0 ? 8 : limit));
        ArrayList<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < max; i--) {
            Entry e = entries.get(i);
            if (cat != null && e.category != cat) continue;
            if (!q.isBlank()) {
                String hay = (e.label + "\n" + e.content + "\n" + nullToEmpty(e.dimension)).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            out.add(e);
        }
        return out;
    }

    public synchronized String formatXml() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(256);
        sb.append("<long_term_memory>\n");
        sb.append("  Durable facts you should preserve across sessions. Use them when planning; update them with remember_memory when the owner gives a stable preference or names a place/storage.\n");
        for (Entry e : entries) {
            sb.append("  <memory category=\"").append(e.category.id()).append("\" label=\"")
              .append(escape(e.label)).append("\"");
            if (e.dimension != null) sb.append(" dimension=\"").append(escape(e.dimension)).append("\"");
            if (e.x != null && e.y != null && e.z != null) {
                sb.append(" x=\"").append(e.x).append("\" y=\"").append(e.y)
                  .append("\" z=\"").append(e.z).append("\"");
            }
            sb.append(">").append(escape(e.content)).append("</memory>\n");
        }
        sb.append("</long_term_memory>");
        return sb.toString();
    }

    private void load() {
        if (!Files.isRegularFile(file) && !Files.isRegularFile(SafeJsonStore.backup(file))) return;
        try {
            var stored = SafeJsonStore.read(file, value -> value.getAsJsonArray());
            if (stored.value().isEmpty()) return;
            if (stored.recoveredFromBackup()) Constants.LOG.warn("[numen-memory] recovered {} from backup", file);
            JsonArray arr = stored.value().orElseThrow();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                entries.add(new Entry(
                        Category.parse(str(o, "category")),
                        clean(str(o, "label"), "note"),
                        clean(str(o, "content"), ""),
                        blankToNull(str(o, "dimension")),
                        optInt(o, "x"), optInt(o, "y"), optInt(o, "z"),
                        optLong(o, "created_at"), optLong(o, "updated_at")));
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-memory] failed to load long-term memory {}: {}", file, ex.toString());
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("category", e.category.id());
            o.addProperty("label", e.label);
            o.addProperty("content", e.content);
            if (e.dimension != null) o.addProperty("dimension", e.dimension);
            if (e.x != null) o.addProperty("x", e.x);
            if (e.y != null) o.addProperty("y", e.y);
            if (e.z != null) o.addProperty("z", e.z);
            o.addProperty("created_at", e.createdAt);
            o.addProperty("updated_at", e.updatedAt);
            arr.add(o);
        }
        try {
            SafeJsonStore.write(file, arr.toString(), value -> value.getAsJsonArray());
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-memory] failed to save long-term memory {}: {}", file, ex.toString());
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
    private static Integer optInt(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : null; }
    private static long optLong(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L; }
    private static String clean(String s, String fallback) { return s == null || s.isBlank() ? fallback : s.trim(); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static String escape(String s) {
        return nullToEmpty(s).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
