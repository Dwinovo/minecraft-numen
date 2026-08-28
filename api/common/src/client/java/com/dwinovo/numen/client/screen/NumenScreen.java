package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.ui.TokenFormat;
import com.dwinovo.numen.agent.llm.NumenLlmClient;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.data.ClientNumenState;
import com.dwinovo.numen.data.ModLanguageData;
import com.dwinovo.numen.network.payload.RequestStatePayload;
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
import com.dwinovo.numen.client.skin.CompanionFace;
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
import net.minecraft.util.Mth;

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
    // 面板随窗口伸缩,两端夹住:下限保证小窗口下不挤,上限挡住大屏上的无限变宽
    // ——行宽超过阅读舒适区就不再跟了。
    // 不学原版的固定尺寸(箱子/工作台从不随窗口伸缩):那样大窗口下是大面板稀内容,
    // 字的视觉占比被稀释,显小显散。
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
        return new net.minecraft.resources.ResourceLocation(com.dwinovo.numen.Constants.MOD_ID, n);
    }
    /** 悬停编辑铅笔的位图(16×16,[x][y]):照 Lucide pencil 的形。45° 杆用轴向/横向
     *  坐标解析光栅化——u=y-x 沿杆推进(右上端 -10 → 左下笔尖 +10),v=x+y 横跨杆宽,
     *  |v-16|≤宽 即笔体;尾端收圆一像素,u≥8 收成笔尖。描边由渲染时对掩码外扩一像素得到。 */
    private static final boolean[][] PENCIL_MASK = buildPencilMask();

    private static boolean[][] buildPencilMask() {
        boolean[][] m = new boolean[16][16];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                int u = y - x, v = x + y;
                if (u < -10 || u > 10) continue;
                int w = u == -10 ? 1 : u <= 7 ? 2 : u <= 9 ? 1 : 0;
                m[x][y] = Math.abs(v - 16) <= w;
            }
        }
        return m;
    }

    /** 头部编辑铅笔的横座标;-1 = 本帧没画(无同伴/模态中),点不中。 */
    private int editPencilX = -1;

    private boolean overEditPencil(double mx, double my) {
        return editPencilX >= 0 && mx >= editPencilX - 1 && mx < editPencilX + 16
                && my >= top + 3 && my < top + 19;
    }

    /** 画一支 Lucide 形铅笔(16 格位图):笔体单色可指定,深色一像素外描边。 */
    private void drawPencil(GuiGraphics g, int ox, int oy, int bodyColor) {
        for (int gx = 0; gx < 16; gx++) {
            for (int gy = 0; gy < 16; gy++) {
                if (PENCIL_MASK[gx][gy]) {
                    boolean capLine = gy - gx == -6;   // 笔帽分割线(横跨杆宽的一道)
                    g.fill(ox + gx, oy + gy, ox + gx + 1, oy + gy + 1,
                            capLine ? 0xFF1F1F1F : bodyColor);
                } else if (nearPencil(gx, gy)) {
                    g.fill(ox + gx, oy + gy, ox + gx + 1, oy + gy + 1, 0xFF1F1F1F);
                }
            }
        }
    }

    /** (gx,gy) 不在笔体上但与笔体八邻接——描边像素。 */
    private static boolean nearPencil(int gx, int gy) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int nx = gx + dx, ny = gy + dy;
                if (nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && PENCIL_MASK[nx][ny]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final net.minecraft.resources.ResourceLocation CHEVRON_UP = railSpr("chevron_up");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_DOWN = railSpr("chevron_down");

    private UUID uuid;       // active companion (mutable — the rail switches it in place)
    private String name;
    private Tab tab = Tab.CHAT;


    /** 召唤页的皮肤下拉:null = 默认(按名字找同名正版)。 */

    /** Provider entry for the new companion — REQUIRED (no default, no fallback). */
    /** 召唤时的游戏模式选择(默认生存;创造在服务端过权限门)。 */
    /** Voice entry for the new companion — optional (null = silent). */

    /** 聊天输入行(NumenUI):四颗图标钮 + 输入框,见 ChatInputBar。 */
    private com.dwinovo.numen.client.screen.chat.ChatInputBar inputBar;
    private String savedInput = "";

    // "+" summon flow:居中卡 + 暗幕,当前 tab 内容照常渲染作背景
    private boolean summoning;
    /** 召唤卡(NumenUI):名字 + 人设/模型配置/模式/声线/皮肤,见 SummonPanel。 */
    private SummonPanel summonPanel;
    // 编辑流:再点激活头像打开,同款居中卡;每个选择当场落地,见 CompanionEditPanel。
    private boolean editing;
    private CompanionEditPanel editPanel;
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

    /** {@code /numen settings} 入口:直接落在设置页(全局配置与同伴无关,空面板也能用)。 */
    public static void openSettings() {
        var entries = NumenRoster.instance().entries();
        NumenRoster.Entry first = entries.isEmpty() ? null : entries.get(0);
        NumenScreen screen = first == null
                ? new NumenScreen(null, null) : new NumenScreen(first.uuid(), first.name());
        screen.tab = Tab.SETTINGS;
        Minecraft.getInstance().setScreen(screen);
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
        panelW = Mth.clamp(this.width - RAIL_W - 24, PANEL_MIN_W, PANEL_MAX_W);
        panelH = Mth.clamp(this.height - 24, PANEL_MIN_H, PANEL_MAX_H);
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
        if (summoning) { buildSummonCard(); return; }
        if (editing) { buildEditCard(); return; }
        switch (tab) {
            case CHAT -> { if (uuid != null) buildChatWidgets(); }
            case SETTINGS -> settings.buildWidgets();
            case ITEMS -> { /* no widgets */ }
        }
    }

    private SummonPanel summonPanel() {
        if (summonPanel == null) {
            summonPanel = new SummonPanel(new SummonHost());
        }
        return summonPanel;
    }

    private void buildSummonCard() {
        summonPanel().build(modalX(), modalY0() + 6, modalW(), modalCardBottom() - modalY0(),
                top + panelH - 2);
    }

    /** 召唤卡的宿主面:落库(把选择挂到名字上)与发包留在屏幕这边。 */
    private final class SummonHost implements SummonPanel.Host {
        @Override public void onCreate(SummonPanel.Draft d) {
            // 选择按名字记账;CompanionListPayload 在新同伴到达时套用。
            if (d.personaId != null) com.dwinovo.numen.persona.PersonaLibrary.pendSummon(d.name, d.personaId);
            com.dwinovo.numen.agent.llm.ProviderLibrary.pendSummon(d.name, d.providerId);
            if (d.voiceId != null) com.dwinovo.numen.client.voice.VoiceLibrary.pendSummon(d.name, d.voiceId);
            // 自定义皮肤:库里存好的签名数据现成,直接发。选了库条目的同时记账
            // (UUID 到货时落进绑定),编辑卡才能标出她当前穿的是哪张。
            var skinEntry = com.dwinovo.numen.client.skin.SkinLibrary.instance().get(d.skinId);
            if (skinEntry != null && skinEntry.signed()) {
                com.dwinovo.numen.client.skin.SkinLibrary.pendSummon(d.name, d.skinId);
            }
            if (skinEntry != null && skinEntry.signed()) {
                com.dwinovo.numen.Constants.LOG.info("[numen-skin] 召唤 {}: 用皮肤库条目「{}」",
                        d.name, skinEntry.name());
                sendSummon(d, skinEntry.value(), skinEntry.signature());
                return;
            }
            // 默认(按名字):在本机查 Mojang——走玩家自己的代理,失败也说得清原因
            // (服务端那条路吃 JVM 默认网络,国内经常静默超时)。
            summonPanel().setBusy(I18n.get("numen.summon.fetching_skin"));
            com.dwinovo.numen.client.skin.MojangSkinLookup.fetch(d.name)
                    .thenAccept(r -> Minecraft.getInstance().execute(() -> {
                        if (r.problem() != null) {
                            // 降级不挡召唤(默认皮肤照样能玩),但得让主人知道为什么。
                            // 去处是聊天框而不是卡上的提示行:召唤卡这会儿已经关了,
                            // 而且他多半过一会儿才注意到皮肤不对,那时要能翻得到原因。
                            com.dwinovo.numen.client.chat.ChatLines.notice(d.name,
                                    I18n.get("numen.summon.skin_failed", r.problem()));
                        }
                        var skin = r.skin();
                        sendSummon(d, skin == null ? "" : skin.value(),
                                skin == null ? "" : skin.signature());
                    }));
        }

        private void sendSummon(SummonPanel.Draft d, String skinValue, String skinSig) {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.SummonRequestPayload(
                            d.name, skinValue, skinSig, d.creative));
            summoning = false;
            rebuild();   // 新同伴经 CompanionListPayload 到达——点它的头像即可开工
        }

        @Override public void onCancel() {
            summoning = false;
            rebuild();
        }

        @Override public boolean canChooseMode() {
            // 有 gamemode 权限(等级 2,原版已同步到客户端)才给下拉自选。
            return minecraft != null && minecraft.player != null
                    && minecraft.player.hasPermissions(2);
        }

        @Override public boolean ownerCreative() {
            return minecraft != null && minecraft.player != null && minecraft.player.isCreative();
        }
    }

    private CompanionEditPanel editPanel() {
        if (editPanel == null) {
            editPanel = new CompanionEditPanel(new EditHost());
        }
        return editPanel;
    }

    private void buildEditCard() {
        editPanel().build(modalX(), modalY0() + 6, modalW(), modalCardBottom() - modalY0(),
                top + panelH - 2);
    }

    /** 编辑卡的宿主面:身份、网络动作(模式/皮肤发包)与关卡留在屏幕这边。 */
    private final class EditHost implements CompanionEditPanel.Host {
        @Override public UUID uuid() { return uuid; }

        @Override public String name() { return name == null ? "?" : name; }

        @Override public void onDismiss() {
            editing = false;   // 遣散确认卡顶上来,编辑卡先收——取消确认后回到面板本身
            openDismissConfirm(uuid);
        }

        @Override public void onClose() {
            editing = false;
            rebuild();
        }

        @Override public boolean canChooseMode() {
            // 与服务端 applyGameMode 的门同一判据:有 gamemode 权限,或主人本人在创造。
            return minecraft != null && minecraft.player != null
                    && (minecraft.player.hasPermissions(2) || minecraft.player.isCreative());
        }

        @Override public boolean currentCreative() {
            for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
                if (e.uuid().equals(uuid)) return e.creative();
            }
            return false;
        }

        @Override public void setCreative(boolean creative) {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.SetGameModePayload(uuid, creative));
        }

        @Override public void applySkin(String skinId) {
            UUID target = uuid;   // 异步查询窗口内可能切换同伴:皮肤落到点选择时的那只
            var entry = com.dwinovo.numen.client.skin.SkinLibrary.instance().get(skinId);
            if (entry != null && entry.signed()) {
                sendSkin(target, entry.value(), entry.signature());
                return;
            }
            // 按名字:本机查同名正版(与召唤同一条路);查不到发空值 = 回原版默认皮肤。
            // 保存即关卡,查询过程不占 UI;失败的原因进聊天框留痕。
            String n = name;
            com.dwinovo.numen.client.skin.MojangSkinLookup.fetch(n)
                    .thenAccept(r -> Minecraft.getInstance().execute(() -> {
                        if (r.problem() != null) {
                            com.dwinovo.numen.client.chat.ChatLines.notice(n,
                                    I18n.get("numen.summon.skin_failed", r.problem()));
                        }
                        var skin = r.skin();
                        sendSkin(target, skin == null ? "" : skin.value(),
                                skin == null ? "" : skin.signature());
                    }));
        }

        private void sendSkin(UUID target, String value, String sig) {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.ChangeSkinPayload(target, value, sig));
        }
    }

    // ---- modal cards(召唤/编辑): 居中卡 + 暗幕,当前 tab 内容照常渲染作背景 ----
    private static final int SUMMON_CARD_H = 208;
    private static final int EDIT_CARD_H = 164;
    private int modalCardH() { return summoning ? SUMMON_CARD_H : EDIT_CARD_H; }
    private int modalCardW() { return Math.min(320, panelW - 24); }
    private int modalCardX() { return left + (panelW - modalCardW()) / 2; }
    private int modalCardY() { return top + Math.max(10, (panelH - modalCardH()) / 2); }
    private int modalCardBottom() { return modalCardY() + Math.min(modalCardH(), panelH - 20); }
    /** 卡内内容左缘 / 宽 / 行基准(行偏移沿用原布局表)。 */
    private int modalX() { return modalCardX() + 10; }
    private int modalW() { return modalCardW() - 20; }
    private int modalY0() { return modalCardY() + 2; }

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

    /** 召唤/编辑模态之一在场——背景(页签/聊天/设置)交互一律屏蔽,侧栏留作逃生口。 */
    private boolean modalOpen() {
        return summoning || editing;
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


    private void buildChatWidgets() {
        int inputY = top + panelH - INPUT_H - PAD;
        inputBar = new com.dwinovo.numen.client.screen.chat.ChatInputBar(new ChatBarHost(),
                java.util.EnumSet.allOf(com.dwinovo.numen.client.screen.chat.ChatInputBar.Key.class));
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

        @Override public void onAbort() { loop().abort(); }

        @Override public boolean canAbort() { return loop().canInterrupt(); }

        @Override public String hint() {
            if (micNotice != null && micNoticeUntil > System.currentTimeMillis()) return micNotice;
            return I18n.get("numen.chat.hint", name == null ? "" : name);
        }

        @Override public EntityAgentLoop loop() {
            return uuid == null ? null : NumenScreen.this.loop();
        }

        /** 只注册事件,不进 renderables——画面归 NumenUI。见 {@code McTextInput}。 */
        @Override public void mountInput(AbstractWidget w) { NumenScreen.this.addWidget(w); }

        @Override public void focusInput(AbstractWidget w) { NumenScreen.this.setFocused(w); }

        @Override public void onCommandReply(String reply) {
            showCommandReply(reply);
            chatView.pinToBottom();
        }
    }

    /** 斜杠命令回给主人的话,画在输入框上方,几秒后自己消失。 */
    private java.util.List<String> cmdReply = java.util.List.of();
    private long cmdReplyUntil;

    private void showCommandReply(String reply) {
        if (reply == null || reply.isBlank()) {
            cmdReply = java.util.List.of();
            cmdReplyUntil = 0;
            return;
        }
        cmdReply = java.util.List.of(reply.split("\n"));
        // 行数越多给的时间越长——一屏技能清单三秒看不完。
        cmdReplyUntil = System.currentTimeMillis() + 4000L + cmdReply.size() * 600L;
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

    /** 显示过滤统一走 {@link com.dwinovo.numen.client.chat.ChatDisplayMode}(可整体切换)。 */
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
            Services.NETWORK.sendToServer(new RequestStatePayload(uuid));
        }
    }

    /** 说出去。外脑驱动时话进同一个收件箱、由外脑经 get_events 取走并 say 回话
     *  ——所以那条路不查 provider(外脑不需要):endpoint 检查只拦内脑要开轮的情形。
     *  斜杠命令到不了这儿——输入行在本地跑完了。 */
    private void submitChat(String text) {
        if (text == null || text.isBlank()) return;
        if (!com.dwinovo.numen.mcp.server.McpMode.instance().driving()) {
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
        if (k == 256 && tab == Tab.SETTINGS && !modalOpen()
                && settings.cancelForm()) {
            return true;
        }
        // "连接"分区的内嵌 NumenUI 面板(输入框光标键/粘贴、下拉 Esc 收浮层)。
        if (tab == Tab.SETTINGS && !modalOpen() && settings.keyPressed(keyCode, modifiers)) {
            return true;
        }
        if (summoning) {
            if (k == 256) { summoning = false; rebuild(); return true; } // Esc cancels (doesn't close panel)
            if (summonPanel().keyPressed(keyCode, modifiers)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (editing) {
            if (k == 256) { editing = false; rebuild(); return true; } // Esc 收卡,不关面板
            if (editPanel().keyPressed(keyCode, modifiers)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (tab == Tab.CHAT && inputBar != null && inputBar.keyPressed(keyCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (tab == Tab.SETTINGS && !modalOpen() && settings.charTyped(ch)) {
            return true;
        }
        if (tab == Tab.CHAT && !modalOpen() && inputBar != null && inputBar.charTyped(ch)) {
            return true;
        }
        if (summoning && summonPanel().charTyped(ch)) {
            return true;
        }
        if (editing && editPanel().charTyped(ch)) {
            return true;
        }
        return super.charTyped(ch, modifiers);
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
        if (!modalOpen() && tab == Tab.SETTINGS && settings.formActive()) {
            // 设置页的表单模态:先给表单自己的下拉路由,其余只放行 widget 通道
            // (卡上字段/按钮),侧栏/页签/背景列表全部屏蔽。
            if (button == 0 && settings.mouseClicked(mouseX, mouseY)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            // Summon dropdowns get first pick (their open lists overlay the panel).
            // 遮挡关系:先路由"正展开"的那一个——下排下拉向上翻时,展开列表盖住
            // 上排的折叠框,固定顺序会让上排先吞掉点击。
            if (summoning && summonPanel().mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (editing && editPanel().mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (railPlusAt((int) mouseX, (int) mouseY)) {   // + → start the summon name prompt
                summoning = !summoning;
                editing = false;
                if (summoning) summonPanel().reset();   // 每次开新召唤:默认/无/生存
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
                    if (e.uuid().equals(uuid)) {
                        // 侧栏是纯切换器(Discord 语法):点当前头像不再有动作,
                        // 编辑入口在头部名字旁的铅笔;模态开着时当逃生口收卡。
                        if (editing) { editing = false; rebuild(); }
                        else if (wasSummoning) rebuild();
                    } else {
                        editing = false;
                        switchTo(e.uuid(), e.name());
                    }
                }
                return true;
            }
            if (modalOpen()) {
                // 召唤模态:页签/聊天/设置全在暗幕之下,只放行 widget 通道(卡上控件);
                // 侧栏的 +/头像在上面已处理(保留为模态的逃生口)。
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (tab == Tab.SETTINGS && settings.mouseClicked(mouseX, mouseY)) return true;
            if (uuid != null && !dismissOpen() && overEditPencil(mouseX, mouseY)) {
                editing = true;
                editPanel().reset();   // 开卡:草稿从当下真相取基线
                rebuild();
                return true;
            }
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
        if (tab == Tab.SETTINGS && !modalOpen() && settings.mouseDragged(mx, my, dx, dy)) {
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (tab == Tab.SETTINGS && !modalOpen() && settings.mouseReleased(mx, my, button)) {
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
            if (summoning && summonPanel().mouseScrolled(mx, my, sy)) return true;
            if (editing && editPanel().mouseScrolled(mx, my, sy)) return true;
        }
        if (modalOpen()) return false;   // 召唤/编辑模态:背景(侧栏/聊天/设置)不响应滚轮
        if (tab == Tab.SETTINGS && settings.formActive()) {
            // 表单模态:只放行表单自己的滚动(下拉列表 + 声线表单视口),背景列表/侧栏屏蔽。
            return sy != 0 && settings.mouseScrolledEarly(mx, my, sy);
        }
        // 设置页第一段:表单下拉 + 声线表单整体滚动(顺位与拆分前一致)。
        if (sy != 0 && tab == Tab.SETTINGS && settings.mouseScrolledEarly(mx, my, sy)) return true;
        // Wheel over the left rail column scrolls the roster (works on any tab).
        if (sy != 0 && mx >= railX && mx < railX + RAIL_W && maxRailScroll() > 0) {
            railScroll = (int) Math.max(0L, Math.min((long) maxRailScroll(), (long) (railScroll - sy)));
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
        if (!modalOpen() && !dismissOpen() && tab == Tab.CHAT && uuid != null) {
            headerLimit = renderUsage(g, mouseX, mouseY) - 8;
        }
        String nm = clip(name == null ? "Numen" : name, Math.max(24, headerLimit - (left + PAD)));
        txt(g, Component.literal(nm), left + PAD, top + 7, ON_BAND);
        int afterName = left + PAD + font.width(nm) + 6;
        // 名字旁的铅笔 = 编辑入口("名字在哪,编辑就在哪"的资料页定式);悬停亮 CTA。
        editPencilX = -1;
        if (uuid != null && !modalOpen() && !dismissOpen() && afterName + 18 <= headerLimit) {
            editPencilX = afterName;
            boolean hot = overEditPencil(mouseX, mouseY);
            drawPencil(g, editPencilX, top + 3, hot ? CTA : 0xFFFFFFFF);
            afterName += 18;
        }
        if (uuid != null && NumenRoster.instance().isDead(uuid)) {   // active companion dead — respawn countdown
            // 倒计时归零还没回来 = 周围没有能站的地方,复活在重试。继续显示"0"就是
            // 一个数字卡死不动,教科书级的"看起来坏了"——说清楚在等什么。
            long rem = NumenRoster.instance().remainingMs(uuid);
            String rs = rem <= 0 ? I18n.get(ModLanguageData.Keys.RESPAWN_BLOCKED)
                    : I18n.get("numen.respawn", (int) Math.ceil(rem / 1000.0));
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
        if (!modalOpen() && tab == Tab.CHAT && warnUntil > System.currentTimeMillis()) {
            // endpoint-problem hint above the input
            txt(g, warnText != null ? Component.literal(warnText)
                            : Component.translatable("numen.chat.no_key"),
                    left + PAD, top + panelH - INPUT_H - PAD - 11, FAIL);
        }
        if (summoning) {
            // 召唤模态:暗幕 + 居中卡(与确认卡同族),卡内由 SummonPanel 自绘。
            g.fill(railX, top, railX + RAIL_W + panelW, top + panelH,
                    (UiTheme.current().border() & 0xFFFFFF) | 0x99000000);
            com.dwinovo.numen.client.ui.RoundRect.card(g, modalCardX(), modalCardY(),
                    modalCardX() + modalCardW(), modalCardBottom(), 6,
                    UiTheme.current().aiFill(), UiTheme.current().aiBorder());
            summonPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                    com.dwinovo.numen.client.screen.settings.HostThemeColors.current(),
                    mouseX, mouseY, net.minecraft.Util.getMillis());
            String modeTip = summonPanel().modeTooltipAt(mouseX, mouseY);
            if (modeTip != null) {
                pendingTip = java.util.List.of(Component.literal(modeTip));
                pendingTipX = mouseX;
                pendingTipY = mouseY;
            }
        }
        if (editing) {
            // 编辑模态:同款暗幕 + 居中卡;标题左侧的头像由屏幕补画(面板不碰 GuiGraphics)。
            g.fill(railX, top, railX + RAIL_W + panelW, top + panelH,
                    (UiTheme.current().border() & 0xFFFFFF) | 0x99000000);
            com.dwinovo.numen.client.ui.RoundRect.card(g, modalCardX(), modalCardY(),
                    modalCardX() + modalCardW(), modalCardBottom(), 6,
                    UiTheme.current().aiFill(), UiTheme.current().aiBorder());
            if (uuid != null) {
                CompanionFace.draw(g, uuid, skinFor(uuid), modalX(), modalY0() + 6, 18);
            }
            editPanel().render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                    com.dwinovo.numen.client.screen.settings.HostThemeColors.current(),
                    mouseX, mouseY, net.minecraft.Util.getMillis());
            String modeTip = editPanel().tooltipAt(mouseX, mouseY);
            if (modeTip != null) {
                pendingTip = java.util.List.of(Component.literal(modeTip));
                pendingTipX = mouseX;
                pendingTipY = mouseY;
            }
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
        if (tab == Tab.SETTINGS && !dismissOpen() && !modalOpen()) {
            settings.renderOverlays(g, mouseX, mouseY);
        }
        // Summon warn — shown only when 创建 was clicked and something is missing
        // (error at the action, never ambient text). Takes the hint line's spot.
        if (summoning && warnUntil > System.currentTimeMillis() && warnText != null) {
            g.drawString(font, warnText, modalX(), modalY0() + 186, 0xFFCC6666, false);
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
        railScroll = Mth.clamp(railScroll, 0, maxRailScroll());     // keep valid as the roster grows/shrinks
        int first = railScroll;
        int startY = railStartY();
        for (int i = first; i < entries.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_AV > railBottomEdge()) break;
            NumenRoster.Entry e = entries.get(i);
            boolean active = e.uuid().equals(uuid);
            boolean hovered = mouseX >= ax && mouseX < ax + RAIL_AV
                    && mouseY >= ay && mouseY < ay + RAIL_AV;
            boolean railQuiet = !dismissOpen() && !modalOpen();
            // 选中关系用左缘指示条说话(Discord 服务器栏同语法):长条 = 当前,
            // 悬停未选中出短条 = 可切换。悬停的容器反应与"+"号同语法:边框亮 CTA。
            com.dwinovo.numen.client.ui.RoundRect.card(g, ax - 2, ay - 2,
                    ax + RAIL_AV + 2, ay + RAIL_AV + 2, 3,
                    FIELD, !active && hovered && railQuiet ? CTA : BORDER);
            CompanionFace.draw(g, e.uuid(), skinFor(e.uuid()), ax, ay, RAIL_AV);
            int pillH = active ? RAIL_AV - 6 : (hovered && railQuiet ? 8 : 0);
            if (pillH > 0) {
                int py2 = ay + (RAIL_AV - pillH) / 2;
                g.fill(railX + 1, py2, railX + 3, py2 + pillH, ACCENT);
            }
            if (hovered && !active && railQuiet) {
                // 未选中悬停给名字(即时渲染,无网页式延迟);当前那只的名字在头部常驻。
                pendingTip = java.util.List.of(Component.literal(e.name()));
                pendingTipX = mouseX;
                pendingTipY = mouseY;
            }
            if (e.dead()) {                                           // dead — dim veil + respawn countdown
                g.fill(ax, ay, ax + RAIL_AV, ay + RAIL_AV, 0xB0101010);
                long rem = e.remainingMs();
                // 头像太小写不下字:归零改画一个"等"字记号,细节交给上面的头部行
                String c = rem <= 0 ? "…" : String.valueOf((int) Math.ceil(rem / 1000.0));
                txt(g, Component.literal(c), ax + (RAIL_AV - font.width(c)) / 2, ay + (RAIL_AV - 8) / 2, CTA);
            } else {
                int d = ax + RAIL_AV - 6, e2 = ay + RAIL_AV - 6;     // status LED, bottom-right
                g.fill(d, e2, d + 5, e2 + 5, statusColor(e.uuid()));
                Nb.border(g, d, e2, 5, 5, 1, BORDER);
            }
        }
        // "+" 召唤格:纯代码绘制(圆角卡 + 双矩形十字),跟主题走色——十字是几何,烘焙成
        // 贴图换主题就变色盲。像素画类贴图(头像框/箭头/心饼)不在此列,那是刻意的像素风。
        // 召唤流程开着或悬停时边框与十字亮 CTA。
        int py = top + panelH - PAD - RAIL_AV;
        // scroll cues — gold chevrons when the roster overflows the rail in either direction
        int cx = ax + RAIL_AV / 2;
        if (railScroll > 0) chevron(g, cx, top + 1, true);
        if (railScroll < maxRailScroll()) chevron(g, cx, py - 9, false);
        boolean plusHot = summoning || (mouseX >= ax && mouseX < ax + RAIL_AV
                && mouseY >= py && mouseY < py + RAIL_AV);
        com.dwinovo.numen.client.ui.RoundRect.card(g, ax, py, ax + RAIL_AV, py + RAIL_AV, 3,
                FIELD, plusHot ? CTA : BORDER);
        int pcx = ax + RAIL_AV / 2;
        int pcy = py + RAIL_AV / 2;
        int plusColor = plusHot ? CTA : TXT_MUTED;
        g.fill(pcx - 5, pcy - 1, pcx + 5, pcy + 1, plusColor);
        g.fill(pcx - 1, pcy - 5, pcx + 1, pcy + 5, plusColor);
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
        int first = Mth.clamp(railScroll, 0, maxRailScroll());
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
    /**
     * 用量条:{@code ↑输入 ↓输出 R缓存读 W缓存写 CH命中率 占用%/窗口}。每段有值才出现
     * ——服务商不报缓存的话那三段自然消失,不显示一排零。
     *
     * <h2>颜色只给有信息的段</h2>
     * 输入输出是纯体量,知道多少并不改变什么,保持淡色;<b>命中率和上下文占用</b>才是
     * 会让人想动手的两个数——前者低了说明前缀在被打穿,后者高了说明快要压缩。缓存读用
     * 成功色,它代表省下来的那部分。全部上色等于全部没上色。
     *
     * <p>命中率只看<b>最近一轮</b>:累计命中率会被历史稀释,看不出"刚才那轮把缓存打穿了"。
     */
    private int renderUsage(GuiGraphics g, int mouseX, int mouseY) {
        var loop = loop();
        var sum = loop.usageTotals();
        int pct = loop.contextPercent();
        if (sum.total() <= 0 && pct <= 0) return tabX[0];

        List<String> parts = new java.util.ArrayList<>();
        List<Integer> colors = new java.util.ArrayList<>();
        if (sum.input() > 0) {
            parts.add("↑" + TokenFormat.tokens(sum.input()));
            colors.add(TXT_FAINT);
        }
        if (sum.output() > 0) {
            parts.add("↓" + TokenFormat.tokens(sum.output()));
            colors.add(TXT_FAINT);
        }
        if (sum.cacheRead() > 0) {
            parts.add("R" + TokenFormat.tokens(sum.cacheRead()));
            colors.add(OK);
        }
        if (sum.cacheWrite() > 0) {
            parts.add("W" + TokenFormat.tokens(sum.cacheWrite()));
            colors.add(TXT_MUTED);
        }
        double hit = loop.lastUsage().cacheHitRate();
        if (sum.reportsCache() && hit >= 0) {
            parts.add("CH" + TokenFormat.percent1(hit) + "%");
            colors.add(hit >= 0.7 ? OK : hit >= 0.3 ? RUN : FAIL);
        }
        if (pct > 0) {
            parts.add(pct + "%/" + TokenFormat.tokens(loop.modelWindow()));
            colors.add(pct > 90 ? FAIL : pct > 70 ? RUN : TXT_FAINT);
        }
        if (parts.isEmpty()) return tabX[0];

        int gap = font.width(" ");
        int total = -gap;
        for (String part : parts) {
            total += font.width(part) + gap;
        }
        int tx = tabX[0] - 10 - total;
        int x = tx;
        for (int k = 0; k < parts.size(); k++) {
            txt(g, Component.literal(parts.get(k)), x, top + 7, colors.get(k));
            x += font.width(parts.get(k)) + gap;
        }
        return tx;
    }

    /** 画目标行;没有目标就一个像素都不占。返回正文该从哪儿开始。 */
    private int renderGoalLine(GuiGraphics g, int bodyY, int w) {
        var goal = loop().goal();
        if (goal == null) {
            return bodyY;
        }
        // 目标只有"在"和"不在"两种,所以不需要状态色——它在,就是在跑。
        // 显示目标本身,不显示评估器那句"还差什么":没达成就静默接着干,不该每轮在主人
        // 眼前刷一句判词。想知道进度就 /goal 主动问。
        String head = "◆ 第 " + goal.turnsExecuted() + " 轮 · ";
        String tail = "  " + com.dwinovo.numen.agent.goal.GoalPrompts.elapsed(
                goal.elapsedMs(System.currentTimeMillis()));
        int room = w - font.width(head) - font.width(tail);
        String body = goal.objective();
        String full = body;
        while (body.length() > 1 && font.width(body + "…") > room) {
            body = body.substring(0, body.length() - 1);
        }
        if (!body.equals(full)) {
            body = body + "…";
        }
        txt(g, Component.literal(head + body), left + PAD, bodyY, RUN);
        txt(g, Component.literal(tail),
                left + PAD + w - font.width(tail), bodyY, TXT_FAINT);
        return bodyY + 11;
    }

    private void renderChat(GuiGraphics g, int mouseX, int mouseY) {
        int bodyY = top + HEADER_H + 4;
        int bodyBottom = top + panelH - INPUT_H - PAD - 6;
        int transX = left + PAD;
        int transW = panelW - PAD * 2 - PLAN_W - 8;

        // 长期目标一行:她一轮接一轮在做的那件事。常驻在正文上方——目标是"现在的驱动力",
        // 不是聊天记录里的一条,埋进对话流就翻不到了。
        bodyY = renderGoalLine(g, bodyY, panelW - PAD * 2);

        // right-side PLAN card + the bubble transcript
        int planX = transX + transW + 8;
        com.dwinovo.numen.client.screen.chat.PlanCard.render(
                g, font, loop(), planX - 4, bodyY, PLAN_W + 4, bodyBottom);
        // 外脑驱动中:对话流换成现场——同一套气泡语法,画的是现场缓冲(主人的话、
        // 外脑的 say 与动作行),顶上一条"谁接进来了"的知情行。
        if (com.dwinovo.numen.mcp.server.McpMode.instance().driving()) {
            chatView.renderExternal(g, transX, bodyY, transW, bodyBottom - bodyY);
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

        // 整理记忆:一条随摘要流回来的字数逼近满格的进度条。摘要多长事先不知道,所以它
        // 报的是"还在动",不是"完成了百分之几"——永远差一点,收尾时整条消失。
        if (loop().isCompacting()) {
            double p = loop().compactProgress();
            int bw = panelW - PAD * 2;
            int by = top + panelH - INPUT_H - PAD - 8;
            txt(g, Component.literal("整理记忆… " + Math.round(p * 100) + "%"),
                    left + PAD, by - 11, TXT_MUTED);
            g.fill(left + PAD, by, left + PAD + bw, by + 3, FIELD);
            g.fill(left + PAD, by, left + PAD + (int) Math.round(bw * p), by + 3, ACCENT);
        } else if (cmdReplyUntil > System.currentTimeMillis() && !cmdReply.isEmpty()) {
            int ly = top + panelH - INPUT_H - PAD - 11;
            for (int i = cmdReply.size() - 1; i >= 0 && ly > bodyY; i--, ly -= 10) {
                txt(g, Component.literal(cmdReply.get(i)), left + PAD, ly, TXT_MUTED);
            }
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
