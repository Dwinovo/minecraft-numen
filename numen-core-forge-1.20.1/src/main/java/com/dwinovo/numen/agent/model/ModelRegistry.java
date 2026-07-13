package com.dwinovo.numen.agent.model;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.util.SafeJsonStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LLM "sites" registry (TouhouLittleMaid-style): per site an OpenAI-compatible base URL, optional
 * custom headers, and a list of known models with their context window (tokens). Single source of truth
 * for the settings dropdowns and the per-model auto-compaction threshold.
 *
 * <p>USER-EDITABLE: on first load the bundled {@code /numen_models.json} is copied to
 * {@code config/numen/models.json}; thereafter that file is authoritative (edit it to add your own
 * sites/models). A broken user file falls back to the bundled default.
 */
public final class ModelRegistry {

    public record Model(String id, int ctx, boolean reasoning) {}
    public record Provider(String id, String name, String baseUrl, boolean custom,
                           Map<String, String> headers, List<Model> models) {}

    /** Fallback context window for an unknown model (e.g. a custom one). */
    public static final int DEFAULT_CTX = 64_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile List<Provider> PROVIDERS = load();

    private ModelRegistry() {}

    /** Re-read the user file (after an edit / a site was added). */
    public static void reload() { PROVIDERS = load(); }

