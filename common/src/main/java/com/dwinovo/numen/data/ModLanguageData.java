package com.dwinovo.numen.data;

/**
 * Single source of truth for every translation key the mod emits. Both
 * loader-side data generators ({@code FabricModLanguageProvider},
 * {@code ModLanguageProvider}) feed their builder through this class, so
 * adding / renaming / translating a key happens in one Java file and
 * propagates to every locale's emitted JSON in one go.
 *
 * <h2>Adding a new key</h2>
 * <ol>
 *   <li>Add a constant under {@link Keys}.</li>
 *   <li>Add an {@code adder.add(Keys.MY_KEY, "...")} call inside
 *       {@link #addEn} and {@link #addZh}.</li>
 *   <li>Re-run {@code ./gradlew :fabric:runDatagen :neoforge:runData}.</li>
 * </ol>
 */
public final class ModLanguageData {

    private ModLanguageData() {}

    /** Sink for the loader-specific data generator's translation builder. */
    @FunctionalInterface
    public interface Adder {
        void add(String key, String value);
    }

    /** Catalogue of every key this mod emits. Reference these from runtime code. */
    public static final class Keys {

        private Keys() {}

        // Settings GUI labels.
        public static final String GUI_SETTINGS_TITLE       = "numen.gui.settings.title";
        public static final String GUI_SETTINGS_PROVIDER    = "numen.gui.settings.provider";
        public static final String GUI_SETTINGS_API_KEY     = "numen.gui.settings.api_key";
        public static final String GUI_SETTINGS_MODEL       = "numen.gui.settings.model";
        public static final String GUI_SETTINGS_BASE_URL    = "numen.gui.settings.base_url";
        public static final String GUI_SETTINGS_BASE_URL_HINT = "numen.gui.settings.base_url_hint";
        public static final String GUI_SETTINGS_SAVE        = "numen.gui.settings.save";
        public static final String GUI_SETTINGS_CANCEL      = "numen.gui.settings.cancel";
        public static final String GUI_SETTINGS_SAVED       = "numen.gui.settings.saved";

        /** Hotkey: open the companion roster panel (shown in Controls settings). */
        public static final String KEY_OPEN_ROSTER = "key.numen.open_roster";

        // Model-config (provider library) section: nav label, list, form.
        public static final String PROVIDER_TITLE          = "numen.provider.title";
        public static final String PROVIDER_EMPTY          = "numen.provider.empty";
        public static final String PROVIDER_ADD            = "numen.provider.add";
        public static final String PROVIDER_DELETE_CONFIRM = "numen.provider.delete_confirm";
        public static final String PROVIDER_NO_KEY         = "numen.provider.no_key";
        public static final String PROVIDER_FORM_NAME      = "numen.provider.form_name";
        public static final String PROVIDER_FORM_PROVIDER  = "numen.provider.form_provider";
        public static final String PROVIDER_FORM_BASE_URL  = "numen.provider.form_base_url";

        // Proxy section (IP + port rows; the section/nav title reuses numen.settings.proxy).
        public static final String SETTINGS_PROXY_IP   = "numen.settings.proxy_ip";
        public static final String SETTINGS_PROXY_PORT = "numen.settings.proxy_port";

        // Summon page: field labels, placeholder, actions, click-time warnings.
        public static final String SUMMON_NAME             = "numen.summon.name";
        public static final String SUMMON_NAME_PLACEHOLDER = "numen.summon.name_placeholder";
        public static final String SUMMON_PERSONA_LABEL    = "numen.summon.persona_label";
        public static final String SUMMON_PERSONA_NONE     = "numen.summon.persona_none";
        public static final String SUMMON_WARN_NAME_FORMAT = "numen.summon.warn_name_format";
        public static final String SUMMON_SKIN             = "numen.summon.skin";
        public static final String SUMMON_SKIN_DEFAULT     = "numen.summon.skin_default";

        // Skin library tab (upload png → MineSkin-signed textures).
        public static final String SKIN_TITLE           = "numen.skin.title";
        public static final String SKIN_ADD             = "numen.skin.add";
        public static final String SKIN_EMPTY           = "numen.skin.empty";
        public static final String SKIN_FORM_NAME       = "numen.skin.form_name";
        public static final String SKIN_FORM_VARIANT    = "numen.skin.form_variant";
        public static final String SKIN_VARIANT_CLASSIC = "numen.skin.variant_classic";
        public static final String SKIN_VARIANT_SLIM    = "numen.skin.variant_slim";
        public static final String SKIN_DROP_HINT       = "numen.skin.drop_hint";
        public static final String SKIN_LOADED          = "numen.skin.loaded";
        public static final String SKIN_KEEP_OLD        = "numen.skin.keep_old";
        public static final String SKIN_SIGNING         = "numen.skin.signing";
        public static final String SKIN_SIGN_FAIL       = "numen.skin.sign_fail";
        public static final String SKIN_WARN_NAME       = "numen.skin.warn_name";
        public static final String SKIN_WARN_IMAGE      = "numen.skin.warn_image";
        public static final String SKIN_WARN_SIZE       = "numen.skin.warn_size";
        public static final String SKIN_WARN_READ       = "numen.skin.warn_read";
        public static final String SKIN_SIGNED          = "numen.skin.signed";
        public static final String SKIN_UNSIGNED        = "numen.skin.unsigned";
        public static final String SKIN_DELETE_CONFIRM  = "numen.skin.delete_confirm";
        public static final String SUMMON_PROVIDER_EMPTY   = "numen.summon.provider_empty";
        public static final String SUMMON_CREATE           = "numen.summon.create";
        public static final String SUMMON_WARN_NAME        = "numen.summon.warn_name";
        public static final String SUMMON_WARN_PROVIDER    = "numen.summon.warn_provider";

