package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.util.SafeJsonStore;
import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Integrity envelope, repair, backup and bounded snapshot layer for a JSONL conversation. */
final class ConversationJournal {
    static final int SNAPSHOT_INTERVAL = 64;
    private static final int SNAPSHOT_MAX_ENTRIES = 1_000;
    private static final int SNAPSHOT_SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Consumer<JsonObject> validator;
    /** Rolling known-good tail used for snapshots; avoids re-reading a growing journal every 64 appends. */
    private final ArrayDeque<JsonObject> snapshotTail = new ArrayDeque<>(SNAPSHOT_MAX_ENTRIES);
    private long nextSequence = -1L;
    private long knownEntryCount = -1L;
    private boolean historyTruncated;
    private int appendsSinceSnapshot;

    ConversationJournal(Path file, Consumer<JsonObject> validator) {
        this.file = file;
        this.validator = validator == null ? ignored -> { } : validator;
    }

    synchronized void append(JsonObject payload, boolean preserveBackup) throws IOException {
        validator.accept(payload);
        Files.createDirectories(file.getParent());
        if (preserveBackup && Files.isRegularFile(file)) {
            // Validate and repair the primary before replacing the known-good
            // pre-compaction backup. A hand-edited or crash-damaged primary
            // must never overwrite the last usable recovery point.
            read();
        }
        if (preserveBackup && Files.isRegularFile(file)) {
            Files.copy(file, backup(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (nextSequence < 0L) nextSequence = scanNextSequence();
        JsonObject line = payload.deepCopy();
        line.addProperty("seq", nextSequence++);
        line.addProperty("checksum", checksum(line));
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        remember(line);
        if (knownEntryCount < 0L) knownEntryCount = snapshotTail.size();
        else knownEntryCount++;
        if (++appendsSinceSnapshot >= SNAPSHOT_INTERVAL || preserveBackup) snapshot();
    }

    synchronized List<JsonObject> read() throws IOException {
        if (!Files.isRegularFile(file)) {
            if (Files.isRegularFile(backup()) && restoreFromJsonl(backup(), "backup")) return readRecovered();
            if (restoreSnapshot()) return readRecovered();
            return List.of();
        }
        try {
            return readPrimary();
        } catch (MiddleDamage damage) {
            Constants.LOG.warn("[numen-convo] integrity failure at line {} in {}; attempting recovery",
                    damage.lineNumber, file.getFileName());
            quarantine(file, "middle-damage");
            if (Files.isRegularFile(backup()) && restoreFromJsonl(backup(), "backup")) return readRecovered();
            if (restoreSnapshot()) return readRecovered();
            Constants.LOG.error("[numen-convo] no valid backup or snapshot for {}; starting with empty history",
                    file.getFileName());
            nextSequence = 0L;
            snapshotTail.clear();
            knownEntryCount = 0L;
            historyTruncated = false;
            return List.of();
        }
    }

    synchronized void delete() throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(backup());
        Files.deleteIfExists(snapshotFile());
        Files.deleteIfExists(SafeJsonStore.backup(snapshotFile()));
        nextSequence = 0L;
        snapshotTail.clear();
        knownEntryCount = 0L;
        historyTruncated = false;
        appendsSinceSnapshot = 0;
    }

    private List<JsonObject> readPrimary() throws IOException, MiddleDamage {
        byte[] bytes = Files.readAllBytes(file);
        ArrayList<JsonObject> entries = new ArrayList<>();
        int lineStart = 0;
        int lineNumber = 0;
        long lastSequence = -1L;
        for (int i = 0; i <= bytes.length; i++) {
            boolean end = i == bytes.length;
            if (!end && bytes[i] != '\n') continue;
            int lineEnd = i;
            if (lineEnd > lineStart && bytes[lineEnd - 1] == '\r') lineEnd--;
            String line = new String(bytes, lineStart, Math.max(0, lineEnd - lineStart), StandardCharsets.UTF_8);
            lineNumber++;
            if (!line.isBlank()) {
                JsonObject object;
                try {
                    object = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException invalidJson) {
                    if (end && i == bytes.length && lineStart < bytes.length) {
                        preserveTailAndTruncate(bytes, lineStart);
                        Constants.LOG.warn("[numen-convo] truncated torn tail at line {} in {}",
                                lineNumber, file.getFileName());
                        break;
                    }
                    throw new MiddleDamage(lineNumber, invalidJson);
                }
                try {
                    long sequence = verify(object, lastSequence);
                    validator.accept(object);
                    if (sequence >= 0L) lastSequence = sequence;
                    entries.add(object);
                } catch (RuntimeException invalid) {
                    throw new MiddleDamage(lineNumber, invalid);
                }
            }
            lineStart = i + 1;
        }
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n' && lineStart >= bytes.length) {
            Files.writeString(file, "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
        nextSequence = Math.max(0L, lastSequence + 1L);
        rememberAll(entries);
        return List.copyOf(entries);
    }

    private List<JsonObject> readRecovered() throws IOException {
        try { return readPrimary(); }
        catch (MiddleDamage damage) {
            throw new IOException("recovered conversation journal still failed integrity validation at line "
                    + damage.lineNumber, damage);
        }
    }

    private static long verify(JsonObject object, long previous) {
        boolean hasSeq = object.has("seq");
        boolean hasChecksum = object.has("checksum");
        if (!hasSeq && !hasChecksum) {
            if (previous >= 0L) throw new IllegalArgumentException("legacy record after integrity envelope");
            return -1L; // legacy prefix
        }
        if (!hasSeq || !hasChecksum) throw new IllegalArgumentException("incomplete integrity envelope");
        long sequence = object.get("seq").getAsLong();
        if (sequence <= previous) throw new IllegalArgumentException("non-monotonic sequence");
        String expected = object.get("checksum").getAsString();
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                checksum(object).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("checksum mismatch");
        }
        return sequence;
    }

    private long scanNextSequence() throws IOException {
        List<JsonObject> lines = read();
        long max = -1L;
        for (JsonObject line : lines) if (line.has("seq")) max = Math.max(max, line.get("seq").getAsLong());
        return max + 1L;
    }

    private void snapshot() throws IOException {
        if (knownEntryCount < 0L) {
            try { readPrimary(); }
            catch (MiddleDamage damage) { throw new IOException("cannot snapshot damaged journal", damage); }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", SNAPSHOT_SCHEMA);
        root.addProperty("truncated", historyTruncated || knownEntryCount > SNAPSHOT_MAX_ENTRIES);
        JsonArray array = new JsonArray();
        snapshotTail.forEach(array::add);
        root.add("entries", array);
        SafeJsonStore.write(snapshotFile(), GSON.toJson(root), this::validateSnapshot);
        appendsSinceSnapshot = 0;
    }

    private boolean restoreSnapshot() {
        try {
            var stored = SafeJsonStore.read(snapshotFile(), this::validateSnapshot);
            if (stored.value().isEmpty()) return false;
            JsonObject snapshot = stored.value().orElseThrow();
            writeEntries(snapshot.getAsJsonArray("entries"));
            historyTruncated = snapshot.has("truncated") && snapshot.get("truncated").getAsBoolean();
            Constants.LOG.warn("[numen-convo] restored {} from conversation snapshot", file.getFileName());
            return true;
        } catch (IOException | RuntimeException failure) {
            Constants.LOG.warn("[numen-convo] snapshot recovery failed for {}: {}", file.getFileName(), failure.toString());
            return false;
        }
    }

    private boolean restoreFromJsonl(Path source, String label) {
        try {
            byte[] bytes = Files.readAllBytes(source);
            ArrayList<JsonObject> valid = new ArrayList<>();
            long previous = -1L;
            int number = 0;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
                number++;
                if (line.isBlank()) continue;
                JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                long seq = verify(object, previous); if (seq >= 0L) previous = seq;
                validator.accept(object);
                valid.add(object);
            }
            JsonArray array = new JsonArray(); valid.forEach(array::add); writeEntries(array);
            Constants.LOG.warn("[numen-convo] restored {} from {}", file.getFileName(), label);
            return true;
        } catch (IOException | RuntimeException failure) {
            Constants.LOG.warn("[numen-convo] invalid {} for {}: {}", label, file.getFileName(), failure.toString());
            return false;
        }
    }

    private void writeEntries(JsonArray entries) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".restore.tmp");
        StringBuilder out = new StringBuilder();
        for (JsonElement entry : entries) out.append(entry).append('\n');
        Files.writeString(temp, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
        nextSequence = -1L;
        snapshotTail.clear();
        knownEntryCount = -1L;
        historyTruncated = false;
    }

    private void rememberAll(List<JsonObject> entries) {
        snapshotTail.clear();
        int from = Math.max(0, entries.size() - SNAPSHOT_MAX_ENTRIES);
        for (int i = from; i < entries.size(); i++) remember(entries.get(i));
        knownEntryCount = entries.size();
        historyTruncated = historyTruncated || entries.size() > SNAPSHOT_MAX_ENTRIES;
    }

    private void remember(JsonObject entry) {
        if (snapshotTail.size() == SNAPSHOT_MAX_ENTRIES) snapshotTail.removeFirst();
        snapshotTail.addLast(entry.deepCopy());
    }

    private void preserveTailAndTruncate(byte[] bytes, int validLength) throws IOException {
        Path diagnostics = diagnostics(); Files.createDirectories(diagnostics);
        Path tail = diagnostics.resolve(file.getFileName() + ".torn-tail-" + Instant.now().toEpochMilli());
        Files.write(tail, Arrays.copyOfRange(bytes, validLength, bytes.length), StandardOpenOption.CREATE_NEW);
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.truncate(validLength); channel.force(true); }
    }

