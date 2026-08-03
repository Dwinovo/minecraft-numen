package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.agent.llm.NumenLlmClient;

import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.screen.Dropdown;
import com.dwinovo.numen.client.screen.FlatEditBox;
import com.dwinovo.numen.client.screen.LlmProviders;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.SimpleButton;
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
                                com.dwinovo.numen.client.voice.VoiceLibrary.instance().assignedEntry(host.uuid()));
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
    private int settingsScroll;   // first visible row of the section lists (wheel-scroll when long)

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

    // ---- STT 分区:NumenUI SttPanel(服务商联动/模型双态/麦克风/保存回执) ----
    private SttPanel sttPanel;
    private long savedFlashUntil;

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

    /** 表单模态的暗幕 + 近全幅圆角卡 + 卡顶标题(与 ConfirmModal 同族的视觉参数)。 */
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

    /** Truncate {@code s} with an ellipsis so it fits in {@code maxW} px. */
    private String clip(String s, int maxW) {
        if (font().width(s) <= maxW) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (font().width(b.toString() + s.charAt(i) + "…") > maxW) break;
            b.append(s.charAt(i));
        }
        return b + "…";
    }

    /** Shadowless placeholder for an empty, unfocused field — the EditBox's own hint renders with a shadow. */
    private void placeholder(GuiGraphics g, EditBox f, String text) {
        if (f != null && f.visible && f.getValue().isEmpty() && !f.isFocused()
                && text != null && !text.isEmpty()) {
            txt(g, Component.literal(text), f.getX(), f.getY(), TXT_FAINT);
        }
    }

    private EditBox field(int x, int y, int w, int max, String value) {
        EditBox e = new FlatEditBox(font(), x + FIELD_INSET_X, y + FIELD_INSET_Y,
                w - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setBordered(false);
        e.setTextColor(TXT);
        host.add(e);
        return e;
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
        settingsScroll = 0;
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

    /** 旧删除确认模态已整体退役(各面板自带 ConfirmDialog 浮层);屏幕侧调用面保留。 */
    public boolean modalActive() {
        return false;
    }

    public boolean cancelModal() {
        return false;
    }

    /** Dispatch widget building by the active section (skill/MCP lists render manually). */
    public void buildWidgets() {
        loadPalette();
        switch (section) {
            case SKILLS -> buildSkillsWidgets();
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
            case BRAIN -> buildBrainWidgets();
            case STT -> sttPanel().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2);
            case THEME -> { /* no widgets — plain click rows */ }
        }
    }

    // ---- Proxy section: the global network proxy, its own tab (IP + port) ----

    // ---- Voice input (STT) section: provider dropdown → prefilled base/model, mic dropdown ----

    // ---- External-brain section: 我们自己当 MCP 服务器,把同伴交给外面的 AI 驱动 ----

    /** 本节的纵向锚点(相对 secY0):build 与 render 共读一份,按钮和标签才不会跑偏。 */
    private static final int BR_TOGGLE = 16, BR_HINT = 32, BR_ENDPOINT = 56,
            BR_TOKEN = 86, BR_PROMPT = 118, BR_WARN = 140, BR_STATUS = 158;
    private static final int BR_COPY_W = 46;

    private void buildBrainWidgets() {
        int x = secX(), w = secW(), fy = secY0();
        McpMode mcp = McpMode.instance();
        host.add(new SimpleButton(x + w - BR_COPY_W, fy + BR_ENDPOINT + 9, BR_COPY_W, 14,
                Component.translatable("numen.brain.copy"),
                b -> copyToClipboard(mcp.endpoint())));
        if (!mcp.token().isBlank()) {
            host.add(new SimpleButton(x + w - BR_COPY_W, fy + BR_TOKEN + 9, BR_COPY_W, 14,
                    Component.translatable("numen.brain.copy"),
                    b -> copyToClipboard(mcp.token())));
        }
        host.add(new SimpleButton(x, fy + BR_PROMPT, 150, 16,
                Component.translatable("numen.brain.copy_prompt"),
                b -> copyToClipboard(mcp.accessPrompt())).primary());
    }

    /** 复制并闪一下"已复制"——与保存态共用同一个提示位。 */
    private void copyToClipboard(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
        savedFlashUntil = System.currentTimeMillis() + 1500;
    }

    private void renderBrainSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW(), fy = secY0();
        McpMode mcp = McpMode.instance();
        boolean on = mcp.enabled();

        txt(g, Component.translatable("numen.brain.title"), x, fy - 2, TXT);

        // 开关行:整行可点(与 brainToggleClick 的命中区一致)。
        txt(g, Component.translatable("numen.brain.toggle"), x, fy + BR_TOGGLE, TXT);
        drawToggle(g, x + w - TOG_W, fy + BR_TOGGLE - 1, on);
        txt(g, Component.translatable(on ? "numen.brain.hint_on" : "numen.brain.hint_off"),
                x, fy + BR_HINT, TXT_FAINT);
        String err = mcp.lastError();
        if (err != null) {
            txt(g, Component.translatable("numen.brain.start_failed", err), x, fy + BR_HINT + 10, FAIL);
        }

        txt(g, Component.translatable("numen.brain.endpoint"), x, fy + BR_ENDPOINT, TXT_MUTED);
        txt(g, Component.literal(mcp.endpoint()), x, fy + BR_ENDPOINT + 11, TXT);

        txt(g, Component.translatable("numen.brain.token"), x, fy + BR_TOKEN, TXT_MUTED);
        // 明文令牌不上屏:截图/录屏泄露一次就永久泄露,要整份走复制按钮。
        txt(g, mcp.token().isBlank()
                        ? Component.translatable("numen.brain.token_none")
                        : Component.literal(mcp.maskedToken()),
                x, fy + BR_TOKEN + 11, mcp.token().isBlank() ? TXT_FAINT : TXT);

        txt(g, Component.translatable("numen.brain.prompt_warn"), x, fy + BR_WARN, TXT_FAINT);
        txt(g, brainStatusLine(mcp), x, fy + BR_STATUS, on ? OK : TXT_FAINT);

        if (savedFlashUntil > System.currentTimeMillis()) {
            txt(g, Component.translatable("numen.brain.copied"), x, top() + panelH() - PAD - 14, OK);
        }
    }

    /** 连接状态一行:没开 → 关闭;开着没人连 → 等待接入;连过 → 谁 + 多久前活跃。 */
    private Component brainStatusLine(McpMode mcp) {
        if (!mcp.enabled()) return Component.translatable("numen.brain.status_off");
        String who = mcp.clientName();
        if (who == null) return Component.translatable("numen.brain.status_waiting");
        return Component.translatable("numen.brain.status_connected", who, sinceLabel(mcp.lastActivityMs()));
    }

    /** "12 秒前" / "3 分钟前" —— 面板每帧重算,不缓存。 */
    private static String sinceLabel(long stampMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - stampMs) / 1000);
        if (sec < 60) return I18n.get("numen.brain.since_sec", sec);
        return I18n.get("numen.brain.since_min", sec / 60);
    }

    private boolean brainToggleClick(int mx, int my) {
        int x = secX(), w = secW(), fy = secY0();
        boolean onToggle = overToggle(mx, my, x + w - TOG_W, fy + BR_TOGGLE - 1);
        boolean onRow = mx >= x && mx < x + w && my >= fy + BR_TOGGLE - 2 && my < fy + BR_TOGGLE + 11;
        if (!onToggle && !onRow) return false;
        McpMode mcp = McpMode.instance();
        mcp.setEnabled(!mcp.enabled());
        host.rebuild();   // 令牌行的复制按钮随配置在场与否增减
        return true;
    }

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
                lib.assign(host.uuid(), created.id());
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
            PersonaLibrary.Persona old = lib.get(personaEditId);
            String oldName = old != null ? old.name() : null;
            // 改名会换文件名(id 随之更换),传播用落盘后的新条目。
            PersonaLibrary.Persona saved = lib.update(personaEditId, name, text);
            if (saved != null) {
                for (UUID cu : AgentLoopRegistry.loadedEntityUuids()) {
                    EntityAgentLoop l = AgentLoopRegistry.get(cu).orElse(null);
                    if (l == null) continue;
                    boolean uses = personaEditId.equals(l.personaId())
                            || (l.personaId() == null && oldName != null && oldName.equals(l.personaName()));
                    if (uses) l.setPersona(saved.id(), saved.text(), saved.name());
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

    private void buildSkillsWidgets() {
        // "open skills folder" affordance, top-right of the section.
        host.add(new SimpleButton(left() + panelW() - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable("numen.skill.open_dir"), b -> openSkillsFolder()));
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
        renderSettingsNav(g, mouseX, mouseY);
        switch (section) {
            case MCP -> renderMcpSection(g, mouseX, mouseY);
            case SKILLS -> renderSkillsSection(g, mouseX, mouseY);
            case PERSONA -> renderPersonaSection(g, mouseX, mouseY);
            case PROVIDER -> renderProviderSection(g, mouseX, mouseY);
            case VOICE -> renderVoiceSection(g, mouseX, mouseY);
            case SKIN -> renderSkinSection(g, mouseX, mouseY);
            case BRAIN -> renderBrainSection(g, mouseX, mouseY);
            case STT -> sttPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font()),
                    HostThemeColors.current(), mouseX, mouseY, net.minecraft.Util.getMillis());
            case THEME -> renderThemeSection(g, mouseX, mouseY);
        }
    }

    /** 主题选择:五套配色一行一个(三色小样 + 名字),点击即切换并写入 ui.json。 */
    private void renderThemeSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX();
        txt(g, Component.translatable("numen.settings.theme.title"), x, secY0() - 2, TXT);
        int listY0 = secY0() + 14;
        for (int i = 0; i < UiTheme.ALL.size(); i++) {
            UiTheme t = UiTheme.ALL.get(i);
            int ry = listY0 + i * LIST_ROW;
            boolean cur = t == UiTheme.current();
            hoverRow(g, mouseX, mouseY, x, secW(), ry);
            // 圆角描边环:先画整块圆角底当"框",三色小样叠在内缩区上。
            com.dwinovo.numen.client.ui.RoundRect.fill(g, x - 2, ry - 1, x + 32, ry + 15, 4,
                    cur ? ACCENT : BORDER);
            g.fill(x, ry + 1, x + 10, ry + 13, t.ground());
            g.fill(x + 10, ry + 1, x + 20, ry + 13, t.band());
            g.fill(x + 20, ry + 1, x + 30, ry + 13, t.cta());
            txt(g, Component.literal(t.label()), x + 38, ry + 3, cur ? TXT : TXT_MUTED);
            if (cur) {
                txt(g, Component.literal("✔"), x + 38 + font().width(t.label()) + 6, ry + 3, OK);
            }
        }
        // 快捷对话提醒开关行(默认开:准星指着同伴时浮「按 [键] 对话」)
        int hy = listY0 + UiTheme.ALL.size() * LIST_ROW + 8;
        hoverRow(g, mouseX, mouseY, x, secW(), hy);
        boolean hintOn = UiTheme.talkHintEnabled();
        txt(g, Component.literal((hintOn ? "[开] " : "[关] ") + "快捷对话提醒(准星指着同伴时提示按键)"),
                x, hy + 3, hintOn ? TXT : TXT_MUTED);
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

    /** The config-hub left sub-nav + the divider. */
    private void renderSettingsNav(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = {
                I18n.get(ModLanguageData.Keys.PROVIDER_TITLE),
                I18n.get("numen.settings.nav.mcp"), I18n.get("numen.settings.nav.brain"),
                I18n.get("numen.settings.nav.skills"), I18n.get("numen.settings.nav.persona"),
                I18n.get(ModLanguageData.Keys.VOICE_TITLE),
                I18n.get(ModLanguageData.Keys.SKIN_TITLE),
                I18n.get(ModLanguageData.Keys.STT_NAV),
                I18n.get("numen.settings.nav.theme")};
        int navX = left() + PAD;
        int y = secY0();
        int chip = UiTheme.current().chipFill();
        int chipFaint = (chip & 0xFFFFFF) | (((chip >>> 24) / 2) << 24);   // 悬停胶囊:半透明再减半
        for (int i = 0; i < labels.length; i++) {
            boolean active = section == Section.values()[i];
            int ry = y + i * NAV_SP;
            // 命中区与 navClick 完全一致(ry-3 .. ry+NAV_SP-5)。
            boolean hovered = mouseX >= navX && mouseX < navX + NAV_W
                    && mouseY >= ry - 3 && mouseY < ry + NAV_SP - 5;
            if (active || hovered) {                              // 选中/悬停胶囊底
                com.dwinovo.numen.client.ui.RoundRect.fill(g, navX - 4, ry - 3,
                        navX + NAV_W - 2, ry + NAV_SP - 5, 4, active ? chip : chipFaint);
            }
            if (active) {
                g.fill(navX - 2, ry - 3, navX - 1, ry + NAV_SP - 5, ACCENT);   // gold left bar
                txt(g, Component.literal(labels[i]), navX + 3, ry, TXT);
            } else {
                txt(g, Component.literal(labels[i]), navX + 3, ry, TXT_MUTED);
            }
        }
        int dx = left() + PAD + NAV_W + 3;
        g.fill(dx, secY0() - 2, dx + 1, secBottom(), BORDER);   // vertical divider
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

    private boolean overDelete(int mx, int my, int delX, int ry) {
        return mx >= delX - 2 && mx < delX + 9 && my >= ry + 2 && my < ry + LIST_ROW - 2;
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

    private void renderSkillsSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        txt(g, Component.translatable("numen.skill.title"), x, secY0() - 2, TXT);
        var skills = new ArrayList<>(com.dwinovo.numen.agent.skill.SkillRegistry.instance().all());
        if (skills.isEmpty()) {
            txt(g, Component.translatable("numen.skill.empty"), x, secY0() + 16, TXT_FAINT);
            return;
        }
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp(settingsScroll, 0, Math.max(0, skills.size() - visible));
        for (int i = settingsScroll; i < skills.size(); i++) {
            int row = i - settingsScroll;
            int ry = listY0 + row * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var s = skills.get(i);
            boolean on = !com.dwinovo.numen.agent.skill.SkillRegistry.instance().isDisabled(s.name());
            hoverRow(g, mouseX, mouseY, x, w, ry);
            txt(g, Component.literal(s.name()), x, ry + 1, on ? TXT : TXT_FAINT);
            String desc = s.description() == null ? I18n.get("numen.skill.no_desc") : s.description();
            txt(g, Component.literal(clip(desc, w - 26)), x, ry + 13, TXT_MUTED);
            drawToggle(g, x + w - 20, ry + 5, on);
            if (overRow(mouseX, mouseY, x, w, ry) && !overToggle(mouseX, mouseY, x + w - 20, ry + 5)
                    && s.description() != null) {
                host.tip(List.of(Component.literal(s.name()), Nb.colored(s.description(), TXT_MUTED)),
                        mouseX, mouseY);
            }
        }
    }

    // ---- shared toggle switch (no vanilla widget for this) ----

    private void drawToggle(GuiGraphics g, int x, int y, boolean on) {
        // 轨道恒中性,状态全由滑块表达:开 = 黄色滑块在右,关 = 暗滑块在左。
        g.fill(x, y, x + TOG_W, y + TOG_H, FIELD);
        Nb.border(g, x, y, TOG_W, TOG_H, 1, BORDER);
        int knobX = on ? x + TOG_W - 8 : x + 1;
        g.fill(knobX, y + 1, knobX + 7, y + TOG_H - 1, on ? CTA : TXT_FAINT);
    }

    private boolean overToggle(int mx, int my, int x, int y) {
        return mx >= x && mx < x + TOG_W && my >= y && my < y + TOG_H;
    }

    private boolean overRow(int mx, int my, int x, int w, int ry) {
        return mx >= x && mx < x + w && my >= ry && my < ry + LIST_ROW;
    }

    /** 列表行悬停底:一层 chipFill 圆角暗洗,让"行可点"可感知(表单模态的背景列表
     *  以 (-10000,-10000) 渲染,自然不触发)。画在行内容之前。 */
    private void hoverRow(GuiGraphics g, int mouseX, int mouseY, int x, int w, int ry) {
        if (overRow(mouseX, mouseY, x, w, ry)) {
            com.dwinovo.numen.client.ui.RoundRect.fill(g, x - 3, ry - 1, x + w + 1,
                    ry + LIST_ROW - 3, 4, UiTheme.current().chipFill());
        }
    }

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

    private boolean skillToggleClick(int mx, int my) {
        int x = secX(), w = secW();
        var reg = com.dwinovo.numen.agent.skill.SkillRegistry.instance();
        var skills = new ArrayList<>(reg.all());
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        int scroll = Math.clamp(settingsScroll, 0, Math.max(0, skills.size() - visible));
        for (int i = scroll; i < skills.size(); i++) {
            int ry = listY0 + (i - scroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            if (overToggle(mx, my, x + w - 20, ry + 5)) {
                String n = skills.get(i).name();
                reg.setEnabled(n, reg.isDisabled(n));   // flip
                return true;
            }
        }
        return false;
    }

    // ---- input (called from the screen's mouseClicked / mouseScrolled) ----

    /** The Settings tab's whole click chain — dropdown routing first (open lists overlay
     *  the fields), then the sub-nav / theme rows / per-row toggles. Returns true = consumed. */
    public boolean mouseClicked(double mouseX, double mouseY) {
        loadPalette();
        // 模态确认卡在场:面板内容只是背景,任何命中(子导航/主题行/列表行)都不放行
        // ——卡上的两颗按钮走 Screen 的 widget 通道,不经过这里。
        if (modalActive()) return false;
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
        return settingsClickedAt(mouseX, mouseY);
    }

    /** Sub-nav column, the theme rows, then per-row toggles / edits in the section lists. */
    private boolean settingsClickedAt(double mxd, double myd) {
        int mx = (int) mxd, my = (int) myd;
        // 表单模态:子导航/主题行/列表全在暗幕之下,不放行(表单自己的点击
        // 已在 mouseClicked 的面板路由里处理)。
        if (formActive()) return false;
        int navX = left() + PAD, y = secY0();
        if (mx >= navX && mx < navX + NAV_W) {
            for (int i = 0; i < Section.values().length; i++) {
                int ry = y + i * NAV_SP;
                if (my >= ry - 3 && my < ry + NAV_SP - 5) {
                    selectSection(Section.values()[i]);
                    return true;
                }
            }
        }
        if (section == Section.THEME && mx >= secX()) {
            int listY0 = secY0() + 14;
            for (int i = 0; i < UiTheme.ALL.size(); i++) {
                int ry = listY0 + i * LIST_ROW;
                if (my >= ry && my < ry + LIST_ROW) {
                    UiTheme.select(UiTheme.ALL.get(i).id());
                    host.repaintPalette();          // 屏幕的调色板常量重读新主题
                    return true;
                }
            }
            int hy = listY0 + UiTheme.ALL.size() * LIST_ROW + 8;
            if (my >= hy && my < hy + LIST_ROW) {
                UiTheme.setTalkHint(!UiTheme.talkHintEnabled());
                return true;
            }
        }
        if (section == Section.BRAIN) return brainToggleClick(mx, my);
        if (section == Section.SKILLS) return skillToggleClick(mx, my);
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

    /** Wheel pass 2 (after the rail/chat checks): scroll the section list. */
    public boolean mouseScrolledList(double sy) {
        if (formActive()) return false;   // 列表在表单模态的暗幕之下,不滚
        int count = switch (section) {
            case SKILLS -> com.dwinovo.numen.agent.skill.SkillRegistry.instance().size();
            default -> 0;
        };
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp((long) (settingsScroll - sy), 0, Math.max(0, count - visible));
        return true;
    }

    /** Post-widget overlay pass: field placeholders (shadowless), the voice form's row labels,
     *  and the form dropdowns' open lists (drawn last so they sit above the fields). */
    /** 键盘/字符输入:目前只有模型配置表单的 NumenUI 输入框需要。 */
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
