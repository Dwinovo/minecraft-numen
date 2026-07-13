package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.security.SecretProtector;
import com.dwinovo.numen.util.SafeJsonStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side, UUID-keyed LLM profiles. Old installs inherit the global Forge config on first use. */
public final class CompanionAiConfigStore {

    public static final int VERSION = 1;
    public static final String REASONING_AUTO = "auto";
    /** Default per-companion history estimate that triggers automatic summarization. */
    public static final int DEFAULT_AUTO_COMPACT_TOKENS = 64_000;
    public static final int MIN_AUTO_COMPACT_TOKENS = 16_000;
    public static final int MAX_AUTO_COMPACT_TOKENS = 1_000_000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, Profile> PROFILES = new LinkedHashMap<>();
    private static boolean loaded;
    private static SecretProtector secretProtector;

    public record Profile(String provider, String apiKey, String model, String baseUrl,
                          boolean fullUrl, String proxy, String systemPrompt,
                          String reasoningEffort, boolean webSearchEnabled, boolean lowQualityAi,
                          int autoCompactTokens) {
        public Profile normalized() {
            return new Profile(text(provider, "openai"), text(apiKey, ""), text(model, ""),
                    text(baseUrl, ""), fullUrl && !text(baseUrl, "").isBlank(), text(proxy, ""),
                    text(systemPrompt, ""), normalizeReasoning(reasoningEffort), webSearchEnabled,
                    lowQualityAi, normalizeAutoCompactTokens(autoCompactTokens));
        }
    }

    private CompanionAiConfigStore() { }

    public static synchronized Profile get(UUID companion) {
        ensureLoaded();
        if (companion == null) return defaults();
        return PROFILES.computeIfAbsent(companion, ignored -> defaults()).normalized();
    }

    public static synchronized boolean hasExplicit(UUID companion) {
        ensureLoaded();
        return companion != null && PROFILES.containsKey(companion);
    }

    public static synchronized void put(UUID companion, Profile profile) {
        if (companion == null || profile == null) return;
        ensureLoaded();
        PROFILES.put(companion, profile.normalized());
        save();
    }

    public static synchronized void remove(UUID companion) {
        ensureLoaded();
        if (companion != null && PROFILES.remove(companion) != null) save();
    }

    public static Profile defaults() {
        INumenConfig config = Services.CONFIG;
        String provider = config.getProvider();
        String baseUrl = config.getBaseUrl();
        // Migrate the old bundled DeepSeek /beta default while preserving any real custom endpoint.
        if ("deepseek".equalsIgnoreCase(provider)
                && "https://api.deepseek.com/beta".equalsIgnoreCase(text(baseUrl, "").replaceAll("/+$", ""))) {
            baseUrl = "https://api.deepseek.com";
        }
        return new Profile(provider, config.getApiKey(), config.getModel(),
                baseUrl, config.isFullUrl(), config.getProxy(), config.getSystemPrompt(),
                REASONING_AUTO, true, false, DEFAULT_AUTO_COMPACT_TOKENS).normalized();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.exists(file) && !Files.exists(SafeJsonStore.backup(file))) return;
        try {
            var result = SafeJsonStore.read(file, value -> value.getAsJsonObject());
            if (result.value().isEmpty()) return;
            if (result.recoveredFromBackup()) Constants.LOG.warn("[numen-config] recovered companion-ai.json from backup");
            JsonObject root = result.value().orElseThrow();
            int version = root.has("version") ? root.get("version").getAsInt() : 0;
            if (version != VERSION || !root.has("companions")) {
                Constants.LOG.warn("[numen-config] ignored unsupported companion-ai.json version {}", version);
                return;
            }
            for (var entry : root.getAsJsonObject("companions").entrySet()) {
                try {
                    JsonObject stored = entry.getValue().getAsJsonObject();
                    Profile profile = GSON.fromJson(stored, Profile.class);
                    if (stored.has("apiKeyProtected")) {
                        String key = protector().unprotect(stored.get("apiKeyProtected").getAsString());
                        profile = new Profile(profile.provider(), key, profile.model(), profile.baseUrl(), profile.fullUrl(),
                                profile.proxy(), profile.systemPrompt(), profile.reasoningEffort(), profile.webSearchEnabled(),
                                profile.lowQualityAi(), profile.autoCompactTokens());
                    }
                    PROFILES.put(UUID.fromString(entry.getKey()), profile.normalized());
                } catch (RuntimeException badEntry) {
                    Constants.LOG.warn("[numen-config] ignored invalid companion AI profile {}", entry.getKey());
                }
            }
        } catch (Exception error) {
            Constants.LOG.warn("[numen-config] couldn't load companion-ai.json; global defaults remain usable", error);
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", VERSION);
            JsonObject companions = new JsonObject();
            for (var entry : PROFILES.entrySet()) {
                Profile profile = entry.getValue().normalized();
                JsonObject stored = GSON.toJsonTree(new Profile(profile.provider(), "", profile.model(), profile.baseUrl(),
                        profile.fullUrl(), profile.proxy(), profile.systemPrompt(), profile.reasoningEffort(),
                        profile.webSearchEnabled(), profile.lowQualityAi(), profile.autoCompactTokens())).getAsJsonObject();
                stored.addProperty("apiKeyProtected", protector().protect(profile.apiKey()));
                companions.add(entry.getKey().toString(), stored);
            }
            root.add("companions", companions);
            Path file = file();
            SafeJsonStore.write(file, GSON.toJson(root), value -> {
                JsonObject parsed = value.getAsJsonObject();
                if (!parsed.has("version") || !parsed.has("companions")) throw new IllegalArgumentException("invalid companion config");
                return parsed;
            });
        } catch (Exception error) {
            Constants.LOG.error("[numen-config] couldn't save companion-ai.json", error);
        }
    }

    private static Path file() {
        return Services.PLATFORM.getConfigDir().resolve("numen").resolve("companion-ai.json");
    }

    private static SecretProtector protector() {
        if (secretProtector == null) secretProtector = SecretProtector.forConfigDirectory(file().getParent());
        return secretProtector;
    }

    private static String normalizeReasoning(String value) {
        String normalized = text(value, REASONING_AUTO).toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "minimal", "low", "medium", "high", "xhigh", "none" -> normalized;
            default -> REASONING_AUTO;
        };
    }

    public static int normalizeAutoCompactTokens(int value) {
        if (value <= 0) return DEFAULT_AUTO_COMPACT_TOKENS; // old profiles had no field
        return Math.max(MIN_AUTO_COMPACT_TOKENS, Math.min(value, MAX_AUTO_COMPACT_TOKENS));
    }

    private static String text(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
