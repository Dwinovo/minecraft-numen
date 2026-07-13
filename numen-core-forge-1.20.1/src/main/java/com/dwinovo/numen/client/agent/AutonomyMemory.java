package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.util.SafeJsonStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent goal/checkpoint/location/resource state for one companion brain. */
public final class AutonomyMemory {

    public record Goal(String id, String content, String status, String priority,
                       String checkpoint, long updatedAt) { }
    public record Location(String name, String dimension, int x, int y, int z,
                           String note, long updatedAt) { }
    public record Reservation(String item, int count, String purpose, long updatedAt) { }
    public record Event(long time, String kind, String summary) { }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, AutonomyMemory> INSTANCES = new ConcurrentHashMap<>();
    private static final int MAX_EVENTS = 24;

    private final Path file;
    private final LinkedHashMap<String, Goal> goals = new LinkedHashMap<>();
    private final LinkedHashMap<String, Location> locations = new LinkedHashMap<>();
    private final LinkedHashMap<String, Reservation> reservations = new LinkedHashMap<>();
    private final ArrayList<Event> events = new ArrayList<>();

    private AutonomyMemory(Path file) {
        this.file = file;
        load();
    }

    public static AutonomyMemory forEntity(Path directory, UUID entityUuid) {
        Path file = directory.resolve(entityUuid + ".autonomy.json").toAbsolutePath().normalize();
        return INSTANCES.computeIfAbsent(file.toString(), ignored -> new AutonomyMemory(file));
    }

    public synchronized List<Goal> replaceGoals(List<Goal> replacement) {
        LinkedHashMap<String, Goal> previous = new LinkedHashMap<>(goals);
        goals.clear();
        long now = Instant.now().toEpochMilli();
        if (replacement != null) {
            int index = 1;
            for (Goal input : replacement) {
                String id = clean(input.id(), "goal-" + index++).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
                String content = clean(input.content(), id);
                Goal old = previous.get(id);
                if (old != null && !old.content().equals(content)) old = null;
                String checkpoint = clean(input.checkpoint(), old == null ? "" : old.checkpoint());
                goals.put(id, new Goal(id, content, normalizeStatus(input.status()),
                        normalizePriority(input.priority()), checkpoint, now));
            }
        }
        save();
        return List.copyOf(goals.values());
    }

    public synchronized Goal checkpoint(String id, String status, String note) {
        String key = clean(id, "").toLowerCase(Locale.ROOT);
        Goal old = goals.get(key);
        if (old == null) throw new IllegalArgumentException("unknown goal id: " + id);
        Goal next = new Goal(old.id(), old.content(), normalizeStatus(status), old.priority(),
                clean(note, old.checkpoint()), Instant.now().toEpochMilli());
        goals.put(key, next);
        addEvent("checkpoint", next.id() + ": " + next.status() + " - " + next.checkpoint());
        save();
        return next;
    }

    public synchronized Location rememberLocation(String name, String dimension, int x, int y, int z, String note) {
        String key = clean(name, "location").toLowerCase(Locale.ROOT);
        Location value = new Location(key, clean(dimension, "minecraft:overworld"), x, y, z,
                clean(note, ""), Instant.now().toEpochMilli());
        locations.put(key, value);
        save();
        return value;
    }

    public synchronized boolean forgetLocation(String name) {
        boolean changed = locations.remove(clean(name, "").toLowerCase(Locale.ROOT)) != null;
        if (changed) save();
        return changed;
    }

    public synchronized List<Location> locations() { return List.copyOf(locations.values()); }
    public synchronized List<Goal> goals() { return List.copyOf(goals.values()); }

    public synchronized Reservation reserve(String item, int count, String purpose) {
        String key = clean(item, "").toLowerCase(Locale.ROOT);
        if (key.isBlank()) throw new IllegalArgumentException("item is required");
        if (count <= 0) throw new IllegalArgumentException("reservation count must be positive");
        Reservation value = new Reservation(key, count, clean(purpose, "reserved for active goal"),
                Instant.now().toEpochMilli());
        reservations.put(key, value);
        save();
        return value;
    }

    public synchronized boolean release(String item) {
        boolean changed = reservations.remove(clean(item, "").toLowerCase(Locale.ROOT)) != null;
        if (changed) save();
        return changed;
    }

    public synchronized List<Reservation> reservations() { return List.copyOf(reservations.values()); }

    public synchronized int reservedCount(String item) {
        Reservation value = reservations.get(clean(item, "").toLowerCase(Locale.ROOT));
        return value == null ? 0 : value.count();
    }

