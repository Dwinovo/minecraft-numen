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
        adder.add("numen.chat.hint", "Talk to %s…");
        adder.add("numen.chat.no_key", "⚠ No API key — open Settings to add one");
        adder.add("numen.chat.empty", "Say something to %s.");
        adder.add("numen.chat.compacting", "compacting history…");
        adder.add("numen.chat.compacted", "─── earlier conversation compacted to a summary (originals kept on disk) ───");
        adder.add("numen.chat.persona_changed", "─── persona switched ───");
        adder.add("numen.chat.steps", "%s steps");
        adder.add("numen.chat.plan", "PLAN");
        adder.add("numen.chat.no_plan", "no plan yet");
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
        adder.add("numen.chat.hint", "对 %s 说…");
        adder.add("numen.chat.no_key", "⚠ 未配置 API Key —— 打开设置添加");
        adder.add("numen.chat.empty", "对 %s 说点什么。");
        adder.add("numen.chat.compacting", "正在压缩历史…");
        adder.add("numen.chat.compacted", "─── 更早的对话已压缩为摘要（原文保留在磁盘） ───");
        adder.add("numen.chat.persona_changed", "─── 人设已切换 ───");
        adder.add("numen.chat.steps", "%s 步");
        adder.add("numen.chat.plan", "计划");
        adder.add("numen.chat.no_plan", "暂无计划");
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
    }
}
