package com.dwinovo.numen.platform.services;

/**
 * Cross-loader read surface for user-editable mod configuration. Each loader
 * ships its own implementation hooked into its native config system —
 * NeoForge wraps a {@code ModConfigSpec} (TOML), Fabric reads a Gson JSON
 * file under {@code <gameDir>/config/numen.json}.
 *
 * <h2>Read freshness</h2>
 * Values are read on every call. NeoForge's {@code ConfigValue.get()} is
 * already lazy + cached at the framework level; Fabric's JSON impl caches
 * the parsed values in memory and only re-reads on explicit
 * {@code /reload}-style triggers (Phase-2). For MVP, edits to the config
 * file require a server restart to take effect — acceptable.
 *
 * <h2>Why no setters</h2>
 * Mutation paths differ wildly between loaders (NeoForge needs the screen
 * config GUI, Fabric needs an external editor or our own command). Keep
 * this interface read-only; a future {@code INumenConfigEditor} can layer
 * on top when we need it.
 */
public interface INumenConfig {

    /**
     * OpenAI / OpenAI-compatible API key. Empty string if unset — the LLM
     * client refuses to start a chat completion in that case and logs once.
     */
    String getApiKey();

    /**
     * Optional base URL override. Empty string means "use the SDK default"
     * ({@code https://api.openai.com/v1}). Set to e.g.
     * {@code https://api.deepseek.com/v1} to point at a compatible backend.
     */
    String getBaseUrl();

    /**
     * Model id passed to the {@code model} field of every chat completion
     * request. Backend-defined string ({@code gpt-5-2-mini},
     * {@code claude-3-5-sonnet} via proxy, {@code deepseek-chat}, etc.).
     */
    String getModel();

    /**
     * Provider id selecting the wire-format adapter. Recognised values:
     * <ul>
     *   <li>{@code "openai"} (default) — vanilla OpenAI chat completions shape.
     *       Works with most "OpenAI-compatible" backends (Together, Groq,
     *       Mistral La Plateforme, Moonshot Kimi, Ollama, vLLM, ...).</li>
     *   <li>{@code "deepseek"} — same wire shape but preserves
     *       {@code reasoning_content} on round-trip so thinking models
     *       (deepseek-v3+, deepseek-reasoner) don't 400 on multi-turn.</li>
     * </ul>
     * Unknown values fall back to {@code "openai"}.
     */
    String getProvider();

    /** Optional HTTP proxy for LLM calls as {@code host:port} (empty = direct). For users behind a firewall. */
    String getProxy();

    /**
     * Reasoning / "deep thinking" effort for reasoning-capable models. One of
     * {@code "auto"} (default — send nothing, let the backend decide),
     * {@code "low"}, {@code "medium"}, {@code "high"}. When not {@code "auto"},
     * the client sends the OpenAI-dialect {@code reasoning_effort} request
     * parameter (providers may override the mapping). Unknown values are
     * treated as {@code "auto"}.
     */
    String getReasoningEffort();

    /**
     * System prompt prepended to every conversation. Empty string for none.
     * The agent layer adds tool-use guidance automatically on top of this.
     */
    String getSystemPrompt();

    // ---- write surface (client-side: invoked by SettingsScreen) ----

    /**
     * Mutate the in-memory API key. Caller must {@link #save()} to persist.
     * Null values are normalised to empty string.
     */
    void setApiKey(String value);

    void setBaseUrl(String value);

    void setModel(String value);

    void setProvider(String value);

    void setProxy(String value);

    void setSystemPrompt(String value);

    /** Set the reasoning effort ({@code auto}/{@code low}/{@code medium}/{@code high}). Caller must {@link #save()}. */
    void setReasoningEffort(String value);

    // ---- STT (voice input) — global, one microphone / one transcription service ----

    /** STT provider id selecting the preset (wire adapter + default endpoint). Default {@code siliconflow}. */
    String getSttProvider();

    /** STT service API key (bearer). Empty = voice input disabled. */
    String getSttApiKey();

    /** STT base URL override. Empty = use the provider preset's default endpoint. */
    String getSttBaseUrl();

    /** STT model id (e.g. {@code whisper-1}, {@code FunAudioLLM/SenseVoiceSmall}). */
    String getSttModel();

    /** Chosen input device name; empty = first available microphone. */
    String getSttMicrophone();

    void setSttProvider(String value);

    void setSttApiKey(String value);

    void setSttBaseUrl(String value);

    void setSttModel(String value);

    void setSttMicrophone(String value);

    /**
     * Flush in-memory changes to the loader-native config file. Best-effort:
     * errors are logged but never thrown — settings GUI shouldn't crash the
     * client because of a write failure.
     *
     * <p>Fabric impl rewrites {@code config/numen.json} directly. NeoForge
     * impl writes through {@code ModConfigSpec.ConfigValue.set()}, which
     * NeoForge persists to {@code numen-common.toml} on its own schedule
     * (this method nudges that schedule when possible).
     */
    void save();
}