    public synchronized void recordToolResult(String tool, String resultJson) {
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            boolean success = root.has("success") && root.get("success").getAsBoolean();
            String message = root.has("message") ? root.get("message").getAsString() : "";
            addEvent(success ? "verified_action" : "failure", tool + ": " + message);
            save();
        } catch (RuntimeException ignored) {
            addEvent("failure", tool + ": unreadable result");
            save();
        }
    }

    public synchronized void recordMonitorEvent(String summary) {
        addEvent("monitor", summary == null ? "" : summary.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
        save();
    }

    public synchronized String retrievalContext() {
        StringBuilder out = new StringBuilder();
        for (Goal goal : goals.values()) out.append(goal.content()).append(' ').append(goal.checkpoint()).append(' ');
        for (Location location : locations.values()) out.append(location.name()).append(' ').append(location.note()).append(' ');
        return out.toString();
    }

    public synchronized String formatXml() {
        if (goals.isEmpty() && locations.isEmpty() && reservations.isEmpty() && events.isEmpty()) return "";
        StringBuilder out = new StringBuilder(512).append("<autonomy_state>\n");
        out.append("  Persistent state. Recheck world/inventory before each action; never consume reserved resources for another purpose.\n");
        for (Goal goal : goals.values()) {
            out.append("  <goal id=\"").append(esc(goal.id())).append("\" status=\"")
                    .append(esc(goal.status())).append("\" priority=\"").append(esc(goal.priority())).append("\">")
                    .append(esc(goal.content()));
            if (!goal.checkpoint().isBlank()) out.append(" | checkpoint: ").append(esc(goal.checkpoint()));
            out.append("</goal>\n");
        }
        for (Location location : locations.values()) {
            out.append("  <location name=\"").append(esc(location.name())).append("\" dimension=\"")
                    .append(esc(location.dimension())).append("\" x=\"").append(location.x()).append("\" y=\"")
                    .append(location.y()).append("\" z=\"").append(location.z()).append("\">")
                    .append(esc(location.note())).append("</location>\n");
        }
        for (Reservation reservation : reservations.values()) {
            out.append("  <reservation item=\"").append(esc(reservation.item())).append("\" count=\"")
                    .append(reservation.count()).append("\">").append(esc(reservation.purpose())).append("</reservation>\n");
        }
        int from = Math.max(0, events.size() - 6);
        for (int i = from; i < events.size(); i++) {
            Event event = events.get(i);
            out.append("  <recent kind=\"").append(esc(event.kind())).append("\">")
                    .append(esc(event.summary())).append("</recent>\n");
        }
        return out.append("</autonomy_state>").toString();
    }

    private void addEvent(String kind, String summary) {
        events.add(new Event(Instant.now().toEpochMilli(), kind, truncate(clean(summary, ""), 320)));
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    private void load() {
        if (!Files.isRegularFile(file) && !Files.isRegularFile(SafeJsonStore.backup(file))) return;
        try {
            var result = SafeJsonStore.read(file, value -> GSON.fromJson(value, State.class));
            if (result.value().isEmpty()) return;
            if (result.recoveredFromBackup()) Constants.LOG.warn("[numen-autonomy] recovered {} from backup", file);
            State state = result.value().orElseThrow();
            if (state == null) return;
            if (state.version != 1) throw new IllegalArgumentException("unsupported autonomy version " + state.version);
            if (state.goals != null) for (Goal value : state.goals) goals.put(value.id(), value);
            if (state.locations != null) for (Location value : state.locations) locations.put(value.name(), value);
            if (state.reservations != null) for (Reservation value : state.reservations) reservations.put(value.item(), value);
            if (state.events != null) events.addAll(state.events.subList(Math.max(0, state.events.size() - MAX_EVENTS), state.events.size()));
        } catch (IOException | RuntimeException error) {
            Constants.LOG.warn("[numen-autonomy] failed to load {}: {}", file, error.toString());
        }
    }

    private void save() {
        try {
            SafeJsonStore.write(file, GSON.toJson(new State(1, List.copyOf(goals.values()),
                    List.copyOf(locations.values()), List.copyOf(reservations.values()), List.copyOf(events))),
                    value -> {
                        State state = GSON.fromJson(value, State.class);
                        if (state == null || state.version != 1) throw new IllegalArgumentException("invalid autonomy state");
                        return state;
                    });
        } catch (IOException error) {
            Constants.LOG.warn("[numen-autonomy] failed to save {}: {}", file, error.toString());
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = clean(value, "pending").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pending", "in_progress", "blocked", "completed", "cancelled" -> normalized;
            default -> "pending";
        };
    }

    private static String normalizePriority(String value) {
        String normalized = clean(value, "medium").toLowerCase(Locale.ROOT);
        return switch (normalized) { case "high", "medium", "low" -> normalized; default -> "medium"; };
    }

    private static String clean(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "..."; }
    private static String esc(String value) { return clean(value, "").replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;"); }

    private record State(int version, List<Goal> goals, List<Location> locations,
                         List<Reservation> reservations, List<Event> events) { }
}
