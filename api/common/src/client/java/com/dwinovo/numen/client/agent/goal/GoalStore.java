package com.dwinovo.numen.client.agent.goal;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Best-effort write-through store for one entity's goal state at
 * {@code config/numen/goals/<uuid>.json}. A missing or corrupt file loads as
 * "no goal"; a failed save keeps the in-memory state alive and logs instead of
 * crashing the client.
 */
public final class GoalStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public GoalStore(Path file) {
        this.file = file;
    }

    public static GoalStore forEntity(Path goalsDir, UUID entityUuid) {
        return new GoalStore(goalsDir.resolve(entityUuid + ".json"));
    }

    public Path file() {
        return file;
    }

    public GoalState load() {
        String id = idFromFile();
        if (!Files.isRegularFile(file)) return GoalState.none(id);
        try {
            return GoalState.fromJson(Files.readString(file, StandardCharsets.UTF_8), id);
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-goal] could not read {} ({}); goal state stays empty",
                    file, ex.toString());
            return GoalState.none(id);
        }
    }

    public boolean save(GoalState state) {
        if (state == null) return false;
        try {
            Path target = file.toAbsolutePath();
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(state.toJsonObject()), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            Constants.LOG.warn("[numen-goal] could not write {} ({}); goal change is memory-only",
                    file, ex.toString());
            return false;
        }
    }

    private String idFromFile() {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
