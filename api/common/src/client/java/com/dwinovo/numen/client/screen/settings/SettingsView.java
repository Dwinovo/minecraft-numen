package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.agent.llm.NumenLlmClient;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.screen.Dropdown;
import com.dwinovo.numen.client.screen.LlmProviders;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.persona.PersonaLibrary;
import com.dwinovo.numen.client.platform.ClientServices;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Settings tab of {@link com.dwinovo.numen.client.screen.NumenScreen}, extracted whole: a
 * config hub with a left sub-nav picking one of nine sections (model configs / proxy / MCP /
 * skills / persona / voice / skin / STT / theme), each with its list + form + delete-confirm
 * states. All section state, widget building, rendering, hit-testing and wheel handling live
 * here; the screen supplies geometry, widget registration and shared transient signals through
 * {@link Host}. Behaviour is a 1:1 move from the screen (offsets, ordering, colours unchanged).
 *
 * <p>The legacy 模型接入 section (buildLlmWidgets / onSaveSettings and their dropdown click
 * blocks) was unreachable since 2026-07-14 (the 提供商 library replaced it in the nav) and was
 * dropped during this extraction instead of being carried over dead.
 */
public final class SettingsView {

    /** What the extracted code needs back from the owning screen. */
    public interface Host {
        <T extends AbstractWidget> T add(T w);
        void rebuild();
        void focus(AbstractWidget w);
        Font font();
        int left();
        int top();
        int panelW();
        int panelH();
        /** Left edge of the companion rail — the delete-confirm scrim covers rail + panel. */
        int railX();
        UUID uuid();
        /** Bump the transient warn timer (text untouched — matches the old {@code warnUntil} pokes). */
        void warnPulse();
        /** Collect a hovered-row tooltip; the screen draws it last, above everything. */
        void tip(List<Component> lines, int x, int y);
        /** Re-read the screen's palette statics after a theme switch. */
        void repaintPalette();
    }

    /**
     * The config hub's sections, in nav order. MCP 出现两次是刻意的——方向相反的两件事:
     * {@link #MCP} 是"给同伴的大脑加外部工具"(我们当 client),{@link #BRAIN} 是"把同伴
     * 交给外面的大脑"(我们当 server),故在 UI 上按用户视角分成工具扩展/外接大脑两节。
     */
    private enum Section { PROVIDER, MCP, BRAIN, SKILLS, PERSONA, VOICE, SKIN, STT, THEME }

    // ---- layout constants (mirror the screen's) ----
    private static final int PAD = 8;
    private static final int HEADER_H = 22;
    private static final int FIELD_INSET_X = 5;
    private static final int FIELD_INSET_Y = 4;
    private static final int SET_SP = 33;     // form row pitch (5 rows + Save must fit)
    private static final int NAV_W = 74;      // left sub-nav column width
    private static final int NAV_SP = 20;     // sub-nav row pitch
    private static final int LIST_ROW = 24;   // list row height(两行内容 9+9 加呼吸,贴行显挤)
    private static final int TOG_W = 18, TOG_H = 10;
    /** 试听用的固定测试句(按当前表单参数就地合成)。 */

    private final Host host;

    // ---- palette: re-read from the CURRENT theme on every public entry (theme switch = live) ----
    private int BORDER, ACCENT, TXT, TXT_MUTED, TXT_FAINT, CTA, FIELD, OK, RUN, FAIL;

    private Section section = Section.PROVIDER;
    /** 未被模态屏蔽的真实鼠标坐标(表单卡内的 NumenUI 悬停用)。 */
    private int rawMouseX = -10000, rawMouseY = -10000;

    // ---- 模型配置表单:NumenUI ProfileFormPanel(检测/思考/强度/toast/分类报错) ----
    private ProfileFormPanel providerForm;
    private ProfileFormPanel.Draft providerDraft = new ProfileFormPanel.Draft();

    private ProfileFormPanel providerForm() {
        if (providerForm == null) {
            providerForm = new ProfileFormPanel(
                    this::onProfileSave,
                    () -> { addingProvider = false; providerEditId = null; host.rebuild(); });
        }
        return providerForm;
    }

    // ---- 模型配置列表:通用 LibraryListPanel(ListView 行 + ConfirmDialog 删除闸) ----
    private LibraryListPanel<com.dwinovo.numen.agent.llm.ProviderLibrary.Entry> profileList;

    private LibraryListPanel<com.dwinovo.numen.agent.llm.ProviderLibrary.Entry> profileList() {
        if (profileList == null) {
            profileList = new LibraryListPanel<>(
                    ModLanguageData.Keys.PROVIDER_TITLE, ModLanguageData.Keys.PROVIDER_ADD,
                    ModLanguageData.Keys.PROVIDER_EMPTY,
                    () -> com.dwinovo.numen.agent.llm.ProviderLibrary.instance().list(),
                    e -> {
                        boolean hasKey = nb(e.apiKey());
                        String meta = (nb(e.provider()) ? e.provider() : "?") + " · "
                                + (nb(e.model()) ? e.model() : "?")
                                + (hasKey ? "" : " · " + I18n.get(ModLanguageData.Keys.PROVIDER_NO_KEY));
                        return new LibraryListPanel.Row(e.name() == null ? "" : e.name(), meta, !hasKey, null);
                    },
                    e -> Component.translatable(ModLanguageData.Keys.PROVIDER_DELETE_CONFIRM,
                            e.name() == null ? "" : e.name()).getString(),
                    e -> com.dwinovo.numen.agent.llm.ProviderLibrary.instance().remove(e.id()),
                    () -> {
                        addingProvider = true;
                        providerEditId = null;
                        providerDraft = new ProfileFormPanel.Draft();
                        host.rebuild();
                    },
                    this::beginEditProvider);
        }
        return profileList;
    }

    // ---- 声线列表:同一底盘,加标题行全局开关与行首绑定 ● ----
    private LibraryListPanel<com.dwinovo.numen.client.voice.VoiceLibrary.Entry> voiceListPanel;