    private JsonObject validateSnapshot(JsonElement value) {
        JsonObject root = value.getAsJsonObject();
        if (!root.has("schema") || root.get("schema").getAsInt() != SNAPSHOT_SCHEMA
                || !root.has("entries") || !root.get("entries").isJsonArray()) {
            throw new IllegalArgumentException("invalid conversation snapshot");
        }
        long previous = -1L;
        for (JsonElement entry : root.getAsJsonArray("entries")) {
            JsonObject object = entry.getAsJsonObject();
            long seq = verify(object, previous); if (seq >= 0L) previous = seq;
            validator.accept(object);
        }
        return root;
    }

    private static String checksum(JsonObject object) {
        try {
            JsonObject copy = object.deepCopy(); copy.remove("checksum");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(copy).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String canonical(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";
        if (element.isJsonPrimitive()) return element.toString();
        if (element.isJsonArray()) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            for (JsonElement child : element.getAsJsonArray()) out.add(canonical(child));
            return out.toString();
        }
        TreeMap<String, JsonElement> sorted = new TreeMap<>();
        element.getAsJsonObject().entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
        StringJoiner out = new StringJoiner(",", "{", "}");
        sorted.forEach((key, child) -> out.add(new JsonPrimitive(key) + ":" + canonical(child)));
        return out.toString();
    }

    private void quarantine(Path source, String reason) throws IOException {
        if (!Files.exists(source)) return;
        Path diagnostics = diagnostics(); Files.createDirectories(diagnostics);
        Files.move(source, diagnostics.resolve(source.getFileName() + "." + reason + "-" + Instant.now().toEpochMilli()),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private Path backup() { return file.resolveSibling(file.getFileName() + ".bak"); }
    private Path snapshotFile() { return file.resolveSibling(file.getFileName() + ".snapshot.json"); }
    private Path diagnostics() { return file.getParent().resolve("diagnostics"); }

    private static final class MiddleDamage extends Exception {
        final int lineNumber;
        MiddleDamage(int lineNumber, Throwable cause) { super(cause); this.lineNumber = lineNumber; }
    }
}
