package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.NumenLlmClient;

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
    // 面板随窗口伸缩:下限=从前的固定尺寸(小窗口下与历史布局完全一致),
    // 上限挡住大屏上的无限变宽——行宽超过阅读舒适区就不再跟了。
    // 原版式固定尺寸(箱子/工作台从不随窗口伸缩):大窗口下大面板稀内容,
    // 字的视觉占比被稀释,"显小显散"。定尺=历史布局的原生尺寸,零回归。
    private static final int PANEL_MIN_W = 380;
    private static final int PANEL_MIN_H = 232;
    private static final int PANEL_MAX_W = PANEL_MIN_W;
    private static final int PANEL_MAX_H = PANEL_MIN_H;
    // Left companion rail (folded-in roster): one avatar per Numen, click to switch, + to summon.
    private static final int RAIL_W = 46;        // left rail column width (baked into the workspace sprite)
    private static final int RAIL_AV = 26;       // avatar tile size
    private static final int RAIL_SLOT = 32;     // vertical pitch per avatar
    private static final int RAIL_TOP = 12;      // top margin before the first avatar (clears the active crown)
    private static final int RAIL_BOT_GAP = 6;   // gap kept above the pinned "+" tile
    private static final int HEADER_H = 22;
    private static final int INPUT_H = 18;
    /** Text fields are inset inside their rounded card: the EditBox is shrunk by this much
     *  (so vanilla's top-left unbordered text lands padded + centred) and the card is
     *  inflated back out to the full frame. */
    private static final int FIELD_INSET_X = 5;
    private static final int FIELD_INSET_Y = 4;
    private static final int PAD = 8;
    private static final int LINE_H = 10;
    private static final int PLAN_W = 122;
    private static final int MAX_PROMPT = 1024;

    // ---- palette: static but REFRESHABLE — the theme picker calls repaint() and every
    // constant re-reads UiTheme.current() (single active theme, shared by all instances). ----
    private static int BORDER, ACCENT, TXT, TXT_MUTED, TXT_FAINT, ON_BAND, ON_BAND_FAINT,
            CTA, ON_CTA, FIELD, OK, RUN, FAIL;
    static { repaint(); }

    /** Re-read every palette constant from the current theme (called after a theme switch). */
    static void repaint() {
        UiTheme t = UiTheme.current();
        BORDER = t.border();
        ACCENT = t.cta();
        TXT = t.text();
        TXT_MUTED = t.textDim();
        TXT_FAINT = t.faint();
        ON_BAND = t.onBand();
        ON_BAND_FAINT = t.onBandFaint();
        CTA = t.cta();
        ON_CTA = t.onCta();
        FIELD = t.field();
        OK = t.ok();
        RUN = t.run();
        FAIL = t.fail();
    }
    private static net.minecraft.resources.ResourceLocation railSpr(String n) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, n);
    }
    private static final net.minecraft.resources.ResourceLocation AVATAR_FRAME = railSpr("avatar_frame");
    private static final net.minecraft.resources.ResourceLocation AVATAR_FRAME_ACTIVE = railSpr("avatar_frame_active");
    private static final net.minecraft.resources.ResourceLocation SUMMON_SPRITE = railSpr("summon");
    private static final net.minecraft.resources.ResourceLocation SUMMON_ACTIVE = railSpr("summon_active");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_UP = railSpr("chevron_up");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_DOWN = railSpr("chevron_down");

    private UUID uuid;       // active companion (mutable — the rail switches it in place)
    private String name;
    private Tab tab = Tab.CHAT;

    private static final String VOICE_NONE = "__none__";

    /** 召唤页的皮肤下拉:null = 默认(按名字找同名正版)。 */
    private Dropdown summonSkinDropdown;
    private String summonSkinId;
    private static final String SKIN_DEFAULT = "__default__";

    /** Persona chosen for the companion currently being summoned (null = default / none). */
    private String summonPersonaId;
    private Dropdown summonPersonaDropdown;
    /** Provider entry for the new companion — REQUIRED (no default, no fallback). */
    private Dropdown summonProviderDropdown;
    /** 召唤时的游戏模式选择(默认生存;创造在服务端过权限门)。 */
    private Dropdown summonModeDropdown;
    private boolean summonCreative;
    private String summonProviderId;
    /** Voice entry for the new companion — optional (null = silent). */
    private Dropdown summonVoiceDropdown;
    private String summonVoiceId;
    private static final String PERSONA_DEFAULT = "__default__";

    /** 聊天输入行(NumenUI):四颗图标钮 + 输入框,见 ChatInputBar。 */
    private com.dwinovo.numen.client.screen.chat.ChatInputBar inputBar;
    private String savedInput = "";

    // "+" summon flow: a transient name field shown over the panel
    private boolean summoning;
    private EditBox summonInput;
    /** 屏幕级浮层根:承载模态确认卡(遣散同伴);浮层在场时背景全屏蔽。 */
    private final com.dwinovo.numen.client.ui.widget.UiRoot overlayUi =
            new com.dwinovo.numen.client.ui.widget.UiRoot();
    private final com.dwinovo.numen.client.ui.widget.ConfirmDialog dismissDialog =
            new com.dwinovo.numen.client.ui.widget.ConfirmDialog();

    /** The Settings tab, extracted whole (state + build + render + input) — see SettingsView. */
    private final com.dwinovo.numen.client.screen.settings.SettingsView settings =
            new com.dwinovo.numen.client.screen.settings.SettingsView(
                    new com.dwinovo.numen.client.screen.settings.SettingsView.Host() {
                        @Override public <T extends AbstractWidget> T add(T w) { return NumenScreen.this.add(w); }
                        @Override public void rebuild() { NumenScreen.this.rebuild(); }
                        @Override public void focus(AbstractWidget w) { setInitialFocus(w); }
                        @Override public Font font() { return NumenScreen.this.font; }
                        @Override public int left() { return left; }
                        @Override public int top() { return top; }
                        @Override public int panelW() { return panelW; }
                        @Override public int panelH() { return panelH; }
                        @Override public int railX() { return railX; }
                        @Override public UUID uuid() { return uuid; }
                        @Override public void warnPulse() { warnUntil = System.currentTimeMillis() + 4000; }
                        @Override public void tip(List<Component> lines, int x, int y) {
                            pendingTip = lines;
                            pendingTipX = x;
                            pendingTipY = y;
                        }
                        @Override public void repaintPalette() { repaint(); }
                    });

    private String micNotice;
    private long micNoticeUntil;
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

    // geometry resolved in init()
    private int left, top, railX;
    private int panelW = PANEL_MIN_W, panelH = PANEL_MIN_H;   // resolved in init() from the window size
    private final int[] tabX = new int[3];   // left x of each tab label, for click hit-testing
    private final int[] tabW = new int[3];

    /** Chat transcript view (bubbles + tool chips + eased scroll); reset on companion/tab switch. */
    private final com.dwinovo.numen.client.screen.chat.ChatView chatView =
            new com.dwinovo.numen.client.screen.chat.ChatView(
                    Minecraft.getInstance().font, this::loop, () -> name, () -> uuid);
    private int railScroll;        // index of the first visible rail avatar (wheel-scroll when many companions)

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
        inputBar = null; savedInput = "";       // don't carry typed text across companions
        uuid = u; name = n;
        chatView.reset();
        rebuild();
        if (tab == Tab.ITEMS && u != null) requestInventory();
    }

    private EntityAgentLoop loop() {
        return AgentLoopRegistry.getOrCreate(uuid);
    }

    @Override
    protected void init() {
        // 窗口留 12px 边距后能给多大给多大,夹在上下限之间;窗口比下限还小时
        // railX/top 至少钳到 0,保证头部(标题/tab/关闭途径)永远可见可点。
        panelW = Math.clamp(this.width - RAIL_W - 24, PANEL_MIN_W, PANEL_MAX_W);
        panelH = Math.clamp(this.height - 24, PANEL_MIN_H, PANEL_MAX_H);
        int composite = RAIL_W + panelW;        // rail flush against the panel — one merged sprite
        this.railX = Math.max(0, (this.width - composite) / 2);
        this.left = railX + RAIL_W;
        this.top = Math.max(0, (this.height - panelH) / 2);
        layoutTabs();
        rebuild();
    }

    private static String[] tabLabels() {
        return new String[]{
                I18n.get("numen.tab.chat"), I18n.get("numen.tab.status"), I18n.get("numen.tab.settings")};
    }

    private void layoutTabs() {
        String[] labels = tabLabels();
        int x = left + panelW - PAD;
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
        if (inputBar != null) savedInput = inputBar.text();
        clearWidgets();
        overlay.clear();
        inputBar = null;
        settings.clearWidgets();
        summonInput = null;
        summonSkinDropdown = null;
        summonPersonaDropdown = null;
        summonProviderDropdown = null;
        summonVoiceDropdown = null;
        if (summoning) { buildSummonField(); return; }
        switch (tab) {
            case CHAT -> { if (uuid != null) buildChatWidgets(); }
            case SETTINGS -> settings.buildWidgets();
            case ITEMS -> { /* no widgets */ }
        }
    }

    // ---- summon modal card: 居中卡 + 暗幕,当前 tab 内容照常渲染作背景 ----
    private static final int SUMMON_CARD_H = 208;
    private int sumCardW() { return Math.min(320, panelW - 24); }
    private int sumCardX() { return left + (panelW - sumCardW()) / 2; }
    private int sumCardY() { return top + Math.max(10, (panelH - SUMMON_CARD_H) / 2); }
    private int sumCardBottom() { return sumCardY() + Math.min(SUMMON_CARD_H, panelH - 20); }
    /** 卡内内容左缘 / 宽 / 行基准(行偏移沿用原布局表)。 */
    private int sumX() { return sumCardX() + 10; }
    private int sumW() { return sumCardW() - 20; }
    private int sumY0() { return sumCardY() + 2; }

    /** Row layout (offsets from sumY0()) — each control gets its own label row,
     *  drawn in the render pass at these SAME offsets (keep the two in lockstep):
     *  8 title · 24 名字 label · 34 name field · 58 人设 label · 68 persona dropdown ·
     *  92 模型配置 label · 102 provider dropdown · 126 声线 label · 136 voice dropdown ·
     *  162 buttons · 186 hint/warn. */
    private void buildSummonField() {
        // 人设下拉的数据源是 persona/ 目录:每次打开召唤面板重扫一遍。
        com.dwinovo.numen.persona.PersonaLibrary.instance().reload();
        int y0 = sumY0();
        summonInput = new FlatEditBox(font, sumX() + FIELD_INSET_X, y0 + 34 + FIELD_INSET_Y,
                sumW() - FIELD_INSET_X * 2, 18 - FIELD_INSET_Y * 2, Component.literal(""));
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
        summonPersonaDropdown.setBounds(sumX(), y0 + 68, sumW(), 18);
        summonPersonaDropdown.setDropBottom(top + panelH - 2);
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
            // 模型行与模式下拉平分一行(左模型右模式)。
            summonProviderDropdown.setBounds(sumX(), y0 + 102, summonHalfW(), 18);
            summonProviderDropdown.setDropBottom(top + panelH - 2);
        }
        // 游戏模式:有 gamemode 权限(等级 2,原版已同步到客户端)才给下拉自选;
        // 没有就继承主人当前档(非创造一律按生存),渲染为置灰不可点 + 悬停说明。
        boolean canChooseMode = this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.hasPermissions(2);
        if (canChooseMode) {
            summonModeDropdown = new Dropdown(List.of(
                    new Dropdown.Item("survival", "生存"),
                    new Dropdown.Item("creative", "创造")),
                    summonCreative ? "creative" : "survival");
            summonModeDropdown.setBounds(sumX() + summonHalfW() + 6, y0 + 102, summonHalfW(), 18);
            summonModeDropdown.setDropBottom(top + panelH - 2);
        } else {
            summonModeDropdown = null;
            summonCreative = this.minecraft != null && this.minecraft.player != null
                    && this.minecraft.player.isCreative();
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
            summonVoiceDropdown.setBounds(sumX(), y0 + 136, summonHalfW(), 18);
            summonVoiceDropdown.setDropBottom(top + panelH - 2);
        }
        // 皮肤:默认(按名字找同名正版) + 皮肤库里已签名的条目。
        List<Dropdown.Item> skinItems = new ArrayList<>();
        skinItems.add(new Dropdown.Item(SKIN_DEFAULT, I18n.get(ModLanguageData.Keys.SUMMON_SKIN_DEFAULT)));
        for (var e : com.dwinovo.numen.client.skin.SkinLibrary.instance().list()) {
            if (e.signed()) skinItems.add(new Dropdown.Item(e.id(), e.name()));
        }
        summonSkinDropdown = new Dropdown(skinItems, summonSkinId == null ? SKIN_DEFAULT : summonSkinId);
        summonSkinDropdown.setBounds(sumX() + summonHalfW() + 6, y0 + 136, summonHalfW(), 18);
        summonSkinDropdown.setDropBottom(top + panelH - 2);
        // Explicit actions — Enter stays as the fallback confirm (keyPressed), the
        // buttons are the primary path.
        int bw = 64, gap = 8, totalW = bw * 2 + gap;
        int bx = sumX() + (sumW() - totalW) / 2;
        add(new SimpleButton(bx, y0 + 162, bw, 18, Component.translatable("numen.gui.settings.cancel"),
                b -> { summoning = false; rebuild(); }));
        add(new SimpleButton(bx + bw + gap, y0 + 162, bw, 18,
                Component.translatable(ModLanguageData.Keys.SUMMON_CREATE),
                b -> doSummon()).primary());
        setInitialFocus(summonInput);
    }

    /** 召唤卡"声线|皮肤"共享行的半宽。build 与 render 共用。 */
    private int summonHalfW() {
        return (sumW() - 6) / 2;
    }

    /**
     * 召唤页四个下拉的点击路由:正展开的先吃(它的列表画在最上层,命中也必须
     * 最优先),然后按行序。返回 true = 消费了本次点击。
     */
    private boolean routeSummonDropdownClick(double mx, double my) {
        Dropdown[] all = {summonPersonaDropdown, summonProviderDropdown, summonModeDropdown,
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
            } else if (d == summonModeDropdown) {
                summonCreative = "creative".equals(sel);
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

    /** 召唤页下拉的渲染:收起的先画,正展开的最后画(列表压在一切之上)。 */
    private void renderSummonDropdowns(GuiGraphics g, int mouseX, int mouseY) {
        Dropdown[] all = {summonSkinDropdown, summonVoiceDropdown, summonModeDropdown,
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

    /** 遣散确认:危险操作的最后一道闸——卡外点击吞掉、Esc 取消、删除钮红色。 */
    private void openDismissConfirm(UUID target) {
        dismissDialog.open(overlayUi, railX, top, RAIL_W + panelW, panelH,
                I18n.get("numen.dismiss.title", nameFor(target)),
                I18n.get("numen.dismiss.warning"),
                I18n.get("numen.gui.settings.cancel"), I18n.get("numen.dismiss.delete"),
                () -> {
                    Services.NETWORK.sendToServer(
                            new com.dwinovo.numen.network.payload.DismissRequestPayload(target));
                    if (target.equals(uuid)) {   // 走的是当前这只:跳到另一只/回空屏
                        NumenRoster.Entry next = firstOther(target);
                        if (next != null) {
                            switchTo(next.uuid(), next.name());
                            return;
                        }
                        uuid = null;
                        name = null;
                    }
                    rebuild();
                });
        rebuild();
    }

    /** 模态确认卡在场——屏幕据此屏蔽背景交互。 */
    private boolean dismissOpen() {
        return dismissDialog.isOpen();
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
        Nb.text(g, font, c, x, y, color);
    }

    /** The FormattedCharSequence must already carry its colour in its Style. */
    private void txt(GuiGraphics g, FormattedCharSequence c, int x, int y, int color) {
        Nb.text(g, font, c, x, y);
    }


    private static net.minecraft.resources.ResourceLocation chatIcon(String n) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                com.dwinovo.numen.Constants.MOD_ID, n);
    }
    private static final net.minecraft.resources.ResourceLocation ICON_SEND = chatIcon("icon_send");
    private static final net.minecraft.resources.ResourceLocation ICON_MIC = chatIcon("icon_mic");
    private static final net.minecraft.resources.ResourceLocation ICON_STOP = chatIcon("icon_stop");
    private static final net.minecraft.resources.ResourceLocation ICON_COMPACT = chatIcon("icon_compact");

    private void buildChatWidgets() {
        int inputY = top + panelH - INPUT_H - PAD;
        inputBar = new com.dwinovo.numen.client.screen.chat.ChatInputBar(
                new ChatBarHost(), ICON_COMPACT, ICON_MIC, ICON_SEND, ICON_STOP);
        inputBar.build(left + PAD, inputY, panelW - PAD * 2, INPUT_H);
        if (!savedInput.isEmpty()) {
            inputBar.setText(savedInput);
            savedInput = "";
        }
    }

    /** 输入行的宿主回调面:发言闸门与可按性判据都在屏幕这边。 */
    private final class ChatBarHost implements com.dwinovo.numen.client.screen.chat.ChatInputBar.Host {
        @Override public void onSend(String text) { submitChat(text); }

        @Override public void onMicToggle() { NumenScreen.this.onMicToggle(); }

        @Override public void onCompact() { loop().requestCompact(); }

        @Override public void onAbort() { loop().abort(); }

        @Override public boolean canCompact() { return loop().canCompact(); }

        @Override public boolean canAbort() { return loop().canInterrupt(); }

        @Override public boolean inputLocked() {
            // 「外接大脑」模式:发言入口整排锁死。真正的互斥在 EntityAgentLoop 的开轮
            // 闸门上——这里只是把"按了没反应"提前成"按不下去"。叫停键不锁:那是主人
            // 的急刹车,外部 AI 抽风时更需要它。
            return com.dwinovo.numen.mcp.server.McpMode.instance().enabled();
        }

        @Override public String hint() {
            if (inputLocked()) return I18n.get("numen.brain.chat_locked");
            if (micNotice != null && micNoticeUntil > System.currentTimeMillis()) return micNotice;
            return I18n.get("numen.chat.hint", name == null ? "" : name);
        }
    }

    /** 正常的输入框占位文案("说点什么…, {name}");麦克风状态提示消失后用它复位。 */
    /** 麦克风按钮:点击开录/再点停;转写文本(批量结尾一次、流式边说边刷)落进输入框。 */
    private void onMicToggle() {
        com.dwinovo.numen.client.stt.VoiceInputController.toggle(
                Services.CONFIG,
                text -> { if (inputBar != null) inputBar.setText(text); },
                // 状态提示(未配置/无麦克风/失败)落在输入框的占位文案上——眼睛正看的
                // 地方,醒目却不写进真实输入(输入行每帧现取 hint());框里已有文字时
                // 占位不显示,由渲染里的底部一行兜底。
                status -> {
                    micNotice = status;
                    micNoticeUntil = System.currentTimeMillis() + 4000;
                });
        // 录音中图标换成停止方块——同一颗键,两种含义都一眼可读。
        if (inputBar != null) {
            inputBar.setRecording(com.dwinovo.numen.client.stt.VoiceInputController.isActive());
        }
    }

    private void selectTab(Tab t) {
        if (t == tab) return;
        tab = t;
        chatView.reset();
        if (t == Tab.ITEMS) requestInventory();
        rebuild();
    }

    /** Shadowless placeholder for an empty, unfocused field — the EditBox's own hint renders with a shadow. */
    private void placeholder(GuiGraphics g, EditBox f, String text) {
        if (f != null && f.visible && f.getValue().isEmpty() && !f.isFocused()
                && text != null && !text.isEmpty()) {
            txt(g, Component.literal(text), f.getX(), f.getY(), TXT_FAINT);
        }
    }

    /** 皮肤 png 从系统拖进游戏窗口——皮肤表单打开时由 SettingsView 接住。 */
    @Override
    public void onFilesDrop(List<java.nio.file.Path> paths) {
        if (tab == Tab.SETTINGS) settings.onFilesDrop(paths);
    }

    /** BlockFrame workspace chrome, drawn procedurally from the CURRENT theme — border frame,
     *  rail column, header band + underline, panel ground with the 16px dot grid. Replaces the
     *  old WARM-baked workspace sprite so a theme switch recolours the whole frame. */
    private void drawWorkspace(GuiGraphics g) {
        UiTheme t = UiTheme.current();
        int x0 = railX, y0 = top, x1 = railX + RAIL_W + panelW, y1 = top + panelH;
        g.fill(x0, y0, x1, y1, t.border());                          // frame + rail divider base
        g.fill(x0 + 3, y0 + 3, x0 + RAIL_W, y1 - 3, t.ground());     // rail column
        g.fill(left + 3, y0 + 3, x1 - 3, y0 + 20, t.band());         // header band (underline = border gap)
        g.fill(left + 3, y0 + HEADER_H, x1 - 3, y1 - 3, t.ground()); // panel ground
        for (int dy = y0 + HEADER_H + 7; dy < y1 - 5; dy += 16) {    // dot grid (translucent theme dot)
            for (int dx = left + 10; dx < x1 - 5; dx += 16) {
                g.fill(dx, dy, dx + 2, dy + 2, t.dot());
            }
        }
    }

    /** 显示过滤统一走 {@link com.dwinovo.numen.client.chat.ChatDisplayFilter}(可整体切换)。 */
    /** token 数的人读格式:1350 → "1.4k",132400 → "132.4k",1_200_000 → "1.2m"。 */
    private static String fmtTokens(long n) {
        if (n >= 1_000_000) return String.format("%.1fm", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
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

    /** The active companion's current persona name (green marker in the list), or null. */
    private String activePersonaName() {
        if (uuid == null) return null;
        return AgentLoopRegistry.get(uuid).map(EntityAgentLoop::personaName).orElse(null);
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

    /** 发言闸门:模式开着时一并挡掉(回车绕过了被禁用的发送键,否则消息会进
     *  内置大脑的收件箱、在模式关闭后突然诈尸开轮)。 */
    private void submitChat(String text) {
        if (com.dwinovo.numen.mcp.server.McpMode.instance().enabled()) return;
        if (text == null || text.isBlank()) return;
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
        if (inputBar != null) inputBar.setText("");
        chatView.pinToBottom();
    }

    // ---- input ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int k = keyCode;
        if (dismissOpen()) {   // 确认卡在场:Esc = 取消(UiRoot 浮层通道保证),其余键不下传
            if (overlayUi.keyPressed(keyCode, modifiers)) { rebuild(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // 设置页的模态(删除确认卡 / 新建编辑表单卡):Esc 收起卡片而不是关掉整个面板。
        if (k == 256 && tab == Tab.SETTINGS && !summoning
                && settings.cancelForm()) {
            return true;
        }
        // "连接"分区的内嵌 NumenUI 面板(输入框光标键/粘贴、下拉 Esc 收浮层)。
        if (tab == Tab.SETTINGS && !summoning && settings.keyPressed(keyCode, modifiers)) {
            return true;
        }
        if (summoning) {
            if (k == 257 || k == 335) { doSummon(); return true; }    // Enter
            if (k == 256) { summoning = false; rebuild(); return true; } // Esc cancels (doesn't close panel)
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (tab == Tab.CHAT && inputBar != null && inputBar.keyPressed(keyCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (tab == Tab.SETTINGS && !summoning && settings.charTyped(ch)) {
            return true;
        }
        if (tab == Tab.CHAT && !summoning && inputBar != null && inputBar.charTyped(ch)) {
            return true;
        }
        return super.charTyped(ch, modifiers);
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
                new com.dwinovo.numen.network.payload.SummonRequestPayload(n, skinValue, skinSig,
                        summonCreative));
        summoning = false;
        summonPersonaId = null;
        summonProviderId = null;
        summonVoiceId = null;
        summonCreative = false;
        rebuild();   // the new companion arrives via CompanionListPayload — click its avatar to open
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 崩溃护栏:点击处理出错按"未消费"降级,面板还能继续用
        return com.dwinovo.numen.client.ui.SafeUi.click("panel-click",
                () -> mouseClickedInner(mouseX, mouseY, button));
    }

    private boolean mouseClickedInner(double mouseX, double mouseY, int button) {
        if (dismissOpen()) {   // 确认卡:卡上按钮生效,卡外点击一律吞掉(危险操作不给误触留门)
            boolean handled = overlayUi.mouseClicked(mouseX, mouseY, button);
            if (!dismissOpen()) rebuild();   // 卡关了(取消/确认):背景 widget 复位
            return handled;
        }
        if (!summoning && tab == Tab.SETTINGS && settings.formActive()) {
            // 设置页的表单模态:先给表单自己的下拉路由,其余只放行 widget 通道
            // (卡上字段/按钮),侧栏/页签/背景列表全部屏蔽。
            if (button == 0 && settings.mouseClicked(mouseX, mouseY)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            // Summon dropdowns get first pick (their open lists overlay the panel).
            // 遮挡关系:先路由"正展开"的那一个——下排下拉向上翻时,展开列表盖住
            // 上排的折叠框,固定顺序会让上排先吞掉点击。
            if (summoning && routeSummonDropdownClick(mouseX, mouseY)) {
                return true;
            }
            UUID close = railCloseAt((int) mouseX, (int) mouseY);
            if (close != null) {   // ✕ → 模态确认卡(退出召唤态,免得背景还留着召唤控件)
                summoning = false;
                openDismissConfirm(close);
                return true;
            }
            if (railPlusAt((int) mouseX, (int) mouseY)) {   // + → start the summon name prompt
                summoning = !summoning;
                if (summoning) { summonPersonaId = null; summonVoiceId = null; summonSkinId = null; summonCreative = false; }   // fresh summon starts at "默认/无/生存"
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
            if (summoning) {
                // 召唤模态:页签/聊天/设置全在暗幕之下,只放行 widget 通道(卡上控件);
                // 侧栏的 +/头像/✕ 在上面已处理(保留为模态的逃生口)。
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (tab == Tab.SETTINGS && settings.mouseClicked(mouseX, mouseY)) return true;
            int my = (int) mouseY;
            if (my >= top && my < top + HEADER_H) {
                for (int i = 0; i < 3; i++) {
                    if (mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i]) {
                        selectTab(Tab.values()[i]);
                        return true;
                    }
                }
            }
            if (tab == Tab.CHAT && inputBar != null
                    && inputBar.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (tab == Tab.CHAT && chatView.mouseClicked(mouseX, mouseY)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 声线表单的音量滑条拖动(NumenUI 面板)。
        if (tab == Tab.SETTINGS && !summoning && settings.mouseDragged(mx, my, dx, dy)) {
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (tab == Tab.SETTINGS && !summoning && settings.mouseReleased(mx, my, button)) {
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 模态确认卡在场:背景(侧栏名册/设置列表)不响应滚轮。
        if (dismissOpen()) {
            return false;
        }
        // 打开着的下拉列表优先吃滚轮(列表被面板截断时滚动余下的行)。
        if (sy != 0) {
            for (Dropdown d : new Dropdown[]{summonSkinDropdown, summonPersonaDropdown,
                    summonProviderDropdown, summonVoiceDropdown}) {
                if (d != null && d.mouseScrolled(mx, my, sy)) return true;
            }
        }
        if (summoning) return false;   // 召唤模态:背景(侧栏/聊天/设置)不响应滚轮
        if (tab == Tab.SETTINGS && settings.formActive()) {
            // 表单模态:只放行表单自己的滚动(下拉列表 + 声线表单视口),背景列表/侧栏屏蔽。
            return sy != 0 && settings.mouseScrolledEarly(mx, my, sy);
        }
        // 设置页第一段:表单下拉 + 声线表单整体滚动(顺位与拆分前一致)。
        if (sy != 0 && tab == Tab.SETTINGS && settings.mouseScrolledEarly(mx, my, sy)) return true;
        // Wheel over the left rail column scrolls the roster (works on any tab).
        if (sy != 0 && mx >= railX && mx < railX + RAIL_W && maxRailScroll() > 0) {
            railScroll = Math.clamp((long) (railScroll - sy), 0, maxRailScroll());
            return true;
        }
        if (tab == Tab.CHAT && sy != 0) {
            return chatView.mouseScrolled(sy);
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ---- render ----

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // 崩溃护栏:面板渲染的任何异常都不许带走游戏——降级成一行红字
        if (!com.dwinovo.numen.client.ui.SafeUi.run("panel-render",
                () -> renderInner(g, mouseX, mouseY, partial))) {
            g.drawString(font, "Numen 面板渲染出错,已兜底——详情见 latest.log",
                    left + 10, top + 10, 0xFFFF6B6B, true);
        }
    }

    private void renderInner(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        pendingTip = null;   // recollected each frame by the section renderers

        drawWorkspace(g);                // rail column + panel chrome, in the CURRENT theme's colours
        renderRail(g, mouseX, mouseY);   // avatars + status + summon tile on the rail column

        // 头部一行四个成员从右往左让位:tab(定宽) ← 用量 ← 人设名(可整个消失) ← 名字(最后裁)。
        int headerLimit = tabX[0] - 8;
        if (!summoning && !dismissOpen() && tab == Tab.CHAT && uuid != null) {
            headerLimit = renderUsage(g, mouseX, mouseY) - 8;
        }
        String nm = clip(name == null ? "Numen" : name, Math.max(24, headerLimit - (left + PAD)));
        txt(g, Component.literal(nm), left + PAD, top + 7, ON_BAND);
        int afterName = left + PAD + font.width(nm) + 6;
        if (uuid != null && ClientDeaths.isDead(uuid)) {        // active companion dead — respawn countdown
            long rem = ClientDeaths.remainingMs(uuid);
            String rs = I18n.get("numen.respawn", (int) Math.ceil(rem / 1000.0));
            if (afterName < headerLimit) {
                txt(g, Component.literal(clip(rs, headerLimit - afterName)), afterName, top + 7, ON_BAND);
            }
        } else {
            String pn = activePersonaName();                   // current persona, faint, right after the name
            if (pn != null && afterName + font.width("…") <= headerLimit) {
                txt(g, Component.literal(clip(pn, headerLimit - afterName)), afterName, top + 7, ON_BAND_FAINT);
            }
        }
        renderTabs(g, mouseX, mouseY);

        // 当前 tab 内容永远渲染——召唤模态时它是暗幕下的背景(widgets 只建了召唤卡的,
        // 背景不可交互)。
        switch (tab) {
            case SETTINGS -> settings.render(g, mouseX, mouseY);   // global — works with no companion
            case CHAT -> { if (uuid != null) renderChat(g, mouseX, mouseY); else emptyHint(g); }
            case ITEMS -> {
                if (uuid != null) {
                    com.dwinovo.numen.client.screen.items.ItemsView.render(
                            g, font, uuid, left, top, panelW, panelH, HEADER_H, mouseX, mouseY);
                } else {
                    emptyHint(g);
                }
            }
        }
        if (!summoning && tab == Tab.CHAT && warnUntil > System.currentTimeMillis()) {
            // endpoint-problem hint above the input
            txt(g, warnText != null ? Component.literal(warnText)
                            : Component.translatable("numen.chat.no_key"),
                    left + PAD, top + panelH - INPUT_H - PAD - 11, FAIL);
        }
        if (summoning) {
            // 召唤模态:暗幕 + 居中卡(与确认卡同族),行偏移沿用原布局表。
            g.fill(railX, top, railX + RAIL_W + panelW, top + panelH,
                    (UiTheme.current().border() & 0xFFFFFF) | 0x99000000);
            com.dwinovo.numen.client.ui.RoundRect.card(g, sumCardX(), sumCardY(),
                    sumCardX() + sumCardW(), sumCardBottom(), 6,
                    UiTheme.current().aiFill(), UiTheme.current().aiBorder());
            int y0 = sumY0();   // offsets in lockstep with buildSummonField
            txt(g, Component.translatable("numen.summon.title"), sumX(), y0 + 8, TXT);
            txt(g, Component.translatable(ModLanguageData.Keys.SUMMON_NAME), sumX(), y0 + 24, TXT_MUTED);
            placeholder(g, summonInput, I18n.get(ModLanguageData.Keys.SUMMON_NAME_PLACEHOLDER));
            txt(g, Component.translatable(ModLanguageData.Keys.SUMMON_PERSONA_LABEL), sumX(), y0 + 58, TXT_MUTED);
            txt(g, Component.literal(I18n.get(ModLanguageData.Keys.PROVIDER_TITLE)
                    + (summonProviderDropdown == null ? I18n.get(ModLanguageData.Keys.SUMMON_PROVIDER_EMPTY) : "")),
                    sumX(), y0 + 92, TXT_MUTED);
            txt(g, Component.literal("模式"), sumX() + summonHalfW() + 6, y0 + 92, TXT_MUTED);
            if (summonModeDropdown == null) {
                // 无 gamemode 权限:置灰的继承档,悬停解释为什么点不了
                int mx0 = sumX() + summonHalfW() + 6, my0 = y0 + 102;
                com.dwinovo.numen.client.ui.RoundRect.card(g, mx0, my0,
                        mx0 + summonHalfW(), my0 + 18, 4,
                        UiTheme.current().field(), UiTheme.current().surfaceBorder());
                txt(g, Component.literal((summonCreative ? "创造" : "生存") + "(继承)"),
                        mx0 + 6, my0 + 5, TXT_FAINT);
                if (mouseX >= mx0 && mouseX < mx0 + summonHalfW()
                        && mouseY >= my0 && mouseY < my0 + 18) {
                    g.renderTooltip(font, Component.literal(
                            "没有 gamemode 权限——同伴将继承你当前的模式"), mouseX, mouseY);
                }
            }
            txt(g, Component.literal(I18n.get(ModLanguageData.Keys.VOICE_SUMMON_LABEL)
                    + (summonVoiceDropdown == null ? I18n.get(ModLanguageData.Keys.VOICE_SUMMON_EMPTY) : "")),
                    sumX(), y0 + 126, TXT_MUTED);
            txt(g, Component.translatable(ModLanguageData.Keys.SUMMON_SKIN),
                    sumX() + summonHalfW() + 6, y0 + 126, TXT_MUTED);
            txt(g, Component.translatable("numen.summon.hint"),
                    sumX(), y0 + 186, TXT_FAINT);
        }


        // Widgets render LAST, on top of the panel background (fixes the "dim fields" — the panel fill
        // used to paint over the auto-rendered widgets). Text fields are borderless EditBoxes, so draw
        // the shared rounded field card behind each before it renders its text.
        for (AbstractWidget w : overlay) {
            // visible 检查:声线表单滚出视口的 EditBox 隐藏了自己,框也必须跟着消失
            // (否则空框越过面板边缘悬在世界上)。
            if (w instanceof EditBox eb && eb.visible) {
                // 所有文本字段与气泡同款的圆角奶油卡;聚焦的字段边框亮 CTA。
                com.dwinovo.numen.client.ui.RoundRect.card(g,
                        eb.getX() - FIELD_INSET_X, eb.getY() - FIELD_INSET_Y,
                        eb.getX() + eb.getWidth() + FIELD_INSET_X,
                        eb.getY() + eb.getHeight() + FIELD_INSET_Y, 5,
                        UiTheme.current().aiFill(),
                        eb.isFocused() ? UiTheme.current().cta() : UiTheme.current().aiBorder());
            }
        }
        for (AbstractWidget w : overlay) {
            w.render(g, mouseX, mouseY, partial);
        }
        // Settings-tab overlay pass: field placeholders, voice-form row labels, and the form
        // dropdowns' open lists (drawn last so they sit above the fields) — see SettingsView.
        // 同伴删除模态在场时跳过——占位符/行标题不能画到暗幕上面。
        if (tab == Tab.SETTINGS && !dismissOpen() && !summoning) {
            settings.renderOverlays(g, mouseX, mouseY);
        }
        // (Chat-input placeholder is the FlatEditBox hint now — drawn shadowless and under the
        // caret in the widget pass, so it can't paint over the caret like a screen-side draw did.)
        // Summon warn — shown only when 创建 was clicked and something is missing
        // (error at the action, never ambient text). Takes the hint line's spot.
        if (summoning && warnUntil > System.currentTimeMillis() && warnText != null) {
            g.drawString(font, warnText, sumX(), sumY0() + 186, 0xFFCC6666, false);
        }
        if (summoning) {
            renderSummonDropdowns(g, mouseX, mouseY);
        }

        // 屏幕级浮层(遣散确认卡):暗幕+卡压在一切之上,tooltip 之前。
        overlayUi.render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                com.dwinovo.numen.client.screen.settings.HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());

        // Hovered MCP / skill row tooltip — drawn last so nothing paints over it.
        if (pendingTip != null && !dismissOpen()) {
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
            if (!dismissOpen() && (overAvatar || overBadge)) {
                g.fill(bx, by, bx + 9, by + 9, FAIL);
                Nb.border(g, bx, by, 9, 9, 1, BORDER);
                txt(g, Component.literal("✕"), bx + 2, by + 1, ON_BAND);
            }
        }
        // "+" summon tile (baked "+" glyph), pinned to the rail bottom
        int py = top + panelH - PAD - RAIL_AV;
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
        return top + panelH - PAD - RAIL_AV - RAIL_BOT_GAP;
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
        int py = top + panelH - PAD - RAIL_AV;
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
        return com.dwinovo.numen.client.agent.KnownSkins.of(u);
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

    // ---- chat transcript + plan ----

    /** 头部右侧(tab 左边)的上下文水位+累计消耗。恒定淡色——这是信息不是警报,
     *  临近水位线会自动压缩,不需要玩家做任何事。返回文字左边界,标题据此让位。 */
    private int renderUsage(GuiGraphics g, int mouseX, int mouseY) {
        int pct = loop().contextPercent();
        long total = loop().totalTokensUsed();
        if (pct <= 0 && total <= 0) return tabX[0];
        String s = (pct > 0 ? "context " + pct + "%" : "")
                + (pct > 0 && total > 0 ? " · " : "")
                + (total > 0 ? fmtTokens(total) + " tokens" : "");
        int tx = tabX[0] - 10 - font.width(s);
        txt(g, Component.literal(s), tx, top + 7, TXT_FAINT);
        if (mouseX >= tx && mouseX < tabX[0] - 10 && mouseY >= top + 5 && mouseY < top + 17) {
            g.renderComponentTooltip(font, List.of(
                    Component.translatable("numen.chat.usage_tip.context"),
                    Component.translatable("numen.chat.usage_tip.tokens"),
                    Component.translatable("numen.chat.usage_tip.cache")), mouseX, mouseY);
        }
        return tx;
    }

    private void renderChat(GuiGraphics g, int mouseX, int mouseY) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + panelH - INPUT_H - PAD - 6;
        int transX = left + PAD;
        int transW = panelW - PAD * 2 - PLAN_W - 8;

        // right-side PLAN card + the bubble transcript
        int planX = transX + transW + 8;
        com.dwinovo.numen.client.screen.chat.PlanCard.render(
                g, font, loop(), planX - 4, bodyY, PLAN_W + 4, bodyBottom);
        // 「外接大脑」模式:对话流换成控制台——身体归外部 AI,这屏就该显示它在干嘛。
        if (com.dwinovo.numen.mcp.server.McpMode.instance().enabled()) {
            chatView.renderConsole(g, transX, bodyY, transW, bodyBottom - bodyY);
        } else {
            chatView.render(g, transX, bodyY, transW, bodyBottom - bodyY);
        }

        boolean noticeLive = micNotice != null && micNoticeUntil > System.currentTimeMillis();
        if (!noticeLive && micNoticeUntil != 0) {   // 过期一次性复位(占位文案由输入行现取)
            micNoticeUntil = 0;
            micNotice = null;
        }
        // 框里已有文字时占位不显示,这条兜底行接管(用醒目的 FAIL 色)
        if (noticeLive && inputBar != null && !inputBar.text().isEmpty()) {
            txt(g, Component.literal(micNotice), left + PAD, top + panelH - INPUT_H - PAD - 11, FAIL);
        }

        // 输入行(NumenUI):四颗图标钮 + 输入框;悬停提示由屏幕层画(定位是宿主的事)。
        if (inputBar != null) {
            inputBar.render(g, mouseX, mouseY, net.minecraft.Util.getMillis(),
                    com.dwinovo.numen.client.screen.settings.HostThemeColors.current());
            String tip = inputBar.tooltipAt(mouseX, mouseY);
            if (tip != null) {
                pendingTip = java.util.List.of(Component.literal(tip));
                pendingTipX = mouseX;
                pendingTipY = mouseY;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