    private LibraryListPanel<com.dwinovo.numen.client.voice.VoiceLibrary.Entry> voiceListPanel() {
        if (voiceListPanel == null) {
            voiceListPanel = new LibraryListPanel<>(
                    ModLanguageData.Keys.VOICE_TITLE, ModLanguageData.Keys.VOICE_ADD,
                    ModLanguageData.Keys.VOICE_EMPTY,
                    () -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().list(),
                    e -> {
                        String detail;
                        if (e.isSovits()) detail = nb(e.refAudio()) ? e.refAudio() : "?";
                        else if (e.isMiniMax() || e.isFishAudio()) detail = nb(e.voice()) ? e.voice() : "?";
                        else detail = nb(e.model()) ? e.model() : "?";
                        String meta = (nb(e.backend()) ? e.backend() : "openai") + " · " + detail
                                + " · vol " + Math.round(e.volume() * 5.0f);
                        // 行首 ● = 本同伴正在用的声线(召唤时选定);只读标记,不提供事后换绑。
                        Boolean marked = host.uuid() == null ? null : e.id().equals(
                                com.dwinovo.numen.client.agent.CompanionHome.binding(host.uuid()).voiceId());
                        return new LibraryListPanel.Row(e.name(), meta, false, marked);
                    },
                    e -> Component.translatable(ModLanguageData.Keys.VOICE_DELETE_CONFIRM,
                            e.name() == null ? "" : e.name()).getString(),
                    e -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().remove(e.id()),
                    () -> {
                        addingVoice = true;
                        voiceEditId = null;
                        voiceDraft = VoiceFormPanel.freshDraft();
                        host.rebuild();
                    },
                    this::beginEditVoice)
                    .withToggle(ModLanguageData.Keys.VOICE_ENABLED,
                            () -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().enabled(),
                            v -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().setEnabled(v));
        }
        return voiceListPanel;
    }

    private void onProfileSave(ProfileFormPanel.Draft d) {
        var lib = com.dwinovo.numen.agent.llm.ProviderLibrary.instance();
        if (providerEditId != null) {
            lib.update(new com.dwinovo.numen.agent.llm.ProviderLibrary.Entry(
                    providerEditId, d.name.trim(), d.provider, d.model.trim(),
                    d.apiKey.trim(), d.baseUrl.trim(), d.reasoningEffort, d.proxy.trim()));
        } else {
            lib.create(d.name.trim(), d.provider, d.model.trim(),
                    d.apiKey.trim(), d.baseUrl.trim(), d.reasoningEffort, d.proxy.trim());
        }
        addingProvider = false;
        providerEditId = null;
        host.rebuild();
    }

    // ---- model-config section state (mirrors the persona section) ----
    private boolean addingProvider;
    private String providerEditId;

    // ---- voice section state (mirrors the model-config section: list / form / delete-confirm) ----
    private boolean addingVoice;
    private String voiceEditId;
    // ---- 声线表单:NumenUI VoiceFormPanel(后端切行/滚动/试听/胶囊) ----
    private VoiceFormPanel voiceForm;
    private VoiceFormPanel.Draft voiceDraft = VoiceFormPanel.freshDraft();

    // 皮肤库(列表+表单,照声线库制式)。签名发生在保存时(MineSkin 代签),召唤只读现成结果。
    private boolean addingSkin;
    // ---- 皮肤表单:NumenUI SkinFormPanel(名称/手臂模型/文件导入/MineSkin 签名) ----
    private SkinFormPanel skinForm;
    private SkinFormPanel.Draft skinDraft = new SkinFormPanel.Draft();

    // ---- proxy section state (IP + port) ----

    // Persona library form state (mirrors the MCP add/edit/delete flow).
    private boolean addingPersona;
    private String personaEditId;          // non-null = editing this persona; null = creating
    // ---- 人格表单:NumenUI PersonaFormPanel(名称 + 多行正文编辑器) ----
    private PersonaFormPanel personaForm;
    private PersonaFormPanel.Draft personaDraft = new PersonaFormPanel.Draft();

    // ---- MCP 分区:NumenUI McpFormPanel + LibraryListPanel(行内启停/状态点/tooltip) ----
    private boolean addingMcp;
    private McpFormPanel mcpForm;
    private McpFormPanel.Draft mcpDraft = new McpFormPanel.Draft();

    // ---- 左侧子导航:NumenUI NavPanel(选中胶囊+竖条,悬停动效随 ListView) ----
    private NavPanel navPanel;

    private NavPanel navPanel() {
        if (navPanel == null) {
            navPanel = new NavPanel(i -> selectSection(Section.values()[i]));
        }
        return navPanel;
    }

    /** 子导航标签:与 Section 声明顺序严格对应。 */
    private static List<String> navLabels() {
        return List.of(
                I18n.get(ModLanguageData.Keys.PROVIDER_TITLE),
                I18n.get("numen.settings.nav.mcp"),
                I18n.get("numen.settings.nav.brain"),
                I18n.get("numen.settings.nav.skills"),
                I18n.get("numen.settings.nav.persona"),
                I18n.get(ModLanguageData.Keys.VOICE_TITLE),
                I18n.get(ModLanguageData.Keys.SKIN_TITLE),
                I18n.get(ModLanguageData.Keys.STT_NAV),
                I18n.get("numen.settings.nav.theme"));
    }

    // ---- STT 分区:NumenUI SttPanel(服务商联动/模型双态/麦克风/保存回执) ----
    private SttPanel sttPanel;

    private SttPanel sttPanel() {
        if (sttPanel == null) sttPanel = new SttPanel();
        return sttPanel;
    }

    public SettingsView(Host host) {
        this.host = host;
    }

    private void loadPalette() {
        UiTheme t = UiTheme.current();
        BORDER = t.border();
        ACCENT = t.cta();
        TXT = t.text();
        TXT_MUTED = t.textDim();
        TXT_FAINT = t.faint();
        CTA = t.cta();
        FIELD = t.field();
        OK = t.ok();
        RUN = t.run();
        FAIL = t.fail();
    }

    // ---- geometry (all off the host so window resizes keep working) ----

    private int left() { return host.left(); }
    private int top() { return host.top(); }
    private int panelW() { return host.panelW(); }
    private int panelH() { return host.panelH(); }
    private Font font() { return host.font(); }

    /** Left x of the section content area (right of the sub-nav column + divider). */
    private int secX() { return left() + PAD + NAV_W + 8; }
    /** Width of the section content area. */
    private int secW() { return panelW() - PAD - NAV_W - 8 - PAD; }
    /** Top y of section content (below the header). */
    private int secY0() { return top() + HEADER_H + 8; }
    /** Bottom y a list row may reach. */
    private int secBottom() { return top() + panelH() - PAD; }

    // ---- form modal (add/edit forms float on a card over the dimmed list) ----

    /** 任一新建/编辑表单在场(表单模态)——屏幕据此屏蔽背景交互。 */
    public boolean formActive() {
        return addingProvider || addingVoice || addingSkin || addingPersona || addingMcp;
    }

