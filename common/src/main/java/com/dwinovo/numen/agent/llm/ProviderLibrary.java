package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's library of named LLM provider configurations, at
 * {@code config/numen/providers.json} — the authoring surface behind the panel's
 * "提供商" tab, mirroring {@link com.dwinovo.numen.persona.PersonaLibrary}'s shape.
 * A companion selects one entry at summon time (or later); its requests then run
 * against that entry's backend, so different companions in the same session can
 * talk to different providers. No selection = the global settings, unchanged.
 *
 * <p>The library starts EMPTY and every entry is player-created and editable —
 * no seeds, no default entry, no fallback: a companion talks through exactly the
 * entry it selected, and an incomplete entry (e.g. no API key yet) surfaces as a
 * plain error on first use. Client-side singleton.
 */
public final class ProviderLibrary {

    /** One named provider configuration. May be INCOMPLETE — only the name is
     *  required to save; a missing API key surfaces as a plain error the first time
     *  a companion tries to talk through it (error-driven guidance, no silent
     *  fallback of any kind). */
    public record Entry(String id, String name, String provider, String model,
                        String apiKey, String baseUrl, String reasoningEffort) {}

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static ProviderLibrary instance;

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private ProviderLibrary(Path file) {
        this.file = file;
    }

    public static ProviderLibrary instance() {
        if (instance == null) {
            Path dir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("numen");
            instance = new ProviderLibrary(dir.resolve("providers.json"));
            instance.load();
        }
        return instance;
    }

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    public Entry get(String id) {
        return id == null ? null : entries.get(id);
    }

    /**
     * Resolve an entry id to connection parameters, verbatim — no fallback of any
     * kind. Unknown/deleted id → an empty endpoint, whose missing API key produces
     * the same honest error a keyless entry does. Only the proxy comes from the
     * global settings (a network-environment property, not a provider property).
     */
    public LlmEndpoint resolve(String id) {
        Entry e = get(id);
        if (e == null) {
            return new LlmEndpoint("", "", "", "", Services.CONFIG.getProxy(), "");
        }
        return new LlmEndpoint(e.provider(), e.model(), e.apiKey(),
                e.baseUrl(), Services.CONFIG.getProxy(), e.reasoningEffort());
    }

    /** Create an entry — only the name is required; everything else may be blank. */
    public Entry create(String name, String provider, String model,
                        String apiKey, String baseUrl, String reasoningEffort) {
        String id = "prov_" + Long.toHexString(System.currentTimeMillis()) + "_" + entries.size();
        Entry e = new Entry(id, name, provider, model, apiKey, baseUrl, reasoningEffort);
        entries.put(id, e);
        save();
        return e;
    }

    public void update(Entry e) {
        if (e == null || !entries.containsKey(e.id())) return;
        entries.put(e.id(), e);
        save();
    }

    public void remove(String id) {
        if (entries.remove(id) != null) save();
    }

    // ---- per-companion assignment (uuid → entry id), persisted alongside entries ----

    private final Map<String, String> assignments = new LinkedHashMap<>();

    /** The entry id assigned to a companion, or null (= none / null companion — the
     *  blank hotkey-opened panel constructs a loop with no UUID). */
    public String assignedEntry(java.util.UUID companion) {
        return companion == null ? null : assignments.get(companion.toString());
    }

    /** Assign an entry to a companion ({@code entryId} null = back to global) and persist. */
    public void assign(java.util.UUID companion, String entryId) {
        if (companion == null) return;
        if (entryId == null || entryId.isBlank()) {
            assignments.remove(companion.toString());
        } else {
            assignments.put(companion.toString(), entryId);
        }
        save();
    }

    // ---- pending summon assignment (same mechanism as PersonaLibrary.pendSummon:
    // the new companion's UUID is unknown until the roster snapshot arrives) ----

    private static final Map<String, String> PENDING_SUMMON = new LinkedHashMap<>();

    /** Remember the provider entry chosen for a companion being summoned (by name). */
    public static void pendSummon(String name, String entryId) {
        if (name == null || entryId == null) return;
        PENDING_SUMMON.put(name, entryId);
    }

    /** Take (and clear) the entry id pending for a just-arrived companion name, or null. */
    public static String takePendingSummon(String name) {
        return PENDING_SUMMON.remove(name);
    }

    // ---- persistence ----

    private void load() {
        entries.clear();
        if (!Files.exists(file)) {
            return;   // fresh install: the tab starts EMPTY — the player creates entries
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonElement el : root.getAsJsonObject().getAsJsonArray("entries")) {
                JsonObject o = el.getAsJsonObject();
                Entry e = new Entry(str(o, "id"), str(o, "name"), str(o, "provider"),
                        str(o, "model"), str(o, "api_key"), str(o, "base_url"),
                        str(o, "reasoning_effort"));
                if (e.id() != null && !e.id().isBlank()) entries.put(e.id(), e);
            }
            JsonObject rootObj = root.getAsJsonObject();
            if (rootObj.has("assignments")) {
                for (var kv : rootObj.getAsJsonObject("assignments").entrySet()) {
                    if (kv.getValue().isJsonPrimitive()) {
                        assignments.put(kv.getKey(), kv.getValue().getAsString());
                    }
                }
            }
        } catch (RuntimeException | IOException ex) {
            Constants.LOG.warn("[numen-providers] unreadable {} — starting empty ({})",
                    file, ex.getMessage());
            entries.clear();
        }
    }

    private void save() {
        JsonArray arr = new JsonArray();
        for (Entry e : entries.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("name", e.name());
            if (nb(e.provider())) o.addProperty("provider", e.provider());
            if (nb(e.model())) o.addProperty("model", e.model());
            if (nb(e.apiKey())) o.addProperty("api_key", e.apiKey());
            if (nb(e.baseUrl())) o.addProperty("base_url", e.baseUrl());
            if (nb(e.reasoningEffort())) o.addProperty("reasoning_effort", e.reasoningEffort());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("entries", arr);
        JsonObject assign = new JsonObject();
        assignments.forEach(assign::addProperty);
        root.add("assignments", assign);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, PRETTY.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-providers] can't save {}: {}", file, ex.getMessage());
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }
}