        // Endpoint problems surfaced in chat (EntityAgentLoop#endpointProblem).
        public static final String ENDPOINT_UNBOUND = "numen.endpoint.unbound";
        public static final String ENDPOINT_NO_KEY  = "numen.endpoint.no_key";

        // Voice (TTS) section: nav label, global switch, entry list/form, preview, bindings.
        public static final String VOICE_TITLE          = "numen.voice.title";
        public static final String VOICE_ENABLED        = "numen.voice.enabled";
        public static final String VOICE_EMPTY          = "numen.voice.empty";
        public static final String VOICE_ADD            = "numen.voice.add";
        public static final String VOICE_DELETE_CONFIRM = "numen.voice.delete_confirm";
        public static final String VOICE_FORM_NAME      = "numen.voice.form_name";
        public static final String VOICE_BACKEND_OPENAI  = "numen.voice.backend_openai";
        public static final String VOICE_BACKEND_SOVITS  = "numen.voice.backend_sovits";
        public static final String VOICE_BACKEND_MINIMAX = "numen.voice.backend_minimax";
        public static final String VOICE_BACKEND_FISH    = "numen.voice.backend_fish";
        public static final String VOICE_FORM_URL       = "numen.voice.form_url";
        public static final String VOICE_FORM_KEY_OPENAI  = "numen.voice.form_key_openai";
        public static final String VOICE_FORM_KEY_MINIMAX = "numen.voice.form_key_minimax";
        public static final String VOICE_FORM_KEY_FISH    = "numen.voice.form_key_fish";
        public static final String VOICE_FORM_MINIMAX_MODEL = "numen.voice.form_minimax_model";
        public static final String VOICE_FORM_MINIMAX_VOICE = "numen.voice.form_minimax_voice";
        public static final String VOICE_FORM_GROUP     = "numen.voice.form_group";
        public static final String VOICE_FORM_REFERENCE = "numen.voice.form_reference";
        public static final String VOICE_FORM_FISH_MODEL = "numen.voice.form_fish_model";
        public static final String VOICE_FORM_MODEL     = "numen.voice.form_model";
        public static final String VOICE_FORM_VOICE     = "numen.voice.form_voice";
        public static final String VOICE_FORM_REF       = "numen.voice.form_ref";
        public static final String VOICE_FORM_PROMPT    = "numen.voice.form_prompt";
        public static final String VOICE_FORM_LANG      = "numen.voice.form_lang";
        public static final String VOICE_FORM_VOLUME    = "numen.voice.form_volume";
        public static final String VOICE_TEST           = "numen.voice.test";
        public static final String VOICE_TEST_RUNNING   = "numen.voice.test_running";
        public static final String VOICE_TEST_OK        = "numen.voice.test_ok";
        public static final String VOICE_TEST_FAIL      = "numen.voice.test_fail";
        public static final String VOICE_WARN_NAME      = "numen.voice.warn_name";
        public static final String VOICE_BIND_LABEL     = "numen.voice.bind_label";
        public static final String VOICE_BIND_NONE      = "numen.voice.bind_none";
        public static final String VOICE_SUMMON_LABEL   = "numen.voice.summon_label";
        public static final String VOICE_SUMMON_EMPTY   = "numen.voice.summon_empty";

        // STT (voice input)
        public static final String STT_NAV            = "numen.settings.nav.stt";
        public static final String STT_TITLE          = "numen.stt.title";
        public static final String STT_MICROPHONE     = "numen.stt.microphone";
        public static final String STT_MIC_DEFAULT    = "numen.stt.mic_default";
        public static final String STT_NOT_CONFIGURED = "numen.stt.not_configured";
        public static final String STT_NO_MIC         = "numen.stt.no_mic";
        public static final String STT_FAILED         = "numen.stt.failed";
    }

    /** Loader-side providers funnel both English and Simplified Chinese through here. */
    public static void addTranslations(String locale, Adder adder) {
        if ("zh_cn".equals(locale)) {
            addZh(adder);
        } else {
            addEn(adder);
        }
    }

