package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.INumenConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge implementation of {@link INumenConfig} (and the one TOML spec that also holds the client's voice-input
 * settings, see {@link NeoForgeSttSettings}). Backed by a
 * {@code ModConfigSpec} that NeoForge serialises as TOML under
 * {@code <gameDir>/config/numen-common.toml}. Editable in-game via
 * NeoForge's built-in config screen (Mods → Numen → Config).
 *
 * <h2>Registration timing</h2>
 * The static {@link #SPEC} is constructed during class load (just data —
 * no I/O), so it's safe to reference from the Mod constructor. The Mod
 * registers the spec via {@code ModContainer.registerConfig(...)}; NeoForge
 * loads / writes the TOML when the world loads.
 *
 * <p>Calling {@link ModConfigSpec.ConfigValue#get()} before the spec has
 * been loaded returns the declared default, so reads from this class are
 * safe at any point after {@code SPEC} is registered.
 */
public final class NeoForgeNumenConfig implements INumenConfig {

    public static final ModConfigSpec.ConfigValue<String> PROXY;
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_PROMPT;
    public static final ModConfigSpec.ConfigValue<String> STT_PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> STT_BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> STT_MODEL;
    public static final ModConfigSpec.ConfigValue<String> STT_MICROPHONE;
    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        // 段名沿用 openai:代理在玩家的 toml 里一直住在这一段,改名就丢了他们填的值。
        b.comment("Network settings for LLM calls.").push("openai");
        PROXY = b.comment("Optional HTTP proxy for LLM calls as host:port (empty = direct).")
                .define("proxy", "");
        b.pop();
        b.comment("Behaviour tuning.").push("agent");
        SYSTEM_PROMPT = b.comment("System prompt prepended to every conversation.")
                .define("system_prompt", DEFAULT_SYSTEM_PROMPT);
        b.pop();

        b.comment("Voice input (STT) — global, one microphone / one transcription service.").push("stt");
        STT_PROVIDER = b.comment("Provider preset (whisper-http adapter).",
                "siliconflow | openai | groq | custom (fill base_url + model yourself)")
                .define("provider", "siliconflow");
        STT_BASE_URL = b.comment("Base URL override. Empty = provider preset default.")
                .define("base_url", "");
        STT_MODEL = b.comment("Transcription model id (e.g. whisper-1, FunAudioLLM/SenseVoiceSmall).")
                .define("model", "FunAudioLLM/SenseVoiceSmall");
        STT_MICROPHONE = b.comment("Input device name; empty = first available microphone.")
                .define("microphone", "");
        b.pop();

        SPEC = b.build();
    }

    /** Default constructor used by {@code ServiceLoader}. */
    public NeoForgeNumenConfig() {}

    @Override
    public String getSystemPrompt() {
        return safe(SYSTEM_PROMPT);
    }

    @Override
    public String getProxy() {
        return safe(PROXY);
    }

    // ---- mutations ----

    @Override
    public void setProxy(String value) {
        PROXY.set(value == null ? "" : value);
    }

    @Override
    public void setSystemPrompt(String value) {
        SYSTEM_PROMPT.set(value == null ? "" : value);
    }

    /**
     * Flush the in-memory config to disk. The setters above only mutate the
     * loaded NightConfig in memory — {@link ModConfigSpec.ConfigValue#set}'s
     * own javadoc states it "does NOT write to disk... call
     * {@link ModConfigSpec#save()} eventually". Without this explicit save an
     * in-game settings change is lost on shutdown (the values revert to
     * whatever was last on disk), which is exactly the "reverts to default"
     * bug players hit after changing a setting in the GUI.
     *
     * <p>Guarded by {@link ModConfigSpec#isLoaded()}: {@code save()} throws if
     * the spec hasn't been bound to a config file yet (e.g. called absurdly
     * early). In that case the mutations live in the cached values and the
     * normal NeoForge load/correct cycle will persist them.
     */
    @Override
    public void save() {
        if (SPEC.isLoaded()) {
            SPEC.save();
        }
        com.dwinovo.numen.api.Constants.LOG.info("[numen-config] saved");
    }

    /**
     * Guard against the (rare) corner case where a config value is read
     * before the spec is fully bound — returns the empty string instead of
     * letting an exception escape into the LLM call site.
     */
    static String safe(ModConfigSpec.ConfigValue<String> v) {
        try {
            String s = v.get();
            return s == null ? "" : s;
        } catch (IllegalStateException ex) {
            return "";
        }
    }
}