    /** Esc while a form modal is up: close it back to the list (same semantics as the ✕ button). */
    public boolean cancelForm() {
        if (!formActive()) return false;
        addingProvider = false; providerEditId = null;
        addingVoice = false; voiceEditId = null;
        if (voiceForm != null) voiceForm.cancelPendingTest();
        addingSkin = false;
        if (skinForm != null) skinForm.cancelPending();
        addingPersona = false; personaEditId = null;
        addingMcp = false;
        host.rebuild();
        return true;
    }

    // 表单卡:面板区域内缩 10px 的近全幅卡——小面板下可用面积本就紧张,弹层感
    // 靠四周暗边 + 圆角传达。卡内表单坐标系(f*)只在表单态使用,列表照旧走 sec*。
    private int cardX0() { return left() + 10; }
    private int cardY0() { return top() + 10; }
    private int cardX1() { return left() + panelW() - 10; }
    private int cardY1() { return top() + panelH() - 10; }
    /** Left x of form content inside the card. */
    private int fx() { return cardX0() + 10; }
    /** Width of form content inside the card. */
    private int fw() { return cardX1() - cardX0() - 20; }
    /** Top y of form content (below the card's title row). */
    private int fy0() { return cardY0() + 18; }
    /** Right edge form buttons align to. */
    private int fRight() { return cardX1() - 10; }
    /** Bottom edge the form's save row sits above. */
    private int fBottom() { return cardY1() - 10; }

    /** 表单模态的暗幕 + 近全幅圆角卡 + 卡顶标题。 */
    private void formModal(GuiGraphics g, Component title) {
        UiTheme t = UiTheme.current();
        g.fill(host.railX(), top(), left() + panelW(), top() + panelH(),
                (t.border() & 0xFFFFFF) | 0x99000000);
        com.dwinovo.numen.client.ui.RoundRect.card(g, cardX0(), cardY0(), cardX1(), cardY1(),
                6, t.aiFill(), t.aiBorder());
        txt(g, title, fx(), cardY0() + 6, TXT);
    }

    // ---- shared draw helpers (private copies — see NumenScreen's originals) ----

    private void txt(GuiGraphics g, Component c, int x, int y, int color) {
        Nb.text(g, font(), c, x, y, color);
    }

