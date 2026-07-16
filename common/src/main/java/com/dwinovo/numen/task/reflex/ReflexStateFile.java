package com.dwinovo.numen.task.reflex;

import com.dwinovo.numen.Constants;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The production {@link ReflexRegistry.StateStore}: one flat JSON object of
 * {@code "reflex_id": true/false} at {@code config/numen/reflexes.json} (core
 * has no other config mechanism to ride — numen-api's {@code INumenConfig} is
 * the engine's own file, and the skills sidecar is skill-specific). Best-effort
 * IO: a missing/corrupt file loads as "no overrides" and a failed save is
 * logged, never thrown — a broken switch file must not take the mod down.
 */
public final class ReflexStateFile implements ReflexRegistry.StateStore {

    private final Path file;

    public ReflexStateFile(Path file) {
        this.file = file;
    }

    @Override
    public Map<String, Boolean> load() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) {
                        out.put(e.getKey(), e.getValue().getAsBoolean());
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-core] could not read {} ({}); reflex switches stay at defaults",
                    file, ex.toString());
        }
        return out;
    }

    @Override
    public void save(Map<String, Boolean> state) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            state.forEach(root::addProperty);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-core] could not write {} ({}); reflex switch change is memory-only",
                    file, ex.toString());
        }
    }
}
