package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.client.data.JsonLibrary;
import com.dwinovo.numen.platform.Services;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
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
public final class ProviderLibrary extends JsonLibrary<ProviderLibrary.Entry> {

    /** One named provider configuration. May be INCOMPLETE — only the name is
     *  required to save; a missing API key surfaces as a plain error the first time
     *  a companion tries to talk through it (error-driven guidance, no silent
     *  fallback of any kind). */
    public record Entry(String id, String name, String provider, String model,
                        String apiKey, String baseUrl, String reasoningEffort) {}

    private static ProviderLibrary instance;

    private ProviderLibrary(java.nio.file.Path file) {
        super(file);
    }

    public static ProviderLibrary instance() {
        if (instance == null) {
            instance = new ProviderLibrary(configDir().resolve("providers.json"));
            instance.load();
        }
        return instance;
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
        Entry e = new Entry(freshId("prov"), name, provider, model, apiKey, baseUrl,
                reasoningEffort);
        putAndSave(e);
        return e;
    }

    public void update(Entry e) {
        if (e == null || !entries.containsKey(e.id())) return;
        putAndSave(e);
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

    // ---- persistence hooks ----

    @Override
    protected String logTag() {
        return "numen-providers";
    }

    @Override
    protected String idOf(Entry e) {
        return e.id();
    }

    @Override
    protected Entry readEntry(JsonObject o) {
        return new Entry(strOrNull(o, "id"), strOrNull(o, "name"), strOrNull(o, "provider"),
                strOrNull(o, "model"), strOrNull(o, "api_key"), strOrNull(o, "base_url"),
                strOrNull(o, "reasoning_effort"));
    }

    @Override
    protected JsonObject writeEntry(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("name", e.name());
        if (nb(e.provider())) o.addProperty("provider", e.provider());
        if (nb(e.model())) o.addProperty("model", e.model());
        if (nb(e.apiKey())) o.addProperty("api_key", e.apiKey());
        if (nb(e.baseUrl())) o.addProperty("base_url", e.baseUrl());
        if (nb(e.reasoningEffort())) o.addProperty("reasoning_effort", e.reasoningEffort());
        return o;
    }

    @Override
    protected void readExtra(JsonObject root) {
        readAssignments(root);
    }

    @Override
    protected void writeExtra(JsonObject root) {
        writeAssignments(root);
    }
}