    private static void addEn(Adder adder) {
        adder.add(Keys.GUI_SETTINGS_TITLE,         "Numen Settings");
        adder.add(Keys.GUI_SETTINGS_PROVIDER,      "Provider");
        adder.add(Keys.GUI_SETTINGS_API_KEY,       "API Key");
        adder.add(Keys.GUI_SETTINGS_MODEL,         "Model");
        adder.add(Keys.GUI_SETTINGS_BASE_URL,      "Base URL (optional)");
        adder.add(Keys.GUI_SETTINGS_BASE_URL_HINT, "Leave empty to use provider default");
        adder.add(Keys.GUI_SETTINGS_SAVE,          "Save");
        adder.add(Keys.GUI_SETTINGS_CANCEL,        "Cancel");
        adder.add(Keys.GUI_SETTINGS_SAVED,         "Saved");

        adder.add(Keys.KEY_OPEN_ROSTER, "Open Companion Roster");

        // --- consolidated into the datagen source (persona / mcp / reasoning / tabs / status ...) ---
        adder.add("numen.tab.chat", "Chat");
        adder.add("numen.tab.status", "Status");
        adder.add("numen.tab.settings", "Settings");
        adder.add("numen.settings.nav.llm", "Models");
        adder.add("numen.settings.nav.mcp", "MCP");
        adder.add("numen.settings.nav.skills", "Skills");
        adder.add("numen.settings.nav.theme", "Theme");
        adder.add("numen.settings.theme.title", "Theme");
        adder.add(Keys.STT_NAV, "Voice input");
        adder.add(Keys.STT_TITLE, "Voice input (STT)");
        adder.add(Keys.STT_MICROPHONE, "Microphone");
        adder.add(Keys.STT_MIC_DEFAULT, "(default microphone)");
        adder.add(Keys.STT_NOT_CONFIGURED, "Voice input not configured — set an STT API key in settings");
        adder.add(Keys.STT_NO_MIC, "No microphone available");
        adder.add(Keys.STT_FAILED, "Voice input failed: %s");
        adder.add("numen.settings.nav.persona", "Persona");
        adder.add("numen.persona.title", "Personas");
        adder.add("numen.persona.empty", "None · click ＋ New (top-right)");
        adder.add("numen.persona.add", "＋ New");
        adder.add("numen.persona.preset_badge", "Preset");
        adder.add("numen.persona.form_name", "Name");
        adder.add("numen.persona.form_text", "Persona");
        adder.add("numen.persona.text_placeholder", "Describe this companion's personality, tone, backstory… (multi-line)");
        adder.add("numen.persona.delete_confirm", "Delete persona \"%s\"?");
        adder.add("numen.persona.default", "Default");
        adder.add("numen.summon.persona", "Persona: %s (click to change)");
        adder.add("numen.status.persona", "%s");
        adder.add("numen.settings.base_url", "Base URL");
        adder.add("numen.settings.proxy", "Proxy");
        adder.add("numen.settings.site_name", "Site name");
        adder.add("numen.settings.saved", "✔ Saved");
        adder.add("numen.settings.custom_model", "Custom…");
        adder.add("numen.settings.add_site", "＋ Add site");
        adder.add("numen.settings.reasoning", "Thinking: %s");
        adder.add("numen.settings.reasoning.auto", "Auto");
        adder.add("numen.settings.reasoning.low", "Low");
        adder.add("numen.settings.reasoning.medium", "Medium");
        adder.add("numen.settings.reasoning.high", "High");
        adder.add("numen.chat.send", "Send");
        adder.add("numen.chat.tip.compact", "Compact history");
        adder.add("numen.chat.tip.mic", "Voice input");
        adder.add("numen.chat.tip.mic_stop", "Stop recording");
        adder.add("numen.chat.tip.stop", "Stop the turn");
        adder.add("numen.chat.hint", "Talk to %s…");
        adder.add("numen.chat.no_key", "⚠ No API key — open Settings to add one");
        adder.add("numen.chat.empty", "Say something to %s.");
        adder.add("numen.chat.compacting", "compacting history…");
        adder.add("numen.chat.compacted", "─── earlier conversation compacted to a summary (originals kept on disk) ───");
        adder.add("numen.chat.persona_changed", "─── persona switched ───");
        adder.add("numen.chat.steps", "%s steps");
        adder.add("numen.chat.plan", "PLAN");
        adder.add("numen.chat.no_plan", "no plan yet");
        adder.add("numen.chat.usage_tip.context", "context: how full the model's memory window is — near the top it compacts itself, nothing to do");
        adder.add("numen.chat.usage_tip.tokens", "tokens: cumulative fresh work = cache-missed input + output");
        adder.add("numen.chat.usage_tip.cache", "cached input is free-ish and not counted, so most messages add only a little");
        // Tool-chip labels (convention: numen.tool.<tool name>; unknown/MCP tools fall back to the raw name).
        adder.add("numen.tool.build", "Build");
        adder.add("numen.tool.close_gui", "Close GUI");
        adder.add("numen.tool.collect_items", "Collect items");
        adder.add("numen.tool.craft", "Craft");
        adder.add("numen.tool.drop_items", "Drop items");
        adder.add("numen.tool.eat_item", "Eat");
        adder.add("numen.tool.equip_item", "Equip");
        adder.add("numen.tool.get_owner_status", "Owner status");
        adder.add("numen.tool.get_self_status", "Self status");
        adder.add("numen.tool.get_world_info", "World info");
        adder.add("numen.tool.goto", "Go to");
        adder.add("numen.tool.inspect_block", "Inspect block");
        adder.add("numen.tool.inspect_block_storage", "Inspect container");
        adder.add("numen.tool.inspect_gui", "Inspect GUI");
        adder.add("numen.tool.interact_at", "Interact");
        adder.add("numen.tool.interact_entity", "Interact entity");
        adder.add("numen.tool.load_skill", "Load skill");
        adder.add("numen.tool.locate_biome", "Locate biome");
        adder.add("numen.tool.locate_structure", "Locate structure");
        adder.add("numen.tool.look_around", "Look around");
        adder.add("numen.tool.lookup_recipe", "Look up recipe");
        adder.add("numen.tool.melee_attack", "Melee attack");
        adder.add("numen.tool.mine", "Mine");
        adder.add("numen.tool.ranged_attack", "Ranged attack");
        adder.add("numen.tool.scan_blocks", "Scan blocks");
        adder.add("numen.tool.scan_nearby_entities", "Scan entities");
        adder.add("numen.tool.todowrite", "Update plan");
        adder.add("numen.tool.transfer", "Transfer items");
        adder.add("numen.mcp.title", "MCP Tools");
        adder.add("numen.mcp.empty", "None · click ＋ Add (top-right)");
        adder.add("numen.mcp.add", "＋ Add");
        adder.add("numen.mcp.type_http", "Type: HTTP (click to switch)");
        adder.add("numen.mcp.type_stdio", "Type: stdio (click to switch)");
        adder.add("numen.mcp.form_name", "Name");
        adder.add("numen.mcp.form_type", "Type");
        adder.add("numen.mcp.form_url", "URL");
        adder.add("numen.mcp.form_command", "Command (space-separated)");
        adder.add("numen.mcp.form_header", "Header (optional, e.g. Authorization: Bearer …; multiple with ;)");
        adder.add("numen.mcp.form_env", "Env (optional, e.g. KEY=value; multiple with ;)");
        adder.add("numen.mcp.delete_confirm", "Delete %s?");
        adder.add("numen.mcp.connected", "%1$s · %2$s tools");
        adder.add("numen.mcp.connecting", "%s · connecting…");
        adder.add("numen.mcp.failed", "%s · failed");
        adder.add("numen.mcp.disabled", "%s · off");
        adder.add("numen.skill.title", "Skills");
        adder.add("numen.skill.empty", "None · drop into config/numen/skills");
        adder.add("numen.skill.open_dir", "＋ Folder");
        adder.add("numen.skill.no_desc", "(no description)");
        adder.add("numen.summon.title", "Summon a companion");
        adder.add("numen.summon.hint", "type a name · Enter to confirm · Esc to cancel");
        adder.add("numen.summon.name_hint", "New companion name…");
        adder.add("numen.dismiss.delete", "Delete");
        adder.add("numen.dismiss.title", "Delete companion \"%s\"?");
        adder.add("numen.dismiss.warning", "Permanent · backpack drops in place · cannot be undone");
        adder.add("numen.empty.no_companions", "No companions. Click + to summon one.");
        adder.add("numen.respawn", "· reviving %ss");
        adder.add("numen.status.loading", "loading…");
        adder.add("numen.status.asleep", "asleep — chat to wake it.");

        // Model-config section
        adder.add(Keys.PROVIDER_TITLE,          "Model Configs");
        adder.add(Keys.PROVIDER_EMPTY,          "No model config yet — click New (top-right) to create one");
        adder.add(Keys.PROVIDER_ADD,            "New");
        adder.add(Keys.PROVIDER_DELETE_CONFIRM, "Delete \"%s\"? Companions using it can't chat until rebound.");
        adder.add(Keys.PROVIDER_NO_KEY,         "no key");
        adder.add(Keys.PROVIDER_FORM_NAME,      "Name (required)");
        adder.add(Keys.PROVIDER_FORM_PROVIDER,  "Provider");
        adder.add(Keys.PROVIDER_FORM_BASE_URL,  "Base URL (adapts to provider, editable)");

        // Proxy section
        adder.add(Keys.SETTINGS_PROXY_IP,   "IP (empty = direct)");
        adder.add(Keys.SETTINGS_PROXY_PORT, "Port");

        // Summon page
        adder.add(Keys.SUMMON_NAME,             "Name (letters/digits/_; a premium player's name borrows their skin)");
        adder.add(Keys.SUMMON_NAME_PLACEHOLDER, "e.g. Kyu — name a premium player to wear their skin");
        adder.add(Keys.SUMMON_PERSONA_LABEL,    "Persona");
        adder.add(Keys.SUMMON_PERSONA_NONE,     "None");
        adder.add(Keys.SUMMON_WARN_NAME_FORMAT, "Name must be 3–16 letters/digits/underscores (Minecraft naming rules)");
        adder.add(Keys.SUMMON_SKIN,             "Skin");
        adder.add(Keys.SUMMON_SKIN_DEFAULT,     "Default (by name)");
        adder.add(Keys.SKIN_TITLE,           "Skins");
        adder.add(Keys.SKIN_ADD,             "New");
        adder.add(Keys.SKIN_EMPTY,           "No skins yet. Click New, then drag a skin png into the window.");
        adder.add(Keys.SKIN_FORM_NAME,       "Name (required)");
        adder.add(Keys.SKIN_FORM_VARIANT,    "Arm model");
        adder.add(Keys.SKIN_VARIANT_CLASSIC, "Classic (wide arms)");
        adder.add(Keys.SKIN_VARIANT_SLIM,    "Slim (thin arms)");
        adder.add(Keys.SKIN_DROP_HINT,       "Drag a skin png (64x64) into the game window");
        adder.add(Keys.SKIN_LOADED,          "Loaded %s ✓ — Save signs it via MineSkin");
        adder.add(Keys.SKIN_KEEP_OLD,        "Keeping the current image (drag a new png to replace)");
        adder.add(Keys.SKIN_SIGNING,         "Signing via MineSkin… (a few seconds)");
        adder.add(Keys.SKIN_SIGN_FAIL,       "Signing failed: %s");
        adder.add(Keys.SKIN_WARN_NAME,       "Name is required");
        adder.add(Keys.SKIN_WARN_IMAGE,      "Drag a skin png into the window first");
        adder.add(Keys.SKIN_WARN_SIZE,       "Skin must be 64x64 (or legacy 64x32), got %s");
        adder.add(Keys.SKIN_WARN_READ,       "Couldn't read the file: %s");
        adder.add(Keys.SKIN_SIGNED,          "signed");
        adder.add(Keys.SKIN_UNSIGNED,        "not signed — edit & Save to retry");
        adder.add(Keys.SKIN_DELETE_CONFIRM,  "Delete skin \"%s\"?");
        adder.add(Keys.SUMMON_PROVIDER_EMPTY,   " (empty — create one in Settings → Model Configs)");
        adder.add(Keys.SUMMON_CREATE,           "Create");
        adder.add(Keys.SUMMON_WARN_NAME,        "Give your companion a name first");
        adder.add(Keys.SUMMON_WARN_PROVIDER,    "No model configs — create one in Settings → Model Configs first");

        // Endpoint problems (chat warn line)
        adder.add(Keys.ENDPOINT_UNBOUND, "This companion has no model config bound — pick or create one in Settings → Model Configs");
        adder.add(Keys.ENDPOINT_NO_KEY,  "Model config \"%s\" has no API Key yet — add it in Settings → Model Configs");

        // Voice (TTS) section
        adder.add(Keys.VOICE_TITLE,          "Voice");
        adder.add(Keys.VOICE_ENABLED,        "Enabled");
        adder.add(Keys.VOICE_EMPTY,          "No voice yet — click New (top-right) to create one");
        adder.add(Keys.VOICE_ADD,            "New");
        adder.add(Keys.VOICE_DELETE_CONFIRM, "Delete voice \"%s\"? Companions bound to it fall silent.");
        adder.add(Keys.VOICE_FORM_NAME,      "Name (required)");
        adder.add(Keys.VOICE_BACKEND_OPENAI,  "OpenAI-compatible");
        adder.add(Keys.VOICE_BACKEND_SOVITS,  "GPT-SoVITS");
        adder.add(Keys.VOICE_BACKEND_MINIMAX, "MiniMax");
        adder.add(Keys.VOICE_BACKEND_FISH,    "Fish Audio");
        adder.add(Keys.VOICE_FORM_URL,       "Service URL (prefilled per provider, editable)");
        adder.add(Keys.VOICE_FORM_KEY_OPENAI,  "API Key (create in your provider's console)");
        adder.add(Keys.VOICE_FORM_KEY_MINIMAX, "API Key (MiniMax console > Account > API keys)");
        adder.add(Keys.VOICE_FORM_KEY_FISH,    "API Key (fish.audio > API credentials)");
        adder.add(Keys.VOICE_FORM_MINIMAX_MODEL, "Model (blank = speech-02-turbo)");
        adder.add(Keys.VOICE_FORM_MINIMAX_VOICE, "voice_id (copy from the Voices library)");
        adder.add(Keys.VOICE_FORM_GROUP,     "GroupId (fill if API errors ask for it)");
        adder.add(Keys.VOICE_FORM_REFERENCE, "Voice reference_id (paste the voice page URL or ID; blank = account default)");
        adder.add(Keys.VOICE_FORM_FISH_MODEL, "Synthesis model (blank = default; s2.1-pro-free = free tier)");
        adder.add(Keys.VOICE_FORM_MODEL,     "TTS model id");
        adder.add(Keys.VOICE_FORM_VOICE,     "Voice (format model:voice)");
        adder.add(Keys.VOICE_FORM_REF,       "Reference audio path (on the GPT-SoVITS machine)");
        adder.add(Keys.VOICE_FORM_PROMPT,    "Transcript of the reference audio");
        adder.add(Keys.VOICE_FORM_LANG,      "Language (blank = zh)");
        adder.add(Keys.VOICE_FORM_VOLUME,    "Volume 1–10 (default 5)");
        adder.add(Keys.VOICE_TEST,           "Preview");
        adder.add(Keys.VOICE_TEST_RUNNING,   "Synthesizing…");
        adder.add(Keys.VOICE_TEST_OK,        "✔ Playing");
        adder.add(Keys.VOICE_TEST_FAIL,      "Preview failed: %s");
        adder.add(Keys.VOICE_WARN_NAME,      "Name is required");
        adder.add(Keys.VOICE_BIND_LABEL,     "This companion");
        adder.add(Keys.VOICE_BIND_NONE,      "None (silent)");
        adder.add(Keys.VOICE_SUMMON_LABEL,   "Voice");
        adder.add(Keys.VOICE_SUMMON_EMPTY,   " (empty — create one in Settings → Voice)");
    }