    /** Shadowless placeholder for an empty, unfocused field — the EditBox's own hint renders with a shadow. */
    private void placeholder(GuiGraphics g, EditBox f, String text) {
        if (f != null && f.visible && f.getValue().isEmpty() && !f.isFocused()
                && text != null && !text.isEmpty()) {
            txt(g, Component.literal(text), f.getX(), f.getY(), TXT_FAINT);
        }
    }

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }

    private static String nv(String s) {
        return s == null ? "" : s;
    }

    // ---- public surface (called by NumenScreen) ----

    /** Null every widget reference (the screen just cleared the actual widget lists). */
    public void clearWidgets() {
    }

    private void selectSection(Section s) {
        if (s == section) return;
        section = s;
        if (sttPanel != null) sttPanel.reseed();   // 进分区从已存配置重播种
        if (s == Section.PERSONA) {
            // 人设是目录里的 .md 文件:进页先重扫,外部编辑器的修改即时可见。
            PersonaLibrary.instance().reload();
        }
        addingMcp = false;
        addingPersona = false;
        personaEditId = null;
        addingProvider = false;
        providerEditId = null;
        addingVoice = false;
        voiceEditId = null;
        if (voiceForm != null) voiceForm.cancelPendingTest();   // 离开语音表单:在途试听回调作废
        addingSkin = false;
        if (skinForm != null) skinForm.cancelPending();   // 离开皮肤表单:在途 MineSkin 签名回调作废
        host.rebuild();
    }

    // ---- delete-confirm modal (shared by the five sections that can delete) ----

    /** Dispatch widget building by the active section (skill/MCP lists render manually). */
    public void buildWidgets() {
        loadPalette();
        navPanel().build(left() + PAD - 4, secY0() - 3, NAV_W, secBottom() - secY0() + 3,
                navLabels(), section.ordinal());
        switch (section) {
            case SKILLS -> skillsListPanel().build(secX(), secY0() - 2, secW(),
                    secBottom() - secY0() + 2, left(), top(), panelW(), panelH());
            case MCP -> {
                // 列表面板始终在场(表单模态时作背景);删除确认是面板自己的浮层。
                mcpListPanel().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2,
                        left(), top(), panelW(), panelH());
                if (addingMcp) buildMcpForm();
            }
            case PERSONA -> {
                // 列表面板始终在场(表单模态时作背景);删除确认是面板自己的浮层。
                personaListPanel().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2,
                        left(), top(), panelW(), panelH());
                if (addingPersona) buildPersonaForm();
            }
            case PROVIDER -> {
                // 列表面板始终在场(表单模态时作背景);删除确认是面板自己的浮层。
                profileList().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2,
                        left(), top(), panelW(), panelH());
                if (addingProvider) buildProviderFormNew();
            }
            case VOICE -> {
                // 列表面板始终在场(表单模态时作背景);删除确认是面板自己的浮层。
                voiceListPanel().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2,
                        left(), top(), panelW(), panelH());
                if (addingVoice) buildVoiceForm();
            }
            case SKIN -> {
                // 列表面板始终在场(表单模态时作背景);删除确认是面板自己的浮层。
                skinListPanel().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2,
                        left(), top(), panelW(), panelH());
                if (addingSkin) buildSkinForm();
            }
            case BRAIN -> brainPanel().build(secX(), secY0() - 2, secW(),
                    secBottom() - secY0() + 2);
            case STT -> sttPanel().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2);
            case THEME -> themePanel().build(secX(), secY0() - 2, secW(),
                    secBottom() - secY0() + 2);
        }
    }

    // ---- Proxy section: the global network proxy, its own tab (IP + port) ----

    // ---- Voice input (STT) section: provider dropdown → prefilled base/model, mic dropdown ----

    // ---- External-brain section: 我们自己当 MCP 服务器,把同伴交给外面的 AI 驱动 ----

    /** 本节的纵向锚点(相对 secY0):build 与 render 共读一份,按钮和标签才不会跑偏。 */

    // ---- Provider section: the library of named LLM provider configs companions select from ----

    /** 表单卡里的 NumenUI 表单(检测/思考/强度/toast 齐备);卡壳照旧 formModal。 */
    private void buildProviderFormNew() {
        providerForm().open(providerDraft);
        providerForm().build(fx(), fy0(), fw(), fBottom() - fy0(),
                top() + panelH() - 2);
    }


    // ---- Voice section: the library of named TTS voices companions bind to (mirrors the provider section) ----


    /** 表单卡里的 NumenUI 声线表单(后端切行/滚动/试听/胶囊);卡壳照旧 formModal。 */
    private void buildVoiceForm() {
        voiceForm().open(voiceDraft);
        voiceForm().build(fx(), fy0(), fw(), fBottom() - fy0(), top() + panelH() - 2);
    }

    private VoiceFormPanel voiceForm() {
        if (voiceForm == null) {
            voiceForm = new VoiceFormPanel(this::onVoiceSave,
                    () -> {
                        addingVoice = false;
                        voiceEditId = null;
                        voiceForm.cancelPendingTest();
                        host.rebuild();
                    });
        }
        return voiceForm;
    }





    /** 当前表单(w 值)拼成一个 Entry;id 由调用方给(编辑=原 id,试听=临时)。 */
    private void onVoiceSave(VoiceFormPanel.Draft d) {
        String name = d.name.trim();
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
        if (voiceEditId != null) {
            lib.update(VoiceFormPanel.entryOf(d, voiceEditId, name));
        } else {
            var e = VoiceFormPanel.entryOf(d, "", name);
            var created = lib.create(name, e.backend(), e.url(), e.apiKey(), e.groupId(), e.model(),
                    e.voice(), e.refAudio(), e.promptText(), e.textLang(), e.volume());
            // 从某个同伴的设置页新建 → 直接绑给它:用户的心智模型是"建声线就是给
            // 这只配音",绑定下拉只用于换绑/多同伴共用一条声线。
            if (host.uuid() != null) {
                com.dwinovo.numen.client.agent.CompanionHome.bind(host.uuid(),
                        com.dwinovo.numen.client.agent.CompanionHome.binding(host.uuid())
                                .withVoice(created.id()));
            }
        }
        addingVoice = false;
        voiceEditId = null;
        voiceDraft = VoiceFormPanel.freshDraft();
        host.rebuild();
    }

    private void renderVoiceSection(GuiGraphics g, int mouseX, int mouseY) {
        var surface = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font());
        if (addingVoice) {
            // 表单模态:列表照常渲染作背景(不响应 hover),暗幕+表单卡压上。
            voiceListPanel().render(surface, HostThemeColors.current(),
                    -10000, -10000, net.minecraft.Util.getMillis());
            formModal(g, Component.translatable(ModLanguageData.Keys.VOICE_TITLE));
            voiceForm().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), rawMouseX, rawMouseY, net.minecraft.Util.getMillis());
            return;
        }
        voiceListPanel().render(surface, HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());
    }

    private void beginEditVoice(com.dwinovo.numen.client.voice.VoiceLibrary.Entry e) {
        addingVoice = true;
        voiceEditId = e.id();
        var d = new VoiceFormPanel.Draft();
        d.backend = normalizeVoiceBackend(e.backend());
        d.name = nv(e.name());
        d.url = nv(e.url());
        d.apiKey = nv(e.apiKey());
        d.groupId = nv(e.groupId());
        d.model = nv(e.model());
        d.voice = nv(e.voice());
        d.refAudio = nv(e.refAudio());
        d.promptText = nv(e.promptText());
        d.textLang = nv(e.textLang());
        // 存储的是增益(0.2~2.0),表单显示 1~10 档。
        d.volume = Math.round(Math.clamp(e.volume(), 0.2f, 2.0f) * 5.0f);
        voiceDraft = d;
        host.rebuild();
    }

    /** 存储里的 backend 串归一到下拉的四个已知 id(未知/留空按 openai)。 */
    private static String normalizeVoiceBackend(String backend) {
        String b = backend == null ? "" : backend.toLowerCase(java.util.Locale.ROOT).strip();
        return switch (b) {
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_SOVITS,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX,
                 com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_FISH -> b;
            default -> com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_OPENAI;
        };
    }

    // ---- Persona section: a library of reusable personas; apply one to the active companion ----

    // ---- 人格列表:通用 LibraryListPanel + 预设行 ⧉ 克隆 + 标题行 ↻ 重扫 ----
    private LibraryListPanel<PersonaLibrary.Persona> personaListPanel;

    private LibraryListPanel<PersonaLibrary.Persona> personaListPanel() {
        if (personaListPanel == null) {
            personaListPanel = new LibraryListPanel<>(
                    "numen.persona.title", "numen.persona.add", "numen.persona.empty",
                    () -> PersonaLibrary.instance().list(),
                    p -> {
                        String badge = p.preset() ? I18n.get("numen.persona.preset_badge") + " · " : "";
                        // 正文预览压成单行(MD 里的换行在 24px 行里没有意义)。
                        String meta = (badge + p.text()).replace('\n', ' ');
                        return new LibraryListPanel.Row(p.name(), meta, false, null, p.preset());
                    },
                    p -> Component.translatable("numen.persona.delete_confirm", p.name()).getString(),
                    p -> PersonaLibrary.instance().remove(p.id()),
                    () -> {
                        addingPersona = true;
                        personaEditId = null;
                        personaDraft = new PersonaFormPanel.Draft();
                        host.rebuild();
                    },
                    this::beginEditPersona)
                    .withPresetClone(p -> PersonaLibrary.instance().clonePersona(p.id()))
                    // ↻ 重扫 persona/ 目录——外部编辑器改完 md 不用重开面板。
                    .withTitleAction("↻", () -> PersonaLibrary.instance().reload());
        }
        return personaListPanel;
    }

    /** 表单卡里的 NumenUI 人格表单(名称 + 多行正文编辑器);卡壳照旧 formModal。 */
    private void buildPersonaForm() {
        personaForm().open(personaDraft);
        personaForm().build(fx(), fy0(), fw(), fBottom() - fy0());
    }

    private PersonaFormPanel personaForm() {
        if (personaForm == null) {
            personaForm = new PersonaFormPanel(this::onPersonaSave,
                    () -> {
                        addingPersona = false;
                        personaEditId = null;
                        host.rebuild();
                    });
        }
        return personaForm;
    }

    private void onPersonaSave(PersonaFormPanel.Draft d) {
        String name = d.name.trim();
        String text = d.text.trim();
        var lib = PersonaLibrary.instance();
        if (personaEditId != null) {
            PersonaLibrary.Persona saved = lib.update(personaEditId, name, text);
            // 改正文不需要做任何事:同伴只记 id,正文用时去库里现取,编辑即刻
            // 对所有同伴生效(不管它加载没加载)。这里只处理改名——人设的 id
            // 就是文件名,改名等于换了身份,得把在用的同伴重新指过去。
            if (saved != null && !saved.id().equals(personaEditId)) {
                for (UUID cu : AgentLoopRegistry.loadedEntityUuids()) {
                    EntityAgentLoop l = AgentLoopRegistry.get(cu).orElse(null);
                    if (l != null && personaEditId.equals(l.personaId())) {
                        l.setPersona(saved.id(), saved.text(), saved.name());
                    }
                }
            }
        } else {
            lib.create(name, text);
        }
        addingPersona = false;
        personaEditId = null;
        personaDraft = new PersonaFormPanel.Draft();
        host.rebuild();
    }

    // ---- MCP section ----

    // ---- 大脑/主题分区:NumenUI 面板 ----
    private BrainPanel brainPanel;
    private ThemePanel themePanel;

    private BrainPanel brainPanel() {
        if (brainPanel == null) brainPanel = new BrainPanel();
        return brainPanel;
    }

    private ThemePanel themePanel() {
        if (themePanel == null) themePanel = new ThemePanel(host::repaintPalette);
        return themePanel;
    }

    private LibraryListPanel<com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle> mcpListPanel;

    private LibraryListPanel<com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle> mcpListPanel() {
        if (mcpListPanel == null) {
            mcpListPanel = new LibraryListPanel<>(
                    "numen.mcp.title", "numen.mcp.add", "numen.mcp.empty",
                    com.dwinovo.numen.mcp.client.McpClientManager::servers,
                    h -> new LibraryListPanel.Row(h.name(), mcpMeta(h),
                            h.status() == com.dwinovo.numen.mcp.client.McpClientManager.Status.FAILED, null),
                    h -> Component.translatable("numen.mcp.delete_confirm", h.name()).getString(),
                    h -> com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(h.name()),
                    () -> {
                        addingMcp = true;
                        mcpDraft = new McpFormPanel.Draft();
                        host.rebuild();
                    },
                    h -> beginEditMcp(h.name()))
                    .withRowIcon(8, (s, h, ix, iy, size) ->
                            s.fillRoundRect(ix + 1, iy + 1, 6, 6, 3, mcpDotColor(h.status())))
                    .withRowToggle(
                            h -> h.toggledOn(),
                            h -> {
                                var st = h.status();
                                // 连接中/已连接 → 关;禁用/失败 → (重)连(失败的点一下即重试)
                                if (st == com.dwinovo.numen.mcp.client.McpClientManager.Status.CONNECTED
                                        || st == com.dwinovo.numen.mcp.client.McpClientManager.Status.CONNECTING) {
                                    com.dwinovo.numen.mcp.client.McpClientManager.disableServer(h.name());
                                } else {
                                    com.dwinovo.numen.mcp.client.McpClientManager.enableServer(h.name());
                                }
                            });
        }
        return mcpListPanel;
    }

    /** 表单卡里的 NumenUI MCP 表单(类型切行/内联校验);卡壳照旧 formModal。 */
    private void buildMcpForm() {
        mcpForm().open(mcpDraft);
        mcpForm().build(fx(), fy0(), fw(), fBottom() - fy0());
    }

    private McpFormPanel mcpForm() {
        if (mcpForm == null) {
            mcpForm = new McpFormPanel(this::onMcpSave,
                    () -> {
                        addingMcp = false;
                        host.rebuild();
                    });
        }
        return mcpForm;
    }

    private void onMcpSave(McpFormPanel.Draft d) {
        String name = d.name.trim();
        String target = d.target.trim();
        // When editing, preserve the server's on/off state (a plain edit shouldn't flip its toggle).
        boolean enabled = true;
        if (d.editOriginal != null) {
            var orig = com.dwinovo.numen.mcp.client.McpClientManager.spec(d.editOriginal);
            if (orig != null) enabled = orig.enabled();
        }
        com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec spec;
        if (d.stdio) {
            String[] parts = target.split("\\s+");
            String command = parts[0];
            List<String> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) args.add(parts[i]);
            spec = new com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec(name, "stdio", "", java.util.Map.of(),
                    command, List.copyOf(args), parseEnv(d.extra), enabled, 20, 120);
        } else {
            spec = new com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec(name, "http", target, parseHeader(d.extra),
                    "", List.of(), java.util.Map.of(), enabled, 20, 120);
        }
        com.dwinovo.numen.mcp.client.McpClientManager.upsertServer(spec);
        // Renamed while editing → upsert wrote the new-named entry; drop the old one.
        if (d.editOriginal != null && !d.editOriginal.equals(name)) {
            com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(d.editOriginal);
        }
        addingMcp = false;
        mcpDraft = new McpFormPanel.Draft();
        host.rebuild();
    }

    /** Parse "Name: Value" header lines (multiple separated by ';' or newline) into a map. */
    private static java.util.Map<String, String> parseHeader(String line) {
        return parsePairs(line, ':');
    }

    /** Parse "KEY=value" env lines (multiple separated by ';' or newline) into a map. */
    private static java.util.Map<String, String> parseEnv(String line) {
        return parsePairs(line, '=');
    }

    private static java.util.Map<String, String> parsePairs(String line, char sep) {
        String s = line == null ? "" : line.trim();
        if (s.isEmpty()) return java.util.Map.of();
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String part : s.split("[;\\n]")) {
            String p = part.trim();
            int i = p.indexOf(sep);
            if (i <= 0) continue;
            String k = p.substring(0, i).trim();
            String v = p.substring(i + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
        }
        return java.util.Map.copyOf(out);
    }

    // ---- 技能列表:通用 LibraryListPanel 的纯开关形态(无新建/编辑/删除) ----
    private LibraryListPanel<com.dwinovo.numen.agent.skill.SkillInfo> skillsListPanel;

    private LibraryListPanel<com.dwinovo.numen.agent.skill.SkillInfo> skillsListPanel() {
        if (skillsListPanel == null) {
            skillsListPanel = new LibraryListPanel<com.dwinovo.numen.agent.skill.SkillInfo>(
                    "numen.skill.title", null, "numen.skill.empty",
                    () -> new ArrayList<>(com.dwinovo.numen.agent.skill.SkillRegistry.instance().all()),
                    sk -> {
                        String desc = sk.description() == null
                                ? I18n.get("numen.skill.no_desc") : sk.description();
                        return new LibraryListPanel.Row(sk.name(), desc, false, null);
                    },
                    null, sk -> { }, () -> { }, sk -> { })
                    .withRowToggle(
                            sk -> !com.dwinovo.numen.agent.skill.SkillRegistry.instance().isDisabled(sk.name()),
                            sk -> {
                                var reg = com.dwinovo.numen.agent.skill.SkillRegistry.instance();
                                reg.setEnabled(sk.name(), reg.isDisabled(sk.name()));   // flip
                            })
                    .withTitleAction(I18n.get("numen.skill.open_dir"), SettingsView::openSkillsFolder);
        }
        return skillsListPanel;
    }

    private void renderSkillsSection(GuiGraphics g, int mouseX, int mouseY) {
        skillsListPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
        // 悬停行体 → tooltip:技能名 + 完整描述(行内被 clip 过)。
        var sk = skillsListPanel().entryAtBody(mouseX, mouseY);
        if (sk != null && sk.description() != null) {
            host.tip(List.of(Component.literal(sk.name()), Nb.colored(sk.description(), TXT_MUTED)),
                    mouseX, mouseY);
        }
    }

    private static void openSkillsFolder() {
        try {
            java.nio.file.Path dir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve(com.dwinovo.numen.Constants.MOD_ID).resolve("skills");
            java.nio.file.Files.createDirectories(dir);
            net.minecraft.Util.getPlatform().openUri(dir.toUri());
        } catch (Exception ex) {
            com.dwinovo.numen.Constants.LOG.warn("[numen] open skills folder failed: {}", ex.toString());
        }
    }

    /** 声线表单的行标题:画在该行输入框上方(随滚动偏移,出视口不画)。 */

    // ---- Skin section: the named skin library (upload png → MineSkin-signed textures) ----

    // ---- 皮肤列表:通用 LibraryListPanel + 行首脸预览(经 McDrawSurface 取原生画布) ----
    private LibraryListPanel<com.dwinovo.numen.client.skin.SkinLibrary.Entry> skinListPanel;

    private LibraryListPanel<com.dwinovo.numen.client.skin.SkinLibrary.Entry> skinListPanel() {
        if (skinListPanel == null) {
            skinListPanel = new LibraryListPanel<>(
                    ModLanguageData.Keys.SKIN_TITLE, ModLanguageData.Keys.SKIN_ADD,
                    ModLanguageData.Keys.SKIN_EMPTY,
                    () -> com.dwinovo.numen.client.skin.SkinLibrary.instance().list(),
                    e -> {
                        boolean signed = e.signed();
                        String meta = I18n.get(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_SLIM
                                .equals(e.variant())
                                ? ModLanguageData.Keys.SKIN_VARIANT_SLIM
                                : ModLanguageData.Keys.SKIN_VARIANT_CLASSIC)
                                + " · " + I18n.get(signed ? ModLanguageData.Keys.SKIN_SIGNED
                                        : ModLanguageData.Keys.SKIN_UNSIGNED);
                        return new LibraryListPanel.Row(e.name(), meta, !signed, null);
                    },
                    e -> Component.translatable(ModLanguageData.Keys.SKIN_DELETE_CONFIRM,
                            e.name() == null ? "" : e.name()).getString(),
                    e -> com.dwinovo.numen.client.skin.SkinLibrary.instance().remove(e.id()),
                    () -> {
                        addingSkin = true;
                        skinDraft = new SkinFormPanel.Draft();
                        host.rebuild();
                    },
                    this::beginEditSkin)
                    .withRowIcon(16, (s, e, ix, iy, size) -> {
                        if (!(s instanceof com.dwinovo.numen.client.ui.mc.McDrawSurface mc)) return;
                        var lib = com.dwinovo.numen.client.skin.SkinLibrary.instance();
                        var face = com.dwinovo.numen.client.skin.SkinTextures.faceOf(e.id(), lib.pngPath(e.id()));
                        if (face != null) {
                            net.minecraft.client.gui.components.PlayerFaceRenderer.draw(
                                    mc.graphics(), face, ix, iy, size);
                        }
                    });
        }
        return skinListPanel;
    }

    /** 表单卡里的 NumenUI 皮肤表单(名称/手臂模型/文件导入/签名);卡壳照旧 formModal。 */
    private void buildSkinForm() {
        skinForm().open(skinDraft);
        skinForm().build(fx(), fy0(), fw(), fBottom() - fy0());
    }

    private SkinFormPanel skinForm() {
        if (skinForm == null) {
            skinForm = new SkinFormPanel(
                    signedName -> {   // 保存完成;真签过名才在列表页报回执
                        addingSkin = false;
                        skinDraft = new SkinFormPanel.Draft();
                        host.rebuild();
                        if (signedName != null) {
                            skinListPanel().noticeSuccess(Component.translatable(
                                    ModLanguageData.Keys.SKIN_SIGN_OK, signedName).getString());
                        }
                    },
                    () -> {   // ✕ 关闭
                        addingSkin = false;
                        skinForm.cancelPending();
                        host.rebuild();
                    },
                    this::openNativeSkinPicker);
        }
        return skinForm;
    }

    private void beginEditSkin(com.dwinovo.numen.client.skin.SkinLibrary.Entry e) {
        addingSkin = true;
        var d = new SkinFormPanel.Draft();
        d.editId = e.id();
        d.name = e.name();
        d.variant = e.variant();
        // 不换图时沿用落盘原图(改手臂模型重签也从盘上读),dropped 留空。
        skinDraft = d;
        host.rebuild();
    }

    private void renderSkinSection(GuiGraphics g, int mouseX, int mouseY) {
        var surface = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font());
        if (addingSkin) {
            skinListPanel().render(surface, HostThemeColors.current(),
                    -10000, -10000, net.minecraft.Util.getMillis());
            formModal(g, Component.translatable(ModLanguageData.Keys.SKIN_TITLE));
            skinForm().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), rawMouseX, rawMouseY, net.minecraft.Util.getMillis());
            return;
        }
        skinListPanel().render(surface, HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());
    }

    /** 皮肤 png 从系统拖进游戏窗口(皮肤表单打开时)。64×64 或旧版 64×32。 */
    public void onFilesDrop(List<java.nio.file.Path> paths) {
        if (!(section == Section.SKIN && addingSkin)) return;
        for (java.nio.file.Path p : paths) {
            if (!p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
            skinForm().importFile(p);
            return;
        }
    }

    /** 原生文件对话框(LWJGL tinyfd,MC 自带;FCL 端会翻译成安卓文件选择器):
     *  独立线程弹窗防冻主循环,选中后回主线程导入。 */
    private void openNativeSkinPicker() {
        new Thread(() -> {
            String chosen = null;
            try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                var filters = stack.mallocPointer(1);
                filters.put(stack.UTF8("*.png")).flip();
                chosen = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                        "Numen skin (64x64 png)", null, filters, "PNG", false);
            } catch (Throwable t) {
                com.dwinovo.numen.Constants.LOG.warn("[numen-skin] native file dialog unavailable", t);
            }
            String path = chosen;
            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                if (path != null && section == Section.SKIN && addingSkin) {
                    skinForm().importFile(java.nio.file.Path.of(path));
                }
            });
        }, "numen-skin-picker").start();
    }

    // ---- render (nav + active section) ----

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        loadPalette();
        // 任一模态(确认卡/表单卡)在场时整体屏蔽悬停坐标——暗幕下的列表行/导航
        // 不该亮悬停底,MCP 行 tooltip 也不该浮到暗幕上。
        // 但表单卡自己是活的:真实坐标另存一份,供卡内的 NumenUI 表单用
        // (否则表单里的下拉/按钮悬停被误杀)。
        rawMouseX = mouseX;
        rawMouseY = mouseY;
        if (formActive()) {
            mouseX = -10000;
            mouseY = -10000;
        }
        // 内容底板:比地面亮一档的"纸面"垫住整个设置区(导航+正文),文字不再直接
        // 铺在点纹地面上——点纹退成底板四周的氛围纹理,层级和对比度都立起来。
        UiTheme th = UiTheme.current();
        com.dwinovo.numen.client.ui.RoundRect.card(g,
                left() + 5, top() + HEADER_H + 2, left() + panelW() - 5, top() + panelH() - 5,
                6, th.surface(), th.surfaceBorder());
        navPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
        int dx = left() + PAD + NAV_W + 3;
        g.fill(dx, secY0() - 2, dx + 1, secBottom(), BORDER);   // 导航与正文的竖分隔线
        switch (section) {
            case MCP -> renderMcpSection(g, mouseX, mouseY);
            case SKILLS -> {
                renderSkillsSection(g, mouseX, mouseY);
            }
            case PERSONA -> renderPersonaSection(g, mouseX, mouseY);
            case PROVIDER -> renderProviderSection(g, mouseX, mouseY);
            case VOICE -> renderVoiceSection(g, mouseX, mouseY);
            case SKIN -> renderSkinSection(g, mouseX, mouseY);
            case BRAIN -> brainPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
            case STT -> sttPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
            case THEME -> themePanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
        }
    }

    private void renderProviderSection(GuiGraphics g, int mouseX, int mouseY) {
        var surface = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font());
        if (addingProvider) {
            // 表单模态:列表照常渲染作背景(不响应 hover),暗幕+表单卡压在上面。
            profileList().render(surface, HostThemeColors.current(),
                    -10000, -10000, net.minecraft.Util.getMillis());
            formModal(g, Component.translatable(ModLanguageData.Keys.PROVIDER_TITLE));
            providerForm().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), rawMouseX, rawMouseY, net.minecraft.Util.getMillis());
            return;
        }
        profileList().render(surface, HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());
    }

    private void beginEditProvider(com.dwinovo.numen.agent.llm.ProviderLibrary.Entry e) {
        addingProvider = true;
        providerEditId = e.id();
        providerDraft = new ProfileFormPanel.Draft();
        providerDraft.name = e.name() == null ? "" : e.name();
        providerDraft.provider = e.provider() == null ? "" : e.provider();
        providerDraft.model = e.model() == null ? "" : e.model();
        providerDraft.apiKey = e.apiKey() == null ? "" : e.apiKey();
        providerDraft.baseUrl = e.baseUrl() == null ? "" : e.baseUrl();
        providerDraft.reasoningEffort = e.reasoningEffort() == null ? "" : e.reasoningEffort();
        providerDraft.proxy = e.proxy() == null ? "" : e.proxy();
        host.rebuild();
    }

    // ---- MCP section: external server list with a live on/off switch per row ----

    private void renderMcpSection(GuiGraphics g, int mouseX, int mouseY) {
        var surface = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font());
        if (addingMcp) {
            mcpListPanel().render(surface, HostThemeColors.current(),
                    -10000, -10000, net.minecraft.Util.getMillis());
            formModal(g, Component.translatable("numen.mcp.title"));
            mcpForm().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), rawMouseX, rawMouseY, net.minecraft.Util.getMillis());
            return;
        }
        mcpListPanel().render(surface, HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());
        // 悬停行体 → tooltip:工具名 + url/命令 + 错误(行尾动作热区上不弹)。
        var hovered = mcpListPanel().entryAtBody(mouseX, mouseY);
        if (hovered != null) {
            host.tip(mcpTooltip(hovered), mouseX, mouseY);
        }
    }

    private int mcpDotColor(com.dwinovo.numen.mcp.client.McpClientManager.Status s) {
        return switch (s) {
            case CONNECTED -> OK;
            case CONNECTING -> RUN;
            case FAILED -> FAIL;
            case DISABLED -> TXT_FAINT;
        };
    }

    private String mcpMeta(com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle h) {
        return switch (h.status()) {
            case CONNECTED -> I18n.get("numen.mcp.connected", h.type(), h.toolCount());
            case CONNECTING -> I18n.get("numen.mcp.connecting", h.type());
            case FAILED -> I18n.get("numen.mcp.failed", h.type());
            case DISABLED -> I18n.get("numen.mcp.disabled", h.type());
        };
    }

    private List<Component> mcpTooltip(com.dwinovo.numen.mcp.client.McpClientManager.ServerHandle h) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(h.name()));
        var spec = com.dwinovo.numen.mcp.client.McpClientManager.spec(h.name());
        if (spec != null) {
            lines.add(Nb.colored(spec.isStdio() ? spec.command() : spec.url(), TXT_FAINT));
        }
        if (h.status() == com.dwinovo.numen.mcp.client.McpClientManager.Status.FAILED && !h.error().isBlank()) {
            lines.add(Nb.colored(h.error(), FAIL));
        } else if (!h.toolNames().isEmpty()) {
            lines.add(Nb.colored(String.join(", ", h.toolNames()), TXT_MUTED));
        }
        return lines;
    }

    // ---- Skills section: skill list with a live on/off switch per row ----

    // ---- shared toggle switch (no vanilla widget for this) ----

    // ---- Persona section render + hit-test ----

    private void renderPersonaSection(GuiGraphics g, int mouseX, int mouseY) {
        var surface = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font());
        if (addingPersona) {
            personaListPanel().render(surface, HostThemeColors.current(),
                    -10000, -10000, net.minecraft.Util.getMillis());
            formModal(g, Component.translatable("numen.persona.title"));
            personaForm().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), rawMouseX, rawMouseY, net.minecraft.Util.getMillis());
            return;
        }
        personaListPanel().render(surface, HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());
    }

    private void beginEditPersona(PersonaLibrary.Persona p) {
        addingPersona = true;
        personaEditId = p.id();
        var d = new PersonaFormPanel.Draft();
        d.name = p.name();
        d.text = p.text();
        personaDraft = d;
        host.rebuild();
    }

    // ---- input (called from the screen's mouseClicked / mouseScrolled) ----

    /** The Settings tab's whole click chain — dropdown routing first (open lists overlay
     *  the fields), then the sub-nav / theme rows / per-row toggles. Returns true = consumed. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        loadPalette();
        // 模型配置表单(NumenUI):事件整体交给表单面板(浮层打开时它优先吃掉一切)。
        if (section == Section.PROVIDER && addingProvider
                && providerForm().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // NumenUI 列表面板:删除确认卡开着时面板吃掉一切(模态);
        // 平时接行/图标/开关/新建,没命中就放行给子导航。
        if (section == Section.PROVIDER && !addingProvider
                && profileList().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        if (section == Section.VOICE && !addingVoice
                && voiceListPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // STT 分区(NumenUI):下拉浮层/双态切换/保存全在面板里。
        if (section == Section.STT && sttPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // 声线表单(NumenUI):事件整体交给表单面板(浮层打开时它优先吃掉一切)。
        if (section == Section.VOICE && addingVoice
                && voiceForm().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // 人格表单(NumenUI):名称/正文编辑器/保存。
        if (section == Section.PERSONA && addingPersona
                && personaForm().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        if (section == Section.PERSONA && !addingPersona
                && personaListPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // 皮肤表单/列表(NumenUI)。
        if (section == Section.SKIN && addingSkin
                && skinForm().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        if (section == Section.SKIN && !addingSkin
                && skinListPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // MCP 表单/列表(NumenUI)。
        if (section == Section.MCP && addingMcp
                && mcpForm().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        if (section == Section.MCP && !addingMcp
                && mcpListPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // 技能列表(NumenUI 纯开关形态)。
        if (section == Section.SKILLS && skillsListPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // 大脑/主题分区(NumenUI)。
        if (section == Section.BRAIN && brainPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        if (section == Section.THEME && themePanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        // 子导航(NumenUI):表单模态时在暗幕之下不放行。
        if (!formActive() && navPanel().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        return false;
    }

    /** Open the add-form PRE-FILLED with {@code name}'s current spec — saving REPLACES the entry. */
    private void beginEditMcp(String name) {
        var spec = com.dwinovo.numen.mcp.client.McpClientManager.spec(name);
        if (spec == null) return;
        addingMcp = true;
        var d = new McpFormPanel.Draft();
        d.editOriginal = name;
        d.stdio = spec.isStdio();
        d.name = spec.name();
        if (d.stdio) {
            StringBuilder cmd = new StringBuilder(spec.command() == null ? "" : spec.command());
            for (String a : spec.args()) cmd.append(' ').append(a);
            d.target = cmd.toString().trim();
            d.extra = joinPairs(spec.env(), '=');       // stdio → env "KEY=value"
        } else {
            d.target = spec.url() == null ? "" : spec.url();
            d.extra = joinPairs(spec.headers(), ':');    // http → header "Name: Value"
        }
        mcpDraft = d;
        host.rebuild();
    }

    /** Reconstruct the header/env editor line from a spec map ("K: V; K2: V2" or "K=V; K2=V2"). */
    private static String joinPairs(java.util.Map<String, String> m, char sep) {
        if (m == null || m.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (var e : m.entrySet()) {
            if (b.length() > 0) b.append("; ");
            b.append(e.getKey()).append(sep == ':' ? ": " : "=").append(e.getValue());
        }
        return b.toString();
    }

    /** Wheel pass 1 (before the rail/chat checks, mirroring the old order): open dropdown
     *  lists first, then the voice form's own scroll. */
    public boolean mouseScrolledEarly(double mx, double my, double sy) {
        if (section == Section.PROVIDER && addingProvider
                && providerForm().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.PROVIDER && !addingProvider
                && profileList().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.VOICE && !addingVoice
                && voiceListPanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.VOICE && addingVoice
                && voiceForm().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.PERSONA && addingPersona
                && personaForm().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.PERSONA && !addingPersona
                && personaListPanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.SKIN && addingSkin
                && skinForm().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.SKIN && !addingSkin
                && skinListPanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.STT && sttPanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.MCP && !addingMcp
                && mcpListPanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.SKILLS && skillsListPanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        if (section == Section.THEME && themePanel().mouseScrolled(mx, my, sy)) {
            return true;
        }
        return false;
    }

    /** 拖动/松开:声线表单的音量滑条与人格正文的拖选需要。 */
    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        if (section == Section.VOICE && addingVoice) {
            return voiceForm().mouseDragged(mx, my, dx, dy);
        }
        if (section == Section.PERSONA && addingPersona) {
            return personaForm().mouseDragged(mx, my, dx, dy);
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (section == Section.VOICE && addingVoice) {
            return voiceForm().mouseReleased(mx, my, button);
        }
        if (section == Section.PERSONA && addingPersona) {
            return personaForm().mouseReleased(mx, my, button);
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int modifiers) {
        if (section == Section.PROVIDER) {
            if (addingProvider) return providerForm().keyPressed(keyCode, modifiers);
            return profileList().keyPressed(keyCode, modifiers);   // ESC 关删除确认(= 取消)
        }
        if (section == Section.VOICE) {
            if (addingVoice) return voiceForm().keyPressed(keyCode, modifiers);
            return voiceListPanel().keyPressed(keyCode, modifiers);
        }
        if (section == Section.PERSONA) {
            if (addingPersona) return personaForm().keyPressed(keyCode, modifiers);
            return personaListPanel().keyPressed(keyCode, modifiers);
        }
        if (section == Section.SKIN) {
            if (addingSkin) return skinForm().keyPressed(keyCode, modifiers);
            return skinListPanel().keyPressed(keyCode, modifiers);
        }
        if (section == Section.STT) return sttPanel().keyPressed(keyCode, modifiers);
        if (section == Section.MCP) {
            if (addingMcp) return mcpForm().keyPressed(keyCode, modifiers);
            return mcpListPanel().keyPressed(keyCode, modifiers);
        }
        return false;
    }

    public boolean charTyped(char ch) {
        if (section == Section.PROVIDER && addingProvider) return providerForm().charTyped(ch);
        if (section == Section.VOICE && addingVoice) return voiceForm().charTyped(ch);
        if (section == Section.PERSONA && addingPersona) return personaForm().charTyped(ch);
        if (section == Section.SKIN && addingSkin) return skinForm().charTyped(ch);
        if (section == Section.STT) return sttPanel().charTyped(ch);
        if (section == Section.MCP && addingMcp) return mcpForm().charTyped(ch);
        return false;
    }

    public void renderOverlays(GuiGraphics g, int mouseX, int mouseY) {
        loadPalette();
    }
}
