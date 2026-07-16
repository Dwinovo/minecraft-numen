package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.model.ModelRegistry;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientDeaths;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.data.ClientNumenInventory;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.RequestInventoryPayload;
import com.dwinovo.numen.persona.PersonaLibrary;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner-facing companion panel: one tabbed screen per Numen (Chat / Items /
 * Settings), opened from the roster or hotkey. Resolves the companion lazily by
 * UUID so it works at any distance.
 *
 * <h2>Chat tab</h2>
 * A scrollable transcript on the left + a live PLAN panel on the right. Tool calls
 * show a spinner while running and a green check once their result lands — the raw
 * tool-result JSON is NOT shown (it only flips the call to done), keeping the chat
 * readable. The plan is the companion's latest {@code todowrite}.
 */
public final class NumenScreen extends Screen {

    private enum Tab { CHAT, ITEMS, SETTINGS }

    // ---- layout ----
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 232;
    // Left companion rail (folded-in roster): one avatar per Numen, click to switch, + to summon.
    private static final int RAIL_W = 46;        // left rail column width (baked into the workspace sprite)
    private static final int RAIL_AV = 26;       // avatar tile size
    private static final int RAIL_SLOT = 32;     // vertical pitch per avatar
    private static final int RAIL_TOP = 12;      // top margin before the first avatar (clears the active crown)
    private static final int RAIL_BOT_GAP = 6;   // gap kept above the pinned "+" tile
    private static final int HEADER_H = 22;
    private static final int INPUT_H = 18;
    /** Text fields are inset inside their parchment frame: the EditBox is shrunk by this much
     *  (so vanilla's top-left unbordered text lands padded + centred) and the FIELD_SPRITE is
     *  inflated back out to the full frame. */
    private static final int FIELD_INSET_X = 5;
    private static final int FIELD_INSET_Y = 4;
    private static final int PAD = 8;
    private static final int LINE_H = 10;
    private static final int PLAN_W = 122;
    private static final int MAX_PROMPT = 1024;
    private static final int TOOL_ARG_CHARS = 44;