    /** Append a user-defined OpenAI-compatible site to {@code config/numen/models.json} and reload.
     *  Returns the new site id, or null on failure. */
    public static String addCustomSite(String name, String baseUrl, String modelId) {
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve("numen").resolve("models.json");
            var stored = SafeJsonStore.read(file, ModelRegistry::validateRoot);
            JsonObject root = stored.value().orElseGet(ModelRegistry::bundledRoot).deepCopy();
            com.google.gson.JsonArray providers = root.getAsJsonArray("providers");
            String id = uniqueId(slug(name), providers);
            com.google.gson.JsonObject p = new com.google.gson.JsonObject();
            p.addProperty("id", id);
            p.addProperty("name", name == null || name.isBlank() ? id : name.trim());
            p.addProperty("baseUrl", baseUrl == null ? "" : baseUrl.trim());
            com.google.gson.JsonArray models = new com.google.gson.JsonArray();
            if (modelId != null && !modelId.isBlank()) {
                com.google.gson.JsonObject m = new com.google.gson.JsonObject();
                m.addProperty("id", modelId.trim());
                models.add(m);
            }
            p.add("models", models);
            providers.add(p);
            SafeJsonStore.write(file, GSON.toJson(root), ModelRegistry::validateRoot);
            reload();
            return id;
        } catch (Exception e) {
            Constants.LOG.error("[numen] failed to add custom site '{}'", name, e);
            return null;
        }
    }

    private static String slug(String name) {
        if (name == null) return "site";
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "site" : s;
    }

    private static String uniqueId(String base, com.google.gson.JsonArray providers) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var e : providers) ids.add(e.getAsJsonObject().get("id").getAsString());
        if (!ids.contains(base)) return base;
        for (int i = 2; ; i++) if (!ids.contains(base + "_" + i)) return base + "_" + i;
    }

    private static List<Provider> load() {
        JsonObject bundled = bundledRoot();
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve("numen").resolve("models.json");
            var stored = SafeJsonStore.read(file, ModelRegistry::validateRoot);
            if (stored.value().isPresent()) {
                if (stored.recoveredFromBackup()) {
                    Constants.LOG.warn("[numen] recovered config/numen/models.json from backup");
                }
                return List.copyOf(parse(stored.value().orElseThrow()));
            }
            if (bundled != null) {
                SafeJsonStore.write(file, GSON.toJson(bundled), ModelRegistry::validateRoot);
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen] couldn't recover/seed config/numen/models.json, using bundled", e);
        }
        return bundled == null ? List.of() : List.copyOf(parse(bundled));
    }

    private static String readBundled() {
        try (var in = ModelRegistry.class.getResourceAsStream("/numen_models.json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Constants.LOG.error("[numen] numen_models.json not readable", e);
            return null;
        }
    }

    private static JsonObject bundledRoot() {
        String bundled = readBundled();
        return bundled == null ? null : validateRoot(JsonParser.parseString(bundled));
    }

    static JsonObject validateRoot(JsonElement value) {
        return ModelRegistryJson.validate(value);
    }

    private static List<Provider> parse(JsonObject root) {
        List<Provider> out = new ArrayList<>();
        if (!root.has("providers") || !root.get("providers").isJsonArray()) {
            throw new IllegalArgumentException("models registry has no providers array");
        }
        for (var pe : root.getAsJsonArray("providers")) {
            JsonObject p = pe.getAsJsonObject();
            String id = ModelRegistryJson.requiredString(p, "id");
            String name = ModelRegistryJson.requiredString(p, "name");
            List<Model> models = new ArrayList<>();
            if (p.has("models")) {
                if (!p.get("models").isJsonArray()) throw new IllegalArgumentException("models is not an array for " + id);
                for (var me : p.getAsJsonArray("models")) {
                    JsonObject m = me.getAsJsonObject();
                    int ctx = m.has("ctx") ? m.get("ctx").getAsInt() : DEFAULT_CTX;
                    if (ctx <= 0) throw new IllegalArgumentException("invalid context window for " + id);
                    models.add(new Model(ModelRegistryJson.requiredString(m, "id"), ctx,
                            m.has("reasoning") && m.get("reasoning").getAsBoolean()));
                }
            }
            Map<String, String> headers = new LinkedHashMap<>();
            if (p.has("headers")) {
                if (!p.get("headers").isJsonObject()) throw new IllegalArgumentException("headers is not an object for " + id);
                for (var h : p.getAsJsonObject("headers").entrySet()) {
                    headers.put(h.getKey(), h.getValue().getAsString());
                }
            }
            out.add(new Provider(id, name,
                    p.has("baseUrl") ? p.get("baseUrl").getAsString() : "",
                    p.has("custom") && p.get("custom").getAsBoolean(),
                    Map.copyOf(headers), List.copyOf(models)));
        }
        return out;
    }

    public static List<Provider> providers() { return PROVIDERS; }

    /** Provider by id (config aliases resolved), or the first one (or null if the registry is empty). */
    public static Provider provider(String id) {
        String c = canon(id);
        for (Provider p : PROVIDERS) {
            if (p.id().equals(c)) return p;
        }
        return PROVIDERS.isEmpty() ? null : PROVIDERS.get(0);
    }

    /** True iff {@code id} (alias-resolved) names a real registered site — no first-entry fallback. */
    public static boolean has(String id) {
        String c = canon(id);
        for (Provider p : PROVIDERS) if (p.id().equals(c)) return true;
        return false;
    }

    /** OpenAI-compatible base URL for a site (empty if unknown). */
    public static String baseUrl(String providerId) {
        Provider p = provider(providerId);
        return p == null ? "" : p.baseUrl();
    }

    /** Map config aliases (kimi/doubao/qwen/glm/silicon) onto canonical registry ids. */
    private static String canon(String id) {
        if (id == null) return "openai";
        return switch (id.toLowerCase()) {
            case "kimi" -> "moonshot";
            case "doubao", "ark" -> "volcengine";
            case "qwen", "tongyi", "aliyun" -> "dashscope";
            case "glm" -> "zhipu";
            case "silicon" -> "siliconflow";
            default -> id.toLowerCase();
        };
    }

    /** Custom request headers for a site (empty if none). */
    public static Map<String, String> headers(String providerId) {
        Provider p = provider(providerId);
        return p == null ? Map.of() : p.headers();
    }

    /** Context window for a (provider, model) pair, or {@link #DEFAULT_CTX} if unknown / custom. */
    public static int contextWindow(String providerId, String modelId) {
        Provider p = provider(providerId);
        if (p != null && modelId != null) {
            for (Model m : p.models()) {
                if (m.id().equals(modelId)) return m.ctx();
            }
        }
        return DEFAULT_CTX;
    }
}
