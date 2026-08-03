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
    private static final String CUSTOM_MODEL = "__custom__";
    /** 试听用的固定测试句(按当前表单参数就地合成)。 */
    private static final String VOICE_TEST_SENTENCE = "你好,我是你的同伴,这是我的声音。";

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

    // ---- 模型配置列表:NumenUI ProfileListPanel(ListView 行 + ConfirmDialog 删除闸) ----
    private ProfileListPanel profileList;

    private ProfileListPanel profileList() {
        if (profileList == null) {
            profileList = new ProfileListPanel(
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
    private String voiceDeletePending;
    /** Form backend type (openai / gpt_sovits / minimax / fish_audio),经下拉选择,切换即换字段行。 */
    private String wVoiceBackend = com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_OPENAI;
    private Dropdown voiceBackendDropdown;
    /** 声线表单的垂直滚动偏移(px)——MiniMax 八行放不下,滚轮驱动。 */
    private int voiceFormScroll;
    private String wVoiceName = "", wVoiceUrl = "", wVoiceKey = "", wVoiceGroup = "", wVoiceModel = "",
            wVoiceVoice = "", wVoiceRef = "", wVoicePrompt = "", wVoiceLang = "", wVoiceVolume = "5";
    private EditBox voiceNameInput, voiceUrlInput, voiceKeyInput, voiceGroupInput, voiceModelInput,
            voiceVoiceInput, voiceRefInput, voicePromptInput, voiceLangInput, voiceVolumeInput;

    /** 试听/保存的状态行(表单底部;fail = 红色)。 */
    private String voiceMsg;
    private boolean voiceMsgFail;
    private long voiceMsgUntil;
    /** 试听代际:再次点击/离开表单让在途合成回调作废;preview 引用用于停掉上一次试听。 */
    private int voiceTestGen;
    private com.dwinovo.numen.client.voice.VoicePreviewSound voicePreview;

    // 皮肤库(列表+表单,照声线库制式)。签名发生在保存时(MineSkin 代签),召唤只读现成结果。
    private boolean addingSkin;
    private String skinEditId;
    private String skinDeletePending;
    private String wSkinName = "";
    private String wSkinVariant = com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_CLASSIC;
    private EditBox skinNameInput;
    private Dropdown skinVariantDropdown;
    /** 拖进窗口的皮肤 png 原始字节(表单会话内;保存成功后随条目落盘)。 */
    private byte[] skinDropped;
    private int skinDroppedW, skinDroppedH;
    /** 保存(签名)进行中——防重复点击;skinFormGen 作废在途回调。 */
    private boolean skinSigning;
    private int skinFormGen;
    private String skinMsg;
    private boolean skinMsgFail;
    private long skinMsgUntil;

    // ---- proxy section state (IP + port) ----

    // Persona library form state (mirrors the MCP add/edit/delete flow).
    private boolean addingPersona;
    private String personaEditId;          // non-null = editing this persona; null = creating
    private String personaDeletePending;   // id awaiting delete confirm
    private String wPersonaName = "", wPersonaText = "";
    private EditBox personaNameInput;
    private net.minecraft.client.gui.components.MultiLineEditBox personaTextArea;

    // MCP "add server" form
    private boolean addingMcp;
    private boolean mcpStdio;                 // form type: false = http, true = stdio
    private String wMcpName = "", wMcpTarget = "", wMcpHeader = "";
    private EditBox mcpNameInput, mcpTargetInput, mcpHeaderInput;
    private String mcpDeletePending;          // non-null = showing the delete-confirm bar for this server
    private String mcpEditOriginal;           // non-null = the add-form is EDITING this server (replace on save)

    // STT section
    private EditBox sttKeyInput, sttBaseUrlInput, sttModelInput;
    private Dropdown sttProviderDropdown, sttModelDropdown, sttMicDropdown;
    private boolean sttCustomModel;
    private String wSttProvider, wSttKey, wSttBaseUrl, wSttModel, wSttMic;
    private long savedFlashUntil;

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
        addingVoice = false; voiceEditId = null; voiceTestGen++;
        addingSkin = false; skinEditId = null; skinFormGen++;
        addingPersona = false; personaEditId = null;
        addingMcp = false; mcpEditOriginal = null;
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
        sttKeyInput = sttBaseUrlInput = sttModelInput = null;
        sttProviderDropdown = sttModelDropdown = sttMicDropdown = null;
        mcpNameInput = mcpTargetInput = mcpHeaderInput = null;
        personaNameInput = null;
        personaTextArea = null;
        voiceNameInput = voiceUrlInput = voiceKeyInput = voiceGroupInput = voiceModelInput = null;
        voiceVoiceInput = voiceRefInput = voicePromptInput = voiceLangInput = voiceVolumeInput = null;
        voiceBackendDropdown = null;
        skinNameInput = null;
        skinVariantDropdown = null;
    }

    private void selectSection(Section s) {
        if (s == section) return;
        section = s;
        settingsScroll = 0;
        wSttProvider = null;   // re-seed STT form from saved config on entry
        if (s == Section.PERSONA) {
            // 人设是目录里的 .md 文件:进页先重扫,外部编辑器的修改即时可见。
            PersonaLibrary.instance().reload();
        }
        addingMcp = false;
        mcpDeletePending = null;
        mcpEditOriginal = null;
        addingPersona = false;
        personaEditId = null;
        personaDeletePending = null;
        addingProvider = false;
        providerEditId = null;
        addingVoice = false;
        voiceEditId = null;
        voiceDeletePending = null;
        voiceTestGen++;   // 离开语音表单:在途试听回调作废
        addingSkin = false;
        skinEditId = null;
        skinDeletePending = null;
        skinFormGen++;    // 离开皮肤表单:在途 MineSkin 签名回调作废
        host.rebuild();
    }

    // ---- delete-confirm modal (shared by the five sections that can delete) ----

    /** 当前待确认删除的标题(null = 无模态)。build 与 render 共用同一份文案,
     *  ConfirmModal 的卡片几何(高度随换行数)才不会漂。 */
    private Component activeModalTitle() {
        if (voiceDeletePending != null) {
            var e = com.dwinovo.numen.client.voice.VoiceLibrary.instance().get(voiceDeletePending);
            return Component.translatable(ModLanguageData.Keys.VOICE_DELETE_CONFIRM, e != null ? e.name() : "");
        }
        if (skinDeletePending != null) {
            var e = com.dwinovo.numen.client.skin.SkinLibrary.instance().get(skinDeletePending);
            return Component.translatable(ModLanguageData.Keys.SKIN_DELETE_CONFIRM, e != null ? e.name() : "");
        }
        if (personaDeletePending != null) {
            PersonaLibrary.Persona p = PersonaLibrary.instance().get(personaDeletePending);
            return Component.translatable("numen.persona.delete_confirm", p != null ? p.name() : "");
        }
        if (mcpDeletePending != null) {
            return Component.translatable("numen.mcp.delete_confirm", mcpDeletePending);
        }
        return null;
    }

    /** A delete-confirm modal is up — the screen blocks rail/tab/scroll interaction meanwhile. */
    public boolean modalActive() {
        return activeModalTitle() != null;
    }

    /** Esc while a confirm modal is up: dismiss it (instead of closing the whole screen). */
    public boolean cancelModal() {
        if (!modalActive()) return false;
        clearDeletePending();
        host.rebuild();
        return true;
    }

    private void clearDeletePending() {
        voiceDeletePending = null;
        skinDeletePending = null;
        personaDeletePending = null;
        mcpDeletePending = null;
    }

    /** The confirm card's two buttons (widget pass draws them above the scrim):
     *  Cancel left, destructive Delete right — positioned by the SAME box the render uses. */
    private void buildConfirmButtons(Runnable onDelete) {
        var box = com.dwinovo.numen.client.ui.ConfirmModal.box(font(), host.railX(),
                left() + panelW(), top(), panelH(), activeModalTitle(), null);
        int bw = com.dwinovo.numen.client.ui.ConfirmModal.BTN_W;
        int gap = com.dwinovo.numen.client.ui.ConfirmModal.BTN_GAP;
        int bx = box.x() + (box.w() - (bw * 2 + gap)) / 2;
        host.add(new SimpleButton(bx, box.buttonY(), bw, com.dwinovo.numen.client.ui.ConfirmModal.BTN_H,
                Component.translatable("numen.gui.settings.cancel"),
                b -> { clearDeletePending(); host.rebuild(); }));
        host.add(new SimpleButton(bx + bw + gap, box.buttonY(), bw,
                com.dwinovo.numen.client.ui.ConfirmModal.BTN_H,
                Component.translatable("numen.dismiss.delete"), b -> onDelete.run()).danger());
    }

    /** Dispatch widget building by the active section (skill/MCP lists render manually). */
    public void buildWidgets() {
        loadPalette();
        switch (section) {
            case SKILLS -> buildSkillsWidgets();
            case MCP -> {
                if (mcpDeletePending != null) buildMcpDeleteConfirm();
                else if (addingMcp) buildMcpForm();
                else buildMcpListWidgets();
            }
            case PERSONA -> {
                if (personaDeletePending != null) buildPersonaDeleteConfirm();
                else if (addingPersona) buildPersonaForm();
                else buildPersonaListWidgets();
            }
            case PROVIDER -> {
                // 列表面板始终在场(表单模态时作背景);删除确认是面板自己的浮层。
                profileList().build(secX(), secY0() - 2, secW(), secBottom() - secY0() + 2,
                        left(), top(), panelW(), panelH());
                if (addingProvider) buildProviderFormNew();
            }
            case VOICE -> {
                if (voiceDeletePending != null) buildVoiceDeleteConfirm();
                else if (addingVoice) buildVoiceForm();
                else buildVoiceListWidgets();
            }
            case SKIN -> {
                if (skinDeletePending != null) buildSkinDeleteConfirm();
                else if (addingSkin) buildSkinForm();
                else buildSkinListWidgets();
            }
            case BRAIN -> buildBrainWidgets();
            case STT -> buildSttWidgets();
            case THEME -> { /* no widgets — plain click rows */ }
        }
    }

    // ---- Proxy section: the global network proxy, its own tab (IP + port) ----

    // ---- Voice input (STT) section: provider dropdown → prefilled base/model, mic dropdown ----

    private void buildSttWidgets() {
        int x = secX(), w = secW();
        int fy = secY0();
        INumenConfig cfg = Services.CONFIG;
        if (wSttProvider == null) {   // seed working fields from config on section entry
            wSttProvider = cfg.getSttProvider();
            wSttKey = cfg.getSttApiKey();
            wSttBaseUrl = cfg.getSttBaseUrl();
            wSttModel = cfg.getSttModel();
            wSttMic = cfg.getSttMicrophone();
            com.dwinovo.numen.client.stt.SttProviders.Option seed =
                    com.dwinovo.numen.client.stt.SttProviders.byId(wSttProvider);
            sttCustomModel = seed.models().isEmpty() || !seed.models().contains(wSttModel);
        }
        com.dwinovo.numen.client.stt.SttProviders.Option opt =
                com.dwinovo.numen.client.stt.SttProviders.byId(wSttProvider);
        sttProviderDropdown = new Dropdown(sttProviderItems(), opt.id());
        sttProviderDropdown.setBounds(x, fy + 25, w, 18);
        sttProviderDropdown.setDropBottom(top() + panelH() - 2);
        sttKeyInput = field(x, fy + 25 + SET_SP, w, 256, wSttKey);
        // Model row: provider's known models as a dropdown (+ 自定义 → free text).
        int modelY = fy + 25 + 2 * SET_SP;
        if (sttCustomModel || opt.models().isEmpty()) {
            sttModelDropdown = null;
            boolean hasModels = !opt.models().isEmpty();
            sttModelInput = field(x, modelY, hasModels ? w - 20 : w, 128, wSttModel);
            if (hasModels) {
                host.add(new SimpleButton(x + w - 18, modelY, 18, 18, Component.literal("▾"),
                        b -> { preserveSttForm(); sttCustomModel = false; host.rebuild(); }));
            }
        } else {
            sttModelInput = null;
            String sel = opt.models().contains(wSttModel) ? wSttModel : opt.models().get(0);
            sttModelDropdown = new Dropdown(sttModelItems(opt), sel);
            sttModelDropdown.setBounds(x, modelY, w, 18);
            sttModelDropdown.setDropBottom(top() + panelH() - 2);
        }
        sttBaseUrlInput = field(x, fy + 25 + 3 * SET_SP, w, 256, wSttBaseUrl);
        sttMicDropdown = new Dropdown(sttMicItems(), wSttMic == null ? "" : wSttMic);
        sttMicDropdown.setBounds(x, fy + 25 + 4 * SET_SP, w, 18);
        sttMicDropdown.setDropBottom(top() + panelH() - 2);
        host.add(new SimpleButton(left() + panelW() - PAD - 64, top() + panelH() - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> {
                    String sttModel = sttModelDropdown != null
                            && !CUSTOM_MODEL.equals(sttModelDropdown.selectedId())
                            ? sttModelDropdown.selectedId()
                            : (sttModelInput != null ? sttModelInput.getValue().trim() : wSttModel);
                    cfg.setSttProvider(sttProviderDropdown.selectedId());
                    cfg.setSttApiKey(sttKeyInput.getValue().trim());
                    cfg.setSttModel(sttModel);
                    cfg.setSttBaseUrl(sttBaseUrlInput.getValue().trim());
                    cfg.setSttMicrophone(sttMicDropdown.selectedId());
                    cfg.save();
                    savedFlashUntil = System.currentTimeMillis() + 1500;
                }).primary());
    }

    private List<Dropdown.Item> sttProviderItems() {
        List<Dropdown.Item> out = new ArrayList<>();
        for (com.dwinovo.numen.client.stt.SttProviders.Option o
                : com.dwinovo.numen.client.stt.SttProviders.all()) {
            out.add(new Dropdown.Item(o.id(), o.displayName()));
        }
        return out;
    }

    private List<Dropdown.Item> sttMicItems() {
        List<Dropdown.Item> out = new ArrayList<>();
        out.add(new Dropdown.Item("", I18n.get(ModLanguageData.Keys.STT_MIC_DEFAULT)));
        for (String name : com.dwinovo.numen.client.stt.MicrophoneManager.deviceNames()) {
            out.add(new Dropdown.Item(name, name));
        }
        return out;
    }

    private List<Dropdown.Item> sttModelItems(com.dwinovo.numen.client.stt.SttProviders.Option o) {
        List<Dropdown.Item> items = new ArrayList<>();
        for (String m : o.models()) {
            items.add(new Dropdown.Item(m, m));
        }
        items.add(new Dropdown.Item(CUSTOM_MODEL, I18n.get("numen.settings.custom_model")));
        return items;
    }

    /** Keep typed key/model/baseUrl across a rebuild triggered by a dropdown. */
    private void preserveSttForm() {
        if (sttKeyInput != null) wSttKey = sttKeyInput.getValue();
        if (sttBaseUrlInput != null) wSttBaseUrl = sttBaseUrlInput.getValue();
        if (sttModelInput != null) {
            wSttModel = sttModelInput.getValue();
        } else if (sttModelDropdown != null && !CUSTOM_MODEL.equals(sttModelDropdown.selectedId())) {
            wSttModel = sttModelDropdown.selectedId();
        }
    }

    /** Provider changed → adapt model + base URL to the pick's preset defaults (still editable). */
    private void adaptToSttProvider(String id) {
        wSttProvider = id;
        com.dwinovo.numen.client.stt.SttProviders.Option o =
                com.dwinovo.numen.client.stt.SttProviders.byId(id);
        sttCustomModel = o.models().isEmpty();   // custom provider → free-text model
        wSttModel = o.defaultModel();
        wSttBaseUrl = o.defaultBaseUrl();
    }

    private void renderSttSection(GuiGraphics g) {
        int x = secX();
        int fy = secY0();
        txt(g, Component.translatable(ModLanguageData.Keys.STT_TITLE), x, fy - 2, TXT);
        txt(g, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_PROVIDER), x, fy + 14, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_API_KEY), x, fy + 14 + SET_SP, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_MODEL), x, fy + 14 + 2 * SET_SP, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_BASE_URL), x, fy + 14 + 3 * SET_SP, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.STT_MICROPHONE), x, fy + 14 + 4 * SET_SP, TXT_MUTED);
    }

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

    private void buildVoiceListWidgets() {
        host.add(new SimpleButton(left() + panelW() - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable(ModLanguageData.Keys.VOICE_ADD), b -> {
                    addingVoice = true; voiceEditId = null;
                    resetVoiceForm();
                    host.rebuild();
                }));
        // 绑定不再是单独一行下拉:声线在召唤时选定、新建时自动绑定,列表行内的
        // ●/○ 标记负责事后换绑(点 ○ 换用,点 ● 解绑静音)。
    }

    /**
     * 声线表单:模型配置同款制式——每个输入框上方一行标题({@code SET_SP} 行距),
     * 名称 → 提供商下拉 → URL(选型预填)→ 各后端专属字段 → 音量 + 试听。
     * 行数随选型变化(MiniMax 最多 8 行),放不下的部分由 {@link #voiceFormScroll}
     * 滚动(滚轮),出视口的行连标题带控件一起隐藏;保存/关闭钉在面板右下不随滚。
     */
    private void buildVoiceForm() {
        int x = fx(), w = fw();
        voiceFormScroll = Math.clamp(voiceFormScroll, 0, maxVoiceFormScroll());
        voiceNameInput = vclip(field(x, voiceVy(0), w, 48, wVoiceName), 0);
        // 后端下拉——召唤页人设/模型下拉同款控件;点击路由在 mouseClicked,
        // 展开列表在 render 末尾最后画(压在字段上面)。
        voiceBackendDropdown = new Dropdown(List.of(
                new Dropdown.Item(com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_OPENAI,
                        I18n.get(ModLanguageData.Keys.VOICE_BACKEND_OPENAI)),
                new Dropdown.Item(com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_SOVITS,
                        I18n.get(ModLanguageData.Keys.VOICE_BACKEND_SOVITS)),
                new Dropdown.Item(com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX,
                        I18n.get(ModLanguageData.Keys.VOICE_BACKEND_MINIMAX)),
                new Dropdown.Item(com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_FISH,
                        I18n.get(ModLanguageData.Keys.VOICE_BACKEND_FISH))),
                wVoiceBackend);
        voiceBackendDropdown.setBounds(x, voiceVy(1), w, 18);
        voiceBackendDropdown.setDropBottom(top() + panelH() - 2);
        voiceUrlInput = vclip(field(x, voiceVy(2), w, 256, wVoiceUrl), 2);
        int row = 3;
        switch (wVoiceBackend) {
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_SOVITS -> {
                voiceRefInput = vclip(field(x, voiceVy(row), w, 256, wVoiceRef), row++);
                voicePromptInput = vclip(field(x, voiceVy(row), w, 512, wVoicePrompt), row++);
                voiceLangInput = vclip(field(x, voiceVy(row), w, 16, wVoiceLang), row++);
            }
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX -> {
                voiceKeyInput = vclip(field(x, voiceVy(row), w, 1024, wVoiceKey), row++);
                voiceGroupInput = vclip(field(x, voiceVy(row), w, 64, wVoiceGroup), row++);
                voiceModelInput = vclip(field(x, voiceVy(row), w, 64, wVoiceModel), row++);
                voiceVoiceInput = vclip(field(x, voiceVy(row), w, 128, wVoiceVoice), row++);
            }
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_FISH -> {
                voiceKeyInput = vclip(field(x, voiceVy(row), w, 256, wVoiceKey), row++);
                voiceVoiceInput = vclip(field(x, voiceVy(row), w, 128, wVoiceVoice), row++);
                voiceModelInput = vclip(field(x, voiceVy(row), w, 64, wVoiceModel), row++);
            }
            default -> {
                voiceKeyInput = vclip(field(x, voiceVy(row), w, 256, wVoiceKey), row++);
                voiceModelInput = vclip(field(x, voiceVy(row), w, 128, wVoiceModel), row++);
                voiceVoiceInput = vclip(field(x, voiceVy(row), w, 128, wVoiceVoice), row++);
            }
        }
        voiceVolumeInput = vclip(field(x, voiceVy(row), 70, 8, wVoiceVolume), row);
        SimpleButton test = new SimpleButton(x + w - 64, voiceVy(row), 64, 18,
                Component.translatable(ModLanguageData.Keys.VOICE_TEST), b -> onVoiceTest());
        test.visible = voiceRowVisible(row);
        test.active = test.visible;
        host.add(test);
        host.add(new SimpleButton(fRight() - 64, fBottom() - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveVoice()).primary());
        host.add(new SimpleButton(fRight() - 64 - 22, fBottom() - 18, 18, 18,
                Component.literal("✕"), b -> {
                    addingVoice = false; voiceEditId = null; voiceTestGen++;
                    host.rebuild();
                }));
        if (voiceNameInput.visible) {
            host.focus(voiceNameInput);
        }
    }

    /** 表单第 {@code row} 行输入框的 y(标题画在其上方 11px);随滚动偏移。 */
    private int voiceVy(int row) {
        return fy0() + 11 + row * SET_SP - voiceFormScroll;
    }

    /** 第 {@code row} 行(标题+输入框)完整落在视口内? */
    private boolean voiceRowVisible(int row) {
        int y = voiceVy(row);
        return y - 11 >= fy0() - 2 && y + 18 <= voiceFormBottom();
    }

    /** 表单视口底:保存行与状态行的上沿(卡内坐标)。 */
    private int voiceFormBottom() {
        return fBottom() - 20;
    }

    /** 当前选型的总行数:名称/提供商/URL 三行 + 各后端专属行 + 音量行。 */
    private int voiceFormRowCount() {
        return com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX.equals(wVoiceBackend) ? 8 : 7;
    }

    private int maxVoiceFormScroll() {
        int content = 11 + (voiceFormRowCount() - 1) * SET_SP + 18 + 2;
        return Math.max(0, content - (voiceFormBottom() - fy0()));
    }

    /** 出视口的行隐藏(不可见的 EditBox 既不渲染也不接输入)。 */
    private EditBox vclip(EditBox f, int row) {
        boolean vis = voiceRowVisible(row);
        f.visible = vis;
        f.active = vis;
        return f;
    }

    private void buildVoiceDeleteConfirm() {
        buildConfirmButtons(() -> {
            com.dwinovo.numen.client.voice.VoiceLibrary.instance().remove(voiceDeletePending);
            voiceDeletePending = null;
            host.rebuild();
        });
    }

    /** Keep typed values across a rebuild (backend switch / edit entry). */
    private void preserveVoiceForm() {
        if (voiceNameInput != null) wVoiceName = voiceNameInput.getValue();
        if (voiceUrlInput != null) wVoiceUrl = voiceUrlInput.getValue();
        if (voiceKeyInput != null) wVoiceKey = voiceKeyInput.getValue();
        if (voiceGroupInput != null) wVoiceGroup = voiceGroupInput.getValue();
        if (voiceModelInput != null) wVoiceModel = voiceModelInput.getValue();
        if (voiceVoiceInput != null) wVoiceVoice = voiceVoiceInput.getValue();
        if (voiceRefInput != null) wVoiceRef = voiceRefInput.getValue();
        if (voicePromptInput != null) wVoicePrompt = voicePromptInput.getValue();
        if (voiceLangInput != null) wVoiceLang = voiceLangInput.getValue();
        if (voiceVolumeInput != null) wVoiceVolume = voiceVolumeInput.getValue();
    }

    private void resetVoiceForm() {
        voiceFormScroll = 0;
        wVoiceBackend = com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_OPENAI;
        wVoiceName = ""; wVoiceKey = ""; wVoiceGroup = ""; wVoiceModel = "";
        wVoiceVoice = ""; wVoiceRef = ""; wVoicePrompt = ""; wVoiceLang = "";
        wVoiceUrl = defaultVoiceUrl(wVoiceBackend);   // 官方端点预填,用户只补 key/音色
        wVoiceVolume = "5";
        voiceMsg = null;
    }

    /** 各后端的官方端点,选型即预填(后端 composeUrl 对空 URL 也回落到同一个值,
     *  所以删空保存照样能用)。 */
    private static String defaultVoiceUrl(String backend) {
        return switch (backend) {
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_SOVITS ->
                    com.dwinovo.numen.client.voice.GptSovitsTts.DEFAULT_BASE;
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX ->
                    com.dwinovo.numen.client.voice.MiniMaxTts.DEFAULT_BASE;
            case com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_FISH ->
                    com.dwinovo.numen.client.voice.FishAudioTts.DEFAULT_BASE;
            default -> com.dwinovo.numen.client.voice.OpenAiCompatibleTts.DEFAULT_BASE;
        };
    }

    /** 当前表单(w 值)拼成一个 Entry;id 由调用方给(编辑=原 id,试听=临时)。 */
    private com.dwinovo.numen.client.voice.VoiceLibrary.Entry formVoiceEntry(String id, String name) {
        float vol;
        try { vol = Float.parseFloat(wVoiceVolume.trim()); }
        catch (NumberFormatException ex) { vol = 5.0f; }
        // UI 档位 1~10 → 存储增益 0.2~2.0(5 档 = 原始响度 1.0,老数据无需迁移)。
        vol = Math.clamp(vol, 1.0f, 10.0f) / 5.0f;
        return new com.dwinovo.numen.client.voice.VoiceLibrary.Entry(id, name,
                wVoiceBackend,
                wVoiceUrl.trim(), wVoiceKey.trim(), wVoiceGroup.trim(),
                wVoiceModel.trim(), wVoiceVoice.trim(),
                wVoiceRef.trim(), wVoicePrompt.trim(), wVoiceLang.trim(),
                com.dwinovo.numen.client.voice.VoiceLibrary.clampVolume(vol));
    }

    private void onSaveVoice() {
        preserveVoiceForm();
        String name = wVoiceName.trim();
        if (name.isEmpty()) {
            voiceNote(I18n.get(ModLanguageData.Keys.VOICE_WARN_NAME), true);
            return;
        }
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
        if (voiceEditId != null) {
            lib.update(formVoiceEntry(voiceEditId, name));
        } else {
            var e = formVoiceEntry("", name);
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
        voiceTestGen++;
        resetVoiceForm();
        host.rebuild();
    }

    /**
     * 试听:用当前表单参数合成固定测试句,就地 2D 播放(不挂实体,
     * {@link com.dwinovo.numen.client.voice.VoicePreviewSound} 走与 3D 语音同一条
     * mixin 取数路径)。失败把错误人话写到表单状态行(红色),与 summon 页
     * warnText 同样的"错误在动作处出现"做法。
     */
    private void onVoiceTest() {
        preserveVoiceForm();
        var probe = formVoiceEntry("__preview__", wVoiceName.isBlank() ? "preview" : wVoiceName.trim());
        voiceNote(I18n.get(ModLanguageData.Keys.VOICE_TEST_RUNNING), false);
        final int gen = ++voiceTestGen;
        final float vol = probe.volume();
        // 同步防线:后端构建/合成同步抛(坏 URL 曾直接崩掉渲染线程)也只落到状态行。
        java.util.concurrent.CompletableFuture<byte[]> synth;
        final com.dwinovo.numen.client.voice.TtsBackend backend;
        try {
            backend = probe.createBackend();
            synth = backend.synthesize(VOICE_TEST_SENTENCE);
        } catch (Exception ex) {
            String why = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            com.dwinovo.numen.Constants.LOG.warn("[numen-voice] 试音失败(同步): {}", why);
            voiceNote(I18n.get(ModLanguageData.Keys.VOICE_TEST_FAIL, clip(why, fw() - 10)), true);
            return;
        }
        synth.whenComplete((wav, err) -> {
            com.dwinovo.numen.client.voice.PcmAudio decoded = null;
            Throwable failure = err;
            if (err == null) {
                try {
                    decoded = com.dwinovo.numen.client.voice.WavCodec.decode(wav).amplified(vol);
                } catch (Exception ex) {
                    failure = ex;
                }
            }
            final var audio = decoded;
            final Throwable fail = failure;
            Minecraft.getInstance().execute(() -> {
                if (gen != voiceTestGen) return;   // 表单已离开/又点了一次:作废
                if (fail != null) {
                    Throwable cur = fail;
                    while (cur.getCause() != null && cur != cur.getCause()) cur = cur.getCause();
                    String why = cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
                    // 完整原因进日志(红字被 clip 且只停留几秒,排障全靠这行)。
                    com.dwinovo.numen.Constants.LOG.warn("[numen-voice] 试音失败({}): {}",
                            backend.describe(), why);
                    voiceNote(I18n.get(ModLanguageData.Keys.VOICE_TEST_FAIL, clip(why, fw() - 10)), true);
                    return;
                }
                var sm = Minecraft.getInstance().getSoundManager();
                if (voicePreview != null) sm.stop(voicePreview);   // 重听:停掉上一句
                voicePreview = ClientServices.VOICE.previewVoice(audio, 1.0f);   // 响度已烙进 PCM;平台工厂:取数机制两侧不同
                sm.play(voicePreview);
                voiceNote(I18n.get(ModLanguageData.Keys.VOICE_TEST_OK), false);
            });
        });
    }

    private void voiceNote(String msg, boolean fail) {
        voiceMsg = msg;
        voiceMsgFail = fail;
        // 失败信息多停一会儿——HTTP 错误原文读一遍不止 5 秒。
        voiceMsgUntil = System.currentTimeMillis() + (fail ? 12000 : 5000);
    }

    private void renderVoiceSection(GuiGraphics g, int mouseX, int mouseY) {
        if (addingVoice) {
            // 表单模态:列表照常渲染作背景,暗幕+表单卡压上;字段/标题在 overlay 通道。
            renderVoiceList(g, -10000, -10000);
            formModal(g, Component.translatable(ModLanguageData.Keys.VOICE_TITLE));
            // 表单本体是占位符自述的字段 + 自标注的类型按钮;这里只画状态行。
            if (voiceMsg != null && voiceMsgUntil > System.currentTimeMillis()) {
                txt(g, Component.literal(clip(voiceMsg, fw() - 94)), fx(), fBottom() - 14,
                        voiceMsgFail ? FAIL : OK);
            }
            return;
        }
        renderVoiceList(g, mouseX, mouseY);
    }

    private void renderVoiceList(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
        txt(g, Component.translatable(ModLanguageData.Keys.VOICE_TITLE), x, secY0() - 2, TXT);
        // 列表视图:全局总开关(标题行右侧,新建按钮左边)。
        int togX = x + w - 64 - 10 - TOG_W;
        String onLabel = I18n.get(ModLanguageData.Keys.VOICE_ENABLED);
        txt(g, Component.literal(onLabel), togX - font().width(onLabel) - 4, secY0() - 1, TXT_MUTED);
        drawToggle(g, togX, secY0() - 2, lib.enabled());
        var list = lib.list();
        if (list.isEmpty()) {
            txt(g, Component.translatable(ModLanguageData.Keys.VOICE_EMPTY), x, secY0() + 16, TXT_FAINT);
            return;
        }
        int listY0 = voiceListY0();
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        String bound = host.uuid() != null ? lib.assignedEntry(host.uuid()) : null;
        for (int i = settingsScroll; i < list.size(); i++) {
            int ry = listY0 + (i - settingsScroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var e = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            hoverRow(g, mouseX, mouseY, x, w, ry);
            int tx = x;
            if (host.uuid() != null) {
                // 行首 ● = 本同伴正在用的声线(召唤时选定/新建时自动绑定)。只读标记,
                // 用户裁决:声线在开始时选好即可,不提供事后换绑。
                if (e.id().equals(bound)) {
                    txt(g, Component.literal("●"), x, ry + 6, CTA);
                }
                tx = x + 12;
            }
            txt(g, Component.literal(e.name()), tx, ry + 1, TXT);
            String detail;
            if (e.isSovits()) detail = nb(e.refAudio()) ? e.refAudio() : "?";
            else if (e.isMiniMax()) detail = nb(e.voice()) ? e.voice() : "?";
            else if (e.isFishAudio()) detail = nb(e.voice()) ? e.voice() : "?";
            else detail = nb(e.model()) ? e.model() : "?";
            String meta = (nb(e.backend()) ? e.backend() : "openai") + " · " + detail
                    + " · vol " + Math.round(e.volume() * 5.0f);
            txt(g, Component.literal(clip(meta, w - 30 - (tx - x))), tx, ry + 13, TXT_MUTED);
            txt(g, Component.literal("✎"), editX, ry + 6,
                    overDelete(mouseX, mouseY, editX, ry) ? CTA : TXT_FAINT);
            txt(g, Component.literal("✕"), delX, ry + 6,
                    overDelete(mouseX, mouseY, delX, ry) ? FAIL : TXT_FAINT);
        }
    }

    /** 声线列表首行的 y。 */
    private int voiceListY0() {
        return secY0() + 14;
    }

    private boolean voiceClick(int mx, int my) {
        if (addingVoice || voiceDeletePending != null) return false;
        int x = secX(), w = secW();
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
        // 全局总开关。
        int togX = x + w - 64 - 10 - TOG_W;
        if (overToggle(mx, my, togX, secY0() - 2)) {
            lib.setEnabled(!lib.enabled());
            return true;
        }
        var list = lib.list();
        int listY0 = voiceListY0();
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        int scroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        for (int i = scroll; i < list.size(); i++) {
            int ry = listY0 + (i - scroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var e = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            if (overDelete(mx, my, editX, ry)) { beginEditVoice(e); return true; }
            if (overDelete(mx, my, delX, ry)) { voiceDeletePending = e.id(); host.rebuild(); return true; }
            if (overRow(mx, my, x, w, ry)) { beginEditVoice(e); return true; }
        }
        return false;
    }

    private void beginEditVoice(com.dwinovo.numen.client.voice.VoiceLibrary.Entry e) {
        addingVoice = true;
        voiceFormScroll = 0;
        voiceEditId = e.id();
        wVoiceBackend = normalizeVoiceBackend(e.backend());
        wVoiceName = nv(e.name());
        wVoiceUrl = nv(e.url());
        wVoiceKey = nv(e.apiKey());
        wVoiceGroup = nv(e.groupId());
        wVoiceModel = nv(e.model());
        wVoiceVoice = nv(e.voice());
        wVoiceRef = nv(e.refAudio());
        wVoicePrompt = nv(e.promptText());
        wVoiceLang = nv(e.textLang());
        // 存储的是增益(0.2~2.0),表单显示 1~10 档。
        wVoiceVolume = String.valueOf(Math.round(Math.clamp(e.volume(), 0.2f, 2.0f) * 5.0f));
        voiceMsg = null;
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

    private void buildPersonaListWidgets() {
        // ↻ 刷新:重扫 persona/ 目录——外部编辑器改完 md 不用重开面板。
        host.add(new SimpleButton(left() + panelW() - PAD - 64 - 22, secY0() - 2, 18, 14,
                Component.literal("↻"), b -> {
                    PersonaLibrary.instance().reload();
                    host.rebuild();
                }));
        host.add(new SimpleButton(left() + panelW() - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable("numen.persona.add"), b -> {
                    addingPersona = true; personaEditId = null;
                    wPersonaName = ""; wPersonaText = "";
                    host.rebuild();
                }));
    }

    private void buildPersonaForm() {
        int x = fx(), w = fw();
        int fy = fy0();
        // 名称即文件名;正文(自由 MD)占满剩余高度。
        personaNameInput = field(x, fy + 11, w, 48, wPersonaName);
        int ty = fy + 44;
        int th = (fBottom() - 22) - ty;
        personaTextArea = new net.minecraft.client.gui.components.MultiLineEditBox(
                font(), x, ty, w, th,
                Component.translatable("numen.persona.text_placeholder"), Component.empty()) {
            @Override
            protected void renderBackground(GuiGraphics gg) {
                // 圆角深色编辑底替换原版方框——正文字色是原版硬编码的浅色,底必须够深;
                // 主题 text 深色正好当底,聚焦时边框亮 CTA,与单行字段同一套语言。
                UiTheme t = UiTheme.current();
                com.dwinovo.numen.client.ui.RoundRect.card(gg, getX(), getY(),
                        getX() + getWidth(), getY() + getHeight(), 5,
                        t.text(), isFocused() ? t.cta() : t.aiBorder());
            }
        };
        personaTextArea.setValue(wPersonaText);
        personaTextArea.setCharacterLimit(4096);
        host.add(personaTextArea);
        host.add(new SimpleButton(fRight() - 64, fBottom() - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSavePersona()).primary());
        host.add(new SimpleButton(fRight() - 64 - 22, fBottom() - 18, 18, 18,
                Component.literal("✕"), b -> { addingPersona = false; personaEditId = null; host.rebuild(); }));
        host.focus(personaNameInput);
    }

    private void buildPersonaDeleteConfirm() {
        buildConfirmButtons(() -> {
            PersonaLibrary.instance().remove(personaDeletePending);
            personaDeletePending = null;
            host.rebuild();
        });
    }

    private void onSavePersona() {
        String name = personaNameInput.getValue().trim();
        String text = personaTextArea == null ? "" : personaTextArea.getValue().trim();
        if (name.isEmpty() || text.isEmpty()) { host.warnPulse(); return; }
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
        wPersonaName = ""; wPersonaText = "";
        host.rebuild();
    }

    // ---- MCP section ----

    private void buildMcpDeleteConfirm() {
        buildConfirmButtons(() -> {
            com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(mcpDeletePending);
            mcpDeletePending = null;
            host.rebuild();
        });
    }

    private void buildMcpListWidgets() {
        // "add server" affordance, top-right of the section.
        host.add(new SimpleButton(left() + panelW() - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable("numen.mcp.add"), b -> {
                    addingMcp = true; mcpEditOriginal = null;                 // fresh add — not editing
                    wMcpName = ""; wMcpTarget = ""; wMcpHeader = ""; mcpStdio = false;
                    host.rebuild();
                }));
    }

    /** The add-MCP-server form: name, type (http/stdio) toggle, and URL / command. */
    private void buildMcpForm() {
        int x = fx(), w = fw();
        int fy = fy0();
        mcpNameInput = field(x, fy + 11, w, 48, wMcpName);
        // type toggle button (cycles http ↔ stdio; rebuild swaps the URL/command row)
        host.add(new SimpleButton(x, fy + 34, w, 18,
                Component.translatable(mcpStdio ? "numen.mcp.type_stdio" : "numen.mcp.type_http"),
                b -> { preserveMcpForm(); mcpStdio = !mcpStdio; host.rebuild(); }));
        mcpTargetInput = field(x, fy + 67, w, 512, wMcpTarget);
        // 4th field: HTTP → request header(s) "Name: Value"; stdio → env "KEY=value" (';'-separated).
        mcpHeaderInput = field(x, fy + 100, w, 1024, wMcpHeader);
        // Save + Cancel
        host.add(new SimpleButton(fRight() - 64, fBottom() - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveMcp()).primary());
        host.add(new SimpleButton(fRight() - 64 - 22, fBottom() - 18, 18, 18,
                Component.literal("✕"), b -> { addingMcp = false; mcpEditOriginal = null; host.rebuild(); }));
        host.focus(mcpNameInput);   // ready to type the name immediately
    }

    private void preserveMcpForm() {
        if (mcpNameInput != null) wMcpName = mcpNameInput.getValue();
        if (mcpTargetInput != null) wMcpTarget = mcpTargetInput.getValue();
        if (mcpHeaderInput != null) wMcpHeader = mcpHeaderInput.getValue();
    }

    private void onSaveMcp() {
        String name = mcpNameInput.getValue().trim();
        String target = mcpTargetInput.getValue().trim();
        if (name.isEmpty() || target.isEmpty()) { host.warnPulse(); return; }
        // When editing, preserve the server's on/off state (a plain edit shouldn't flip its toggle).
        boolean enabled = true;
        if (mcpEditOriginal != null) {
            var orig = com.dwinovo.numen.mcp.client.McpClientManager.spec(mcpEditOriginal);
            if (orig != null) enabled = orig.enabled();
        }
        com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec spec;
        String extra = mcpHeaderInput == null ? "" : mcpHeaderInput.getValue();
        if (mcpStdio) {
            String[] parts = target.split("\\s+");
            String command = parts[0];
            List<String> args = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) args.add(parts[i]);
            spec = new com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec(name, "stdio", "", java.util.Map.of(),
                    command, List.copyOf(args), parseEnv(extra), enabled, 20, 120);
        } else {
            spec = new com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec(name, "http", target, parseHeader(extra),
                    "", List.of(), java.util.Map.of(), enabled, 20, 120);
        }
        com.dwinovo.numen.mcp.client.McpClientManager.upsertServer(spec);
        // Renamed while editing → upsert wrote the new-named entry; drop the old one.
        if (mcpEditOriginal != null && !mcpEditOriginal.equals(name)) {
            com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(mcpEditOriginal);
        }
        mcpEditOriginal = null;
        addingMcp = false;
        wMcpName = ""; wMcpTarget = ""; wMcpHeader = ""; mcpStdio = false;
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
    private void voiceLabel(GuiGraphics g, int row, String text) {
        if (!voiceRowVisible(row)) return;
        txt(g, Component.literal(text), fx(), voiceVy(row) - 11, TXT_MUTED);
    }

    // ---- Skin section: the named skin library (upload png → MineSkin-signed textures) ----

    private void buildSkinListWidgets() {
        host.add(new SimpleButton(left() + panelW() - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable(ModLanguageData.Keys.SKIN_ADD), b -> {
                    addingSkin = true;
                    skinEditId = null;
                    resetSkinForm();
                    host.rebuild();
                }));
    }

    /**
     * 皮肤表单:名称 + 手臂模型下拉 + 拖拽提示区(png 从系统里拖进游戏窗口,
     * {@link #onFilesDrop} 接住)。保存 = 先 MineSkin 代签再落库,失败红字可重试。
     */
    private void buildSkinForm() {
        int x = fx(), w = fw();
        int fy = fy0();
        skinNameInput = field(x, fy + 11, w, 48, wSkinName);
        skinVariantDropdown = new Dropdown(List.of(
                new Dropdown.Item(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_CLASSIC,
                        I18n.get(ModLanguageData.Keys.SKIN_VARIANT_CLASSIC)),
                new Dropdown.Item(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_SLIM,
                        I18n.get(ModLanguageData.Keys.SKIN_VARIANT_SLIM))),
                wSkinVariant);
        skinVariantDropdown.setBounds(x, fy + 11 + SET_SP, w, 18);
        skinVariantDropdown.setDropBottom(top() + panelH() - 2);
        host.add(new SimpleButton(fRight() - 64, fBottom() - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveSkin()).primary());
        host.add(new SimpleButton(fRight() - 64 - 22, fBottom() - 18, 18, 18,
                Component.literal("✕"), b -> {
                    addingSkin = false;
                    skinEditId = null;
                    skinFormGen++;
                    host.rebuild();
                }));
        host.focus(skinNameInput);
    }

    private void buildSkinDeleteConfirm() {
        buildConfirmButtons(() -> {
            com.dwinovo.numen.client.skin.SkinLibrary.instance().remove(skinDeletePending);
            skinDeletePending = null;
            host.rebuild();
        });
    }

    private void resetSkinForm() {
        wSkinName = "";
        wSkinVariant = com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_CLASSIC;
        skinDropped = null;
        skinDroppedW = skinDroppedH = 0;
        skinSigning = false;
        skinMsg = null;
        skinFormGen++;
    }

    private void beginEditSkin(com.dwinovo.numen.client.skin.SkinLibrary.Entry e) {
        addingSkin = true;
        skinEditId = e.id();
        wSkinName = e.name();
        wSkinVariant = e.variant();
        skinDropped = null;   // 不换图时沿用落盘原图(改手臂模型重签也从盘上读)
        skinDroppedW = skinDroppedH = 0;
        skinSigning = false;
        skinMsg = null;
        skinFormGen++;
        host.rebuild();
    }

    /**
     * 保存 = 签名 + 落库。需要重签的情形:新图、或手臂模型变了(variant 编码在
     * 签名数据里);仅改名直接落库。签名在 MineSkin 排队,期间禁止重复点击。
     */
    private void onSaveSkin() {
        if (skinSigning) return;
        if (skinNameInput != null) wSkinName = skinNameInput.getValue();
        String name = wSkinName.trim();
        if (name.isEmpty()) {
            skinNote(I18n.get(ModLanguageData.Keys.SKIN_WARN_NAME), true);
            return;
        }
        var lib = com.dwinovo.numen.client.skin.SkinLibrary.instance();
        var old = skinEditId != null ? lib.get(skinEditId) : null;
        byte[] png = skinDropped;
        boolean needSign = png != null || old == null || !old.variant().equals(wSkinVariant)
                || !old.signed();
        if (needSign && png == null) {
            if (old != null) {
                try {
                    png = java.nio.file.Files.readAllBytes(lib.pngPath(old.id()));
                } catch (java.io.IOException ex) {
                    png = null;
                }
            }
            if (png == null) {
                skinNote(I18n.get(ModLanguageData.Keys.SKIN_WARN_IMAGE), true);
                return;
            }
        }
        String id = old != null ? old.id() : lib.freshId();
        if (!needSign) {
            lib.put(new com.dwinovo.numen.client.skin.SkinLibrary.Entry(
                    id, name, wSkinVariant, old.value(), old.signature()), null);
            addingSkin = false;
            skinEditId = null;
            host.rebuild();
            return;
        }
        skinSigning = true;
        skinNote(I18n.get(ModLanguageData.Keys.SKIN_SIGNING), false);
        final int gen = ++skinFormGen;
        final byte[] fPng = png;
        final String fVariant = wSkinVariant;
        com.dwinovo.numen.client.skin.MineSkinClient.generate(fPng, fVariant, name)
                .whenComplete((signed, err) -> Minecraft.getInstance().execute(() -> {
                    if (gen != skinFormGen) return;   // 表单已离开/重开:作废
                    skinSigning = false;
                    if (err != null || signed == null) {
                        Throwable cur = err;
                        while (cur != null && cur.getCause() != null && cur != cur.getCause()) {
                            cur = cur.getCause();
                        }
                        String why = cur == null ? "?" : (cur.getMessage() == null
                                ? cur.getClass().getSimpleName() : cur.getMessage());
                        com.dwinovo.numen.Constants.LOG.warn("[numen-skin] MineSkin 签名失败: {}", why);
                        skinNote(I18n.get(ModLanguageData.Keys.SKIN_SIGN_FAIL, clip(why, fw() - 10)), true);
                        return;
                    }
                    com.dwinovo.numen.Constants.LOG.info("[numen-skin] MineSkin 签名成功: {}", name);
                    com.dwinovo.numen.client.skin.SkinLibrary.instance().put(
                            new com.dwinovo.numen.client.skin.SkinLibrary.Entry(
                                    id, name, fVariant, signed.value(), signed.signature()),
                            fPng);
                    addingSkin = false;
                    skinEditId = null;
                    host.rebuild();
                }));
    }

    private void skinNote(String msg, boolean fail) {
        skinMsg = msg;
        skinMsgFail = fail;
        skinMsgUntil = System.currentTimeMillis() + (fail ? 12000 : 60000);   // 签名中的提示常驻到结果
    }

    private void renderSkinSection(GuiGraphics g, int mouseX, int mouseY) {
        if (addingSkin) {
            renderSkinList(g, -10000, -10000);
            formModal(g, Component.translatable(ModLanguageData.Keys.SKIN_TITLE));
            int x = fx(), w = fw();
            int fy = fy0();
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_FORM_NAME), x, fy, TXT_MUTED);
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_FORM_VARIANT), x, fy + SET_SP, TXT_MUTED);
            // 导入区:唯一入口「选择文件…」——原生文件对话框(FCL 会把它翻译成
            // 安卓的文件选择器);拖拽入窗仍被 onFilesDrop 静默接住,不再宣传。
            int dy = fy + 2 * SET_SP + 4;
            UiTheme sth = UiTheme.current();
            int[] rc = skinChipRect();
            boolean hov = mouseX >= rc[0] && mouseX < rc[0] + rc[2]
                    && mouseY >= rc[1] && mouseY < rc[1] + rc[3];
            com.dwinovo.numen.client.ui.RoundRect.card(g, rc[0], rc[1],
                    rc[0] + rc[2], rc[1] + rc[3], 3, sth.field(),
                    hov ? CTA : sth.surfaceBorder());
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_PICK_FILE),
                    rc[0] + 6, rc[1] + 4, TXT);
            if (skinDropped != null) {
                txt(g, Component.translatable(ModLanguageData.Keys.SKIN_LOADED,
                        skinDroppedW + "x" + skinDroppedH), x, dy + 22, OK);
            } else if (skinEditId != null) {
                txt(g, Component.translatable(ModLanguageData.Keys.SKIN_KEEP_OLD), x, dy + 22, TXT_FAINT);
            }
            if (skinMsg != null && skinMsgUntil > System.currentTimeMillis()) {
                txt(g, Component.literal(clip(skinMsg, w - 94)), x, fBottom() - 14,
                        skinMsgFail ? FAIL : OK);
            }
            // 手臂模型下拉最后画(展开列表压在下方文字上)。
            if (skinVariantDropdown != null) {
                skinVariantDropdown.render(g, font(), mouseX, mouseY);
            }
            return;
        }
        renderSkinList(g, mouseX, mouseY);
    }

    private void renderSkinList(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        var lib = com.dwinovo.numen.client.skin.SkinLibrary.instance();
        txt(g, Component.translatable(ModLanguageData.Keys.SKIN_TITLE), x, secY0() - 2, TXT);
        var list = lib.list();
        if (list.isEmpty()) {
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_EMPTY), x, secY0() + 16, TXT_FAINT);
            return;
        }
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        for (int i = settingsScroll; i < list.size(); i++) {
            int ry = listY0 + (i - settingsScroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var e = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            hoverRow(g, mouseX, mouseY, x, w, ry);
            var face = com.dwinovo.numen.client.skin.SkinTextures.faceOf(e.id(), lib.pngPath(e.id()));
            if (face != null) {
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(g, face, x, ry + 1, 16);
            }
            int tx = x + 20;
            txt(g, Component.literal(e.name()), tx, ry + 1, TXT);
            String meta = I18n.get(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_SLIM.equals(e.variant())
                    ? ModLanguageData.Keys.SKIN_VARIANT_SLIM : ModLanguageData.Keys.SKIN_VARIANT_CLASSIC)
                    + " · " + I18n.get(e.signed() ? ModLanguageData.Keys.SKIN_SIGNED
                            : ModLanguageData.Keys.SKIN_UNSIGNED);
            txt(g, Component.literal(clip(meta, w - 50)), tx, ry + 13, e.signed() ? TXT_MUTED : FAIL);
            txt(g, Component.literal("✎"), editX, ry + 6,
                    overDelete(mouseX, mouseY, editX, ry) ? CTA : TXT_FAINT);
            txt(g, Component.literal("✕"), delX, ry + 6,
                    overDelete(mouseX, mouseY, delX, ry) ? FAIL : TXT_FAINT);
        }
    }

    /** 「选择文件…」按钮矩形 {x, y, w, h}(render 与 click 同一套推导)。 */
    private int[] skinChipRect() {
        int x = fx();
        int cy = fy0() + 2 * SET_SP + 4;
        int w0 = font().width(I18n.get(ModLanguageData.Keys.SKIN_PICK_FILE)) + 14;
        return new int[]{x, cy, w0, 16};
    }

    private boolean skinClick(int mx, int my) {
        if (skinDeletePending != null) return false;
        if (addingSkin) {
            // 手臂模型下拉先于其它命中(展开列表覆盖在表单文字上)。
            if (skinVariantDropdown != null && skinVariantDropdown.mouseClicked(mx, my)) {
                wSkinVariant = skinVariantDropdown.selectedId();
                return true;
            }
            int[] rc = skinChipRect();
            if (mx >= rc[0] && mx < rc[0] + rc[2] && my >= rc[1] && my < rc[1] + rc[3]) {
                openNativeSkinPicker();
                return true;
            }
            return false;
        }
        int x = secX(), w = secW();
        var lib = com.dwinovo.numen.client.skin.SkinLibrary.instance();
        var list = lib.list();
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        int scroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        for (int i = scroll; i < list.size(); i++) {
            int ry = listY0 + (i - scroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var e = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            if (overDelete(mx, my, editX, ry)) { beginEditSkin(e); return true; }
            if (overDelete(mx, my, delX, ry)) { skinDeletePending = e.id(); host.rebuild(); return true; }
            if (overRow(mx, my, x, w, ry)) { beginEditSkin(e); return true; }
        }
        return false;
    }

    /** 皮肤 png 从系统拖进游戏窗口(皮肤表单打开时)。64×64 或旧版 64×32。 */
    public void onFilesDrop(List<java.nio.file.Path> paths) {
        if (!(section == Section.SKIN && addingSkin)) return;
        for (java.nio.file.Path p : paths) {
            if (!p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
            importSkinFile(p);
            return;
        }
    }

    /** 皮肤导入的统一入口:拖拽、原生文件对话框、导入文件夹点选三条路共用。 */
    private void importSkinFile(java.nio.file.Path p) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            try (var img = com.mojang.blaze3d.platform.NativeImage.read(
                    new java.io.ByteArrayInputStream(bytes))) {
                int iw = img.getWidth(), ih = img.getHeight();
                if (iw != 64 || (ih != 64 && ih != 32)) {
                    skinNote(I18n.get(ModLanguageData.Keys.SKIN_WARN_SIZE, iw + "x" + ih), true);
                    return;
                }
                if (skinNameInput != null) wSkinName = skinNameInput.getValue();
                skinDropped = bytes;
                skinDroppedW = iw;
                skinDroppedH = ih;
                skinNote(I18n.get(ModLanguageData.Keys.SKIN_LOADED, iw + "x" + ih), false);
            }
        } catch (java.io.IOException | RuntimeException ex) {
            skinNote(I18n.get(ModLanguageData.Keys.SKIN_WARN_READ, ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage()), true);
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
                    importSkinFile(java.nio.file.Path.of(path));
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
        if (activeModalTitle() != null || formActive()) {
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
            case STT -> renderSttSection(g);
            case THEME -> renderThemeSection(g, mouseX, mouseY);
        }
        // 删除确认改模态:列表照常渲染作背景,暗幕+确认卡压在上面(按钮走 widget
        // 通道,在暗幕之后渲染,天然浮在卡上)。
        Component modal = activeModalTitle();
        if (modal != null) {
            com.dwinovo.numen.client.ui.ConfirmModal.render(g, font(), host.railX(),
                    left() + panelW(), top(), panelH(), modal, null);
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
        if (addingMcp) {
            renderMcpList(g, -10000, -10000);
            formModal(g, Component.translatable("numen.mcp.title"));
            renderMcpForm(g);
            return;
        }
        renderMcpList(g, mouseX, mouseY);
    }

    private void renderMcpList(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        txt(g, Component.translatable("numen.mcp.title"), x, secY0() - 2, TXT);
        var servers = com.dwinovo.numen.mcp.client.McpClientManager.servers();
        if (servers.isEmpty()) {
            txt(g, Component.translatable("numen.mcp.empty"), x, secY0() + 16, TXT_FAINT);
            return;
        }
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp(settingsScroll, 0, Math.max(0, servers.size() - visible));
        for (int i = settingsScroll; i < servers.size(); i++) {
            int row = i - settingsScroll;
            int ry = listY0 + row * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var h = servers.get(i);
            int togX = x + w - 34, delX = x + w - 12;
            hoverRow(g, mouseX, mouseY, x, w, ry);
            // status dot
            int dy = ry + 3;
            g.fill(x, dy, x + 5, dy + 5, mcpDotColor(h.status()));
            Nb.border(g, x, dy, 5, 5, 1, BORDER);
            // name + meta line
            txt(g, Component.literal(h.name()), x + 10, ry + 1, TXT);
            txt(g, Component.literal(mcpMeta(h)), x + 10, ry + 13, TXT_MUTED);
            // toggle + delete, right-aligned
            drawToggle(g, togX, ry + 5, h.toggledOn());
            boolean overDel = overDelete(mouseX, mouseY, delX, ry);
            txt(g, Component.literal("✕"), delX, ry + 6, overDel ? FAIL : TXT_FAINT);
            // hover tooltip: tool names + url/command + any error (not over a control)
            if (overRow(mouseX, mouseY, x, w, ry) && !overToggle(mouseX, mouseY, togX, ry + 5) && !overDel) {
                host.tip(mcpTooltip(h), mouseX, mouseY);
            }
        }
    }

    /** Add-server form labels + placeholders (fields/buttons are widgets, drawn in the overlay pass). */
    private void renderMcpForm(GuiGraphics g) {
        int x = fx();
        int fy = fy0();   // matches buildMcpForm
        txt(g, Component.translatable("numen.mcp.form_name"), x, fy, TXT_MUTED);
        // the type row is the self-labelled toggle button (no separate label)
        txt(g, Component.translatable(mcpStdio ? "numen.mcp.form_command" : "numen.mcp.form_url"),
                x, fy + 56, TXT_MUTED);
        txt(g, Component.translatable(mcpStdio ? "numen.mcp.form_env" : "numen.mcp.form_header"), x, fy + 89, TXT_MUTED);
        // field placeholders are drawn in the post-widget pass (see renderOverlays), so they sit above the frames
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
        if (addingPersona) {
            renderPersonaList(g, -10000, -10000);
            formModal(g, Component.translatable("numen.persona.title"));
            renderPersonaForm(g);
            return;
        }
        renderPersonaList(g, mouseX, mouseY);
    }

    private void renderPersonaList(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        txt(g, Component.translatable("numen.persona.title"), x, secY0() - 2, TXT);
        var list = PersonaLibrary.instance().list();
        if (list.isEmpty()) {
            txt(g, Component.translatable("numen.persona.empty"), x, secY0() + 16, TXT_FAINT);
            return;
        }
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        for (int i = settingsScroll; i < list.size(); i++) {
            int ry = listY0 + (i - settingsScroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            PersonaLibrary.Persona p = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            hoverRow(g, mouseX, mouseY, x, w, ry);
            txt(g, Component.literal(p.name()), x, ry + 1, TXT);
            String badge = p.preset() ? I18n.get("numen.persona.preset_badge") + " · " : "";
            txt(g, Component.literal(clip(badge + p.text(), w - 30)), x, ry + 13, TXT_MUTED);
            if (p.preset()) {
                txt(g, Component.literal("⧉"), delX, ry + 6,
                        overDelete(mouseX, mouseY, delX, ry) ? CTA : TXT_FAINT);
            } else {
                txt(g, Component.literal("✎"), editX, ry + 6,
                        overDelete(mouseX, mouseY, editX, ry) ? CTA : TXT_FAINT);
                txt(g, Component.literal("✕"), delX, ry + 6,
                        overDelete(mouseX, mouseY, delX, ry) ? FAIL : TXT_FAINT);
            }
        }
    }

    private void renderPersonaForm(GuiGraphics g) {
        int x = fx();
        int fy = fy0();
        txt(g, Component.translatable("numen.persona.form_name"), x, fy, TXT_MUTED);
        txt(g, Component.translatable("numen.persona.form_text"), x, fy + 33, TXT_MUTED);
    }

    private boolean personaClick(int mx, int my) {
        if (addingPersona || personaDeletePending != null) return false;
        int x = secX(), w = secW();
        var lib = PersonaLibrary.instance();
        var list = lib.list();
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        int scroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        for (int i = scroll; i < list.size(); i++) {
            int ry = listY0 + (i - scroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            PersonaLibrary.Persona p = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            if (p.preset()) {
                if (overDelete(mx, my, delX, ry)) { lib.clonePersona(p.id()); host.rebuild(); return true; }
            } else {
                if (overDelete(mx, my, editX, ry)) { beginEditPersona(p); return true; }
                if (overDelete(mx, my, delX, ry)) { personaDeletePending = p.id(); host.rebuild(); return true; }
                if (overRow(mx, my, x, w, ry)) { beginEditPersona(p); return true; }   // body → edit a custom persona
            }
        }
        return false;
    }

    private void beginEditPersona(PersonaLibrary.Persona p) {
        addingPersona = true;
        personaEditId = p.id();
        wPersonaName = p.name();
        wPersonaText = p.text();
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
        // 模型配置列表(NumenUI):删除确认卡开着时面板吃掉一切(模态);
        // 平时接行/图标/新建,没命中就放行给子导航。
        if (section == Section.PROVIDER && !addingProvider
                && profileList().mouseClicked(mouseX, mouseY, 0)) {
            return true;
        }
        if (section == Section.STT && sttProviderDropdown != null) {
            String beforeStt = sttProviderDropdown.selectedId();
            if (sttProviderDropdown.mouseClicked(mouseX, mouseY)) {
                if (sttModelDropdown != null) sttModelDropdown.close();
                if (sttMicDropdown != null) sttMicDropdown.close();
                String selStt = sttProviderDropdown.selectedId();
                if (!selStt.equals(beforeStt)) {   // provider changed → prefill model + base URL
                    preserveSttForm();
                    adaptToSttProvider(selStt);
                    host.rebuild();
                }
                return true;
            }
        }
        if (section == Section.STT && sttModelDropdown != null
                && sttModelDropdown.mouseClicked(mouseX, mouseY)) {
            if (sttProviderDropdown != null) sttProviderDropdown.close();
            if (sttMicDropdown != null) sttMicDropdown.close();
            if (CUSTOM_MODEL.equals(sttModelDropdown.selectedId())) {   // 自定义 → free text
                preserveSttForm();
                sttCustomModel = true;
                wSttModel = "";
                host.rebuild();
            }
            return true;
        }
        if (section == Section.STT && sttMicDropdown != null
                && sttMicDropdown.mouseClicked(mouseX, mouseY)) {
            if (sttProviderDropdown != null) sttProviderDropdown.close();
            if (sttModelDropdown != null) sttModelDropdown.close();
            return true;
        }
        // 声线表单的后端下拉:选型变了就随之刷新字段区(typed 值经 preserve 存活)。
        // 行滚出视口时不接点击(控件仍在,只是被表单滚动藏起来了)。
        if (section == Section.VOICE
                && addingVoice && voiceBackendDropdown != null && voiceRowVisible(1)) {
            String before = voiceBackendDropdown.selectedId();
            if (voiceBackendDropdown.mouseClicked(mouseX, mouseY)) {
                String sel = voiceBackendDropdown.selectedId();
                if (!sel.equals(before)) {
                    preserveVoiceForm();
                    wVoiceBackend = sel;
                    // URL 跟着选型换成新后端的官方端点——但只覆盖"空或还是旧默认"
                    // 的值,用户手改过的自定义地址不动。
                    if (wVoiceUrl.isBlank() || wVoiceUrl.equals(defaultVoiceUrl(before))) {
                        wVoiceUrl = defaultVoiceUrl(sel);
                    }
                    host.rebuild();
                }
                return true;
            }
        }
        return settingsClickedAt(mouseX, mouseY);
    }

    /** Sub-nav column, the theme rows, then per-row toggles / edits in the section lists. */
    private boolean settingsClickedAt(double mxd, double myd) {
        int mx = (int) mxd, my = (int) myd;
        // 表单模态:子导航/主题行/列表全在暗幕之下,不放行;皮肤表单的手臂下拉
        // 仍要路由(它在 skinClick 的 addingSkin 分支里)。
        if (formActive()) {
            if (section == Section.SKIN && addingSkin) return skinClick(mx, my);
            return false;
        }
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
        if (section == Section.MCP) return mcpToggleClick(mx, my);
        if (section == Section.BRAIN) return brainToggleClick(mx, my);
        if (section == Section.SKILLS) return skillToggleClick(mx, my);
        if (section == Section.PERSONA) return personaClick(mx, my);
        if (section == Section.VOICE) return voiceClick(mx, my);
        if (section == Section.SKIN) return skinClick(mx, my);
        return false;
    }

    private boolean mcpToggleClick(int mx, int my) {
        if (addingMcp || mcpDeletePending != null) return false;   // form / confirm widgets handle clicks
        int x = secX(), w = secW();
        var servers = com.dwinovo.numen.mcp.client.McpClientManager.servers();
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        int scroll = Math.clamp(settingsScroll, 0, Math.max(0, servers.size() - visible));
        for (int i = scroll; i < servers.size(); i++) {
            int ry = listY0 + (i - scroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            int togX = x + w - 34, delX = x + w - 12;
            var h = servers.get(i);
            if (overToggle(mx, my, togX, ry + 5)) {
                var st = h.status();
                // CONNECTED / CONNECTING → turn off; DISABLED / FAILED → (re)connect (retry a failed one)
                if (st == com.dwinovo.numen.mcp.client.McpClientManager.Status.CONNECTED
                        || st == com.dwinovo.numen.mcp.client.McpClientManager.Status.CONNECTING) {
                    com.dwinovo.numen.mcp.client.McpClientManager.disableServer(h.name());
                } else {
                    com.dwinovo.numen.mcp.client.McpClientManager.enableServer(h.name());
                }
                return true;
            }
            if (overDelete(mx, my, delX, ry)) {
                mcpDeletePending = h.name();   // ask first — deletion is confirmed via the bar
                host.rebuild();
                return true;
            }
            if (overRow(mx, my, x, w, ry)) {   // body (name/meta) click → edit this server
                beginEditMcp(h.name());
                return true;
            }
        }
        return false;
    }

    /** Open the add-form PRE-FILLED with {@code name}'s current spec — saving REPLACES the entry. */
    private void beginEditMcp(String name) {
        var spec = com.dwinovo.numen.mcp.client.McpClientManager.spec(name);
        if (spec == null) return;
        mcpEditOriginal = name;
        addingMcp = true;
        mcpStdio = spec.isStdio();
        wMcpName = spec.name();
        if (mcpStdio) {
            StringBuilder cmd = new StringBuilder(spec.command() == null ? "" : spec.command());
            for (String a : spec.args()) cmd.append(' ').append(a);
            wMcpTarget = cmd.toString().trim();
            wMcpHeader = joinPairs(spec.env(), '=');       // stdio → env "KEY=value"
        } else {
            wMcpTarget = spec.url() == null ? "" : spec.url();
            wMcpHeader = joinPairs(spec.headers(), ':');    // http → header "Name: Value"
        }
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
        for (Dropdown d : new Dropdown[]{voiceBackendDropdown, skinVariantDropdown}) {
            if (d != null && d.mouseScrolled(mx, my, sy)) return true;
        }
        // 声线表单:滚轮上下滚整个表单(MiniMax 八行超出视口);命中区 = 表单卡。
        if (section == Section.VOICE
                && addingVoice && mx >= cardX0() && maxVoiceFormScroll() > 0) {
            preserveVoiceForm();
            voiceFormScroll = Math.clamp((long) (voiceFormScroll - sy * 16), 0, maxVoiceFormScroll());
            host.rebuild();
            return true;
        }
        return false;
    }

    /** Wheel pass 2 (after the rail/chat checks): scroll the section list. */
    public boolean mouseScrolledList(double sy) {
        if (formActive()) return false;   // 列表在表单模态的暗幕之下,不滚
        int count = switch (section) {
            case MCP -> com.dwinovo.numen.mcp.client.McpClientManager.servers().size();
            case SKILLS -> com.dwinovo.numen.agent.skill.SkillRegistry.instance().size();
            case PERSONA -> PersonaLibrary.instance().list().size();
            case VOICE -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().list().size();
            case SKIN -> com.dwinovo.numen.client.skin.SkinLibrary.instance().list().size();
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
        if (section != Section.PROVIDER) return false;
        if (addingProvider) return providerForm().keyPressed(keyCode, modifiers);
        return profileList().keyPressed(keyCode, modifiers);   // ESC 关删除确认(= 取消)
    }

    public boolean charTyped(char ch) {
        return section == Section.PROVIDER && addingProvider
                && providerForm().charTyped(ch);
    }

    public void renderOverlays(GuiGraphics g, int mouseX, int mouseY) {
        loadPalette();
        // 声线表单:模型配置同款——每行标题画在输入框上方,框内只留短示例占位。
        // 行序与 buildVoiceForm 的 switch 严格一致,随 voiceFormScroll 偏移。
        if (section == Section.VOICE && addingVoice) {
            boolean fish = com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_FISH.equals(wVoiceBackend);
            boolean minimax = com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX.equals(wVoiceBackend);
            boolean sovits = com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_SOVITS.equals(wVoiceBackend);
            voiceLabel(g, 0, I18n.get(ModLanguageData.Keys.VOICE_FORM_NAME));
            voiceLabel(g, 1, I18n.get(ModLanguageData.Keys.PROVIDER_FORM_PROVIDER));
            voiceLabel(g, 2, I18n.get(ModLanguageData.Keys.VOICE_FORM_URL));
            placeholder(g, voiceUrlInput, defaultVoiceUrl(wVoiceBackend));
            int row = 3;
            if (sovits) {
                voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_REF));
                placeholder(g, voiceRefInput, "D:/refs/voice.wav");
                voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_PROMPT));
                voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_LANG));
                placeholder(g, voiceLangInput, "zh");
            } else {
                voiceLabel(g, row++, I18n.get(fish ? ModLanguageData.Keys.VOICE_FORM_KEY_FISH
                        : minimax ? ModLanguageData.Keys.VOICE_FORM_KEY_MINIMAX
                        : ModLanguageData.Keys.VOICE_FORM_KEY_OPENAI));
                placeholder(g, voiceKeyInput, minimax ? "eyJ…" : "sk-…");
                if (minimax) {
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_GROUP));
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_MINIMAX_MODEL));
                    placeholder(g, voiceModelInput, "speech-02-turbo");
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_MINIMAX_VOICE));
                    placeholder(g, voiceVoiceInput, "male-qn-qingse");
                } else if (fish) {
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_REFERENCE));
                    placeholder(g, voiceVoiceInput, "fish.audio/m/… 或纯 ID");
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_FISH_MODEL));
                    placeholder(g, voiceModelInput, "s1 / s2.1-pro-free");
                } else {
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_MODEL));
                    placeholder(g, voiceModelInput, "FunAudioLLM/CosyVoice2-0.5B");
                    voiceLabel(g, row++, I18n.get(ModLanguageData.Keys.VOICE_FORM_VOICE));
                    placeholder(g, voiceVoiceInput, "FunAudioLLM/CosyVoice2-0.5B:alex");
                }
            }
            voiceLabel(g, row, I18n.get(ModLanguageData.Keys.VOICE_FORM_VOLUME));
            placeholder(g, voiceVolumeInput, "5");
        }
        // 声线表单的后端下拉最后画(展开的列表要压在字段上面)。
        if (section == Section.VOICE
                && addingVoice && voiceBackendDropdown != null && voiceRowVisible(1)) {
            voiceBackendDropdown.render(g, font(), mouseX, mouseY);
        }
        if (section == Section.STT) {
            Dropdown[] sttDd = { sttProviderDropdown, sttModelDropdown, sttMicDropdown };
            Dropdown sttOpen = null;
            for (Dropdown d : sttDd) {
                if (d == null) continue;
                if (d.isOpen()) sttOpen = d;
                else d.render(g, font(), mouseX, mouseY);
            }
            if (sttOpen != null) sttOpen.render(g, font(), mouseX, mouseY);   // open list overlays fields
        }
        if (section == Section.MCP && addingMcp) {
            placeholder(g, mcpNameInput, "kfc");
            placeholder(g, mcpTargetInput, mcpStdio ? "cmd /c npx -y <server>" : "https://mcp.mcd.cn");
            placeholder(g, mcpHeaderInput, mcpStdio ? "KEY=value; KEY2=value2" : "Authorization: Bearer <token>");
        }
        if (section == Section.PERSONA && addingPersona) {
            placeholder(g, personaNameInput, "名称(即文件名),如 小焰");   // the text area has its own built-in placeholder
        }
    }
}