    // ---- palette (BlockFrame "Cottage" theme — single theme for now, see UiTheme) ----
    private static final UiTheme TH = UiTheme.WARM;
    private static final int BORDER = TH.border();
    private static final int ACCENT = TH.cta();
    private static final int TXT = TH.text();
    private static final int TXT_MUTED = TH.textDim();
    private static final int TXT_FAINT = 0xFF8C7C62;
    private static final int ON_BAND = TH.onBand();
    /** Faint on-band text (persona name after the companion name): cream blended toward the green band. */
    private static final int ON_BAND_FAINT = 0xFFB2BF9F;
    private static final int CTA = TH.cta();
    private static final int ON_CTA = TH.onCta();
    private static final int FIELD = TH.field();
    private static final int YOU = TH.reply();          // user messages — teal
    private static final int AI = 0xFF35562F;            // assistant replies — deep moss green (the "point")
    private static final int TOOL = TH.textDim();        // folded tool-call rows — muted, secondary
    private static final int OK = TH.ok();
    private static final int RUN = TH.run();
    private static final int FAIL = TH.fail();
    private static net.minecraft.resources.ResourceLocation railSpr(String n) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, n);
    }
    /** rail + panel composited into ONE sprite (continuous header, no gap; panel's left border = divider). */
    private static final net.minecraft.resources.ResourceLocation WORKSPACE_SPRITE = railSpr("workspace");
    private static final net.minecraft.resources.ResourceLocation AVATAR_FRAME = railSpr("avatar_frame");
    private static final net.minecraft.resources.ResourceLocation AVATAR_FRAME_ACTIVE = railSpr("avatar_frame_active");
    private static final net.minecraft.resources.ResourceLocation SUMMON_SPRITE = railSpr("summon");
    private static final net.minecraft.resources.ResourceLocation SUMMON_ACTIVE = railSpr("summon_active");
    /** API-key reveal toggle icons: open eye = "click to show", slashed eye = "click to hide". */
    private static final net.minecraft.resources.ResourceLocation EYE = railSpr("eye");
    private static final net.minecraft.resources.ResourceLocation EYE_OFF = railSpr("eye_off");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_UP = railSpr("chevron_up");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_DOWN = railSpr("chevron_down");

    private static final String[] SPIN = {"|", "/", "-", "\\"};
    /** Armor column on the Items tab (top → bottom); offhand is drawn separately below it. */
    private static final EquipmentSlot[] ARMOR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private UUID uuid;       // active companion (mutable — the rail switches it in place)
    private String name;
    private Tab tab = Tab.CHAT;

    /** The Settings tab is a config hub: a left sub-nav picks one of these sections. */
    private enum SettingsSection { PROVIDER, PROXY, MCP, SKILLS, PERSONA, VOICE, SKIN }

    // ---- model-config section state (mirrors the persona section) ----
    private boolean addingProvider;
    private String providerEditId;
    private String providerDeletePending;
    private String wProvName = "", wProvProvider = "", wProvModel = "", wProvKey = "", wProvBaseUrl = "";
    private net.minecraft.client.gui.components.EditBox provNameInput,
            provModelInput, provKeyInput, provBaseUrlInput;
    /** Form pickers: the provider catalog + the picked provider's model list. */
    private ProviderDropdown provProviderDropdown;
    private Dropdown provModelDropdown;
    private boolean provCustomModel;

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
    private static final String VOICE_NONE = "__none__";

    // 皮肤库 tab(列表+表单,照声线库制式)。签名发生在保存时(MineSkin 代签),
    // 召唤只读现成结果。
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
    /** 召唤页的皮肤下拉:null = 默认(按名字找同名正版)。 */
    private Dropdown summonSkinDropdown;
    private String summonSkinId;
    private static final String SKIN_DEFAULT = "__default__";

    /** 试听/保存的状态行(表单底部;fail = 红色),照 summon 页 warnText 的即时反馈做法。 */
    private String voiceMsg;
    private boolean voiceMsgFail;
    private long voiceMsgUntil;
    /** 试听代际:再次点击/离开表单让在途合成回调作废;preview 引用用于停掉上一次试听。 */
    private int voiceTestGen;
    private com.dwinovo.numen.client.voice.VoicePreviewSound voicePreview;

    // ---- proxy section state (a dedicated tab: IP + port) ----
    private net.minecraft.client.gui.components.EditBox proxyIpInput, proxyPortInput;
    private SettingsSection settingsSection = SettingsSection.PROVIDER;

    // Persona library form state (mirrors the MCP add/edit/delete flow).
    private boolean addingPersona;
    private String personaEditId;          // non-null = editing this persona; null = creating
    private String personaDeletePending;   // id awaiting delete confirm
    private String wPersonaName = "", wPersonaText = "";
    private net.minecraft.client.gui.components.EditBox personaNameInput;
    private net.minecraft.client.gui.components.MultiLineEditBox personaTextArea;   // roomy multi-line persona editor
    /** Persona chosen for the companion currently being summoned (null = default / none). */
    private String summonPersonaId;
    private Dropdown summonPersonaDropdown;
    /** Provider entry for the new companion — REQUIRED (no default, no fallback). */
    private Dropdown summonProviderDropdown;
    private String summonProviderId;
    /** Voice entry for the new companion — optional (null = silent). */
    private Dropdown summonVoiceDropdown;
    private String summonVoiceId;
    private static final String PERSONA_DEFAULT = "__default__";
    private int settingsScroll;   // first visible row of the MCP / skill list (wheel-scroll when long)

    // MCP "add server" form (mirrors the LLM add-site flow)
    private boolean addingMcp;
    private boolean mcpStdio;                 // form type: false = http, true = stdio
    private String wMcpName = "", wMcpTarget = "", wMcpHeader = "";
    private EditBox mcpNameInput, mcpTargetInput, mcpHeaderInput;
    private String mcpDeletePending;          // non-null = showing the delete-confirm bar for this server
    private String mcpEditOriginal;           // non-null = the add-form is EDITING this server (replace on save)

    private EditBox input;
    private SimpleButton sendButton;
    private SimpleButton stopButton;
    private SimpleButton compactButton;
    private String savedInput = "";

    // "+" summon flow: a transient name field shown over the panel
    private boolean summoning;
    private EditBox summonInput;
    private UUID dismissPending;   // non-null = showing the "delete companion?" confirm bar for this uuid

    // settings tab widgets
    private ProviderDropdown providerDropdown;
    private Dropdown modelDropdown;          // null when in custom-model mode
    private boolean customModel;             // model is a free-text custom id (not a registry preset)
    private static final String CUSTOM_MODEL = "__custom__";
    // unsaved working state — settings widgets are (re)built from these, NOT from config, so a rebuild
    // (provider change / custom toggle) doesn't revert what you just picked or typed.
    private String wProvider = "", wApiKey = "", wModel = "", wBaseUrl = "", wProxy = "", wSiteName = "";
    private String wReasoning = "auto";      // reasoning/thinking effort: auto | low | medium | high
    private boolean addingSite;              // "+ 添加站点" mode: name + base URL + model → writes a site
    private EditBox proxyInput;
    private EditBox siteNameInput;
    private EditBox apiKeyInput;
    private EditBox modelInput;
    private EditBox baseUrlInput;
    private long savedFlashUntil;
    private long warnUntil;        // transient "no API key" hint on the chat tab
    /** The current warn hint's text (endpoint problems vary: unbound provider vs keyless
     *  entry); null falls back to the generic no-key translation. */
    private String warnText;

    // A hovered-row tooltip (MCP / skill list) collected during section render, drawn last so
    // it sits above every later draw. Cleared each frame.
    private List<Component> pendingTip;
    private int pendingTipX, pendingTipY;

    // Widgets are registered for EVENTS only (addWidget) and rendered MANUALLY at the end of the
    // frame, so they sit ON TOP of the panel background instead of being painted over by it (the
    // "dim fields" bug — the panel fill ran after the auto-rendered widgets).
    private final List<AbstractWidget> overlay = new ArrayList<>();
    /** API key is masked by default; the eye button toggles it. */
    private boolean showKey;

    // geometry resolved in init()
    private int left, top, railX;
    private final int[] tabX = new int[3];   // left x of each tab label, for click hit-testing
    private final int[] tabW = new int[3];

    // chat transcript scroll
    private int scroll;            // px scrolled down from the top of the content
    private boolean pinBottom = true;
    private int lastMaxScroll;
    private int railScroll;        // index of the first visible rail avatar (wheel-scroll when many companions)
    /** Completed tool-call groups the user clicked open (keyed by the group's first call id). */
    private final Set<String> expandedGroups = new HashSet<>();

    /** Re-request the backpack every ~1 s while the Items tab is open. */
    private static final int INV_REFRESH_TICKS = 20;
    private int tickCounter;

    private NumenScreen(UUID uuid, String name) {
        super(Component.literal(name == null ? "Numen" : "Numen - " + name));
        this.uuid = uuid;
        this.name = name;
    }

    /** Open the panel focused on a specific companion. */
    public static void open(UUID uuid, String name) {
        Minecraft.getInstance().setScreen(new NumenScreen(uuid, name));
    }

    /** Hotkey entry: open the workspace on the first companion (or an empty panel to summon from). */
    public static void openWorkspace() {
        var entries = NumenRoster.instance().entries();
        if (entries.isEmpty()) { Minecraft.getInstance().setScreen(new NumenScreen(null, null)); return; }
        NumenRoster.Entry first = entries.get(0);
        Minecraft.getInstance().setScreen(new NumenScreen(first.uuid(), first.name()));
    }

    /** Switch the panel to another companion in place (left-rail click) — no reopen. */
    private void switchTo(UUID u, String n) {
        if (java.util.Objects.equals(u, uuid)) return;
        input = null; savedInput = "";          // don't carry typed text across companions
        uuid = u; name = n;
        scroll = 0; pinBottom = true; expandedGroups.clear();
        rebuild();
        if (tab == Tab.ITEMS && u != null) requestInventory();
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(uuid);
    }

    @Override
    protected void init() {
        int composite = RAIL_W + PANEL_W;        // rail flush against the panel — one merged sprite
        this.railX = (this.width - composite) / 2;
        this.left = railX + RAIL_W;
        this.top = (this.height - PANEL_H) / 2;
        layoutTabs();
        rebuild();
    }

    private static String[] tabLabels() {
        return new String[]{
                I18n.get("numen.tab.chat"), I18n.get("numen.tab.status"), I18n.get("numen.tab.settings")};
    }

    private void layoutTabs() {
        String[] labels = tabLabels();
        int x = left + PANEL_W - PAD;
        for (int i = labels.length - 1; i >= 0; i--) {
            int w = font.width(labels[i]) + 10;
            x -= w;
            tabX[i] = x;
            tabW[i] = w;
            x -= 4;
        }
    }

    /** Rebuild the widgets for the active tab. */
    private void rebuild() {
        if (input != null) savedInput = input.getValue();
        clearWidgets();
        overlay.clear();
        input = null;
        sendButton = stopButton = compactButton = null;
        apiKeyInput = modelInput = baseUrlInput = proxyInput = siteNameInput = null;
        mcpNameInput = mcpTargetInput = mcpHeaderInput = null;
        personaNameInput = null;
        personaTextArea = null;
        provNameInput = provModelInput = provKeyInput = provBaseUrlInput = null;
        provProviderDropdown = null;
        provModelDropdown = null;
        voiceNameInput = voiceUrlInput = voiceKeyInput = voiceGroupInput = voiceModelInput = null;
        voiceVoiceInput = voiceRefInput = voicePromptInput = voiceLangInput = voiceVolumeInput = null;
        voiceBackendDropdown = null;
        skinNameInput = null;
        skinVariantDropdown = null;
        proxyIpInput = proxyPortInput = null;
        modelDropdown = null;
        summonInput = null;
        summonSkinDropdown = null;
        summonPersonaDropdown = null;
        summonProviderDropdown = null;
        summonVoiceDropdown = null;
        if (summoning) { buildSummonField(); return; }
        if (dismissPending != null) { buildDismissConfirm(); return; }
        switch (tab) {
            case CHAT -> { if (uuid != null) buildChatWidgets(); }
            case SETTINGS -> buildSettingsWidgets();
            case ITEMS -> { /* no widgets */ }
        }
    }

    /** Row layout (offsets from top+HEADER_H) — each control gets its own label row,
     *  drawn in the render pass at these SAME offsets (keep the two in lockstep):
     *  8 title · 24 名字 label · 34 name field · 58 人设 label · 68 persona dropdown ·
     *  92 模型配置 label · 102 provider dropdown · 126 声线 label · 136 voice dropdown ·
     *  162 buttons · 186 hint/warn. */
    private void buildSummonField() {
        // 人设下拉的数据源是 persona/ 目录:每次打开召唤面板重扫一遍。
        com.dwinovo.numen.persona.PersonaLibrary.instance().reload();
        int y0 = top + HEADER_H;
        summonInput = new FlatEditBox(font, left + PAD + FIELD_INSET_X, y0 + 34 + FIELD_INSET_Y,
                PANEL_W - PAD * 2 - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        summonInput.setMaxLength(com.dwinovo.numen.network.payload.SummonRequestPayload.MAX_NAME);
        summonInput.setBordered(false);
        summonInput.setTextColor(TXT);
        add(summonInput);
        // Persona is OPTIONAL: first item = 不配置 (the persona slot then tells the
        // model "未配置人设,可以自由发挥"), presets and user personas follow. The name
        // "hint" renders in the render pass as a FAINT placeholder — the EditBox's
        // own hint drew in full text color and read as typed input.
        List<Dropdown.Item> items = new ArrayList<>();
        items.add(new Dropdown.Item(PERSONA_DEFAULT, I18n.get(ModLanguageData.Keys.SUMMON_PERSONA_NONE)));
        for (PersonaLibrary.Persona p : PersonaLibrary.instance().list()) {
            items.add(new Dropdown.Item(p.id(), p.name()));
        }
        summonPersonaDropdown = new Dropdown(items, summonPersonaId == null ? PERSONA_DEFAULT : summonPersonaId);
        summonPersonaDropdown.setBounds(left + PAD, y0 + 68, PANEL_W - PAD * 2, 18);
        summonPersonaDropdown.setDropBottom(top + PANEL_H - 2);
        // REQUIRED model config — no default item and no fallback: an empty library
        // shows no dropdown; clicking 创建 then explains (doSummon).
        var provEntries = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().list();
        if (!provEntries.isEmpty()) {
            List<Dropdown.Item> provItems = new ArrayList<>();
            for (var e : provEntries) {
                provItems.add(new Dropdown.Item(e.id(), e.name()));
            }
            if (summonProviderId == null) summonProviderId = provEntries.get(0).id();
            summonProviderDropdown = new Dropdown(provItems, summonProviderId);
            summonProviderDropdown.setBounds(left + PAD, y0 + 102, PANEL_W - PAD * 2, 18);
            summonProviderDropdown.setDropBottom(top + PANEL_H - 2);
        }
        // OPTIONAL voice — first item = 无(静音), entries follow (same pattern as the
        // persona pick above); an empty library shows no dropdown, just a hint.
        var voiceEntries = com.dwinovo.numen.client.voice.VoiceLibrary.instance().list();
        if (!voiceEntries.isEmpty()) {
            List<Dropdown.Item> voiceItems = new ArrayList<>();
            voiceItems.add(new Dropdown.Item(VOICE_NONE, I18n.get(ModLanguageData.Keys.VOICE_BIND_NONE)));
            for (var e : voiceEntries) {
                voiceItems.add(new Dropdown.Item(e.id(), e.name()));
            }
            summonVoiceDropdown = new Dropdown(voiceItems, summonVoiceId == null ? VOICE_NONE : summonVoiceId);
            // 声线行与皮肤下拉平分一行(左声线右皮肤),不再新占一行。
            summonVoiceDropdown.setBounds(left + PAD, y0 + 136, summonHalfW(), 18);
            summonVoiceDropdown.setDropBottom(top + PANEL_H - 2);
        }
        // 皮肤:默认(按名字找同名正版) + 皮肤库里已签名的条目。
        List<Dropdown.Item> skinItems = new ArrayList<>();
        skinItems.add(new Dropdown.Item(SKIN_DEFAULT, I18n.get(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT)));
        for (var e : com.dwinovo.numen.client.skin.SkinLibrary.instance().list()) {
            if (e.signed()) skinItems.add(new Dropdown.Item(e.id(), e.name()));
        }
        summonSkinDropdown = new Dropdown(skinItems, summonSkinId == null ? SKIN_DEFAULT : summonSkinId);
        summonSkinDropdown.setBounds(left + PAD + summonHalfW() + 6, y0 + 136, summonHalfW(), 18);
        summonSkinDropdown.setDropBottom(top + PANEL_H - 2);
        // Explicit actions — Enter stays as the fallback confirm (keyPressed), the
        // buttons are the primary path.
        int bw = 64, gap = 8, totalW = bw * 2 + gap;
        int bx = left + (PANEL_W - totalW) / 2;
        add(new SimpleButton(bx, y0 + 162, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { summoning = false; rebuild(); }));
        add(new SimpleButton(bx + bw + gap, y0 + 162, bw, 18,
                Component.translatable(ModLanguageData.Keys.SUMMON_CREATE),
                b -> doSummon()));
        setInitialFocus(summonInput);
    }

    /** 召唤页"声线|皮肤"共享行的半宽。build 与 render 共用。 */
    private int summonHalfW() {
        return (PANEL_W - PAD * 2 - 6) / 2;
    }

    /**
     * 召唤页四个下拉的点击路由:正展开的先吃(它的列表画在最上层,命中也必须
     * 最优先),然后按行序。返回 true = 消费了本次点击。
     */
    private boolean routeSummonDropdownClick(double mx, double my) {
        Dropdown[] all = {summonPersonaDropdown, summonProviderDropdown,
                summonVoiceDropdown, summonSkinDropdown};
        Dropdown open = null;
        for (Dropdown d : all) {
            if (d != null && d.isOpen()) { open = d; break; }
        }
        for (Dropdown d : (open != null ? new Dropdown[]{open} : all)) {
            if (d == null || !d.mouseClicked(mx, my)) continue;
            String sel = d.selectedId();
            if (d == summonPersonaDropdown) {
                summonPersonaId = PERSONA_DEFAULT.equals(sel) ? null : sel;
            } else if (d == summonProviderDropdown) {
                summonProviderId = sel;
            } else if (d == summonVoiceDropdown) {
                summonVoiceId = VOICE_NONE.equals(sel) ? null : sel;
            } else {
                summonSkinId = SKIN_DEFAULT.equals(sel) ? null : sel;
            }
            return true;
        }
        // 有列表展开时,点到列表外 = 收起并消费(mouseClicked 已处理);点到这里
        // 说明没有任何下拉消费——放行给后面的命中。
        return false;
    }

    /** 召唤页四个下拉的渲染:收起的先画,正展开的最后画(列表压在一切之上)。 */
    private void renderSummonDropdowns(GuiGraphics g, int mouseX, int mouseY) {
        Dropdown[] all = {summonSkinDropdown, summonVoiceDropdown,
                summonProviderDropdown, summonPersonaDropdown};
        Dropdown open = null;
        for (Dropdown d : all) {
            if (d == null) continue;
            if (d.isOpen() && open == null) { open = d; continue; }
            d.render(g, font, mouseX, mouseY);
        }
        if (open != null) {
            open.render(g, font, mouseX, mouseY);
        }
    }

    /** Two buttons for the "delete companion?" confirm bar — Cancel and the destructive Delete. */
    private void buildDismissConfirm() {
        UUID target = dismissPending;
        int bw = 64, gap = 8, totalW = bw * 2 + gap;
        int bx = left + (PANEL_W - totalW) / 2;
        int by = top + HEADER_H + 52;
        add(new SimpleButton(bx, by, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { dismissPending = null; rebuild(); }));
        add(new SimpleButton(bx + bw + gap, by, bw, 18, Component.translatable("numen.dismiss.delete"), b -> {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.DismissRequestPayload(target));
            dismissPending = null;
            if (target.equals(uuid)) {                       // active one is leaving — jump to another / empty
                NumenRoster.Entry next = firstOther(target);
                if (next != null) { switchTo(next.uuid(), next.name()); return; }
                uuid = null; name = null;
            }
            rebuild();
        }));
    }

    /** First roster companion that isn't {@code exclude}, or null if none. */
    private NumenRoster.Entry firstOther(UUID exclude) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (!e.uuid().equals(exclude)) return e;
        }
        return null;
    }

    private String nameFor(UUID u) {
        for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
            if (e.uuid().equals(u)) return e.name();
        }
        return "?";
    }

    /** Register a widget for EVENTS only; it's rendered manually (on top of the panel) in {@link
     *  #render}. */
    private <T extends AbstractWidget> T add(T w) {
        addWidget(w);
        overlay.add(w);
        return w;
    }

    // Shadowless text — BlockFrame is flat, and a drop shadow on DARK text over a LIGHT ground makes
    // the glyph merge with its own shadow ("smudged"). This build's shadowless path ignores the colour
    // PARAM, so we bake the colour into the text's Style instead.
    private void txt(GuiGraphics g, Component c, int x, int y, int color) {
        g.drawString(font, c.copy().withStyle(s -> s.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF))), x, y, -1, false);
    }

    /** The FormattedCharSequence must already carry its colour (see {@link #colored}). */
    private void txt(GuiGraphics g, FormattedCharSequence c, int x, int y, int color) {
        g.drawString(font, c, x, y, -1, false);
    }

    /** A coloured text Component (colour in the Style, so shadowless rendering keeps it). */
    private static Component colored(String s, int color) {
        return Component.literal(s).withStyle(st -> st.withColor(
                net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF)));
    }


    private void buildChatWidgets() {
        int inputY = top + PANEL_H - INPUT_H - PAD;
        int compactW = 26;
        int sendW = 42;
        int stopW = 22;
        int inX = left + PAD + compactW + 4;
        int inW = PANEL_W - PAD * 2 - compactW - sendW - stopW - 12;

        compactButton = add(new SimpleButton(left + PAD, inputY, compactW, INPUT_H,
                Component.literal("⤬"), b -> loop().requestCompact()));
        compactButton.active = loop().canCompact();

        input = new FlatEditBox(font, inX + FIELD_INSET_X, inputY + FIELD_INSET_Y,
                inW - FIELD_INSET_X * 2, INPUT_H - FIELD_INSET_Y * 2, Component.literal("numen.chat.input"));
        input.setMaxLength(MAX_PROMPT);
        input.setBordered(false);
        input.setTextColor(TXT);
        // FlatEditBox draws the hint shadowless and UNDER the caret (same widget pass), so use it
        // directly — no separate screen-side placeholder that would paint over the blinking caret.
        // Faint colour is baked into the Component's Style.
        input.setHint(Nb.colored(I18n.get("numen.chat.hint", name == null ? "" : name), TXT_FAINT));
        if (!savedInput.isEmpty()) { input.setValue(savedInput); savedInput = ""; }
        add(input);
        setInitialFocus(input);

        sendButton = add(new SimpleButton(inX + inW + 4, inputY, sendW, INPUT_H,
                Component.translatable("numen.chat.send"), b -> onSend()));

        stopButton = add(new SimpleButton(inX + inW + 4 + sendW + 4, inputY, stopW, INPUT_H,
                Component.literal("■"), b -> loop().abort()));
        stopButton.active = loop().canInterrupt();
    }

    private void selectTab(Tab t) {
        if (t == tab) return;
        tab = t;
        scroll = 0;
        pinBottom = true;
        if (t == Tab.ITEMS) requestInventory();
        if (t == Tab.SETTINGS) initModelMode();
        rebuild();
    }

    /** Decide once (on entering Settings) whether the model field starts as a preset dropdown or a
     *  custom text box: custom-provider or a configured model that isn't a known preset → custom. */
    private void initModelMode() {
        INumenConfig cfg = Services.CONFIG;
        wProvider = cfg.getProvider() == null ? "openai" : cfg.getProvider();
        wApiKey = cfg.getApiKey() == null ? "" : cfg.getApiKey();
        wModel = cfg.getModel() == null ? "" : cfg.getModel();
        wBaseUrl = cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl();
        wProxy = cfg.getProxy() == null ? "" : cfg.getProxy();
        wReasoning = normalizeReasoning(cfg.getReasoningEffort());
        addingSite = false;
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvider));
        boolean known = mp != null && mp.models().stream().anyMatch(m -> m.id().equals(wModel));
        customModel = (mp != null && mp.custom()) || (!wModel.isBlank() && !known);
    }

    /** Snapshot the API-key + base-URL fields before a settings rebuild so the edits survive it. */
    private void preserveKeyUrl() {
        if (apiKeyInput != null) wApiKey = apiKeyInput.getValue();
        if (baseUrlInput != null) wBaseUrl = baseUrlInput.getValue();
        if (proxyInput != null) wProxy = proxyInput.getValue();
    }

    // ---- settings tab (config hub: sub-nav + section) ----

    private static final int SET_SP = 33;     // LLM-section row pitch (5 rows + Save must fit)
    private static final int NAV_W = 74;      // left sub-nav column width
    private static final int NAV_SP = 20;     // sub-nav row pitch
    private static final int LIST_ROW = 22;   // MCP / skill list row height

    /** Left x of the section content area (right of the sub-nav column + divider). */
    private int secX() { return left + PAD + NAV_W + 8; }
    /** Width of the section content area. */
    private int secW() { return PANEL_W - PAD - NAV_W - 8 - PAD; }
    /** Top y of section content (below the header). */
    private int secY0() { return top + HEADER_H + 8; }
    /** Bottom y a list row may reach. */
    private int secBottom() { return top + PANEL_H - PAD; }

    private void selectSection(SettingsSection s) {
        if (s == settingsSection) return;
        settingsSection = s;
        settingsScroll = 0;
        if (s == SettingsSection.PERSONA) {
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
        providerDeletePending = null;
        addingVoice = false;
        voiceEditId = null;
        voiceDeletePending = null;
        voiceTestGen++;   // 离开语音表单:在途试听回调作废
        addingSkin = false;
        skinEditId = null;
        skinDeletePending = null;
        skinFormGen++;    // 离开皮肤表单:在途 MineSkin 签名回调作废
        rebuild();
    }

    /** Dispatch widget building by the active section (skill/MCP lists render manually). */
    private void buildSettingsWidgets() {
        switch (settingsSection) {
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
                if (providerDeletePending != null) buildProviderDeleteConfirm();
                else if (addingProvider) buildProviderForm();
                else buildProviderListWidgets();
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
            case PROXY -> buildProxyWidgets();
        }
    }

    // ---- Proxy section: the global network proxy, its own tab (IP + port) ----

    private void buildProxyWidgets() {
        int x = secX(), w = secW();
        int fy = secY0();
        String cur = Services.CONFIG.getProxy() == null ? "" : Services.CONFIG.getProxy().trim();
        String ip = "", port = "";
        int colon = cur.lastIndexOf(':');
        if (colon > 0) { ip = cur.substring(0, colon); port = cur.substring(colon + 1); }
        else ip = cur;
        // One row below the section title (only two rows here — space is plentiful).
        proxyIpInput = field(x, fy + 25, w, 64, ip);
        proxyPortInput = field(x, fy + 25 + SET_SP, w, 8, port);
        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> {
                    String i = proxyIpInput.getValue().trim();
                    String p = proxyPortInput.getValue().trim();
                    Services.CONFIG.setProxy(i.isEmpty() ? "" : (p.isEmpty() ? i : i + ":" + p));
                    Services.CONFIG.save();
                    NumenLlmClient.reset();
                    savedFlashUntil = System.currentTimeMillis() + 1500;
                }));
    }

    private void renderProxySection(GuiGraphics g) {
        int x = secX();
        int fy = secY0();
        txt(g, Component.translatable("numen.settings.proxy"), x, fy - 2, TXT);
        txt(g, Component.translatable(ModLanguageData.Keys.SETTINGS_PROXY_IP), x, fy + 14, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.SETTINGS_PROXY_PORT), x, fy + 14 + SET_SP, TXT_MUTED);
        if (savedFlashUntil > System.currentTimeMillis()) {
            txt(g, Component.translatable("numen.settings.saved"), x, top + PANEL_H - PAD - 14, OK);
        }
    }

    // ---- Provider section: the library of named LLM provider configs companions select from ----

    private void buildProviderListWidgets() {
        add(new SimpleButton(left + PANEL_W - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable(ModLanguageData.Keys.PROVIDER_ADD), b -> {
                    addingProvider = true; providerEditId = null;
                    wProvName = ""; wProvProvider = ""; wProvModel = ""; wProvKey = ""; wProvBaseUrl = "";
                    rebuild();
                }));
    }

    /**
     * The model-config form: 名称 → 提供商 (the live provider catalog, reused) →
     * 模型 + Base URL, both ADAPTIVE to the picked provider (its model list / site
     * default URL, editable) → API Key. Saving yields a complete config.
     */
    private void buildProviderForm() {
        int x = secX(), w = secW();
        int fy = secY0();
        provNameInput = field(x, fy + 11, w, 48, wProvName);
        // Provider picker — same catalog as everywhere else (built-ins + user sites),
        // no "+add site" row here. Blank state (fresh form) starts on the first entry
        // and adapts model/baseUrl to it.
        if (wProvProvider == null || wProvProvider.isBlank()) {
            adaptToProvider(LlmProviders.all().isEmpty() ? "" : LlmProviders.all().get(0).id());
        }
        provProviderDropdown = new ProviderDropdown(wProvProvider, false);
        provProviderDropdown.setBounds(x, fy + 11 + SET_SP, w, 18);
        provProviderDropdown.setDropBottom(top + PANEL_H - 2);
        // Model row: the provider's known models as a dropdown (+ 自定义 → free text),
        // free text only for custom providers.
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvProvider));
        boolean providerCustom = mp != null && mp.custom();
        if (provCustomModel || providerCustom || mp == null || mp.models().isEmpty()) {
            provModelDropdown = null;
            provModelInput = field(x, fy + 11 + 2 * SET_SP, providerCustom || mp == null ? w : w - 20, 128, wProvModel);
            if (!providerCustom && mp != null && !mp.models().isEmpty()) {
                add(new SimpleButton(x + w - 18, fy + 11 + 2 * SET_SP, 18, 18, Component.literal("▾"),
                        b -> { preserveProviderForm(); provCustomModel = false; rebuild(); }));
            }
        } else {
            provModelInput = null;
            boolean known = mp.models().stream().anyMatch(m -> m.id().equals(wProvModel));
            String sel = known ? wProvModel : mp.models().get(0).id();
            provModelDropdown = new Dropdown(modelItems(mp), sel);
            provModelDropdown.setBounds(x, fy + 11 + 2 * SET_SP, w, 18);
            provModelDropdown.setDropBottom(top + PANEL_H - 2);
        }
        provKeyInput = field(x, fy + 11 + 3 * SET_SP, w, 256, wProvKey);
        provBaseUrlInput = field(x, fy + 11 + 4 * SET_SP, w, 256, wProvBaseUrl);
        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveProvider()));
        add(new SimpleButton(left + PANEL_W - PAD - 64 - 22, top + PANEL_H - PAD - 18, 18, 18,
                Component.literal("✕"), b -> { addingProvider = false; providerEditId = null; rebuild(); }));
        setInitialFocus(provNameInput);
    }

    /** Provider changed (or fresh form): adapt model + Base URL to the pick —
     *  the site's default URL and its first model, both still editable. */
    private void adaptToProvider(String providerId) {
        wProvProvider = providerId;
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(providerId));
        provCustomModel = mp != null && mp.custom();
        wProvModel = (mp != null && !mp.models().isEmpty()) ? mp.models().get(0).id() : "";
        wProvBaseUrl = LlmProviders.byId(providerId).defaultBaseUrl();
    }

    /** Keep typed values across a rebuild (mirror of the persona/MCP form preserves). */
    private void preserveProviderForm() {
        if (provNameInput != null) wProvName = provNameInput.getValue();
        if (provKeyInput != null) wProvKey = provKeyInput.getValue();
        if (provBaseUrlInput != null) wProvBaseUrl = provBaseUrlInput.getValue();
        if (provModelInput != null) wProvModel = provModelInput.getValue();
        else if (provModelDropdown != null && !CUSTOM_MODEL.equals(provModelDropdown.selectedId())) {
            wProvModel = provModelDropdown.selectedId();
        }
    }

    private void buildProviderDeleteConfirm() {
        int x = secX();
        int by = secY0() + 24;
        int bw = 64, gap = 8;
        add(new SimpleButton(x, by, bw, 18, Component.translatable("numen.dismiss.delete"), b -> {
            com.dwinovo.numen.agent.llm.ProviderLibrary.instance().remove(providerDeletePending);
            providerDeletePending = null;
            rebuild();
        }));
        add(new SimpleButton(x + bw + gap, by, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { providerDeletePending = null; rebuild(); }));
    }

    private void onSaveProvider() {
        String name = provNameInput.getValue().trim();
        if (name.isEmpty()) { warnUntil = System.currentTimeMillis() + 4000; return; }
        var lib = com.dwinovo.numen.agent.llm.ProviderLibrary.instance();
        String provider = provProviderDropdown != null ? provProviderDropdown.selectedId() : wProvProvider;
        String model = provModelInput != null ? provModelInput.getValue().trim()
                : (provModelDropdown != null && !CUSTOM_MODEL.equals(provModelDropdown.selectedId())
                        ? provModelDropdown.selectedId() : "");
        String key = provKeyInput.getValue().trim();
        String baseUrl = provBaseUrlInput.getValue().trim();
        if (providerEditId != null) {
            var old = lib.get(providerEditId);
            lib.update(new com.dwinovo.numen.agent.llm.ProviderLibrary.Entry(
                    providerEditId, name, provider, model, key, baseUrl,
                    old != null ? old.reasoningEffort() : ""));
        } else {
            lib.create(name, provider, model, key, baseUrl, "");
        }
        addingProvider = false;
        providerEditId = null;
        provCustomModel = false;
        wProvName = ""; wProvProvider = ""; wProvModel = ""; wProvKey = ""; wProvBaseUrl = "";
        rebuild();
    }

    // ---- Voice section: the library of named TTS voices companions bind to (mirrors the provider section) ----

    /** Voice form row pitch — 7 field rows + the Save row must fit, so tighter than SET_SP
     *  (labels ride inside the fields as placeholders instead of taking their own rows). */
    /** 试听用的固定测试句(按当前表单参数就地合成)。 */
    private static final String VOICE_TEST_SENTENCE = "你好,我是你的同伴,这是我的声音。";

    private void buildVoiceListWidgets() {
        add(new SimpleButton(left + PANEL_W - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable(ModLanguageData.Keys.VOICE_ADD), b -> {
                    addingVoice = true; voiceEditId = null;
                    resetVoiceForm();
                    rebuild();
                }));
        // 当前同伴的声线绑定下拉(有同伴且库非空时;首项 = 无(静音))。
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
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
        int x = secX(), w = secW();
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
        voiceBackendDropdown.setDropBottom(top + PANEL_H - 2);
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
        add(test);
        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveVoice()));
        add(new SimpleButton(left + PANEL_W - PAD - 64 - 22, top + PANEL_H - PAD - 18, 18, 18,
                Component.literal("✕"), b -> {
                    addingVoice = false; voiceEditId = null; voiceTestGen++;
                    rebuild();
                }));
        if (voiceNameInput.visible) {
            setInitialFocus(voiceNameInput);
        }
    }

    /** 表单第 {@code row} 行输入框的 y(标题画在其上方 11px);随滚动偏移。 */
    private int voiceVy(int row) {
        return secY0() + 11 + row * SET_SP - voiceFormScroll;
    }

    /** 第 {@code row} 行(标题+输入框)完整落在视口内? */
    private boolean voiceRowVisible(int row) {
        int y = voiceVy(row);
        return y - 11 >= secY0() - 2 && y + 18 <= voiceFormBottom();
    }

    /** 表单视口底:保存行与状态行的上沿。 */
    private int voiceFormBottom() {
        return top + PANEL_H - PAD - 20;
    }

    /** 当前选型的总行数:名称/提供商/URL 三行 + 各后端专属行 + 音量行。 */
    private int voiceFormRowCount() {
        return com.dwinovo.numen.client.voice.VoiceLibrary.BACKEND_MINIMAX.equals(wVoiceBackend) ? 8 : 7;
    }

    private int maxVoiceFormScroll() {
        int content = 11 + (voiceFormRowCount() - 1) * SET_SP + 18 + 2;
        return Math.max(0, content - (voiceFormBottom() - secY0()));
    }

    /** 出视口的行隐藏(不可见的 EditBox 既不渲染也不接输入)。 */
    private EditBox vclip(EditBox f, int row) {
        boolean vis = voiceRowVisible(row);
        f.visible = vis;
        f.active = vis;
        return f;
    }

    private void buildVoiceDeleteConfirm() {
        int x = secX();
        int by = secY0() + 24;
        int bw = 64, gap = 8;
        add(new SimpleButton(x, by, bw, 18, Component.translatable("numen.dismiss.delete"), b -> {
            com.dwinovo.numen.client.voice.VoiceLibrary.instance().remove(voiceDeletePending);
            voiceDeletePending = null;
            rebuild();
        }));
        add(new SimpleButton(x + bw + gap, by, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { voiceDeletePending = null; rebuild(); }));
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
            if (uuid != null) {
                lib.assign(uuid, created.id());
            }
        }
        addingVoice = false;
        voiceEditId = null;
        voiceTestGen++;
        resetVoiceForm();
        rebuild();
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
            voiceNote(I18n.get(ModLanguageData.Keys.VOICE_TEST_FAIL, clip(why, secW() - 10)), true);
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
                    voiceNote(I18n.get(ModLanguageData.Keys.VOICE_TEST_FAIL, clip(why, secW() - 10)), true);
                    return;
                }
                var sm = Minecraft.getInstance().getSoundManager();
                if (voicePreview != null) sm.stop(voicePreview);   // 重听:停掉上一句
                voicePreview = Services.VOICE.previewVoice(audio, 1.0f);   // 响度已烙进 PCM;平台工厂:取数机制两侧不同
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
        int x = secX(), w = secW();
        var lib = com.dwinovo.numen.client.voice.VoiceLibrary.instance();
        if (!addingVoice) {
            txt(g, Component.translatable(ModLanguageData.Keys.VOICE_TITLE), x, secY0() - 2, TXT);
        }
        if (voiceDeletePending != null) {
            var e = lib.get(voiceDeletePending);
            txt(g, Component.translatable(ModLanguageData.Keys.VOICE_DELETE_CONFIRM, e != null ? e.name() : ""),
                    x, secY0() + 10, TXT);
            return;
        }
        if (addingVoice) {
            // 表单本体是占位符自述的字段 + 自标注的类型按钮;这里只画状态行。
            if (voiceMsg != null && voiceMsgUntil > System.currentTimeMillis()) {
                txt(g, Component.literal(clip(voiceMsg, w - 94)), x, top + PANEL_H - PAD - 14,
                        voiceMsgFail ? FAIL : OK);
            }
            return;
        }
        // 列表视图:全局总开关(标题行右侧,新建按钮左边)。
        int togX = x + w - 64 - 10 - TOG_W;
        String onLabel = I18n.get(ModLanguageData.Keys.VOICE_ENABLED);
        txt(g, Component.literal(onLabel), togX - font.width(onLabel) - 4, secY0() - 1, TXT_MUTED);
        drawToggle(g, togX, secY0() - 2, lib.enabled());
        var list = lib.list();
        if (list.isEmpty()) {
            txt(g, Component.translatable(ModLanguageData.Keys.VOICE_EMPTY), x, secY0() + 16, TXT_FAINT);
            return;
        }
        int listY0 = voiceListY0();
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        settingsScroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        String bound = uuid != null ? lib.assignedEntry(uuid) : null;
        for (int i = settingsScroll; i < list.size(); i++) {
            int ry = listY0 + (i - settingsScroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var e = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            int tx = x;
            if (uuid != null) {
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
            txt(g, Component.literal(clip(meta, w - 30 - (tx - x))), tx, ry + 11, TXT_FAINT);
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
            if (overDelete(mx, my, delX, ry)) { voiceDeletePending = e.id(); rebuild(); return true; }
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
        rebuild();
    }

    private static String nv(String s) {
        return s == null ? "" : s;
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
        add(new SimpleButton(left + PANEL_W - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable("numen.persona.add"), b -> {
                    addingPersona = true; personaEditId = null;
                    wPersonaName = ""; wPersonaText = "";
                    rebuild();
                }));
    }

    private void buildPersonaForm() {
        int x = secX(), w = secW();
        int fy = secY0() + 14;
        personaNameInput = field(x, fy + 11, w, 48, wPersonaName);
        // Roomy multi-line editor for the persona description (a paragraph, not one line): from below the
        // name field down to just above the Save row.
        int ty = fy + 44;
        int th = (top + PANEL_H - PAD - 22) - ty;
        personaTextArea = new net.minecraft.client.gui.components.MultiLineEditBox(
                font, x, ty, w, th,
                Component.translatable("numen.persona.text_placeholder"), Component.empty());
        personaTextArea.setValue(wPersonaText);
        personaTextArea.setCharacterLimit(4096);
        add(personaTextArea);
        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSavePersona()));
        add(new SimpleButton(left + PANEL_W - PAD - 64 - 22, top + PANEL_H - PAD - 18, 18, 18,
                Component.literal("✕"), b -> { addingPersona = false; personaEditId = null; rebuild(); }));
        setInitialFocus(personaNameInput);
    }

    private void buildPersonaDeleteConfirm() {
        int x = secX();
        int by = secY0() + 24;
        int bw = 64, gap = 8;
        add(new SimpleButton(x, by, bw, 18, Component.translatable("numen.dismiss.delete"), b -> {
            com.dwinovo.numen.persona.PersonaLibrary.instance().remove(personaDeletePending);
            personaDeletePending = null;
            rebuild();
        }));
        add(new SimpleButton(x + bw + gap, by, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { personaDeletePending = null; rebuild(); }));
    }

    private void onSavePersona() {
        String name = personaNameInput.getValue().trim();
        String text = personaTextArea == null ? "" : personaTextArea.getValue().trim();
        if (name.isEmpty() || text.isEmpty()) { warnUntil = System.currentTimeMillis() + 4000; return; }
        var lib = com.dwinovo.numen.persona.PersonaLibrary.instance();
        if (personaEditId != null) {
            PersonaLibrary.Persona old = lib.get(personaEditId);
            String oldName = old != null ? old.name() : null;
            lib.update(personaEditId, name, text);
            // Propagate the edit to any loaded companion currently using this persona: a live switch with
            // a reconciliation message (match by library id, or by the old name for pre-id companions).
            for (UUID cu : AgentLoopRegistry.loadedEntityUuids()) {
                EntityAgentLoop l = AgentLoopRegistry.get(cu).orElse(null);
                if (l == null) continue;
                boolean uses = personaEditId.equals(l.personaId())
                        || (l.personaId() == null && oldName != null && oldName.equals(l.personaName()));
                if (uses) l.setPersona(personaEditId, text, name);
            }
        } else {
            lib.create(name, text);
        }
        addingPersona = false;
        personaEditId = null;
        wPersonaName = ""; wPersonaText = "";
        rebuild();
    }

    private void buildMcpDeleteConfirm() {
        int x = secX();
        int by = secY0() + 24;
        int bw = 64, gap = 8;
        add(new SimpleButton(x, by, bw, 18, Component.translatable("numen.dismiss.delete"), b -> {
            com.dwinovo.numen.mcp.client.McpClientManager.deleteServer(mcpDeletePending);
            mcpDeletePending = null;
            rebuild();
        }));
        add(new SimpleButton(x + bw + gap, by, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { mcpDeletePending = null; rebuild(); }));
    }

    private void buildMcpListWidgets() {
        // "add server" affordance, top-right of the section.
        add(new SimpleButton(left + PANEL_W - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable("numen.mcp.add"), b -> {
                    addingMcp = true; mcpEditOriginal = null;                 // fresh add — not editing
                    wMcpName = ""; wMcpTarget = ""; wMcpHeader = ""; mcpStdio = false;
                    rebuild();
                }));
    }

    /** The add-MCP-server form: name, type (http/stdio) toggle, and URL / command. */
    private void buildMcpForm() {
        int x = secX(), w = secW();
        int fy = secY0() + 14;     // start below the "MCP 工具" title so nothing overlaps
        mcpNameInput = field(x, fy + 11, w, 48, wMcpName);
        // type toggle button (cycles http ↔ stdio; rebuild swaps the URL/command row)
        add(new SimpleButton(x, fy + 34, w, 18,
                Component.translatable(mcpStdio ? "numen.mcp.type_stdio" : "numen.mcp.type_http"),
                b -> { preserveMcpForm(); mcpStdio = !mcpStdio; rebuild(); }));
        mcpTargetInput = field(x, fy + 67, w, 512, wMcpTarget);
        // 4th field: HTTP → request header(s) "Name: Value"; stdio → env "KEY=value" (';'-separated).
        mcpHeaderInput = field(x, fy + 100, w, 1024, wMcpHeader);
        // Save + Cancel
        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveMcp()));
        add(new SimpleButton(left + PANEL_W - PAD - 64 - 22, top + PANEL_H - PAD - 18, 18, 18,
                Component.literal("✕"), b -> { addingMcp = false; mcpEditOriginal = null; rebuild(); }));
        setInitialFocus(mcpNameInput);   // ready to type the name immediately
    }

    private void preserveMcpForm() {
        if (mcpNameInput != null) wMcpName = mcpNameInput.getValue();
        if (mcpTargetInput != null) wMcpTarget = mcpTargetInput.getValue();
        if (mcpHeaderInput != null) wMcpHeader = mcpHeaderInput.getValue();
    }

    private void onSaveMcp() {
        String name = mcpNameInput.getValue().trim();
        String target = mcpTargetInput.getValue().trim();
        if (name.isEmpty() || target.isEmpty()) { warnUntil = System.currentTimeMillis() + 4000; return; }
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
        rebuild();
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
        add(new SimpleButton(left + PANEL_W - PAD - 64, secY0() - 2, 64, 14,
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

    // ==== DEAD CODE — the legacy 模型接入 section was removed from the nav (2026-07-14;
    // the 提供商 library replaces it). buildLlmWidgets / onSaveSettings / buildModelRow /
    // buildApiKeyRow / renderLlmSettings and their dropdown click blocks are unreachable;
    // delete wholesale in a dedicated cleanup commit. ====
    private void buildLlmWidgets() {
        int x = secX(), w = secW();
        int y0 = secY0();

        if (addingSite) {
            // The provider picker is stale in add-site mode (it still holds the "+ 添加站点" sentinel and
            // its bounds overlap the site-name field, stealing that field's clicks so the name can never be
            // typed → Save early-returns). Drop it entirely while the add-site form is up.
            providerDropdown = null;
            // row0: site name + cancel
            siteNameInput = field(x, y0 + 11, w - 20, 64, wSiteName);
            add(new SimpleButton(x + w - 18, y0 + 11, 18, 18, Component.literal("✕"),
                    b -> { addingSite = false; rebuild(); }));
            buildApiKeyRow(x, y0 + SET_SP + 11, w);
            modelInput = field(x, y0 + 2 * SET_SP + 11, w, 128, wModel);
            baseUrlInput = field(x, y0 + 3 * SET_SP + 11, w, 256, wBaseUrl);
            setInitialFocus(siteNameInput);   // ready to type the name immediately (no click needed)
        } else {
            providerDropdown = new ProviderDropdown(wProvider, true);   // live + "+ 添加站点"
            providerDropdown.setBounds(x, y0 + 11, w, 18);
            providerDropdown.setDropBottom(top + PANEL_H - 2);
            buildApiKeyRow(x, y0 + SET_SP + 11, w);
            buildModelRow(x, y0 + 2 * SET_SP + 11, w);
            baseUrlInput = field(x, y0 + 3 * SET_SP + 11, w, 256, wBaseUrl);
            proxyInput = field(x, y0 + 4 * SET_SP + 11, w, 128, wProxy);
            // Reasoning/thinking effort cycle — a compact button in the bottom band, left of Save.
            add(new SimpleButton(x, top + PANEL_H - PAD - 18, 118, 18, reasoningLabel(),
                    b -> { cycleReasoning(); b.setMessage(reasoningLabel()); }));
        }

        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18,
                64, 18, Component.translatable("numen.gui.settings.save"), b -> onSaveSettings()));
    }

    private void buildApiKeyRow(int x, int y, int w) {
        int eyeW = 22;
        apiKeyInput = field(x, y, w - eyeW - 2, 512, wApiKey);
        // Show the real key while editing (focused) or when revealed via the eye — masking with a
        // fixed "•" mis-sizes against the variable-width font, so a long key drifts the caret and
        // leaves gaps while typing. When unfocused + hidden, mask it for shoulder-surfing.
        apiKeyInput.setFormatter((text, idx) -> (showKey || (apiKeyInput != null && apiKeyInput.isFocused()))
                ? FormattedCharSequence.forward(text, net.minecraft.network.chat.Style.EMPTY)
                : FormattedCharSequence.forward("•".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
        // Eye icon instead of a 见/隐 glyph: open eye when masked (click to show), slashed when shown.
        add(new SimpleButton(x + w - eyeW, y, eyeW, 18, Component.empty(),
                b -> { showKey = !showKey; ((SimpleButton) b).icon(showKey ? EYE_OFF : EYE); })
                .icon(showKey ? EYE_OFF : EYE));
    }

    /** Model row: a preset dropdown for the provider's known models, or a free-text box (custom mode)
     *  with a "▾" toggle back to presets. A custom provider (openai-compatible) is always free-text. */
    private void buildModelRow(int x, int y, int w) {
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(providerDropdown.selectedId()));
        boolean providerCustom = mp != null && mp.custom();
        if (customModel || providerCustom) {
            customModel = true;
            modelDropdown = null;
            modelInput = field(x, y, providerCustom ? w : w - 20, 128, wModel);
            if (!providerCustom) {     // a way back to the preset list (custom providers have none)
                add(new SimpleButton(x + w - 18, y, 18, 18, Component.literal("▾"),
                        b -> { preserveKeyUrl(); customModel = false; rebuild(); }));
            }
        } else {
            modelInput = null;
            boolean known = mp != null && mp.models().stream().anyMatch(m -> m.id().equals(wModel));
            String sel = known ? wModel
                    : (mp != null && !mp.models().isEmpty() ? mp.models().get(0).id() : CUSTOM_MODEL);
            modelDropdown = new Dropdown(modelItems(mp), sel);
            modelDropdown.setBounds(x, y, w, 18);
            modelDropdown.setDropBottom(top + PANEL_H - 2);
        }
    }

    private List<Dropdown.Item> modelItems(ModelRegistry.Provider mp) {
        List<Dropdown.Item> items = new ArrayList<>();
        if (mp != null) for (ModelRegistry.Model m : mp.models()) items.add(new Dropdown.Item(m.id(), m.id()));
        items.add(new Dropdown.Item(CUSTOM_MODEL, I18n.get("numen.settings.custom_model")));
        return items;
    }

    /** Shadowless placeholder for an empty, unfocused field — the EditBox's own hint renders with a shadow. */
    private void placeholder(GuiGraphics g, EditBox f, String text) {
        if (f != null && f.visible && f.getValue().isEmpty() && !f.isFocused()
                && text != null && !text.isEmpty()) {
            txt(g, Component.literal(text), f.getX(), f.getY(), TXT_FAINT);
        }
    }

    /** 声线表单的行标题:画在该行输入框上方(随滚动偏移,出视口不画)。 */
    private void voiceLabel(GuiGraphics g, int row, String text) {
        if (!voiceRowVisible(row)) return;
        txt(g, Component.literal(text), secX(), voiceVy(row) - 11, TXT_MUTED);
    }

    // ---- Skin section: the named skin library (upload png → MineSkin-signed textures) ----

    private void buildSkinListWidgets() {
        add(new SimpleButton(left + PANEL_W - PAD - 64, secY0() - 2, 64, 14,
                Component.translatable(ModLanguageData.Keys.SKIN_ADD), b -> {
                    addingSkin = true;
                    skinEditId = null;
                    resetSkinForm();
                    rebuild();
                }));
    }

    /**
     * 皮肤表单:名称 + 手臂模型下拉 + 拖拽提示区(png 从系统里拖进游戏窗口,
     * {@link #onFilesDrop} 接住)。保存 = 先 MineSkin 代签再落库,失败红字可重试。
     */
    private void buildSkinForm() {
        int x = secX(), w = secW();
        int fy = secY0();
        skinNameInput = field(x, fy + 11, w, 48, wSkinName);
        skinVariantDropdown = new Dropdown(List.of(
                new Dropdown.Item(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_CLASSIC,
                        I18n.get(ModLanguageData.Keys.SKIN_VARIANT_CLASSIC)),
                new Dropdown.Item(com.dwinovo.numen.client.skin.SkinLibrary.VARIANT_SLIM,
                        I18n.get(ModLanguageData.Keys.SKIN_VARIANT_SLIM))),
                wSkinVariant);
        skinVariantDropdown.setBounds(x, fy + 11 + SET_SP, w, 18);
        skinVariantDropdown.setDropBottom(top + PANEL_H - 2);
        add(new SimpleButton(left + PANEL_W - PAD - 64, top + PANEL_H - PAD - 18, 64, 18,
                Component.translatable("numen.gui.settings.save"), b -> onSaveSkin()));
        add(new SimpleButton(left + PANEL_W - PAD - 64 - 22, top + PANEL_H - PAD - 18, 18, 18,
                Component.literal("✕"), b -> {
                    addingSkin = false;
                    skinEditId = null;
                    skinFormGen++;
                    rebuild();
                }));
        setInitialFocus(skinNameInput);
    }

    private void buildSkinDeleteConfirm() {
        int x = secX();
        int by = secY0() + 24;
        int bw = 64, gap = 8;
        add(new SimpleButton(x, by, bw, 18, Component.translatable("numen.dismiss.delete"), b -> {
            com.dwinovo.numen.client.skin.SkinLibrary.instance().remove(skinDeletePending);
            skinDeletePending = null;
            rebuild();
        }));
        add(new SimpleButton(x + bw + gap, by, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { skinDeletePending = null; rebuild(); }));
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
        rebuild();
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
            rebuild();
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
                        skinNote(I18n.get(ModLanguageData.Keys.SKIN_SIGN_FAIL, clip(why, secW() - 10)), true);
                        return;
                    }
                    com.dwinovo.numen.Constants.LOG.info("[numen-skin] MineSkin 签名成功: {}", name);
                    com.dwinovo.numen.client.skin.SkinLibrary.instance().put(
                            new com.dwinovo.numen.client.skin.SkinLibrary.Entry(
                                    id, name, fVariant, signed.value(), signed.signature()),
                            fPng);
                    addingSkin = false;
                    skinEditId = null;
                    rebuild();
                }));
    }

    private void skinNote(String msg, boolean fail) {
        skinMsg = msg;
        skinMsgFail = fail;
        skinMsgUntil = System.currentTimeMillis() + (fail ? 12000 : 60000);   // 签名中的提示常驻到结果
    }

    private void renderSkinSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        var lib = com.dwinovo.numen.client.skin.SkinLibrary.instance();
        if (!addingSkin) {
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_TITLE), x, secY0() - 2, TXT);
        }
        if (skinDeletePending != null) {
            var e = lib.get(skinDeletePending);
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_DELETE_CONFIRM,
                    e != null ? e.name() : ""), x, secY0() + 10, TXT);
            return;
        }
        if (addingSkin) {
            int fy = secY0();
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_FORM_NAME), x, fy, TXT_MUTED);
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_FORM_VARIANT), x, fy + SET_SP, TXT_MUTED);
            // 拖拽区:提示文字 + 已加载状态(新图优先;编辑态没换图就提示沿用原图)。
            int dy = fy + 2 * SET_SP + 4;
            txt(g, Component.translatable(ModLanguageData.Keys.SKIN_DROP_HINT), x, dy, TXT_FAINT);
            if (skinDropped != null) {
                txt(g, Component.translatable(ModLanguageData.Keys.SKIN_LOADED,
                        skinDroppedW + "x" + skinDroppedH), x, dy + 12, OK);
            } else if (skinEditId != null) {
                txt(g, Component.translatable(ModLanguageData.Keys.SKIN_KEEP_OLD), x, dy + 12, TXT_FAINT);
            }
            if (skinMsg != null && skinMsgUntil > System.currentTimeMillis()) {
                txt(g, Component.literal(clip(skinMsg, w - 94)), x, top + PANEL_H - PAD - 14,
                        skinMsgFail ? FAIL : OK);
            }
            // 手臂模型下拉最后画(展开列表压在下方文字上)。
            if (skinVariantDropdown != null) {
                skinVariantDropdown.render(g, font, mouseX, mouseY);
            }
            return;
        }
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
            txt(g, Component.literal(clip(meta, w - 50)), tx, ry + 11, e.signed() ? TXT_FAINT : FAIL);
            txt(g, Component.literal("✎"), editX, ry + 6,
                    overDelete(mouseX, mouseY, editX, ry) ? CTA : TXT_FAINT);
            txt(g, Component.literal("✕"), delX, ry + 6,
                    overDelete(mouseX, mouseY, delX, ry) ? FAIL : TXT_FAINT);
        }
    }

    private boolean skinClick(int mx, int my) {
        if (skinDeletePending != null) return false;
        if (addingSkin) {
            // 手臂模型下拉先于其它命中(展开列表覆盖在表单文字上)。
            if (skinVariantDropdown != null && skinVariantDropdown.mouseClicked(mx, my)) {
                wSkinVariant = skinVariantDropdown.selectedId();
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
            if (overDelete(mx, my, delX, ry)) { skinDeletePending = e.id(); rebuild(); return true; }
            if (overRow(mx, my, x, w, ry)) { beginEditSkin(e); return true; }
        }
        return false;
    }

    /** 皮肤 png 从系统拖进游戏窗口(表单打开时)。64×64 或旧版 64×32。 */
    @Override
    public void onFilesDrop(List<java.nio.file.Path> paths) {
        if (!(tab == Tab.SETTINGS && settingsSection == SettingsSection.SKIN && addingSkin)) return;
        for (java.nio.file.Path p : paths) {
            if (!p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) continue;
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
                    return;
                }
            } catch (java.io.IOException | RuntimeException ex) {
                skinNote(I18n.get(ModLanguageData.Keys.SKIN_WARN_READ, ex.getMessage() == null
                        ? ex.getClass().getSimpleName() : ex.getMessage()), true);
                return;
            }
        }
    }

    private EditBox field(int x, int y, int w, int max, String value) {
        EditBox e = new FlatEditBox(font, x + FIELD_INSET_X, y + FIELD_INSET_Y,
                w - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
        e.setMaxLength(max);
        e.setValue(value == null ? "" : value);
        e.setBordered(false);
        e.setTextColor(TXT);
        add(e);
        return e;
    }

    private void onSaveSettings() {
        INumenConfig cfg = Services.CONFIG;
        if (addingSite) {                          // create a new user site, then select it
            String name = siteNameInput.getValue().trim();
            String url = baseUrlInput.getValue().trim();
            String mdl = modelInput.getValue().trim();
            if (name.isEmpty() || url.isEmpty()) { warnUntil = System.currentTimeMillis() + 4000; return; }
            String id = ModelRegistry.addCustomSite(name, url, mdl);
            if (id == null) { warnUntil = System.currentTimeMillis() + 4000; return; }
            cfg.setProvider(id);
            cfg.setModel(mdl);
            cfg.setApiKey(apiKeyInput.getValue());
            cfg.setBaseUrl("");                    // site carries the URL now
            cfg.setProxy(wProxy);
            cfg.save();
            NumenLlmClient.reset();
            addingSite = false;
            wProvider = id; wModel = mdl; wBaseUrl = ""; customModel = false;
            rebuild();
            savedFlashUntil = System.currentTimeMillis() + 1500;
            return;
        }
        cfg.setProvider(providerDropdown.selectedId());
        cfg.setApiKey(apiKeyInput.getValue());
        String model = customModel
                ? (modelInput != null ? modelInput.getValue().trim() : "")
                : (modelDropdown != null && !CUSTOM_MODEL.equals(modelDropdown.selectedId())
                        ? modelDropdown.selectedId() : "");
        cfg.setModel(model);
        cfg.setBaseUrl(baseUrlInput.getValue());
        cfg.setProxy(proxyInput == null ? wProxy : proxyInput.getValue());
        cfg.setReasoningEffort(wReasoning);
        cfg.save();
        NumenLlmClient.reset();
        savedFlashUntil = System.currentTimeMillis() + 1500;
    }

    // ---- reasoning / thinking effort control ----

    private static final String[] REASONING_LEVELS = {"auto", "low", "medium", "high"};

    /** Coerce any stored value to one of {@link #REASONING_LEVELS} ("auto" = leave to backend default). */
    private static String normalizeReasoning(String v) {
        if (v == null) return "auto";
        String s = v.trim().toLowerCase();
        for (String lvl : REASONING_LEVELS) if (lvl.equals(s)) return lvl;
        return "auto";
    }

    /** Advance the working reasoning level auto → low → medium → high → auto. Saved with the rest on Save. */
    private void cycleReasoning() {
        String cur = normalizeReasoning(wReasoning);
        for (int i = 0; i < REASONING_LEVELS.length; i++) {
            if (REASONING_LEVELS[i].equals(cur)) {
                wReasoning = REASONING_LEVELS[(i + 1) % REASONING_LEVELS.length];
                return;
            }
        }
        wReasoning = "auto";
    }

    private Component reasoningLabel() {
        return Component.translatable("numen.settings.reasoning",
                I18n.get("numen.settings.reasoning." + normalizeReasoning(wReasoning)));
    }

    private void renderSettings(GuiGraphics g, int mouseX, int mouseY) {
        renderSettingsNav(g);
        switch (settingsSection) {
            case MCP -> renderMcpSection(g, mouseX, mouseY);
            case SKILLS -> renderSkillsSection(g, mouseX, mouseY);
            case PERSONA -> renderPersonaSection(g, mouseX, mouseY);
            case PROVIDER -> renderProviderSection(g, mouseX, mouseY);
            case VOICE -> renderVoiceSection(g, mouseX, mouseY);
            case SKIN -> renderSkinSection(g, mouseX, mouseY);
            case PROXY -> renderProxySection(g);
        }
    }

    private void renderProviderSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        // The form fills the section from the very top (5 rows + Save is a tight fit),
        // so the section title only draws in list/confirm states — the form's own
        // "名称(必填)" first label takes the top line.
        if (!addingProvider) {
            txt(g, Component.translatable(ModLanguageData.Keys.PROVIDER_TITLE), x, secY0() - 2, TXT);
        }
        if (providerDeletePending != null) {
            var e = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().get(providerDeletePending);
            txt(g, Component.translatable(ModLanguageData.Keys.PROVIDER_DELETE_CONFIRM, e != null ? e.name() : ""),
                    x, secY0() + 10, TXT);
            return;
        }
        if (addingProvider) { renderProviderForm(g); return; }
        var list = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().list();
        if (list.isEmpty()) {
            txt(g, Component.translatable(ModLanguageData.Keys.PROVIDER_EMPTY), x, secY0() + 16, TXT_FAINT);
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
            txt(g, Component.literal(e.name()), x, ry + 1, TXT);
            String meta = (nb(e.provider()) ? e.provider() : "?") + " · "
                    + (nb(e.model()) ? e.model() : "?")
                    + (nb(e.apiKey()) ? "" : " · " + I18n.get(ModLanguageData.Keys.PROVIDER_NO_KEY));
            txt(g, Component.literal(clip(meta, w - 30)), x, ry + 11, nb(e.apiKey()) ? TXT_FAINT : FAIL);
            txt(g, Component.literal("✎"), editX, ry + 6,
                    overDelete(mouseX, mouseY, editX, ry) ? CTA : TXT_FAINT);
            txt(g, Component.literal("✕"), delX, ry + 6,
                    overDelete(mouseX, mouseY, delX, ry) ? FAIL : TXT_FAINT);
        }
    }

    private void renderProviderForm(GuiGraphics g) {
        int x = secX();
        int fy = secY0();
        txt(g, Component.translatable(ModLanguageData.Keys.PROVIDER_FORM_NAME), x, fy, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.PROVIDER_FORM_PROVIDER), x, fy + SET_SP, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_MODEL), x, fy + 2 * SET_SP, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.GUI_SETTINGS_API_KEY), x, fy + 3 * SET_SP, TXT_MUTED);
        txt(g, Component.translatable(ModLanguageData.Keys.PROVIDER_FORM_BASE_URL), x, fy + 4 * SET_SP, TXT_MUTED);
    }

    private static boolean nb(String s) {
        return s != null && !s.isBlank();
    }

    private boolean providerClick(int mx, int my) {
        if (addingProvider || providerDeletePending != null) return false;
        int x = secX(), w = secW();
        var list = com.dwinovo.numen.agent.llm.ProviderLibrary.instance().list();
        int listY0 = secY0() + 14;
        int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
        int scroll = Math.clamp(settingsScroll, 0, Math.max(0, list.size() - visible));
        for (int i = scroll; i < list.size(); i++) {
            int ry = listY0 + (i - scroll) * LIST_ROW;
            if (ry + LIST_ROW > secBottom()) break;
            var e = list.get(i);
            int delX = x + w - 12, editX = x + w - 26;
            if (overDelete(mx, my, editX, ry)) { beginEditProvider(e); return true; }
            if (overDelete(mx, my, delX, ry)) { providerDeletePending = e.id(); rebuild(); return true; }
            if (overRow(mx, my, x, w, ry)) { beginEditProvider(e); return true; }
        }
        return false;
    }

    private void beginEditProvider(com.dwinovo.numen.agent.llm.ProviderLibrary.Entry e) {
        addingProvider = true;
        providerEditId = e.id();
        wProvName = e.name() == null ? "" : e.name();
        wProvProvider = e.provider() == null ? "" : e.provider();
        wProvModel = e.model() == null ? "" : e.model();
        wProvKey = e.apiKey() == null ? "" : e.apiKey();
        wProvBaseUrl = e.baseUrl() == null ? "" : e.baseUrl();
        // The stored model may not be in the provider's known list — open in free-text then.
        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvProvider));
        provCustomModel = mp == null || mp.custom()
                || mp.models().stream().noneMatch(m -> m.id().equals(wProvModel));
        rebuild();
    }

    /** The config-hub left sub-nav: 模型接入 / MCP / 技能, plus the divider. */
    private void renderSettingsNav(GuiGraphics g) {
        String[] labels = {
                I18n.get(ModLanguageData.Keys.PROVIDER_TITLE), I18n.get("numen.settings.proxy"),
                I18n.get("numen.settings.nav.mcp"),
                I18n.get("numen.settings.nav.skills"), I18n.get("numen.settings.nav.persona"),
                I18n.get(ModLanguageData.Keys.VOICE_TITLE),
                I18n.get(ModLanguageData.Keys.SKIN_TITLE)};
        int navX = left + PAD;
        int y = secY0();
        for (int i = 0; i < labels.length; i++) {
            boolean active = settingsSection == SettingsSection.values()[i];
            int ry = y + i * NAV_SP;
            if (active) {
                g.fill(navX - 2, ry - 3, navX - 1, ry + NAV_SP - 5, ACCENT);   // gold left bar
                txt(g, Component.literal(labels[i]), navX + 3, ry, TXT);
            } else {
                txt(g, Component.literal(labels[i]), navX + 3, ry, TXT_MUTED);
            }
        }
        int dx = left + PAD + NAV_W + 3;
        g.fill(dx, secY0() - 2, dx + 1, secBottom(), BORDER);   // vertical divider
    }

    private void renderLlmSettings(GuiGraphics g) {
        int x = secX();
        int y0 = secY0();
        if (addingSite) {
            txt(g, Component.translatable("numen.settings.site_name"), x, y0, TXT_MUTED);
            txt(g, Component.translatable("numen.gui.settings.api_key"), x, y0 + SET_SP, TXT_MUTED);
            txt(g, Component.translatable("numen.gui.settings.model"), x, y0 + 2 * SET_SP, TXT_MUTED);
            txt(g, Component.translatable("numen.settings.base_url"), x, y0 + 3 * SET_SP, TXT_MUTED);
        } else {
            txt(g, Component.translatable("numen.gui.settings.provider"), x, y0, TXT_MUTED);
            txt(g, Component.translatable("numen.gui.settings.api_key"), x, y0 + SET_SP, TXT_MUTED);
            txt(g, Component.translatable("numen.gui.settings.model"), x, y0 + 2 * SET_SP, TXT_MUTED);
            txt(g, Component.translatable("numen.settings.base_url"), x, y0 + 3 * SET_SP, TXT_MUTED);
            txt(g, Component.translatable("numen.settings.proxy"), x, y0 + 4 * SET_SP, TXT_MUTED);
        }
        if (savedFlashUntil > System.currentTimeMillis()) {
            txt(g, Component.translatable("numen.settings.saved"), x, top + PANEL_H - PAD - 14, OK);
        }
        // the dropdowns themselves render in render, AFTER the widgets (open list on top)
    }

    // ---- MCP section: external server list with a live on/off switch per row ----

    private void renderMcpSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        txt(g, Component.translatable("numen.mcp.title"), x, secY0() - 2, TXT);
        if (mcpDeletePending != null) {
            txt(g, Component.translatable("numen.mcp.delete_confirm", mcpDeletePending), x, secY0() + 10, TXT);
            return;
        }
        if (addingMcp) { renderMcpForm(g); return; }
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
            // status dot
            int dy = ry + 3;
            g.fill(x, dy, x + 5, dy + 5, mcpDotColor(h.status()));
            Nb.border(g, x, dy, 5, 5, 1, BORDER);
            // name + meta line
            txt(g, Component.literal(h.name()), x + 10, ry + 1, TXT);
            txt(g, Component.literal(mcpMeta(h)), x + 10, ry + 11, TXT_FAINT);
            // toggle + delete, right-aligned
            drawToggle(g, togX, ry + 5, h.toggledOn());
            boolean overDel = overDelete(mouseX, mouseY, delX, ry);
            txt(g, Component.literal("✕"), delX, ry + 6, overDel ? FAIL : TXT_FAINT);
            // hover tooltip: tool names + url/command + any error (not over a control)
            if (overRow(mouseX, mouseY, x, w, ry) && !overToggle(mouseX, mouseY, togX, ry + 5) && !overDel) {
                pendingTip = mcpTooltip(h);
                pendingTipX = mouseX;
                pendingTipY = mouseY;
            }
        }
    }

    /** Add-server form labels + placeholders (fields/buttons are widgets, drawn in the overlay pass). */
    private void renderMcpForm(GuiGraphics g) {
        int x = secX();
        int fy = secY0() + 14;   // matches buildMcpForm
        txt(g, Component.translatable("numen.mcp.form_name"), x, fy, TXT_MUTED);
        // the type row is the self-labelled toggle button (no separate label)
        txt(g, Component.translatable(mcpStdio ? "numen.mcp.form_command" : "numen.mcp.form_url"),
                x, fy + 56, TXT_MUTED);
        txt(g, Component.translatable(mcpStdio ? "numen.mcp.form_env" : "numen.mcp.form_header"), x, fy + 89, TXT_MUTED);
        // field placeholders are drawn in the post-widget pass (see render), so they sit above the frames
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
            txt(g, Component.literal(s.name()), x, ry + 1, on ? TXT : TXT_FAINT);
            String desc = s.description() == null ? I18n.get("numen.skill.no_desc") : s.description();
            txt(g, Component.literal(clip(desc, w - 26)), x, ry + 11, TXT_FAINT);
            drawToggle(g, x + w - 20, ry + 5, on);
            if (overRow(mouseX, mouseY, x, w, ry) && !overToggle(mouseX, mouseY, x + w - 20, ry + 5)
                    && s.description() != null) {
                pendingTip = List.of(Component.literal(s.name()), Nb.colored(s.description(), TXT_MUTED));
                pendingTipX = mouseX;
                pendingTipY = mouseY;
            }
        }
    }

    private static final java.util.regex.Pattern QUERY_PAT =
            java.util.regex.Pattern.compile("(?s)<query>(.*?)</query>");

    /**
     * The owner's own words from a user message, for display. New messages wrap the owner's text in
     * {@code <query>…</query>} (see {@code EntityAgentLoop.submitPrompt}), so we show only that; legacy
     * untagged messages fall back to the raw text with injected directives stripped. Display-only — the
     * LLM still receives the full user message.
     */
    private static String ownerText(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = QUERY_PAT.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            if (b.length() > 0) b.append('\n');
            b.append(m.group(1));
        }
        if (b.length() > 0) return b.toString().strip();
        return stripInjectedDirectives(s);   // legacy / untagged owner message
    }

    /**
     * Strip numen-injected directive blocks ({@code <persona-change>…</persona-change>},
     * {@code <event …>…</event>}) from a user message so only the owner's own words show in chat.
     * The full message (directives included) is still what the LLM receives — this is display-only.
     */
    private static String stripInjectedDirectives(String s) {
        if (s == null) return "";
        String out = s.replaceAll("(?s)<persona-change>.*?</persona-change>", "")
                .replaceAll("(?s)<event\\b[^>]*>.*?</event>", "")
                .replaceAll("(?s)<event\\b[^>]*/>", "");
        return out.strip();
    }

    /** Truncate {@code s} with an ellipsis so it fits in {@code maxW} px. */
    private String clip(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (font.width(b.toString() + s.charAt(i) + "…") > maxW) break;
            b.append(s.charAt(i));
        }
        return b + "…";
    }

    // ---- shared toggle switch (no vanilla widget for this) ----

    private static final int TOG_W = 18, TOG_H = 10;

    private void drawToggle(GuiGraphics g, int x, int y, boolean on) {
        // 轨道恒中性,状态全由滑块表达:开 = 黄色滑块在右,关 = 暗滑块在左。
        // (旧画法开着时整条轨道变黄,黄色大块压在左侧,读起来像"滑块在左"。)
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

    /** Settings-tab clicks: the sub-nav column, then per-row toggles in the MCP / skill lists. */
    private boolean settingsClickedAt(double mxd, double myd) {
        int mx = (int) mxd, my = (int) myd;
        int navX = left + PAD, y = secY0();
        if (mx >= navX && mx < navX + NAV_W) {
            for (int i = 0; i < SettingsSection.values().length; i++) {
                int ry = y + i * NAV_SP;
                if (my >= ry - 3 && my < ry + NAV_SP - 5) {
                    selectSection(SettingsSection.values()[i]);
                    return true;
                }
            }
        }
        if (settingsSection == SettingsSection.MCP) return mcpToggleClick(mx, my);
        if (settingsSection == SettingsSection.SKILLS) return skillToggleClick(mx, my);
        if (settingsSection == SettingsSection.PERSONA) return personaClick(mx, my);
        if (settingsSection == SettingsSection.PROVIDER) return providerClick(mx, my);
        if (settingsSection == SettingsSection.VOICE) return voiceClick(mx, my);
        if (settingsSection == SettingsSection.SKIN) return skinClick(mx, my);
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
                rebuild();
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
        rebuild();
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

    // ---- Persona section render + hit-test ----

    private void renderPersonaSection(GuiGraphics g, int mouseX, int mouseY) {
        int x = secX(), w = secW();
        txt(g, Component.translatable("numen.persona.title"), x, secY0() - 2, TXT);
        if (personaDeletePending != null) {
            PersonaLibrary.Persona p = PersonaLibrary.instance().get(personaDeletePending);
            txt(g, Component.translatable("numen.persona.delete_confirm", p != null ? p.name() : ""),
                    x, secY0() + 10, TXT);
            return;
        }
        if (addingPersona) { renderPersonaForm(g); return; }
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
            txt(g, Component.literal(p.name()), x, ry + 1, TXT);
            String badge = p.preset() ? I18n.get("numen.persona.preset_badge") + " · " : "";
            txt(g, Component.literal(clip(badge + p.text(), w - 30)), x, ry + 11, TXT_FAINT);
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
        int x = secX();
        int fy = secY0() + 14;
        txt(g, Component.translatable("numen.persona.form_name"), x, fy, TXT_MUTED);
        txt(g, Component.translatable("numen.persona.form_text"), x, fy + 33, TXT_MUTED);
    }

    /** The active companion's current persona name (green marker in the list), or null. */
    private String activePersonaName() {
        if (uuid == null) return null;
        return AgentLoopRegistry.get(uuid).map(EntityAgentLoop::personaName).orElse(null);
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
                if (overDelete(mx, my, delX, ry)) { lib.clonePersona(p.id()); rebuild(); return true; }
            } else {
                if (overDelete(mx, my, editX, ry)) { beginEditPersona(p); return true; }
                if (overDelete(mx, my, delX, ry)) { personaDeletePending = p.id(); rebuild(); return true; }
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
        rebuild();
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

    @Override
    public void tick() {
        if (tab == Tab.ITEMS && ++tickCounter % INV_REFRESH_TICKS == 0) {
            requestInventory();
        }
    }

    private void requestInventory() {
        // No companion selected (empty roster / hotkey-opened blank panel) → nothing to fetch.
        // The payload's UUID stream-codec can't encode null, so this guard also prevents a crash.
        if (uuid == null) return;
        if (Minecraft.getInstance().getConnection() != null) {
            Services.NETWORK.sendToServer(new RequestInventoryPayload(uuid));
        }
    }

    private void onSend() {
        if (input == null) return;
        String text = input.getValue() == null ? "" : input.getValue().trim();
        if (text.isEmpty()) return;
        // Endpoint check for THIS companion (its provider entry, not the legacy global
        // key): unbound / keyless surfaces as a visible hint, never a crash or a
        // silent no-op — the no-provider safety net.
        String problem = loop().endpointProblem();
        if (problem != null) {
            com.dwinovo.numen.Constants.LOG.warn("[numen-chat] {}", problem);
            warnText = problem;
            warnUntil = System.currentTimeMillis() + 4000;
            return;
        }
        loop().submitPrompt(text);
        input.setValue("");
        pinBottom = true;
    }

    // ---- input ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int k = keyCode;
        if (dismissPending != null) {
            if (k == 256) { dismissPending = null; rebuild(); return true; }   // Esc cancels the confirm
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (summoning) {
            if (k == 257 || k == 335) { doSummon(); return true; }    // Enter
            if (k == 256) { summoning = false; rebuild(); return true; } // Esc cancels (doesn't close panel)
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if ((k == 257 || k == 335) && input != null && input.isFocused()) {
            onSend();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void doSummon() {
        String n = summonInput == null ? "" : summonInput.getValue().trim();
        if (n.isEmpty()) {
            warnText = I18n.get(ModLanguageData.Keys.SUMMON_WARN_NAME);
            warnUntil = System.currentTimeMillis() + 4000;
            return;
        }
        // A model config is REQUIRED. Empty library → error AT THE CLICK, pointing
        // the way (no ambient red text before the player acts).
        if (summonProviderId == null) {
            warnText = I18n.get(ModLanguageData.Keys.SUMMON_WARN_PROVIDER);
            warnUntil = System.currentTimeMillis() + 4000;
            return;
        }
        // 名字限定 Minecraft 官方命名规则(3~16 位英文/数字/下划线)——中文名在玩家系统
        // 各处容易出错,而且名字同时就是皮肤来源:同名正版玩家的皮肤会自动穿上。
        if (!n.matches("[A-Za-z0-9_]{3,16}")) {
            warnText = I18n.get(ModLanguageData.Keys.SUMMON_WARN_NAME_FORMAT);
            warnUntil = System.currentTimeMillis() + 4000;
            return;
        }
        // Remember the picks by name; CompanionListPayload applies them when the new companion arrives.
        if (summonPersonaId != null) com.dwinovo.numen.persona.PersonaLibrary.pendSummon(n, summonPersonaId);
        com.dwinovo.numen.agent.llm.ProviderLibrary.pendSummon(n, summonProviderId);
        if (summonVoiceId != null) com.dwinovo.numen.client.voice.VoiceLibrary.pendSummon(n, summonVoiceId);
        // 自定义皮肤:库里存好的 Mojang 签名数据随包捎给服务端(自验证,伪造不了);
        // 没选就留空,服务端按名字找同名正版皮肤。
        String skinValue = "", skinSig = "";
        var skinEntry = com.dwinovo.numen.client.skin.SkinLibrary.instance().get(summonSkinId);
        if (skinEntry != null && skinEntry.signed()) {
            skinValue = skinEntry.value();
            skinSig = skinEntry.signature();
        }
        com.dwinovo.numen.Constants.LOG.info("[numen-skin] 召唤 {}: 皮肤选择={} 条目={} 携带签名数据={}",
                n, summonSkinId, skinEntry == null ? "null" : skinEntry.name(), !skinValue.isEmpty());
        Services.NETWORK.sendToServer(
                new com.dwinovo.numen.network.payload.SummonRequestPayload(n, skinValue, skinSig));
        summoning = false;
        summonPersonaId = null;
        summonProviderId = null;
        summonVoiceId = null;
        rebuild();   // the new companion arrives via CompanionListPayload — click its avatar to open
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (dismissPending != null) {
            return super.mouseClicked(mouseX, mouseY, button);   // modal confirm — let its Cancel/Delete buttons handle it
        }
        if (button == 0) {
            // Summon dropdowns get first pick (their open lists overlay the panel).
            // 遮挡关系:先路由"正展开"的那一个——下排下拉向上翻时,展开列表盖住
            // 上排的折叠框,固定顺序会让上排先吞掉点击。
            if (summoning && routeSummonDropdownClick(mouseX, mouseY)) {
                return true;
            }
            UUID close = railCloseAt((int) mouseX, (int) mouseY);
            if (close != null) { dismissPending = close; rebuild(); return true; }   // ✕ → confirm bar
            if (railPlusAt((int) mouseX, (int) mouseY)) {   // + → start the summon name prompt
                summoning = !summoning;
                if (summoning) { summonPersonaId = null; summonVoiceId = null; summonSkinId = null; }   // fresh summon starts at "默认/无"
                rebuild();
                return true;
            }
            int rail = railIndexAt((int) mouseX, (int) mouseY);
            if (rail >= 0) {
                List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
                if (rail < entries.size()) {
                    boolean wasSummoning = summoning;
                    summoning = false;
                    NumenRoster.Entry e = entries.get(rail);
                    if (e.uuid().equals(uuid)) { if (wasSummoning) rebuild(); }   // already active — just exit summon
                    else switchTo(e.uuid(), e.name());
                }
                return true;
            }
            if (tab == Tab.SETTINGS && providerDropdown != null) {
                String before = providerDropdown.selectedId();
                if (providerDropdown.mouseClicked(mouseX, mouseY)) {
                    if (modelDropdown != null) modelDropdown.close();
                    String sel = providerDropdown.selectedId();
                    if (ProviderDropdown.ADD_SITE.equals(sel)) {            // "+ 添加站点" → add-site editor
                        preserveKeyUrl();
                        addingSite = true; wSiteName = ""; wBaseUrl = ""; wModel = "";
                        rebuild();
                    } else if (!sel.equals(before)) {                      // provider changed → reset model
                        preserveKeyUrl();
                        wProvider = sel;
                        ModelRegistry.Provider mp = ModelRegistry.provider(LlmProviders.normalize(wProvider));
                        customModel = mp != null && mp.custom();
                        wModel = (mp != null && !mp.models().isEmpty()) ? mp.models().get(0).id() : "";
                        rebuild();
                    }
                    return true;
                }
            }
            if (tab == Tab.SETTINGS && modelDropdown != null
                    && modelDropdown.mouseClicked(mouseX, mouseY)) {
                providerDropdown.close();
                if (CUSTOM_MODEL.equals(modelDropdown.selectedId())) {       // "自定义…" → free-text box
                    preserveKeyUrl();
                    customModel = true;
                    wModel = "";
                    rebuild();
                }
                return true;
            }
            // Model-config form pickers get first pick (their open lists overlay the form).
            if (tab == Tab.SETTINGS && addingProvider && provProviderDropdown != null) {
                String before = provProviderDropdown.selectedId();
                if (provProviderDropdown.mouseClicked(mouseX, mouseY)) {
                    if (provModelDropdown != null) provModelDropdown.close();
                    String sel = provProviderDropdown.selectedId();
                    if (!sel.equals(before)) {         // provider changed → adapt model + Base URL
                        preserveProviderForm();
                        adaptToProvider(sel);
                        rebuild();
                    }
                    return true;
                }
            }
            if (tab == Tab.SETTINGS && addingProvider && provModelDropdown != null
                    && provModelDropdown.mouseClicked(mouseX, mouseY)) {
                if (provProviderDropdown != null) provProviderDropdown.close();
                if (CUSTOM_MODEL.equals(provModelDropdown.selectedId())) {   // 自定义… → free text
                    preserveProviderForm();
                    provCustomModel = true;
                    wProvModel = "";
                    rebuild();
                }
                return true;
            }
            // 声线表单的后端下拉:选型变了就随之刷新字段区(typed 值经 preserve 存活)。
            // 行滚出视口时不接点击(控件仍在,只是被表单滚动藏起来了)。
            if (tab == Tab.SETTINGS && settingsSection == SettingsSection.VOICE
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
                        rebuild();
                    }
                    return true;
                }
            }
            if (tab == Tab.SETTINGS && settingsClickedAt(mouseX, mouseY)) return true;
            int my = (int) mouseY;
            if (my >= top && my < top + HEADER_H) {
                for (int i = 0; i < 3; i++) {
                    if (mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]) {
                        selectTab(Tab.values()[i]);
                        return true;
                    }
                }
            }
            if (tab == Tab.CHAT && toggleFoldAt((int) mouseX, my)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** If a chat fold-toggle row sits under (mx,my), flip its expanded state. Mirrors renderChat geometry. */
    private boolean toggleFoldAt(int mx, int my) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + PANEL_H - INPUT_H - PAD - 6;
        int transX = left + PAD;
        int transW = PANEL_W - PAD * 2 - PLAN_W - 8;
        if (mx < transX || mx >= transX + transW || my < bodyY || my >= bodyBottom) return false;
        List<Row> rows = buildRows(transW);
        int idx = (my - (bodyY - scroll)) / LINE_H;
        if (idx < 0 || idx >= rows.size()) return false;
        String key = rows.get(idx).foldKey();
        if (key == null) return false;
        if (!expandedGroups.add(key)) expandedGroups.remove(key);   // toggle open/closed
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 声线表单:滚轮上下滚整个表单(MiniMax 八行超出视口)。
        if (sy != 0 && tab == Tab.SETTINGS && settingsSection == SettingsSection.VOICE
                && addingVoice && mx >= secX() && maxVoiceFormScroll() > 0) {
            preserveVoiceForm();
            voiceFormScroll = Math.clamp((long) (voiceFormScroll - sy * 16), 0, maxVoiceFormScroll());
            rebuild();
            return true;
        }
        // Wheel over the left rail column scrolls the roster (works on any tab).
        if (sy != 0 && mx >= railX && mx < railX + RAIL_W && maxRailScroll() > 0) {
            railScroll = Math.clamp((long) (railScroll - sy), 0, maxRailScroll());
            return true;
        }
        if (tab == Tab.CHAT && sy != 0) {
            scroll = Math.clamp((long) (scroll - sy * LINE_H * 3), 0, lastMaxScroll);
            pinBottom = scroll >= lastMaxScroll;
            return true;
        }
        if (tab == Tab.SETTINGS && sy != 0 && !addingPersona && !addingProvider && !addingVoice) {
            int count = switch (settingsSection) {
                case MCP -> com.dwinovo.numen.mcp.client.McpClientManager.servers().size();
                case SKILLS -> com.dwinovo.numen.agent.skill.SkillRegistry.instance().size();
                case PERSONA -> PersonaLibrary.instance().list().size();
                case PROVIDER -> com.dwinovo.numen.agent.llm.ProviderLibrary.instance().list().size();
                case VOICE -> com.dwinovo.numen.client.voice.VoiceLibrary.instance().list().size();
                case SKIN -> com.dwinovo.numen.client.skin.SkinLibrary.instance().list().size();
                default -> 0;
            };
            int listY0 = secY0() + 14;
            int visible = Math.max(1, (secBottom() - listY0) / LIST_ROW);
            settingsScroll = Math.clamp((long) (settingsScroll - sy), 0, Math.max(0, count - visible));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ---- render ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        pendingTip = null;   // recollected each frame by the section renderers

        // ONE merged Cottage sprite: left rail column + panel, continuous header, no gap.
        g.blitSprite(
                WORKSPACE_SPRITE, railX, top, RAIL_W + PANEL_W, PANEL_H);
        renderRail(g, mouseX, mouseY);   // avatars + status + summon tile on the rail column

        txt(g, Component.literal(name == null ? "Numen" : name), left + PAD, top + 7, ON_BAND);
        int afterName = left + PAD + font.width(name == null ? "Numen" : name) + 6;
        if (uuid != null && ClientDeaths.isDead(uuid)) {        // active companion dead — respawn countdown
            long rem = ClientDeaths.remainingMs(uuid);
            txt(g, Component.translatable("numen.respawn", (int) Math.ceil(rem / 1000.0)), afterName, top + 7, ON_BAND);
        } else {
            String pn = activePersonaName();                   // current persona, faint, right after the name
            if (pn != null) txt(g, Component.literal(pn), afterName, top + 7, ON_BAND_FAINT);
        }
        renderTabs(g, mouseX, mouseY);

        if (dismissPending != null) {
            txt(g, Component.translatable("numen.dismiss.title", nameFor(dismissPending)),
                    left + PAD, top + HEADER_H + 12, TXT);
            txt(g, Component.translatable("numen.dismiss.warning"),
                    left + PAD, top + HEADER_H + 30, FAIL);
        } else if (summoning) {
            int y0 = top + HEADER_H;   // offsets in lockstep with buildSummonField
            txt(g, Component.translatable("numen.summon.title"), left + PAD, y0 + 8, TXT);
            txt(g, Component.translatable(ModLanguageData.Keys.SUMMON_NAME), left + PAD, y0 + 24, TXT_MUTED);
            placeholder(g, summonInput, I18n.get(ModLanguageData.Keys.SUMMON_NAME_PLACEHOLDER));
            txt(g, Component.translatable(ModLanguageData.Keys.SUMMON_PERSONA_LABEL), left + PAD, y0 + 58, TXT_MUTED);
            txt(g, Component.literal(I18n.get(ModLanguageData.Keys.PROVIDER_TITLE)
                    + (summonProviderDropdown == null ? I18n.get(ModLanguageData.Keys.SUMMON_PROVIDER_EMPTY) : "")),
                    left + PAD, y0 + 92, TXT_MUTED);
            txt(g, Component.literal(I18n.get(ModLanguageData.Keys.VOICE_SUMMON_LABEL)
                    + (summonVoiceDropdown == null ? I18n.get(ModLanguageData.Keys.VOICE_SUMMON_EMPTY) : "")),
                    left + PAD, y0 + 126, TXT_MUTED);
            txt(g, Component.translatable(ModLanguageData.Keys.SUMMON_SKIN),
                    left + PAD + summonHalfW() + 6, y0 + 126, TXT_MUTED);
            txt(g, Component.translatable("numen.summon.hint"),
                    left + PAD, y0 + 186, TXT_FAINT);
        } else {
            if (uuid != null) {
                if (compactButton != null) compactButton.active = loop().canCompact();
                if (stopButton != null) stopButton.active = loop().canInterrupt();
            }
            switch (tab) {
                case SETTINGS -> renderSettings(g, mouseX, mouseY);   // global — works with no companion
                case CHAT -> { if (uuid != null) renderChat(g); else emptyHint(g); }
                case ITEMS -> { if (uuid != null) renderItems(g, mouseX, mouseY); else emptyHint(g); }
            }
            if (tab == Tab.CHAT && warnUntil > System.currentTimeMillis()) {   // endpoint-problem hint above the input
                txt(g, warnText != null ? Component.literal(warnText)
                                : Component.translatable("numen.chat.no_key"),
                        left + PAD, top + PANEL_H - INPUT_H - PAD - 11, FAIL);
            }
        }

        // Widgets render LAST, on top of the panel background (fixes the "dim fields" — the panel fill
        // used to paint over the auto-rendered widgets). Text fields are borderless EditBoxes, so draw
        // a parchment field background + border behind each before it renders its text.
        for (AbstractWidget w : overlay) {
            // visible 检查:声线表单滚出视口的 EditBox 隐藏了自己,框也必须跟着消失
            // (否则空框越过面板边缘悬在世界上)。
            if (w instanceof EditBox eb && eb.visible) {            // parchment frame, inflated past the inset text
                g.blitSprite(
                        FIELD_SPRITE, eb.getX() - FIELD_INSET_X, eb.getY() - FIELD_INSET_Y,
                        eb.getWidth() + FIELD_INSET_X * 2, eb.getHeight() + FIELD_INSET_Y * 2);
            }
        }
        for (AbstractWidget w : overlay) {
            w.render(g, mouseX, mouseY, partial);
        }
        // Field placeholders, drawn shadowless by us (the EditBox hint renders with a shadow).
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.PROVIDER && addingProvider) {
            placeholder(g, provKeyInput, "sk-…");
            placeholder(g, provBaseUrlInput, "https://… (OpenAI-compatible)");
            placeholder(g, provModelInput, "model id");
        }
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.PROXY) {
            placeholder(g, proxyIpInput, "127.0.0.1");
            placeholder(g, proxyPortInput, "7890");
        }
        // 声线表单:模型配置同款——每行标题画在输入框上方,框内只留短示例占位。
        // 行序与 buildVoiceForm 的 switch 严格一致,随 voiceFormScroll 偏移。
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.VOICE && addingVoice) {
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
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.VOICE
                && addingVoice && voiceBackendDropdown != null && voiceRowVisible(1)) {
            voiceBackendDropdown.render(g, font, mouseX, mouseY);
        }
        // The model-config form's open dropdown lists must sit above the fields.
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.PROVIDER && addingProvider) {
            if (provModelDropdown != null && provProviderDropdown != null && provProviderDropdown.isOpen()) {
                provModelDropdown.render(g, font, mouseX, mouseY);
                provProviderDropdown.render(g, font, mouseX, mouseY);
            } else {
                if (provProviderDropdown != null) provProviderDropdown.render(g, font, mouseX, mouseY);
                if (provModelDropdown != null) provModelDropdown.render(g, font, mouseX, mouseY);
            }
        }
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.MCP && addingMcp) {
            placeholder(g, mcpNameInput, "kfc");
            placeholder(g, mcpTargetInput, mcpStdio ? "cmd /c npx -y <server>" : "https://mcp.mcd.cn");
            placeholder(g, mcpHeaderInput, mcpStdio ? "KEY=value; KEY2=value2" : "Authorization: Bearer <token>");
        }
        if (tab == Tab.SETTINGS && settingsSection == SettingsSection.PERSONA && addingPersona) {
            placeholder(g, personaNameInput, "雷");   // the text area has its own built-in placeholder
        }
        // (Chat-input placeholder is the FlatEditBox hint now — drawn shadowless and under the
        // caret in the widget pass, so it can't paint over the caret like a screen-side draw did.)
        // Summon warn — shown only when 创建 was clicked and something is missing
        // (error at the action, never ambient text). Takes the hint line's spot.
        if (summoning && warnUntil > System.currentTimeMillis() && warnText != null) {
            g.drawString(font, warnText, left + PAD, top + HEADER_H + 186, 0xFFCC6666, false);
        }
        if (summoning) {
            renderSummonDropdowns(g, mouseX, mouseY);
        }

        // Hovered MCP / skill row tooltip — drawn last so nothing paints over it.
        if (pendingTip != null) {
            g.renderComponentTooltip(font, pendingTip, pendingTipX, pendingTipY);
        }
    }

    // ---- left companion rail ----

    /** The folded-in roster (on the merged sprite's rail column): one avatar head per companion below the
     *  green header, active one framed gold, a status dot each, + tile at the bottom. */
    private void renderRail(GuiGraphics g, int mouseX, int mouseY) {
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        railScroll = Math.clamp(railScroll, 0, maxRailScroll());     // keep valid as the roster grows/shrinks
        int first = railScroll;
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            NumenRoster.Entry e = entries.get(i);
            boolean active = e.uuid().equals(uuid);
            // textured socket behind the head (gold-bordered when active), then the avatar, then a status LED
            g.blitSprite(active ? AVATAR_FRAME_ACTIVE : AVATAR_FRAME, ax - 2, ay - 2, RAIL_AV + 4, RAIL_AV + 4);
            PlayerFaceRenderer.draw(g, skinFor(e.uuid()), ax, ay, RAIL_AV);
            if (ClientDeaths.isDead(e.uuid())) {                      // dead — dim veil + respawn countdown
                g.fill(ax, ay, ax + RAIL_AV, ay + RAIL_AV, 0xB0101010);
                long rem = ClientDeaths.remainingMs(e.uuid());
                if (rem >= 0) {
                    String c = String.valueOf((int) Math.ceil(rem / 1000.0));
                    txt(g, Component.literal(c), ax + (RAIL_AV - font.width(c)) / 2, ay + (RAIL_AV - 8) / 2, CTA);
                }
            } else {
                int d = ax + RAIL_AV - 6, e2 = ay + RAIL_AV - 6;     // status LED, bottom-right
                g.fill(d, e2, d + 5, e2 + 5, statusColor(e.uuid()));
                Nb.border(g, d, e2, 5, 5, 1, BORDER);
            }
            // hover → a small ✕ badge breaking OUT of the avatar's top-right corner (overhangs the frame).
            // Show it while the cursor is over the avatar OR the badge itself (the badge sticks out, so
            // moving onto it must not make it vanish).
            int bx = ax + RAIL_AV - 3, by = ay - 5;
            boolean overAvatar = mouseX >= ax && mouseX < ax + RAIL_AV && mouseY >= ay && mouseY < ay + RAIL_AV;
            boolean overBadge = mouseX >= bx && mouseX < bx + 9 && mouseY >= by && mouseY < by + 9;
            if (dismissPending == null && (overAvatar || overBadge)) {
                g.fill(bx, by, bx + 9, by + 9, FAIL);
                Nb.border(g, bx, by, 9, 9, 1, BORDER);
                txt(g, Component.literal("✕"), bx + 2, by + 1, ON_BAND);
            }
        }
        // "+" summon tile (baked "+" glyph), pinned to the rail bottom
        int py = top + PANEL_H - PAD - RAIL_AV;
        // scroll cues — gold chevrons when the roster overflows the rail in either direction
        int cx = ax + RAIL_AV / 2;
        if (railScroll > 0) chevron(g, cx, top + 1, true);
        if (railScroll < maxRailScroll()) chevron(g, cx, py - 9, false);
        g.blitSprite(summoning ? SUMMON_ACTIVE : SUMMON_SPRITE, ax, py, RAIL_AV, RAIL_AV);
    }

    /** Scroll-affordance chevron sprite (amber pixel-art triangle, up = more above / down = more below).
     *  Blitted at its native 11×6 so the pixels stay crisp (no scaling, no AA). */
    private void chevron(GuiGraphics g, int cx, int y, boolean up) {
        g.blitSprite(
                up ? CHEVRON_UP : CHEVRON_DOWN, cx - 5, y, 11, 6);
    }

    /** Bottom edge an avatar may reach (a gap above the pinned "+" tile). */
    private int railBottomEdge() {
        return top + PANEL_H - PAD - RAIL_AV - RAIL_BOT_GAP;
    }

    /** How many avatar slots fit in the rail above the pinned "+" tile. */
    private int railVisibleSlots() {
        int slots = 0;
        while (top + RAIL_TOP + slots * RAIL_SLOT + RAIL_AV <= railBottomEdge()) slots++;
        return Math.max(1, slots);
    }

    private int maxRailScroll() {
        return Math.max(0, NumenRoster.instance().entries().size() - railVisibleSlots());
    }

    /** Y of the first (visible) avatar: centred vertically when the whole roster fits, top-aligned once
     *  it overflows and scrolls. Fixes the big bottom gap + the first avatar poking past the top edge. */
    private int railStartY() {
        int n = NumenRoster.instance().entries().size();
        if (n > railVisibleSlots()) return top + RAIL_TOP;          // scrolling — top-align
        int blockH = Math.max(0, n - 1) * RAIL_SLOT + RAIL_AV;
        int span = railBottomEdge() - (top + RAIL_TOP);
        return top + RAIL_TOP + Math.max(0, (span - blockH) / 2);   // centre the block
    }

    /** The companion whose hover-✕ badge is under (mx,my), or null. Mirrors renderRail geometry. */
    private UUID railCloseAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            int bx = ax + RAIL_AV - 3, by = ay - 5;   // overhanging top-right badge (matches renderRail)
            if (mx >= bx && mx < bx + 9 && my >= by && my < by + 9) return entries.get(i).uuid();
        }
        return null;
    }

    private boolean railPlusAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        int py = top + PANEL_H - PAD - RAIL_AV;
        return mx >= ax && mx < ax + RAIL_AV && my >= py && my < py + RAIL_AV;
    }

    /** idle = green, working/compacting = amber, queued = gold; faint if no loop yet. */
    private int statusColor(UUID u) {
        return AgentLoopRegistry.get(u).map(loop -> {
            if (loop.isCompacting() || loop.isBusy()) return RUN;
            if (loop.hasQueuedPrompts()) return CTA;
            return OK;
        }).orElse(TXT_FAINT);
    }

    private static PlayerSkin skinFor(UUID u) {
        AbstractClientPlayer e = ClientNumenLookup.resolve(u);
        return e != null ? e.getSkin() : DefaultPlayerSkin.get(u);
    }

    /** Roster index of the avatar under (mx,my), or -1. */
    private int railIndexAt(int mx, int my) {
        int ax = railX + (RAIL_W - RAIL_AV) / 2;
        if (mx < ax || mx >= ax + RAIL_AV) return -1;
        List<NumenRoster.Entry> entries = NumenRoster.instance().entries();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            if (my >= ay && my < ay + RAIL_AV) return i;
        }
        return -1;
    }

    private void emptyHint(GuiGraphics g) {
        txt(g, Component.translatable("numen.empty.no_companions"),
                left + PAD, top + HEADER_H + 10, TXT_FAINT);
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        String[] labels = tabLabels();
        for (int i = 0; i < 3; i++) {
            boolean active = tab == Tab.values()[i];
            boolean hover = mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]
                    && mouseY >= top && mouseY < top + HEADER_H;
            int color = (active || hover) ? ON_BAND : (0x00FFFFFF & ON_BAND) | 0xA0000000;   // dim on band
            txt(g, Component.literal(labels[i]), tabX[i] + 5, top + 7, color);
            if (active) {                                                                     // gold CTA underline
                g.fill(tabX[i] + 3, top + HEADER_H - 4, tabX[i] + tabW[i] - 3, top + HEADER_H - 1, ACCENT);
            }
        }
    }

    private static final int ICON = 9;        // native vitals-icon size
    private static final int ICON_STEP = 9;   // touching = one chunky bar

    /** A row of segmented icons for a 0..max stat (2 units per icon): empty sockets first, then
     *  full / half overlaid. Used for hearts (HP) and drumsticks (hunger). */
    private void renderStatRow(GuiGraphics g, int x, int y, float value, float max,
                               net.minecraft.resources.ResourceLocation full,
                               net.minecraft.resources.ResourceLocation half,
                               net.minecraft.resources.ResourceLocation empty) {
        int units = Math.max(1, (int) Math.ceil(max / 2f));
        for (int i = 0; i < units; i++) {
            int ix = x + i * ICON_STEP;
            g.blitSprite(empty, ix, y, ICON, ICON);
            float v = value - i * 2f;
            if (v >= 2f)      g.blitSprite(full, ix, y, ICON, ICON);
            else if (v >= 1f) g.blitSprite(half, ix, y, ICON, ICON);
        }
    }

    /** Live mouse-following 3D portrait of the companion — the body IS a client player entity, so the
     *  vanilla player renderer draws it for free. Sits in a recessed socket (slot_alt stretched). */
    private void renderPortrait(GuiGraphics g, AbstractClientPlayer e,
                                int x, int y, int w, int h, int mouseX, int mouseY) {
        g.blitSprite(SLOT_ALT, x, y, w, h);
        if (e == null) return;
        int scale = (int) (h * 0.45f);
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, x + 2, y + 2, x + w - 2, y + h - 2, scale, 0.0625f,
                (float) mouseX, (float) mouseY, e);
    }

    private void slotBg(GuiGraphics g, net.minecraft.resources.ResourceLocation sprite, int x, int y) {
        g.blitSprite(sprite, x, y, 16, 16);
    }

    private void stackOn(GuiGraphics g, ItemStack st, int x, int y, int mouseX, int mouseY) {
        if (st == null || st.isEmpty()) return;
        g.renderItem(st, x, y);
        g.renderItemDecorations(font, st, x, y);
        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            g.renderTooltip(font, st, mouseX, mouseY);
        }
    }

    /** One equipment/armor socket, read off the live client entity (equipment IS client-synced). */
    private void drawEquip(GuiGraphics g, AbstractClientPlayer e, EquipmentSlot slot,
                           int x, int y, int mouseX, int mouseY) {
        slotBg(g, SLOT_SPRITE, x, y);
        if (e != null) stackOn(g, e.getItemBySlot(slot), x, y, mouseX, mouseY);
    }

    // ---- chat transcript + plan ----

    private void renderChat(GuiGraphics g) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + PANEL_H - INPUT_H - PAD - 6;
        int transX = left + PAD;
        int transW = PANEL_W - PAD * 2 - PLAN_W - 8;
        int viewH = bodyBottom - bodyY;

        // plan panel divider + content
        int planX = transX + transW + 8;
        g.fill(planX - 4, bodyY, planX - 3, bodyBottom, BORDER);
        renderPlan(g, planX, bodyY, bodyBottom);

        List<Row> rows = buildRows(transW);
        int contentH = rows.size() * LINE_H;
        lastMaxScroll = Math.max(0, contentH - viewH);
        if (pinBottom) scroll = lastMaxScroll;
        scroll = Math.clamp((long) scroll, 0, lastMaxScroll);

        g.enableScissor(transX, bodyY, transX + transW, bodyBottom);
        int y = bodyY - scroll;
        long t = System.currentTimeMillis();
        Set<String> done = doneIds();
        Set<String> failed = failedIds();
        for (Row row : rows) {
            if (y + LINE_H > bodyY && y < bodyBottom) {
                if (row.foldKey() != null) {                 // clickable fold toggle — glyph baked into text
                    txt(g, row.text, transX, y, row.color);
                } else if (row.toolIds() != null) {          // tool row — status icon + text
                    boolean anyRunning = row.toolIds().stream().anyMatch(id -> !done.contains(id));
                    boolean anyFail = row.toolIds().stream().anyMatch(failed::contains);
                    String icon = anyRunning ? SPIN[(int) ((t / 120) % 4)] : (anyFail ? "✗" : "✔");
                    int ic = anyRunning ? RUN : (anyFail ? FAIL : OK);
                    txt(g, Component.literal(icon), transX, y, ic);
                    txt(g, row.text, transX + 11, y, row.color);
                } else {
                    txt(g, row.text, transX, y, row.color);
                }
            }
            y += LINE_H;
        }
        g.disableScissor();

        // scrollbar — Cottage track + thumb sprites (was off-theme white fills)
        if (lastMaxScroll > 0) {
            int trackH = viewH;
            int thumbH = Math.max(12, trackH * viewH / (viewH + lastMaxScroll));
            int thumbY = bodyY + (trackH - thumbH) * scroll / lastMaxScroll;
            int sbX = transX + transW - 4;
            g.blitSprite(SCROLL_TRACK, sbX, bodyY, 4, viewH);
            g.blitSprite(SCROLL_THUMB, sbX, thumbY, 4, thumbH);
        }
    }

    /** Flatten the convo into render rows. Tool RESULT messages aren't drawn — they only mark the
     *  matching tool call done (via {@link #doneIds()}); the call line shows the spinner/check. */
    private List<Row> buildRows(int width) {
        List<Row> out = new ArrayList<>();
        Set<String> done = doneIds();
        Set<String> failed = failedIds();
        List<LlmToolCall> group = new ArrayList<>();        // a run of consecutive tool calls
        // The PHYSICAL transcript, not the LLM context: compaction rewires what
        // the model sees but must never eat the owner's visible history.
        for (ConvoState.Msg msg : loop().display()) {
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    flushTools(out, group, done, failed, width);
                    if (ConvoLog.PERSONA_DIVIDER.equals(u.content())) {
                        wrapPlain(out, I18n.get("numen.chat.persona_changed"), TXT_FAINT, width);
                        continue;
                    }
                    if (ConvoLog.COMPACT_DIVIDER.equals(u.content())) {
                        wrapPlain(out, I18n.get("numen.chat.compacted"), TXT_FAINT, width);
                        continue;
                    }
                    String shown = ownerText(u.content());       // show only the owner's words, not injected content
                    if (shown.isEmpty()) continue;               // a pure directive/injected message → not shown
                    wrapPlain(out, shown, YOU, width);           // user = teal body, no label
                }
                case ConvoState.Msg.Assistant a -> {
                    AssistantTurn turn = a.turn();
                    if (turn.content() != null && !turn.content().isBlank()) {
                        flushTools(out, group, done, failed, width);   // spoken reply breaks the fold
                        addHeader(out, name, AI, width);         // bold name header on its OWN line
                        wrapPlain(out, turn.content(), AI, width);
                    }
                    group.addAll(turn.toolCalls());
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a row */ }
            }
        }
        flushTools(out, group, done, failed, width);
        // Prompts still waiting for a protocol-valid splice point (typed
        // mid-task, or pushed in by an external bridge via NumenGateway) —
        // visible immediately so a queued message never feels swallowed.
        for (String queued : loop().queuedPrompts()) {
            String shown = ownerText(queued);       // injected events (persona-change / <event>) → empty → not shown
            if (shown.isEmpty()) continue;
            wrapPlain(out, "⌛ " + shown, TXT_FAINT, width);
        }
        if (loop().isCompacting()) {
            wrapPlain(out, I18n.get("numen.chat.compacting"), TXT_MUTED, width);
        }
        if (out.isEmpty()) {
            wrapPlain(out, I18n.get("numen.chat.empty", name), TXT_FAINT, width);
        }
        return out;
    }

    /** Emit rows for a run of consecutive tool calls. A single call is always one plain row. A run of
     *  many stays EXPANDED while any is still running (live per-tool spinners) and AUTO-FOLDS to a muted
     *  "N steps · names" summary once all are done — unless the user clicked it open (keyed by the first
     *  id in {@link #expandedGroups}), in which case it shows a "▾" header + the tool rows. */
    private void flushTools(List<Row> out, List<LlmToolCall> group, Set<String> done, Set<String> failed, int width) {
        if (group.isEmpty()) return;
        if (group.size() == 1) {                                  // single tool — never folds
            addToolRow(out, group.get(0), width);
            group.clear();
            return;
        }
        String key = group.get(0).id();
        boolean running = group.stream().anyMatch(tc -> !done.contains(tc.id()));
        boolean expanded = running || expandedGroups.contains(key);
        if (!expanded) {                                          // folded summary (click to expand)
            List<String> names = new ArrayList<>();
            for (LlmToolCall tc : group) if (!names.contains(tc.name())) names.add(tc.name());
            boolean anyFail = group.stream().anyMatch(tc -> failed.contains(tc.id()));
            String summary = "▸ " + I18n.get("numen.chat.steps", group.size()) + " · " + String.join(" · ", names);
            out.add(new Row(colored(fitOneLine(summary, width - 2), anyFail ? FAIL : TOOL).getVisualOrderText(),
                    anyFail ? FAIL : TOOL, null, key));
        } else {
            if (!running) {                                       // manually expanded → collapsible header
                String hdr = "▾ " + I18n.get("numen.chat.steps", group.size());
                out.add(new Row(colored(hdr, TXT_MUTED).getVisualOrderText(), TXT_MUTED, null, key));
            }
            for (LlmToolCall tc : group) addToolRow(out, tc, width);
        }
        group.clear();
    }

    private void addToolRow(List<Row> out, LlmToolCall tc, int width) {
        FormattedCharSequence seq = colored(fitOneLine(toolLine(tc), width - 2 - 11), TOOL).getVisualOrderText();
        out.add(new Row(seq, TOOL, List.of(tc.id()), null));
    }

    /** Trim a string with an ellipsis so it fits one line of the given pixel width. */
    private String fitOneLine(String s, int pxWidth) {
        if (font.width(s) <= pxWidth) return s;
        while (s.length() > 1 && font.width(s + "…") > pxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private String toolLine(LlmToolCall tc) {
        String args = tc.arguments() == null ? "" : tc.arguments().replaceAll("\\s+", " ").trim();
        if (args.length() > TOOL_ARG_CHARS) args = args.substring(0, TOOL_ARG_CHARS) + "…";
        return tc.name() + "  " + args;
    }

    /** A bold name header on its OWN line (fixed format — never merges into the body). */
    private void addHeader(List<Row> out, String label, int color, int width) {
        var tc = net.minecraft.network.chat.TextColor.fromRgb(color & 0xFFFFFF);
        Component c = Component.literal(label).withStyle(s -> s.withColor(tc).withBold(true));
        for (FormattedCharSequence seq : font.split(c, width - 2)) {
            out.add(new Row(seq, color, null, null));
        }
    }

    /** A plain, regular-weight line (status hints) — colour baked into the style. */
    private void wrapPlain(List<Row> out, String text, int color, int width) {
        for (FormattedCharSequence seq : font.split(colored(text, color), width - 2)) {
            out.add(new Row(seq, color, null, null));
        }
    }

    private Set<String> doneIds() {
        Set<String> s = new HashSet<>();
        for (ConvoState.Msg m : loop().display()) {
            if (m instanceof ConvoState.Msg.Tool t) s.add(t.toolCallId());
        }
        return s;
    }

    private Set<String> failedIds() {
        Set<String> s = new HashSet<>();
        for (ConvoState.Msg m : loop().display()) {
            if (m instanceof ConvoState.Msg.Tool t && looksFailed(t.content())) s.add(t.toolCallId());
        }
        return s;
    }

    private static boolean looksFailed(String content) {
        if (content == null) return false;
        String c = content.replaceAll("\\s+", "");
        return c.contains("\"success\":false") || c.startsWith("ERROR") || c.contains("\"error\"");
    }

    /** Right-side PLAN panel: the companion's latest {@code todowrite}, with status glyphs. */
    private void renderPlan(GuiGraphics g, int x, int y, int bottom) {
        txt(g, Component.translatable("numen.chat.plan"), x, y, TXT_MUTED);
        int ly = y + 13;
        JsonArray todos = latestPlan();
        if (todos == null || todos.isEmpty()) {
            txt(g, Component.translatable("numen.chat.no_plan"), x, ly, TXT_FAINT);
            return;
        }
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            String glyph = switch (status) { case "completed" -> "✔"; case "in_progress" -> "▸"; default -> "○"; };
            int color = switch (status) { case "completed" -> OK; case "in_progress" -> RUN; default -> TXT_FAINT; };
            txt(g, Component.literal(glyph), x, ly, color);
            // text hierarchy: in-progress = strong (current focus), completed = recede, pending = faint
            int textColor = switch (status) {
                case "in_progress" -> TXT;
                case "completed" -> TXT_MUTED;
                default -> TXT_FAINT;
            };
            List<FormattedCharSequence> lines = font.split(colored(content, textColor), PLAN_W - 14);
            int sub = 0;
            for (FormattedCharSequence seq : lines) {
                if (ly + LINE_H >= bottom) break;
                txt(g, seq, x + 10, ly, textColor);
                ly += LINE_H;
                if (++sub >= 2) break;   // cap each item at 2 lines
            }
            if (lines.isEmpty()) ly += LINE_H;
        }
    }

    /** Parse the most recent todowrite call's todos array, or null. Reads the
     *  physical transcript so the plan survives a context compaction. */
    private JsonArray latestPlan() {
        JsonArray latest = null;
        for (ConvoState.Msg m : loop().display()) {
            if (m instanceof ConvoState.Msg.Assistant a) {
                for (LlmToolCall tc : a.turn().toolCalls()) {
                    if (!"todowrite".equals(tc.name())) continue;
                    try {
                        JsonObject args = JsonParser.parseString(tc.arguments()).getAsJsonObject();
                        if (args.has("todos") && args.get("todos").isJsonArray()) {
                            latest = args.getAsJsonArray("todos");
                        }
                    } catch (RuntimeException ignored) { /* keep the last good one */ }
                }
            }
        }
        return latest;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** The Items tab: a vanilla-inventory-style "companion sheet" — armor column + offhand, a live
     *  mouse-following portrait, the synced 2×2 craft grid + result, segmented heart/drumstick vitals,
     *  and the read-only checkerboard 3×9 storage + hotbar. Body data is fetched on demand via
     *  RequestInventoryPayload (backpack + craft + food); HP + equipment come off the live client entity. */
    private void renderItems(GuiGraphics g, int mouseX, int mouseY) {
        var snap = ClientNumenInventory.get(uuid).orElse(null);
        AbstractClientPlayer e = ClientNumenLookup.resolve(uuid);
        List<ItemStack> craft = snap != null ? snap.craft() : List.of();

        // Two centred columns: LEFT = big portrait + armor column + offhand; RIGHT = craft + vitals +
        // 3×9 storage + hotbar. Symmetric framing margins (no lopsided whitespace).
        final int STORAGE_W = 9 * 18;                     // 162 — the widest element (caps the band)
        final int COMP_W = 130 + STORAGE_W;               // left col (130) + right col (storage)
        final int COMP_H = 152;
        int startX = left + (PANEL_W - COMP_W) / 2;
        int cTop = top + HEADER_H + (PANEL_H - HEADER_H - COMP_H) / 2;
        int rightX = startX + 130;

        // -- LEFT: portrait socket, armor column + offhand (vertically centred against the portrait) --
        renderPortrait(g, e, startX + 22, cTop, 84, COMP_H, mouseX, mouseY);
        int armorTop = cTop + (COMP_H - 5 * 18) / 2;
        for (int i = 0; i < ARMOR.length; i++) {
            drawEquip(g, e, ARMOR[i], startX, armorTop + i * 18, mouseX, mouseY);
        }
        drawEquip(g, e, EquipmentSlot.OFFHAND, startX, armorTop + 4 * 18, mouseX, mouseY);

        // -- RIGHT top: synced 2×2 craft grid (+ arrow + result) --
        for (int i = 0; i < 4; i++) {
            int cx = rightX + (i % 2) * 18, cy = cTop + (i / 2) * 18;
            slotBg(g, SLOT_SPRITE, cx, cy);
            stackOn(g, i < craft.size() ? craft.get(i) : ItemStack.EMPTY, cx, cy, mouseX, mouseY);
        }
        txt(g, Component.literal("→"), rightX + 38, cTop + 13, TXT_MUTED);
        int resultX = rightX + 54, resultY = cTop + 9;
        slotBg(g, SLOT_SPRITE, resultX, resultY);
        stackOn(g, craft.size() > 4 ? craft.get(4) : ItemStack.EMPTY, resultX, resultY, mouseX, mouseY);

        // -- RIGHT mid: segmented hearts + drumsticks --
        if (e != null) renderStatRow(g, rightX, cTop + 46, e.getHealth(), e.getMaxHealth(),
                HEART_FULL, HEART_HALF, HEART_EMPTY);
        int food = (snap != null && snap.loaded()) ? snap.foodLevel() : 0;
        renderStatRow(g, rightX, cTop + 46 + ICON + 2, food, 20, FOOD_FULL, FOOD_HALF, FOOD_EMPTY);

        // -- RIGHT bottom: checkerboard 3×9 storage + hotbar --
        int storeY = cTop + 74;
        if (snap == null) {
            txt(g, Component.translatable("numen.status.loading"), rightX, storeY + 4, TXT_FAINT);
            return;
        }
        if (!snap.loaded() || snap.items().isEmpty()) {
            txt(g, Component.translatable("numen.status.asleep"), rightX, storeY + 4, TXT_FAINT);
            return;
        }
        List<ItemStack> items = snap.items();
        for (int i = 9; i < 36; i++) {                     // storage rows (slots 9..35)
            int col = (i - 9) % 9, row = (i - 9) / 9;
            int x = rightX + col * 18, y = storeY + row * 18;
            slotBg(g, ((col + row) & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, y);
            stackOn(g, items.get(i), x, y, mouseX, mouseY);
        }
        int hotbarY = storeY + 3 * 18 + 6;                 // hotbar (slots 0..8)
        for (int i = 0; i < 9; i++) {
            int x = rightX + i * 18;
            slotBg(g, (i & 1) == 0 ? SLOT_SPRITE : SLOT_ALT, x, hotbarY);
            stackOn(g, items.get(i), x, hotbarY, mouseX, mouseY);
        }
    }

    private static net.minecraft.resources.ResourceLocation spr(String name) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, name);
    }
    private static final net.minecraft.resources.ResourceLocation SLOT_SPRITE = spr("slot");
    private static final net.minecraft.resources.ResourceLocation SLOT_ALT = spr("slot_alt");        // checkerboard
    /** Parchment frame (reuses the button sprite) behind text fields. */
    private static final net.minecraft.resources.ResourceLocation FIELD_SPRITE = spr("button");
    private static final net.minecraft.resources.ResourceLocation HEART_FULL = spr("heart_full");
    private static final net.minecraft.resources.ResourceLocation HEART_HALF = spr("heart_half");
    private static final net.minecraft.resources.ResourceLocation HEART_EMPTY = spr("heart_empty");
    private static final net.minecraft.resources.ResourceLocation FOOD_FULL = spr("food_full");
    private static final net.minecraft.resources.ResourceLocation FOOD_HALF = spr("food_half");
    private static final net.minecraft.resources.ResourceLocation FOOD_EMPTY = spr("food_empty");
    private static final net.minecraft.resources.ResourceLocation SCROLL_TRACK = spr("scroll_track");
    private static final net.minecraft.resources.ResourceLocation SCROLL_THUMB = spr("scroll_thumb");

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A rendered transcript line. {@code toolIds} non-null = a tool row (status icon = spinner/✔/✗).
     *  {@code foldKey} non-null = a clickable fold toggle (the group's first id); both null = plain text. */
    private record Row(FormattedCharSequence text, int color, List<String> toolIds, String foldKey) {}
}
