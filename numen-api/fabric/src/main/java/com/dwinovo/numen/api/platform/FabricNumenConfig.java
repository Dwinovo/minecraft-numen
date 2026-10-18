package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.store.Atomic;
import com.dwinovo.numen.api.store.StoreException;
import com.dwinovo.numen.api.platform.services.INumenConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Fabric implementation of {@link INumenConfig}. Reads a JSON file at
 * {@code <gameDir>/config/numen.json} on construction; if the file is
 * missing it's created with built-in defaults so users get a discoverable
 * template on first launch.
 *
 * <p>Fabric has no first-party config system the way NeoForge does, so the
 * format is intentionally minimal: plain Gson reflection over a public-field
 * POJO, JSON spec only (no comments — the {@code _readme} field stands in).
 *
 * <h2>Non-destructive read</h2>
 * If the file exists but fails to parse, the user's content is preserved:
 * we copy the broken file to {@code numen.json.bak-<timestamp>}, leave the
 * original untouched on disk, and serve defaults from memory for this
 * session. The user can fix or restore their file without losing edits.
 * Earlier versions silently overwrote with defaults on parse failure — that
 * silently destroyed user API keys and is the canonical footgun this
 * implementation now avoids.
 */
public final class FabricNumenConfig implements INumenConfig {

    private static final String FILE_NAME = "numen.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 服务端配置与客户端的语音识别设置({@link FabricSttSettings})存在同一份文件里,所以共用同一份读进内存的数据:
     * 谁先用到谁读,改动和保存也都落在它上面。
     */
    private static ConfigData shared;

    /** Default constructor used by {@code ServiceLoader}. */
    public FabricNumenConfig() {}

    static synchronized ConfigData data() {
        if (shared == null) {
            shared = loadOrCreate();
        }
        return shared;
    }

    private static ConfigData loadOrCreate() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                ConfigData parsed = GSON.fromJson(r, ConfigData.class);
                if (parsed != null) {
                    Constants.LOG.info("[numen-config] loaded {}", configPath);
                    return parsed.applyDefaults();
                }
                // GSON returned null — file was effectively empty. Back up
                // (since it might be a half-written edit) and let the
                // template be regenerated below.
                Constants.LOG.warn("[numen-config] {} parsed to null; backing up and rewriting",
                        configPath);
                backup(configPath);
            } catch (JsonSyntaxException ex) {
                // Bad JSON — could be a user typo. Preserve their file by
                // copying to a .bak, do NOT overwrite the original, return
                // in-memory defaults for this session.
                Constants.LOG.warn("[numen-config] failed to parse {}: {}; preserving original file as-is",
                        configPath, ex.getMessage());
                Path bak = backup(configPath);
                if (bak != null) {
                    Constants.LOG.warn("[numen-config] copy of broken file at {}", bak);
                }
                Constants.LOG.warn("[numen-config] serving DEFAULTS this session — fix {} and restart",
                        configPath);
                return ConfigData.defaults();
            } catch (IOException ex) {
                Constants.LOG.warn("[numen-config] could not read {}: {}; serving defaults this session",
                        configPath, ex.getMessage());
                return ConfigData.defaults();
            }
        }
        return writeDefaultTemplate(configPath);
    }

    private static ConfigData writeDefaultTemplate(Path configPath) {
        ConfigData defaults = ConfigData.defaults();
        try {
            Atomic.write(configPath, GSON.toJson(defaults));
            Constants.LOG.info("[numen-config] wrote default template to {}", configPath);
        } catch (StoreException ex) {
            Constants.LOG.warn("[numen-config] could not write default config to {}: {}",
                    configPath, ex.getMessage());
        }
        return defaults;
    }

    /**
     * Copy the file to {@code <name>.bak-<yyyyMMdd-HHmmss>} alongside it. Best
     * effort — returns {@code null} on failure and the caller carries on.
     * Used both for parse-failure backups (the original is left in place)
     * and null-parse cases (the original is then overwritten by the
     * template-write path).
     */
    private static Path backup(Path original) {
        try {
            String ts = LocalDateTime.now().format(BACKUP_TIMESTAMP);
            Path bak = original.resolveSibling(original.getFileName() + ".bak-" + ts);
            Files.copy(original, bak, StandardCopyOption.REPLACE_EXISTING);
            return bak;
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-config] could not back up {}: {}", original, ex.getMessage());
            return null;
        }
    }

    @Override
    public String getSystemPrompt() { return data().systemPrompt == null ? "" : data().systemPrompt; }

    @Override
    public String getProxy() { return data().proxy == null ? "" : data().proxy; }

    // ---- mutations ----

    @Override
    public void setProxy(String value) { data().proxy = value == null ? "" : value; }

    @Override
    public void setSystemPrompt(String value) { data().systemPrompt = value == null ? "" : value; }

    @Override
    public void save() {
        saveFile();
    }

    /** 整份写回文件(服务端的项与语音识别的项都在里面)。 */
    static void saveFile() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            Atomic.write(configPath, GSON.toJson(data()));
            Constants.LOG.info("[numen-config] saved {}", configPath);
        } catch (StoreException ex) {
            Constants.LOG.warn("[numen-config] save failed for {}: {}", configPath, ex.getMessage());
        }
    }

    /**
     * Gson-serialised POJO. Public fields kept simple on purpose — the JSON
     * file IS the schema, no need for an extra layer. The {@code _readme}
     * documents the actual JSON keys (camelCase, matching Gson's default
     * IDENTITY naming policy) so users editing the file can't accidentally
     * write {@code "api_key"} (snake_case) and have it silently ignored.
     */
    public static final class ConfigData {
        /** Free-form note for human readers; ignored by the loader. */
        public String _readme = "Numen mod configuration. Model connections (provider, model, API key) are configured in the in-game settings, not here. Field names are camelCase. Restart for changes to take effect.";
        public String proxy = "";   // optional host:port HTTP proxy for LLM calls
        // STT (voice input) — global. provider picks the preset; empty apiKey = disabled.
        public String sttProvider = "siliconflow";
        public String sttBaseUrl = "";
        public String sttModel = "FunAudioLLM/SenseVoiceSmall";
        public String sttMicrophone = "";
        public String systemPrompt = DEFAULT_SYSTEM_PROMPT;

        static ConfigData defaults() {
            return new ConfigData();
        }

        /** Fill in defaults for missing fields on a partially-populated file. */
        ConfigData applyDefaults() {
            ConfigData d = defaults();
            if (proxy == null) proxy = d.proxy;
            if (systemPrompt == null) systemPrompt = d.systemPrompt;
            if (sttProvider == null || sttProvider.isBlank()) sttProvider = d.sttProvider;
            if (sttBaseUrl == null) sttBaseUrl = d.sttBaseUrl;
            if (sttModel == null) sttModel = d.sttModel;
            if (sttMicrophone == null) sttMicrophone = d.sttMicrophone;
            return this;
        }
    }
}
