package com.dwinovo.numen.client.agent.goal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable, persisted state for one Numen goal. Time accounting is split into
 * stored accumulated {@code elapsedMs} plus an active {@code lastStartedAtMs}
 * so pause/resume does not lose wall-clock progress.
 */
public final class GoalState {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_COMMAND_HISTORY = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String id = "";
    private String title = "";
    private GoalStatus status = GoalStatus.NONE;
    private long createdAtMs;
    private long startedAtMs;
    private long lastStartedAtMs;
    private long completedAtMs;
    private long updatedAtMs;
    private long elapsedMs;
    private String currentTask = "";
    private String lastError = "";
    private boolean compactRequested;
    private final List<GoalTodo> todos = new ArrayList<>();
    private final List<GoalCommandEntry> history = new ArrayList<>();

    private GoalState() {}

    public static GoalState none(String id) {
        GoalState state = new GoalState();
        state.id = id == null ? "" : id;
        return state;
    }

    public static GoalState create(String id, String title, long nowMs) {
        GoalState state = new GoalState();
        state.id = id == null ? "" : id;
        state.title = title == null ? "" : title;
        state.status = GoalStatus.ACTIVE;
        state.createdAtMs = state.startedAtMs = state.lastStartedAtMs = state.updatedAtMs = nowMs;
        return state;
    }

