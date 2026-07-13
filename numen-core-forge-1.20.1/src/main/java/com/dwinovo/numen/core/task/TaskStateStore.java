package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.util.SafeJsonStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Schema-2 task persistence. Runtime navigation/scans are deliberately reconstructed, never encoded. */
final class TaskStateStore {

    static final int CURRENT_SCHEMA = 2;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "task-state.json";

    record RestoredQueue(UUID companionUuid, boolean paused, List<TaskRecord> records) {}
    record Loaded(List<RestoredQueue> queues, Map<CompanionTickDispatcher.TaskKey, String> results) {
        static Loaded empty() { return new Loaded(List.of(), Map.of()); }
    }

    private TaskStateStore() {}

    static void save(MinecraftServer server, Collection<TaskQueueSnapshot> snapshots,
                     Map<CompanionTickDispatcher.TaskKey, String> completedResults) {
        if (server == null) return;
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("numen");
        Path file = dir.resolve(FILE_NAME);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(dir);
            JsonObject root = new JsonObject();
            root.addProperty("schema", CURRENT_SCHEMA);
            JsonArray queues = new JsonArray();
            for (TaskQueueSnapshot snapshot : snapshots) {
                JsonObject queue = new JsonObject();
                queue.addProperty("companion_uuid", snapshot.companionUuid().toString());
                queue.addProperty("paused", snapshot.paused());
                JsonArray records = new JsonArray();
                long effectiveNow = snapshot.paused() ? snapshot.pausedAtGameTime() : snapshot.gameTime();
                if (snapshot.active() != null) records.add(encodeRecord(snapshot.active(), effectiveNow, true));
                for (TaskRecord record : snapshot.pending()) records.add(encodeRecord(record, effectiveNow, false));
                queue.add("tasks", records);
                queues.add(queue);
            }
            root.add("queues", queues);
            JsonArray results = new JsonArray();
            for (Map.Entry<CompanionTickDispatcher.TaskKey, String> entry : completedResults.entrySet()) {
                JsonObject result = new JsonObject();
                result.addProperty("companion_uuid", entry.getKey().companionUuid().toString());
                result.addProperty("tool_call_id", entry.getKey().toolCallId());
                result.addProperty("result_json", entry.getValue());
                results.add(result);
            }
            root.add("completed_results", results);
            SafeJsonStore.write(file, GSON.toJson(root), value -> {
                JsonObject parsed = value.getAsJsonObject();
                if (!parsed.has("schema") || parsed.get("schema").getAsInt() != CURRENT_SCHEMA)
                    throw new IllegalArgumentException("invalid task-state schema");
                return parsed;
            });
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            Constants.LOG.warn("Failed to persist Numen task state to {}", file, e);
        }
    }

    static Loaded load(MinecraftServer server) {
        if (server == null) return Loaded.empty();
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("numen").resolve(FILE_NAME);
        if (!Files.isRegularFile(file) && !Files.isRegularFile(SafeJsonStore.backup(file))) return Loaded.empty();
        try {
            var stored = SafeJsonStore.read(file, value -> value.getAsJsonObject());
            if (stored.value().isEmpty()) return Loaded.empty();
            if (stored.recoveredFromBackup()) Constants.LOG.warn("Recovered Numen task state from backup {}", SafeJsonStore.backup(file));
            JsonObject root = stored.value().orElseThrow();
            int schema = root.has("schema") ? root.get("schema").getAsInt() : 0;
            if (schema != CURRENT_SCHEMA) {
                Constants.LOG.warn("Ignoring unsupported Numen task-state schema {} in {}", schema, file);
                return Loaded.empty();
            }
            long now = server.overworld().getGameTime();
            List<RestoredQueue> queues = new ArrayList<>();
            JsonArray queueArray = root.has("queues") && root.get("queues").isJsonArray()
                    ? root.getAsJsonArray("queues") : new JsonArray();
            for (JsonElement queueEl : queueArray) {
                try {
                    JsonObject queue = queueEl.getAsJsonObject();
                    UUID companion = UUID.fromString(string(queue, "companion_uuid"));
                    boolean paused = queue.has("paused") && queue.get("paused").getAsBoolean();
                    List<TaskRecord> records = new ArrayList<>();
                    JsonArray taskArray = queue.has("tasks") && queue.get("tasks").isJsonArray()
                            ? queue.getAsJsonArray("tasks") : new JsonArray();
                    for (JsonElement taskEl : taskArray) {
                        try {
                            records.add(decodeRecord(taskEl.getAsJsonObject(), now));
                        } catch (RuntimeException ex) {
                            Constants.LOG.warn("Skipping damaged persisted task for companion {}: {}", companion, ex.toString());
                        }
                    }
                    if (!records.isEmpty() || paused) queues.add(new RestoredQueue(companion, paused, records));
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("Skipping damaged persisted task queue in {}: {}", file, ex.toString());
                }
            }
            LinkedHashMap<CompanionTickDispatcher.TaskKey, String> results = new LinkedHashMap<>();
            if (root.has("completed_results") && root.get("completed_results").isJsonArray()) {
                for (JsonElement el : root.getAsJsonArray("completed_results")) {
                    try {
                        JsonObject o = el.getAsJsonObject();
                        CompanionTickDispatcher.TaskKey key = new CompanionTickDispatcher.TaskKey(
                                UUID.fromString(string(o, "companion_uuid")), string(o, "tool_call_id"));
                        results.put(key, string(o, "result_json"));
                    } catch (RuntimeException ex) {
                        Constants.LOG.warn("Skipping damaged persisted task result in {}: {}", file, ex.toString());
                    }
                }
            }
            return new Loaded(List.copyOf(queues), Map.copyOf(results));
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("Ignoring unreadable Numen task state {}", file, ex);
            return Loaded.empty();
        }
    }

    static JsonObject encodeRecord(TaskRecord record, long now, boolean wasActive) {
        JsonObject out = new JsonObject();
        out.addProperty("tool_call_id", record.getToolCallId());
        out.addProperty("tool_name", record.getToolName());
        out.addProperty("arguments_json", record.getArgumentsJson());
        out.addProperty("description", record.describe());
        out.addProperty("was_active", wasActive);
        long recordNow = record.getState() == TaskState.PAUSED && record.getIndividuallyPausedAt() >= 0
                ? record.getIndividuallyPausedAt() : now;
        out.addProperty("remaining_timeout_ticks", Math.max(1L, record.getDeadlineGameTime() - recordNow));
        out.addProperty("recovery_attempts", record.getRecoveryAttempts());
        out.addProperty("individually_paused", record.getState() == TaskState.PAUSED);
        JsonObject encoded = TaskRecordCodecRegistry.encode(record);
        if (encoded != null) {
            out.addProperty("recoverable", true);
            out.add("parameters", encoded.get("parameters"));
            out.add("progress", encoded.get("progress"));
        } else {
            out.addProperty("recoverable", false);
            out.add("parameters", new JsonObject());
            out.add("progress", new JsonObject());
        }
        return out;
    }

    static TaskRecord decodeRecord(JsonObject o, long now) {
        String id = string(o, "tool_call_id");
        String tool = string(o, "tool_name");
        String args = o.has("arguments_json") ? o.get("arguments_json").getAsString() : "{}";
        long remaining = Math.max(1L, Math.min(1_728_000L, o.has("remaining_timeout_ticks")
                ? o.get("remaining_timeout_ticks").getAsLong() : 1L));
        if (!o.has("recoverable") || !o.get("recoverable").getAsBoolean()
                || !TaskRecordCodecRegistry.isRecoverableTool(tool)) {
            return new InterruptedTaskRecord(tool, id, now + remaining, args,
                    "interrupted: " + tool + " is not safe to replay after a server restart");
        }
        JsonObject params = o.has("parameters") && o.get("parameters").isJsonObject()
                ? o.getAsJsonObject("parameters") : new JsonObject();
        JsonObject progress = o.has("progress") && o.get("progress").isJsonObject()
                ? o.getAsJsonObject("progress") : new JsonObject();
        TaskRecord record = TaskRecordCodecRegistry.decode(tool, id, now + remaining, params, progress, args);
        record.markRestored(o.has("was_active") && o.get("was_active").getAsBoolean());
        if (o.has("individually_paused") && o.get("individually_paused").getAsBoolean()) record.pauseAt(now);
        if (o.has("recovery_attempts")) record.setRecoveryAttempts(o.get("recovery_attempts").getAsInt());
        return record;
    }

    private static String string(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) throw new IllegalArgumentException("missing " + key);
        return o.get(key).getAsString();
    }
}
