package com.dwinovo.numen.agent.model;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private static volatile List<Provider> PROVIDERS = load();

    private ModelRegistry() {}

    /** Re-read the user file (after an edit / a site was added). */
    public static void reload() { PROVIDERS = load(); }

    /** Append a user-defined OpenAI-compatible site to {@code config/numen/models.json} and reload.
     *  Returns the new site id, or null on failure. */
    public static String addCustomSite(String name, String baseUrl, String modelId) {
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve("numen").resolve("models.json");
            String json = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : readBundled();
            com.google.gson.JsonObject root = JsonParser.parseString(json).getAsJsonObject();
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
            Files.createDirectories(file.getParent());
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
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
        String bundled = readBundled();
        String json = bundled;
        try {
            Path file = Services.PLATFORM.getConfigDir().resolve("numen").resolve("models.json");
            if (Files.exists(file)) {
                json = Files.readString(file, StandardCharsets.UTF_8);   // user-authoritative
            } else if (bundled != null) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, bundled, StandardCharsets.UTF_8);  // seed for editing
            }
        } catch (Exception e) {
            Constants.LOG.warn("[numen] couldn't read/seed config/numen/models.json, using bundled", e);
        }
        List<Provider> out = parse(json);
        if (out.isEmpty() && bundled != null && !bundled.equals(json)) {
            Constants.LOG.warn("[numen] user models.json yielded no sites, falling back to bundled");
            out = parse(bundled);
        }
        return List.copyOf(out);
    }

    private static String readBundled() {
        try (var in = ModelRegistry.class.getResourceAsStream("/numen_models.json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Constants.LOG.error("[numen] numen_models.json not readable", e);
            return null;
        }
    }

    private static List<Provider> parse(String json) {
        List<Provider> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(
                    new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (var pe : root.getAsJsonArray("providers")) {
                JsonObject p = pe.getAsJsonObject();
                List<Model> models = new ArrayList<>();
                if (p.has("models")) {
                    for (var me : p.getAsJsonArray("models")) {
                        JsonObject m = me.getAsJsonObject();
                        models.add(new Model(
                                m.get("id").getAsString(),
                                m.has("ctx") ? m.get("ctx").getAsInt() : DEFAULT_CTX,
                                m.has("reasoning") && m.get("reasoning").getAsBoolean()));
                    }
                }
                Map<String, String> headers = new LinkedHashMap<>();
                if (p.has("headers")) {
                    for (var h : p.getAsJsonObject("headers").entrySet()) {
                        headers.put(h.getKey(), h.getValue().getAsString());
                    }
                }
                out.add(new Provider(
                        p.get("id").getAsString(),
                        p.get("name").getAsString(),
                        p.has("baseUrl") ? p.get("baseUrl").getAsString() : "",
                        p.has("custom") && p.get("custom").getAsBoolean(),
                        Map.copyOf(headers),
                        List.copyOf(models)));
            }
        } catch (Exception e) {
            Constants.LOG.error("[numen] failed to parse models.json", e);
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