    private static void addZh(Adder adder) {
        adder.add(Keys.GUI_SETTINGS_TITLE,         "Numen 设置");
        adder.add(Keys.GUI_SETTINGS_PROVIDER,      "服务商");
        adder.add(Keys.GUI_SETTINGS_API_KEY,       "API Key");
        adder.add(Keys.GUI_SETTINGS_MODEL,         "模型");
        adder.add(Keys.GUI_SETTINGS_BASE_URL,      "Base URL（可选）");
        adder.add(Keys.GUI_SETTINGS_BASE_URL_HINT, "留空使用服务商默认地址");
        adder.add(Keys.GUI_SETTINGS_SAVE,          "保存");
        adder.add(Keys.GUI_SETTINGS_CANCEL,        "取消");
        adder.add(Keys.GUI_SETTINGS_SAVED,         "已保存");

        adder.add(Keys.KEY_OPEN_ROSTER, "打开同伴名册");

        // --- consolidated into the datagen source (persona / mcp / reasoning / tabs / status ...) ---
        adder.add("numen.tab.chat", "对话");
        adder.add("numen.tab.status", "状态");
        adder.add("numen.tab.settings", "设置");
        adder.add("numen.settings.nav.llm", "模型接入");
        adder.add("numen.settings.nav.mcp", "MCP");
        adder.add("numen.settings.nav.skills", "技能");
        adder.add("numen.settings.nav.theme", "主题");
        adder.add("numen.settings.theme.title", "主题");
        adder.add(Keys.STT_NAV, "语音输入");
        adder.add(Keys.STT_TITLE, "语音输入 (STT)");
        adder.add(Keys.STT_MICROPHONE, "麦克风");
        adder.add(Keys.STT_MIC_DEFAULT, "（默认麦克风）");
        adder.add(Keys.STT_NOT_CONFIGURED, "未配置语音输入 —— 请在设置里填入 STT 的 API Key");
        adder.add(Keys.STT_NO_MIC, "无可用麦克风");
        adder.add(Keys.STT_FAILED, "语音输入失败：%s");
        adder.add("numen.settings.nav.persona", "人设");
        adder.add("numen.persona.title", "人设库");
        adder.add("numen.persona.empty", "无 · 点右上「＋ 新建」");
        adder.add("numen.persona.add", "＋ 新建");
        adder.add("numen.persona.preset_badge", "内置");
        adder.add("numen.persona.form_name", "名称");
        adder.add("numen.persona.form_text", "人设描述");
        adder.add("numen.persona.text_placeholder", "描述这个同伴的性格、说话风格、背景…（可多行）");
        adder.add("numen.persona.delete_confirm", "删除人设「%s」？");
        adder.add("numen.persona.default", "默认");
        adder.add("numen.summon.persona", "人设：%s（点击切换）");
        adder.add("numen.status.persona", "%s");
        adder.add("numen.settings.base_url", "Base URL");
        adder.add("numen.settings.proxy", "代理");
        adder.add("numen.settings.site_name", "站点名称");
        adder.add("numen.settings.saved", "✔ 已保存");
        adder.add("numen.settings.custom_model", "自定义…");
        adder.add("numen.settings.add_site", "＋ 添加站点");
        adder.add("numen.settings.reasoning", "深度思考：%s");
        adder.add("numen.settings.reasoning.auto", "自动");
        adder.add("numen.settings.reasoning.low", "低");
        adder.add("numen.settings.reasoning.medium", "中");
        adder.add("numen.settings.reasoning.high", "高");
        adder.add("numen.chat.send", "发送");
        adder.add("numen.chat.tip.compact", "压缩对话历史");
        adder.add("numen.chat.tip.mic", "语音输入");
        adder.add("numen.chat.tip.mic_stop", "停止录音");
        adder.add("numen.chat.tip.stop", "停止当前回合");
        adder.add("numen.chat.hint", "对 %s 说…");
        adder.add("numen.chat.no_key", "⚠ 未配置 API Key —— 打开设置添加");
        adder.add("numen.chat.empty", "对 %s 说点什么。");
        adder.add("numen.chat.compacting", "正在压缩历史…");
        adder.add("numen.chat.compacted", "─── 更早的对话已压缩为摘要（原文保留在磁盘） ───");
        adder.add("numen.chat.persona_changed", "─── 人设已切换 ───");
        adder.add("numen.chat.steps", "%s 步");
        adder.add("numen.chat.plan", "计划");
        adder.add("numen.chat.no_plan", "暂无计划");
        adder.add("numen.chat.usage_tip.context", "context：对话占模型记忆窗口的比例，快满时会自动压缩，无需操作");
        adder.add("numen.chat.usage_tip.tokens", "tokens：累计新处理量 = 未命中缓存的输入 + 输出");
        adder.add("numen.chat.usage_tip.cache", "命中缓存的输入不计入，所以平时每条消息只涨一点");
        // 工具 chip 标签(约定键 numen.tool.<工具名>;未知/MCP 工具回落原名)。
        adder.add("numen.tool.build", "建造");
        adder.add("numen.tool.close_gui", "关闭界面");
        adder.add("numen.tool.collect_items", "拾取掉落物");
        adder.add("numen.tool.craft", "合成");
        adder.add("numen.tool.drop_items", "丢出物品");
        adder.add("numen.tool.eat_item", "进食");
        adder.add("numen.tool.equip_item", "装备");
        adder.add("numen.tool.get_owner_status", "主人状态");
        adder.add("numen.tool.get_self_status", "自身状态");
        adder.add("numen.tool.get_world_info", "世界信息");
        adder.add("numen.tool.goto", "前往");
        adder.add("numen.tool.inspect_block", "查看方块");
        adder.add("numen.tool.inspect_block_storage", "查看容器");
        adder.add("numen.tool.inspect_gui", "查看界面");
        adder.add("numen.tool.interact_at", "交互");
        adder.add("numen.tool.interact_entity", "实体交互");
        adder.add("numen.tool.load_skill", "加载技能");
        adder.add("numen.tool.locate_biome", "定位群系");
        adder.add("numen.tool.locate_structure", "定位结构");
        adder.add("numen.tool.look_around", "环顾四周");
        adder.add("numen.tool.lookup_recipe", "查配方");
        adder.add("numen.tool.melee_attack", "近战攻击");
        adder.add("numen.tool.mine", "挖掘");
        adder.add("numen.tool.ranged_attack", "远程攻击");
        adder.add("numen.tool.scan_blocks", "扫描方块");
        adder.add("numen.tool.scan_nearby_entities", "扫描实体");
        adder.add("numen.tool.todowrite", "更新计划");
        adder.add("numen.tool.transfer", "转移物品");
        adder.add("numen.mcp.title", "MCP 工具");
        adder.add("numen.mcp.empty", "无 · 点右上「＋ 添加」");
        adder.add("numen.mcp.add", "＋ 添加");
        adder.add("numen.mcp.type_http", "类型：HTTP（点击切换）");
        adder.add("numen.mcp.type_stdio", "类型：stdio（点击切换）");
        adder.add("numen.mcp.form_name", "名称");
        adder.add("numen.mcp.form_type", "类型");
        adder.add("numen.mcp.form_url", "URL");
        adder.add("numen.mcp.form_command", "命令（空格分隔）");
        adder.add("numen.mcp.form_header", "请求头（可选，如 Authorization: Bearer …；多个用 ; 分隔）");
        adder.add("numen.mcp.form_env", "环境变量（可选，如 KEY=value；多个用 ; 分隔）");
        adder.add("numen.mcp.delete_confirm", "删除 %s？");
        adder.add("numen.mcp.connected", "%1$s · %2$s 工具");
        adder.add("numen.mcp.connecting", "%s · 连接中…");
        adder.add("numen.mcp.failed", "%s · 连接失败");
        adder.add("numen.mcp.disabled", "%s · 已停用");
        adder.add("numen.skill.title", "技能");
        adder.add("numen.skill.empty", "无 · 放入 config/numen/skills");
        adder.add("numen.skill.open_dir", "＋ 目录");
        adder.add("numen.skill.no_desc", "(无描述)");
        adder.add("numen.summon.title", "召唤一个同伴");
        adder.add("numen.summon.hint", "输入名字 · 回车确认 · Esc 取消");
        adder.add("numen.summon.name_hint", "新同伴名字…");
        adder.add("numen.dismiss.delete", "删除");
        adder.add("numen.dismiss.title", "删除同伴 \"%s\"？");
        adder.add("numen.dismiss.warning", "永久删除 · 背包会掉落在原地 · 无法撤销");
        adder.add("numen.empty.no_companions", "还没有同伴。点 + 召唤一个。");
        adder.add("numen.respawn", "· 复活中 %ss");
        adder.add("numen.status.loading", "加载中…");
        adder.add("numen.status.asleep", "休眠中 —— 对它说话唤醒。");

        // Model-config section
        adder.add(Keys.PROVIDER_TITLE,          "模型配置");
        adder.add(Keys.PROVIDER_EMPTY,          "还没有模型配置——点右上「新建」创建一条");
        adder.add(Keys.PROVIDER_ADD,            "新建");
        adder.add(Keys.PROVIDER_DELETE_CONFIRM, "删除「%s」?正在使用它的同伴将无法对话,直到重新绑定。");
        adder.add(Keys.PROVIDER_NO_KEY,         "未填Key");
        adder.add(Keys.PROVIDER_FORM_NAME,      "名称(必填)");
        adder.add(Keys.PROVIDER_FORM_PROVIDER,  "提供商");
        adder.add(Keys.PROVIDER_FORM_BASE_URL,  "Base URL(随提供商自适应,可改)");

        // Proxy section
        adder.add(Keys.SETTINGS_PROXY_IP,   "IP(留空 = 直连)");
        adder.add(Keys.SETTINGS_PROXY_PORT, "端口");

        // Summon page
        adder.add(Keys.SUMMON_NAME,             "名字(英文,填正版玩家名可穿它的皮肤)");
        adder.add(Keys.SUMMON_NAME_PLACEHOLDER, "如 Kyu;填正版玩家名自动穿同名皮肤");
        adder.add(Keys.SUMMON_PERSONA_LABEL,    "人设");
        adder.add(Keys.SUMMON_PERSONA_NONE,     "无");
        adder.add(Keys.SUMMON_WARN_NAME_FORMAT, "名字需为 3~16 位英文字母/数字/下划线(Minecraft 官方命名规则)");
        adder.add(Keys.SUMMON_SKIN,             "皮肤");
        adder.add(Keys.SUMMON_SKIN_DEFAULT,     "默认(按名字)");
        adder.add(Keys.SKIN_TITLE,           "皮肤库");
        adder.add(Keys.SKIN_ADD,             "新建");
        adder.add(Keys.SKIN_EMPTY,           "还没有皮肤。点右上角\"新建\",再把皮肤 png 拖进游戏窗口。");
        adder.add(Keys.SKIN_FORM_NAME,       "名称(必填)");
        adder.add(Keys.SKIN_FORM_VARIANT,    "手臂模型");
        adder.add(Keys.SKIN_VARIANT_CLASSIC, "经典(粗手臂)");
        adder.add(Keys.SKIN_VARIANT_SLIM,    "纤细(瘦手臂)");
        adder.add(Keys.SKIN_DROP_HINT,       "把皮肤 png(64x64)直接拖进游戏窗口");
        adder.add(Keys.SKIN_LOADED,          "已加载 %s ✓——点保存经 MineSkin 签名");
        adder.add(Keys.SKIN_KEEP_OLD,        "沿用当前图片(拖入新 png 可替换)");
        adder.add(Keys.SKIN_SIGNING,         "MineSkin 签名中…(需要几秒)");
        adder.add(Keys.SKIN_SIGN_FAIL,       "签名失败: %s");
        adder.add(Keys.SKIN_WARN_NAME,       "名称必填");
        adder.add(Keys.SKIN_WARN_IMAGE,      "先把皮肤 png 拖进游戏窗口");
        adder.add(Keys.SKIN_WARN_SIZE,       "皮肤需为 64x64(或旧版 64x32),拖入的是 %s");
        adder.add(Keys.SKIN_WARN_READ,       "文件读取失败: %s");
        adder.add(Keys.SKIN_SIGNED,          "已签名");
        adder.add(Keys.SKIN_UNSIGNED,        "未签名——编辑后保存可重试");
        adder.add(Keys.SKIN_DELETE_CONFIRM,  "删除皮肤「%s」?");
        adder.add(Keys.SUMMON_PROVIDER_EMPTY,   "(空——到 设置 → 模型配置 新建)");
        adder.add(Keys.SUMMON_CREATE,           "创建");
        adder.add(Keys.SUMMON_WARN_NAME,        "先给同伴起个名字");
        adder.add(Keys.SUMMON_WARN_PROVIDER,    "模型配置为空,请先到 设置 → 模型配置 新建一条");

        // Endpoint problems (chat warn line)
        adder.add(Keys.ENDPOINT_UNBOUND, "这个同伴还没有绑定模型配置——到 设置 → 模型配置 新建/选择一条");
        adder.add(Keys.ENDPOINT_NO_KEY,  "模型配置「%s」还没填 API Key——到 设置 → 模型配置 补上");

        // Voice (TTS) section
        adder.add(Keys.VOICE_TITLE,          "语音");
        adder.add(Keys.VOICE_ENABLED,        "启用");
        adder.add(Keys.VOICE_EMPTY,          "还没有声线——点右上「新建」创建一条");
        adder.add(Keys.VOICE_ADD,            "新建");
        adder.add(Keys.VOICE_DELETE_CONFIRM, "删除声线「%s」?绑定它的同伴将静音。");
        adder.add(Keys.VOICE_FORM_NAME,      "名称(必填)");
        adder.add(Keys.VOICE_BACKEND_OPENAI,  "OpenAI 兼容");
        adder.add(Keys.VOICE_BACKEND_SOVITS,  "GPT-SoVITS");
        adder.add(Keys.VOICE_BACKEND_MINIMAX, "MiniMax");
        adder.add(Keys.VOICE_BACKEND_FISH,    "Fish Audio");
        adder.add(Keys.VOICE_FORM_URL,       "服务地址(随提供商预填,可改)");
        adder.add(Keys.VOICE_FORM_KEY_OPENAI,  "API Key(服务商控制台创建)");
        adder.add(Keys.VOICE_FORM_KEY_MINIMAX, "API Key(MiniMax 平台>账户管理>接口密钥)");
        adder.add(Keys.VOICE_FORM_KEY_FISH,    "API Key(fish.audio>API 凭证页创建)");
        adder.add(Keys.VOICE_FORM_MINIMAX_MODEL, "模型(留空即 speech-02-turbo)");
        adder.add(Keys.VOICE_FORM_MINIMAX_VOICE, "voice_id(平台音色库复制)");
        adder.add(Keys.VOICE_FORM_GROUP,     "GroupId(接口报错要求时必填)");
        adder.add(Keys.VOICE_FORM_REFERENCE, "声线 reference_id(可直接粘贴声线页网址;留空用账号默认)");
        adder.add(Keys.VOICE_FORM_FISH_MODEL, "合成模型(留空用默认;s2.1-pro-free 为免费档)");
        adder.add(Keys.VOICE_FORM_MODEL,     "TTS 模型 id");
        adder.add(Keys.VOICE_FORM_VOICE,     "音色(格式 模型:音色名)");
        adder.add(Keys.VOICE_FORM_REF,       "参考音频路径(GPT-SoVITS 所在机器上)");
        adder.add(Keys.VOICE_FORM_PROMPT,    "参考音频的文本");
        adder.add(Keys.VOICE_FORM_LANG,      "语言(留空默认 zh)");
        adder.add(Keys.VOICE_FORM_VOLUME,    "音量 1~10(默认 5)");
        adder.add(Keys.VOICE_TEST,           "试听");
        adder.add(Keys.VOICE_TEST_RUNNING,   "合成中…");
        adder.add(Keys.VOICE_TEST_OK,        "✔ 播放中");
        adder.add(Keys.VOICE_TEST_FAIL,      "试听失败:%s");
        adder.add(Keys.VOICE_WARN_NAME,      "名称必填");
        adder.add(Keys.VOICE_BIND_LABEL,     "本同伴");
        adder.add(Keys.VOICE_BIND_NONE,      "无(静音)");
        adder.add(Keys.VOICE_SUMMON_LABEL,   "声线");
        adder.add(Keys.VOICE_SUMMON_EMPTY,   "(空——到 设置 → 语音 新建)");
    }
}