    public static GoalState fromJson(String raw, String fallbackId) {
        JsonElement root = JsonParser.parseString(raw);
        if (!root.isJsonObject()) throw new IllegalArgumentException("goal state is not a JSON object");
        return fromJson(root.getAsJsonObject(), fallbackId);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public GoalStatus status() {
        return status;
    }

    public long createdAtMs() {
        return createdAtMs;
    }

    public long startedAtMs() {
        return startedAtMs;
    }

    public long lastStartedAtMs() {
        return lastStartedAtMs;
    }

    public long completedAtMs() {
        return completedAtMs;
    }

    public long updatedAtMs() {
        return updatedAtMs;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public long effectiveElapsedMs(long nowMs) {
        if (status == GoalStatus.ACTIVE && lastStartedAtMs > 0 && nowMs >= lastStartedAtMs) {
            return Math.max(0, elapsedMs + (nowMs - lastStartedAtMs));
        }
        return Math.max(0, elapsedMs);
    }

    public String currentTask() {
        return currentTask;
    }

    public String lastError() {
        return lastError;
    }

    public boolean compactRequested() {
        return compactRequested;
    }

    public List<GoalTodo> todos() {
        return Collections.unmodifiableList(new ArrayList<>(todos));
    }

    public List<GoalCommandEntry> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public boolean hasGoal() {
        return status != GoalStatus.NONE && title != null && !title.isBlank();
    }

    public boolean isActive() {
        return status == GoalStatus.ACTIVE;
    }

    public boolean isTerminal() {
        return status == GoalStatus.COMPLETED || status == GoalStatus.CANCELLED;
    }

    public int completedTodoCount() {
        int count = 0;
        for (GoalTodo todo : todos) {
            if ("completed".equals(todo.status())) count++;
        }
        return count;
    }

    public int totalTodoCount() {
        return todos.size();
    }

    public boolean updateTitle(String title, long nowMs) {
        if (title == null || title.isBlank()) return false;
        this.title = title;
        this.updatedAtMs = nowMs;
        return true;
    }

    public boolean setTodos(List<GoalTodo> next, long nowMs) {
        if (next == null) return false;
        todos.clear();
        todos.addAll(next);
        updatedAtMs = nowMs;
        return true;
    }

    public boolean setCurrentTask(String task, long nowMs) {
        currentTask = task == null ? "" : task;
        updatedAtMs = nowMs;
        return true;
    }

    public boolean setLastError(String error, long nowMs) {
        lastError = error == null ? "" : error;
        updatedAtMs = nowMs;
        return true;
    }

    public boolean setCompactRequested(boolean requested, long nowMs) {
        compactRequested = requested;
        updatedAtMs = nowMs;
        return true;
    }

    /** Replace this goal's content with a fresh active goal while keeping its id and history. */
    public boolean reset(String title, long nowMs) {
        if (title == null || title.isBlank()) return false;
        this.title = title;
        this.status = GoalStatus.ACTIVE;
        this.createdAtMs = this.startedAtMs = this.lastStartedAtMs = this.updatedAtMs = nowMs;
        this.completedAtMs = 0;
        this.elapsedMs = 0;
        this.currentTask = "";
        this.lastError = "";
        this.compactRequested = false;
        this.todos.clear();
        return true;
    }

    public boolean recordCommand(String command, long nowMs) {
        return recordCommand(command, "", nowMs);
    }

    public boolean recordCommand(String command, String result, long nowMs) {
        if (command == null || command.isBlank()) return false;
        history.add(new GoalCommandEntry(command, result == null ? "" : result, nowMs));
        while (history.size() > MAX_COMMAND_HISTORY) history.remove(0);
        updatedAtMs = nowMs;
        return true;
    }

    public boolean pause(long nowMs) {
        if (status != GoalStatus.ACTIVE) return false;
        elapsedMs = effectiveElapsedMs(nowMs);
        lastStartedAtMs = 0;
        status = GoalStatus.PAUSED;
        updatedAtMs = nowMs;
        return true;
    }

    public boolean resume(long nowMs) {
        if (status != GoalStatus.PAUSED && status != GoalStatus.FAILED) return false;
        status = GoalStatus.ACTIVE;
        lastStartedAtMs = nowMs;
        if (startedAtMs == 0) startedAtMs = nowMs;
        lastError = "";
        updatedAtMs = nowMs;
        return true;
    }

    public boolean complete(long nowMs) {
        if (status == GoalStatus.NONE || status == GoalStatus.COMPLETED
                || status == GoalStatus.CANCELLED) return false;
        if (status == GoalStatus.ACTIVE) elapsedMs = effectiveElapsedMs(nowMs);
        lastStartedAtMs = 0;
        status = GoalStatus.COMPLETED;
        completedAtMs = nowMs;
        currentTask = "";
        lastError = "";
        updatedAtMs = nowMs;
        return true;
    }

    public boolean cancel(long nowMs) {
        if (status == GoalStatus.NONE || status == GoalStatus.COMPLETED
                || status == GoalStatus.CANCELLED) return false;
        if (status == GoalStatus.ACTIVE) elapsedMs = effectiveElapsedMs(nowMs);
        lastStartedAtMs = 0;
        status = GoalStatus.CANCELLED;
        updatedAtMs = nowMs;
        return true;
    }

    public boolean markFailed(String error, long nowMs) {
        if (status == GoalStatus.NONE || status == GoalStatus.COMPLETED
                || status == GoalStatus.CANCELLED) return false;
        if (status == GoalStatus.ACTIVE) elapsedMs = effectiveElapsedMs(nowMs);
        lastStartedAtMs = 0;
        status = GoalStatus.FAILED;
        lastError = error == null ? "" : error;
        updatedAtMs = nowMs;
        return true;
    }

    public String toJson() {
        return GSON.toJson(toJsonObject());
    }

    public JsonObject toJsonObject() {
        JsonObject out = new JsonObject();
        out.addProperty("version", SCHEMA_VERSION);
        out.addProperty("id", id);
        out.addProperty("title", title);
        out.addProperty("status", status.key());
        out.addProperty("createdAtMs", createdAtMs);
        out.addProperty("startedAtMs", startedAtMs);
        out.addProperty("lastStartedAtMs", lastStartedAtMs);
        out.addProperty("completedAtMs", completedAtMs);
        out.addProperty("updatedAtMs", updatedAtMs);
        out.addProperty("elapsedMs", elapsedMs);
        out.addProperty("currentTask", currentTask);
        out.addProperty("lastError", lastError);
        out.addProperty("compactRequested", compactRequested);

        JsonArray todosArr = new JsonArray();
        for (GoalTodo todo : todos) {
            JsonObject item = new JsonObject();
            item.addProperty("id", todo.id());
            item.addProperty("content", todo.content());
            item.addProperty("status", todo.status());
            item.addProperty("createdAtMs", todo.createdAtMs());
            item.addProperty("updatedAtMs", todo.updatedAtMs());
            todosArr.add(item);
        }
        out.add("todos", todosArr);

        JsonArray historyArr = new JsonArray();
        for (GoalCommandEntry entry : history) {
            JsonObject item = new JsonObject();
            item.addProperty("command", entry.command());
            item.addProperty("result", entry.result());
            item.addProperty("atMs", entry.atMs());
            historyArr.add(item);
        }
        out.add("history", historyArr);
        return out;
    }

    private static GoalState fromJson(JsonObject root, String fallbackId) {
        GoalState state = new GoalState();
        state.id = str(root, "id", fallbackId);
        state.title = str(root, "title", "");
        state.status = GoalStatus.parse(str(root, "status", null), GoalStatus.NONE);
        state.createdAtMs = at(root, "createdAtMs");
        state.startedAtMs = at(root, "startedAtMs");
        state.lastStartedAtMs = at(root, "lastStartedAtMs");
        state.completedAtMs = at(root, "completedAtMs");
        state.updatedAtMs = at(root, "updatedAtMs");
        state.elapsedMs = Math.max(0, at(root, "elapsedMs"));
        state.currentTask = str(root, "currentTask", "");
        state.lastError = str(root, "lastError", "");
        state.compactRequested = root.has("compactRequested")
                && root.get("compactRequested").isJsonPrimitive()
                && root.get("compactRequested").getAsBoolean();

        if (root.has("todos") && root.get("todos").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("todos")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                state.todos.add(new GoalTodo(
                        str(item, "id", ""),
                        str(item, "content", ""),
                        str(item, "status", "pending"),
                        at(item, "createdAtMs"),
                        at(item, "updatedAtMs")));
            }
        }
        if (root.has("history") && root.get("history").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("history")) {
                if (!el.isJsonObject()) continue;
                JsonObject item = el.getAsJsonObject();
                state.history.add(new GoalCommandEntry(
                        str(item, "command", ""),
                        str(item, "result", ""),
                        at(item, "atMs")));
            }
            while (state.history.size() > MAX_COMMAND_HISTORY) state.history.remove(0);
        }
        return state;
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return obj.get(key).getAsString();
        return fallback;
    }

    private static long at(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) return Math.max(0, obj.get(key).getAsLong());
        return 0;
    }
}
