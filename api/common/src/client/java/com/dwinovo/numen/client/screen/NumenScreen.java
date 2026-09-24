package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.agent.llm.NumenLlmClient;

import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.client.agent.ChatFolders;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.SelectedCompanion;
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

/**
 * The owner-facing companion panel: one tabbed screen per Numen (Chat / Items /
 * Settings), opened from the roster or hotkey. Resolves the companion lazily by
 * UUID so it works at any distance.
 *
 * <h2>Chat tab</h2>
 * A scrollable transcript that takes the full width, from the name band down to a dim status line
 * above the input (pi's footer + working indicator in one): spinner while she works, context percent
 * on the right. Her long-term goal is pinned under the header (click to unfold its details, × hides
 * it for this session); her plan ({@code todowrite}) is a checklist message in the transcript. Tool calls
 * show a spinner while running and a green check once their result lands — the raw
 * tool-result JSON is NOT shown (it only flips the call to done), keeping the chat
 * readable.
 */
public final class NumenScreen extends Screen {

    /**
     * 面板此刻对着什么(Telegram 没有页签):CHAT 是对话;ITEMS 是点抬头名字从右边滑进来的资料页
     * (背包、体征);SETTINGS 是左栏顶上 ☰ 从左边滑进来的设置页。滑入滑出都有过渡,见 overlayT。
     */
    private enum Tab { CHAT, ITEMS, SETTINGS, MEMBERS }

    // ---- layout ----
    // 面板随窗口伸缩,两端夹住:下限保证小窗口下不挤,上限挡住大屏上的无限变宽
    // ——行宽超过阅读舒适区就不再跟了。
    // 不学原版的固定尺寸(箱子/工作台从不随窗口伸缩):那样大窗口下是大面板稀内容,
    // 字的视觉占比被稀释,显小显散。
    private static final int PANEL_MIN_W = 300;
    private static final int PANEL_MIN_H = 232;
    private static final int PANEL_MAX_W = 380;
    private static final int PANEL_MAX_H = PANEL_MIN_H;
    // 左栏 = 会话列表(Telegram):一行一个会话——脸、名字、最后一句、时间;选中行整行高亮,点行切换,底部「+」召唤。
    private static final int RAIL_FULL_W = 132;  // 左栏宽(完整:脸 + 名字 + 最后一句)
    private static final int RAIL_NARROW_W = 40; // 窗口装不下时收成只有脸的窄栏(Telegram 缩窗口时会话列表就这么塌)
    private static final int RAIL_AV = 26;       // 脸的边长
    private static final int RAIL_SLOT = 34;     // 行高:脸 + 上下各 4
    private static final int RAIL_TOP = 3 + 22;  // ☰ 那一条(RAIL_BAR_H)的底边;分组标签条从这里往下长,列表在它下面
    private static final int RAIL_BOT_GAP = 10;   // 最后一行下面给"下面还有"的箭头留的缝
    private static final int RAIL_FACE_X = 5;    // 脸离左栏左缘
    /** 抬头两行:名字一行、状态一行(Telegram 的"在线 / 正在输入…"),页签在右侧居中。 */
    private static final int HEADER_H = 30;
    private static final int INPUT_H = 18;
    /** Text fields are inset inside their field box: the EditBox is shrunk by this much
     *  (so vanilla's top-left unbordered text lands padded + centred) and the card is
     *  inflated back out to the full frame. */
    private static final int FIELD_INSET_X = 5;
    private static final int FIELD_INSET_Y = 4;
    private static final int PAD = 8;
    private static final int LINE_H = 10;
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
    /** 图标格边长:与名字那一行的字齐高。 */
    private static final int ICON_N = com.dwinovo.numen.client.ui.mc.Sprites.SIZE;

    /** 抬头右端 ⋮ 的横座标;-1 = 本帧没画(没有会话/盖着别的页/模态中),点不中。 */
    private int moreX = -1;
    private PopupMenu headerMenu;
    /** 输入框上方那一行的高度:只在有一时的提示(整理记忆、没绑模型、命令回话、麦克风)时滑出来。 */
    private static final int STATUS_H = 14;
    /** 那一行露出多高(像素,按趋近走):平时 0,不占地方。 */
    private float dockShown;
    /** 置顶条(Telegram 的置顶消息)的高度、露出多高、这一帧的顶边;她有目标、主人没点 × 时从抬头下面滑出来。 */
    private static final int PIN_H = 26;
    private float pinShown;
    private int pinY;
    private long lastPinFrameMs;
    /** 置顶条上画的那个目标:目标清掉或被收起后,条滑走的那几帧还得画着它的字,不先变空再走。 */
    private com.dwinovo.numen.agent.goal.GoalState pinGoal;
    /** 置顶条点开没有;展开的目标详情这一帧露出多高(像素,按趋近走,收起是往 0 走,走完才不画)。 */
    private boolean goalOpen;
    private float goalShownH;
    /**
     * 悬停提示延迟出:和网页一样,指针停住一会儿才出,扫过去不闪。同一条提示从第一次出现起计时,
     * 内容一变重新计——所以键是提示的文字本身。
     */
    private static final int TIP_DELAY_MS = 400;
    /** 从一条提示挪到旁边另一条,这么久之内免延迟(Radix 的 skipDelay):扫过一排图标时后面的立刻出。 */
    private static final int TIP_SKIP_MS = 300;
    private String tipKey;
    private long tipSince;
    private long tipLastShownMs;
    /** 成员抬头那一行本帧画了谁(与 membersAlive 同序),点击按它判命中;空 = 本帧没画。 */

    private boolean overMore(double mx, double my) {
        return overIcon(moreX, mx, my);
    }

    private boolean overIcon(int iconX, double mx, double my) {
        return iconX >= 0 && mx >= iconX - 1 && mx < iconX + ICON_N + 1
                && my >= iconTop() - 1 && my < iconTop() + ICON_N + 1;
    }

    /** 名字那一行的顶边;状态行在它下面。 */
    private static final int NAME_Y = 6;
    private static final int STATUS_Y = 17;

    /** 图标顶边:与名字共一条中线(行高 9)。 */
    private int iconTop() { return top + NAME_Y + (font.lineHeight - ICON_N) / 2; }

    // ---- 抬头第二行:换字时交叉淡出,不硬切。键是"哪种状态",字可以每帧变(倒计时、动点) ----
    private static final int STATUS_FADE_MS = 180;
    private String statusKey, statusPrevKey, statusPrevText;
    private long statusSwitchMs;

    /** 抬头第二行此刻该说什么:键定淡入淡出,字是这一帧的。 */
    private record HeaderStatus(String key, String text) {}

    private HeaderStatus headerStatus(UUID her, long now) {
        if (conv == null) return null;
        if (her == null) {
            return new HeaderStatus("members", I18n.get(ModLanguageData.Keys.HEADER_MEMBERS,
                    Conversations.instance().membersAlive(conv).size()));
        }
        if (NumenRoster.instance().isDead(her)) {
            // 倒计时归零还没回来 = 周围没有能站的地方,复活在重试。继续显示"0"就是一个数字卡死不动——说清楚在等什么。
            long rem = NumenRoster.instance().remainingMs(her);
            return new HeaderStatus("respawn", rem <= 0 ? I18n.get(ModLanguageData.Keys.RESPAWN_BLOCKED)
                    : I18n.get("numen.respawn", (int) Math.ceil(rem / 1000.0)));
        }
        var st = AgentLoopRegistry.get(her).map(EntityAgentLoop::status).orElse(null);
        if (st == null) return new HeaderStatus("online", I18n.get(ModLanguageData.Keys.HEADER_ONLINE));
        if (st.phase() == com.dwinovo.numen.agent.loop.Phase.COMPACT) {
            return new HeaderStatus("compacting", I18n.get(ModLanguageData.Keys.HEADER_COMPACTING) + dots(now));
        }
        if (st.phase() == com.dwinovo.numen.agent.loop.Phase.MODEL) {
            return new HeaderStatus("typing", I18n.get(ModLanguageData.Keys.HEADER_TYPING) + dots(now));
        }
        if (st.busy()) return new HeaderStatus("busy", I18n.get(ModLanguageData.Keys.HEADER_BUSY) + dots(now));
        return new HeaderStatus("online", I18n.get(ModLanguageData.Keys.HEADER_ONLINE));
    }

    /** "正在输入…"的点:一个、两个、三个轮着来——Telegram 那样,让"正在"活着。 */
    private static String dots(long now) {
        return ".".repeat(1 + (int) ((now / 350) % 3));
    }

    private void renderStatusText(GuiGraphics g, UUID her, int x, int limit) {
        long now = System.currentTimeMillis();
        HeaderStatus st = headerStatus(her, now);
        String key = st == null ? null : st.key();
        if (!java.util.Objects.equals(key, statusKey)) {
            statusPrevKey = statusKey;
            statusPrevText = statusPrevKey == null ? null : lastStatusText;
            statusKey = key;
            statusSwitchMs = now;
        }
        lastStatusText = st == null ? null : st.text();
        float p = Math.min(1f, (now - statusSwitchMs) / (float) STATUS_FADE_MS);
        float e = com.dwinovo.numen.client.ui.Anim.easeOutCubic(p);
        int y = top + STATUS_Y;
        int room = limit - x;
        // 换行式过渡:旧字往上滑出一整行、新字从下面滑入一整行,裁剪框只露这一行——任一时刻只看得见一行,
        // 不会两行字叠在一起(交叉淡出会叠)。滑的同时也淡,边缘不生硬。
        int lh = font.lineHeight;
        g.enableScissor(x, y - 1, limit, y + lh + 1);
        if (statusPrevText != null && p < 1f) {
            txt(g, Component.literal(Nb.clip(font, statusPrevText, room)), x, y - Math.round(lh * e), fade(ON_BAND_FAINT, 1f - e));
        }
        if (st != null && e > 0.02f) {
            txt(g, Component.literal(Nb.clip(font, st.text(), room)), x, y + Math.round(lh * (1f - e)), fade(ON_BAND_FAINT, e));
        }
        g.disableScissor();
    }

    private String lastStatusText;

    /** 按比例压透明度。字体把接近全透明的颜色当不透明画,所以压到底时直接不画(调用方判)。 */
    private static int fade(int argb, float f) {
        int a = Math.max(8, Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, f))));
        return (argb & 0xFFFFFF) | (a << 24);
    }

    private static final net.minecraft.resources.ResourceLocation CHEVRON_UP = railSpr("chevron_up");
    private static final net.minecraft.resources.ResourceLocation CHEVRON_DOWN = railSpr("chevron_down");

    /**
     * 面板对着的会话(左栏切换就地换);null = 空面板(没同伴,或只开设置页)。
     * 就他俩时它就是那只同伴本身,见 {@link #solo()}——面板没有第二个"当前是谁"。
     */
    private Conversation conv;
    private Tab tab = Tab.CHAT;
    /** 盖在对话上的那页(资料或设置)滑进来多少像素(0 = 全在外面,panelW = 全进来);按趋近走。 */
    private float overlayT;
    /** 正在滑的是哪页;收回去的过程中 tab 已经是 CHAT,它记着该往哪边收。 */
    private Tab overlayKind = Tab.ITEMS;
    /**
     * 滑着的那页底下垫的是哪页:平时是对话;从群资料页点人开她的资料时是群资料页——
     * Telegram 一层层推进去,← 一层层退出来。
     */
    private Tab baseTab = Tab.CHAT;
    private MembersPage membersPage;
    private long lastOverlayFrameMs;
    /** 左栏顶上那一条:☰。 */
    private static final int RAIL_BAR_H = 22;


    /** 召唤页的皮肤下拉:null = 默认(按名字找同名正版)。 */

    /** Provider entry for the new companion — REQUIRED (no default, no fallback). */
    /** 召唤时的游戏模式选择(默认生存;创造在服务端过权限门)。 */
    /** Voice entry for the new companion — optional (null = silent). */

    /** 聊天输入行(NumenUI):四颗图标钮 + 输入框,见 ChatInputBar。 */
    private com.dwinovo.numen.client.screen.chat.ChatInputBar inputBar;
    private String savedInput = "";
    /** 重建输入行时带过去的引用(和 savedInput 一样只活过这一次重建)。 */
    private com.dwinovo.numen.client.screen.chat.ChatInputBar.QuoteState savedQuote;

    /** 召唤卡(NumenUI):名字 + 人设/模型配置/模式/声线/皮肤,见 SummonPanel。 */
    private SummonPanel summonPanel;
    /**
     * 模态卡的槽:召唤、编辑同伴、改会话名、邀请同一时刻只开一张,当前 tab 内容照常渲染作背景。
     * 关卡时卡还留在槽里画完淡出({@link #cardBox}),淡没了才拆;这期间背景仍被挡着、卡也不接事件。
     */
    private ModalCard modalCard;
    /** 模态卡的暗幕、外框与出没(和确认卡同一个 DialogBox)。 */
    private final com.dwinovo.numen.client.ui.widget.DialogBox cardBox =
            new com.dwinovo.numen.client.ui.widget.DialogBox();
    private CompanionEditPanel editPanel;
    private ConversationEditPanel convEditPanel;
    private InvitePanel invitePanel;
    /** 屏幕级浮层根:承载遣散/解散的确认卡;浮层在场时背景全屏蔽。 */
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
                        @Override public UUID uuid() { return solo(); }
                        @Override public void tip(List<Component> lines, int x, int y) {
                            pendingTip = lines;
                            pendingTipX = x;
                            pendingTipY = y;
                        }
                        @Override public void repaintPalette() { repaint(); }
                    });

    private String micNotice;
    private long micNoticeUntil;

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
    /** 左栏此刻的宽:完整或窄,init() 按窗口定。 */
    private int railW = RAIL_FULL_W;

    /** Chat transcript view (bubbles + tool chips + eased scroll); reset on companion/tab switch. */
    private final com.dwinovo.numen.client.screen.chat.ChatView chatView =
            new com.dwinovo.numen.client.screen.chat.ChatView(
                    Minecraft.getInstance().font, () -> conv);
    private int railScroll;        // index of the first visible rail avatar (wheel-scroll when many companions)

    // ---- 侧栏拖拽:把一格拖到另一格上 = 把前者的人拉进后者那个会话(手机桌面合并成文件夹的手势)。
    // 按下即记、移过阈值才算拖,点和拖分得开;切换在松手时才做。勾选卡是明路,这是快捷手势,
    // 两者都只是 pullIn 的入口。 ----
    private static final int DRAG_THRESHOLD = 4;
    /** 按下的那一格;-1 = 没按着。 */
    private int railPressed = -1;
    private double railPressX, railPressY;
    private boolean railDragging;
    private double dragX, dragY;
    /** 松手后的残影:拖着的那张脸飞向目标并缩小(合并),或飞回原格(弹回)。动完自己消失。 */
    private Ghost ghost;
    /**
     * 悬停预览(手机桌面合并文件夹那一下):拖到一格上,那格里原来的脸缩到左上角、拖着的脸从
     * 右下角长出来——松手前就看见合并后的叠脸格;拖开就复原。{@code shrinkPx} 是缩进去的像素数,
     * 按帧率无关的趋近走,{@code shrinkIndex} 是正在预览的那格。
     */
    private int shrinkIndex = -1;
    private float shrinkPx;
    private long lastRailFrameMs;
    /** 叠脸格里每张脸的边长与错位,和 {@link com.dwinovo.numen.client.skin.ConversationFaces} 同一比例。 */
    private static final int RAIL_SMALL = RAIL_AV * 7 / 10;
    private static final int RAIL_STEP = RAIL_AV - RAIL_SMALL;

    private record Ghost(Conversation faces, int fromX, int fromY, int fromSize,
                         int toX, int toY, int toSize, long startMs, int durationMs) {}

    /** Re-request the backpack every ~1 s while the Items tab is open. */
    private static final int INV_REFRESH_TICKS = 20;
    private int tickCounter;

    private NumenScreen(Conversation conv) {
        super(Component.literal(titleOf(conv)));
        this.conv = conv;
        // 面板对着谁 = 当前交互对象:开在谁身上、切到谁,R/Y/V 就对着谁——一个"当前",不是两个
        if (conv != null) SelectedCompanion.set(conv);
        if (conv != null) savedInput = Conversations.instance().draft(conv);   // 上次没发完的话还在
    }

    private static String titleOf(Conversation c) {
        return c == null ? "Numen" : "Numen - " + c.displayName(NumenRoster.instance()::name);
    }

    /** Open the panel focused on a specific companion. */
    public static void open(UUID uuid) {
        Minecraft.getInstance().setScreen(new NumenScreen(Conversations.instance().of(uuid)));
    }

    /** {@code /numen settings} 入口:直接落在设置页(全局配置与同伴无关,空面板也能用)。 */
    public static void openSettings() {
        var entries = NumenRoster.instance().entries();
        NumenScreen screen = new NumenScreen(
                entries.isEmpty() ? null : Conversations.instance().of(entries.get(0).uuid()));
        screen.tab = Tab.SETTINGS;
        screen.overlayKind = Tab.SETTINGS;   // 从命令开的:直接开在设置页,init 里把它摆到位
        Minecraft.getInstance().setScreen(screen);
    }

    /**
     * Hotkey entry: open the workspace on the companion that is waiting for the owner's consent (the one the
     * HUD hint names — its chat input is the answer box), else the first companion (or an empty panel to summon from).
     */
    public static void openWorkspace() {
        var asking = com.dwinovo.numen.client.consent.ConsentCards.first();
        if (asking != null) {
            Minecraft.getInstance().setScreen(
                    new NumenScreen(Conversations.instance().of(asking.companion())));
            return;
        }
        // 开在当前交互对象上(转盘选的、面板上次对着的);没选过就落在第一只。
        // 不看准星:准星是 Y/V"走到跟前说话"的规矩,开面板时正好看着谁不该把选择改成她的私聊。
        Conversation target = SelectedCompanion.get();
        if (target == null) {
            var entries = NumenRoster.instance().entries();
            target = entries.isEmpty() ? null : Conversations.instance().of(entries.get(0).uuid());
        }
        Minecraft.getInstance().setScreen(new NumenScreen(target));
    }

    /** Switch the panel to another conversation in place (left-rail click) — no reopen. */
    private void switchTo(Conversation c) {
        boolean same = sameAs(c, conv);
        Conversation from = conv;
        conv = c;   // 同一个会话也换成最新的那份——成员表、名字可能刚变
        SelectedCompanion.set(c);
        if (same) return;
        // 没发出去的话留在原来那个会话里(Telegram 的草稿),切到的会话拿回它自己的
        if (from != null && inputBar != null) Conversations.instance().setDraft(from, inputBar.text());
        inputBar = null;
        savedInput = Conversations.instance().draft(c);
        savedQuote = null;   // 引用是对着原来那个会话里的话,不跟过去
        findOpen = false;     // 在对话里搜的那个词也是对着原来那个会话的
        findActive = false;
        findQuery = "";
        chatView.search(null);
        if (tab == Tab.ITEMS || tab == Tab.MEMBERS) selectTab(Tab.CHAT);   // 换了会话,资料页收起(Telegram 也这样)
        goalOpen = false;
        goalShownH = 0f;
        chatView.reset();
        rebuild();
        if (tab == Tab.ITEMS && solo() != null) requestInventory();
    }

    private static boolean sameAs(Conversation a, Conversation b) {
        return a == null ? b == null : b != null && a.id().equals(b.id());
    }

    /**
     * 就他俩时是她;落过盘的会话没有单一的主,null——那时背包、用量、目标行、人设、遣散这些
     * 同伴专属的东西没有主,不画。判据在 {@link Conversations#soloOf}。
     */
    private UUID solo() {
        return conv == null ? null : Conversations.instance().soloOf(conv);
    }

    /** 抬头上的名字:主人起的,或拼成员名。 */
    private String name() {
        return conv == null ? null : conv.displayName(NumenRoster.instance()::name);
    }

    /** 就他俩那只的大脑;会话没有单一的主时 null。 */
    private EntityAgentLoop loop() {
        UUID her = solo();
        return her == null ? null : AgentLoopRegistry.getOrCreate(her);
    }

    /**
     * 左栏列的会话:每帧现取,名册一变它就跟着变。选中的分组和搜索框的字在这一处叠着筛——
     * 左栏、上下切会话、回车开第一个、拖拽合并认的都是这一份。
     */
    private List<Conversation> rail() {
        Conversations convos = Conversations.instance();
        String folder = convos.folder();
        // 搜索框里有字:只列名字对得上的(不分大小写)
        String q = railQuery.strip().toLowerCase(java.util.Locale.ROOT);
        return convos.all().stream()
                .filter(c -> convos.inFolder(folder, c))
                .filter(c -> q.isEmpty()
                        || c.displayName(NumenRoster.instance()::name).toLowerCase(java.util.Locale.ROOT).contains(q))
                .toList();
    }

    // ---- 左栏顶上的搜索框(Telegram ☰ 右边那一格) ----

    /** 搜索框里的字;空 = 不筛。 */
    private String railQuery = "";
    /** 搜索框在接字。控件每次重建都跟着重建(真编辑器挂在屏幕的控件表上),这一位把焦点带过去。 */
    private boolean searchActive;
    private final com.dwinovo.numen.client.ui.widget.UiRoot searchUi = new com.dwinovo.numen.client.ui.widget.UiRoot();
    private com.dwinovo.numen.client.ui.widget.TextField searchField;
    private static final int SEARCH_H = 14;

    /**
     * 窄栏(窗口小、左栏只剩脸)时 ☰ 旁边是一枚放大镜,点开搜索框浮在抬头左边(Telegram 窄栏也留着搜索);
     * 宽栏时搜索框就住在 ☰ 右边那一格。
     */
    private boolean searchPopup;

    private boolean narrow() { return railW < RAIL_FULL_W; }
    private int searchBoxX() { return narrow() ? left + 4 : railX + 3 + PAD + ICON_N + 6; }
    private int searchBoxY() { return narrow() ? top + 6 : top + 3 + (RAIL_BAR_H - SEARCH_H) / 2; }
    private int searchBoxW() { return narrow() ? 140 : railX + railW - 5 - searchBoxX(); }
    /** 窄栏上那枚放大镜。 */
    private int searchIconX() { return railX + 3 + PAD + ICON_N + 2; }

    private boolean overSearchIcon(double mx, double my) {
        int ix = searchIconX(), iy = top + 3 + (RAIL_BAR_H - ICON_N) / 2;
        return narrow() && mx >= ix - 1 && mx < ix + ICON_N + 1 && my >= iy - 3 && my < iy + ICON_N + 3;
    }

    // ---- 对话里搜(Ctrl+F,Telegram 同一个键):对话流顶上滑下来一条搜索栏 ----

    private boolean findOpen;
    /** 搜索栏在接字;和左栏搜索框一样,控件重建时靠它把焦点带过去。 */
    private boolean findActive;
    private String findQuery = "";
    private final com.dwinovo.numen.client.ui.widget.UiRoot findUi = new com.dwinovo.numen.client.ui.widget.UiRoot();
    private com.dwinovo.numen.client.ui.widget.TextField findField;
    /** 搜索栏露出多高(连下面那道缝);按趋近走。 */
    private float findShown;
    private long lastFindFrameMs;
    private static final int FIND_H = 16;

    private int findX() { return left + PAD; }
    private int findY() { return top + HEADER_H + 3; }
    private int findW() { return panelW - PAD * 2; }
    /** 栏右端三枚:↑(往旧的)、↓(往新的)、×;各占一格。 */
    private int findBtnX(int i) { return findX() + findW() - 12 * (3 - i); }

    private void buildFind() {
        findUi.clear();
        findField = null;
        if (!findOpen || tab != Tab.CHAT || conv == null) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        findUi.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        findUi.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
        findField = findUi.add(new com.dwinovo.numen.client.ui.widget.TextField(findQuery, v -> {
            findQuery = v;
            chatView.search(v);
        }).placeholder(I18n.get("numen.chat.find")).bare(true));
        findField.setBounds(findX() + 16, findY() + 1, findW() - 16 - 12 * 3 - 44, FIND_H - 2);
        if (findActive) findUi.requestFocus(findField);
    }

    private void openFind() {
        findOpen = true;
        findActive = true;
        rebuild();
    }

    private void closeFind() {
        findOpen = false;
        findActive = false;
        findQuery = "";
        chatView.search(null);
        findUi.clear();
        findField = null;
        if (inputBar != null) inputBar.setFocused(true);
    }

    private void blurFind() {
        findActive = false;
        findUi.requestFocus(null);
        if (inputBar != null) inputBar.setFocused(true);
    }

    private boolean overFindBar(double mx, double my) {
        return findField != null && mx >= findX() && mx < findX() + findW() && my >= findY() && my < findY() + FIND_H;
    }

    private int findButtonAt(double mx, double my) {
        if (!overFindBar(mx, my)) return -1;
        for (int i = 0; i < 3; i++) {
            if (mx >= findBtnX(i) && mx < findBtnX(i) + 12) return i;
        }
        return -1;
    }

    /** 搜索栏:从抬头下面滑下来,一枚放大镜、输入框、"第几个/共几个"、↑ ↓ ×。 */
    private void renderFind(GuiGraphics g, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float dt = lastFindFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFindFrameMs) / 1000f);
        lastFindFrameMs = now;
        findShown = com.dwinovo.numen.client.ui.Anim.approach(findShown, findOpen ? FIND_H + 3 : 0f, 18f, dt);
        if (findShown < 0.5f) return;
        int x = findX(), y = findY(), w = findW();
        // 收起的途中输入框已经拆了,外框照样画、跟着滑走,不硬切
        boolean focused = findField != null && findField.isFocused();
        g.enableScissor(left + 3, top + HEADER_H, left + panelW - 3, top + HEADER_H + Math.round(findShown));
        int slide = Math.round(findShown) - (FIND_H + 3);   // 露一半时整条往上缩一半
        g.pose().pushPose();
        g.pose().translate(0, slide, 0);
        com.dwinovo.numen.client.ui.NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                x, y, w, FIND_H, FIELD, focused ? CTA : FIELD);
        com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.SEARCH,
                x + 3, y + (FIND_H - ICON_N) / 2, ICON_N, focused ? CTA : TXT_FAINT);
        if (findField != null) findUi.render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                com.dwinovo.numen.client.screen.settings.HostThemeColors.current(), mouseX, mouseY - slide,
                net.minecraft.Util.getMillis());
        int n = chatView.matchCount();
        String count = findQuery.isBlank() ? ""
                : n == 0 ? I18n.get("numen.chat.find_none")
                : (chatView.matchAt() < 0 ? "" : (chatView.matchAt() + 1) + "/") + n;
        txt(g, Component.literal(count), findBtnX(0) - 4 - font.width(count), y + (FIND_H - 8) / 2, TXT_MUTED);
        int hot = slide == 0 ? findButtonAt(mouseX, mouseY) : -1;
        chevron(g, findBtnX(0) + 6, y + 5, true);
        chevron(g, findBtnX(1) + 6, y + 5, false);
        if (hot == 0 || hot == 1) g.fill(findBtnX(hot), y + 1, findBtnX(hot) + 12, y + FIND_H - 1, 0x30FFFFFF);
        txt(g, Component.literal("×"), findBtnX(2) + (12 - font.width("×")) / 2, y + (FIND_H - 8) / 2, hot == 2 ? TXT : TXT_MUTED);
        g.pose().popPose();
        g.disableScissor();
    }

    /** 搜索框随控件一起重建;窄栏时只有点开了才有。 */
    private void buildSearch() {
        searchUi.clear();
        searchField = null;
        if (narrow() && !searchPopup) {
            searchActive = false;
            return;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        searchUi.setClipboard(() -> mc.keyboardHandler.getClipboard(), s -> mc.keyboardHandler.setClipboard(s));
        searchUi.setInputFactory(com.dwinovo.numen.client.ui.mc.McTextInput.factory());
        searchField = searchUi.add(new com.dwinovo.numen.client.ui.widget.TextField(railQuery, v -> {
            railQuery = v;
            railScroll = 0;
        }).placeholder(I18n.get(ModLanguageData.Keys.RAIL_SEARCH)).bare(true));
        searchField.setBounds(searchBoxX() + 16, searchBoxY(), searchBoxW() - 28, SEARCH_H);
        if (searchActive) searchUi.requestFocus(searchField);
    }

    private boolean overSearch(double mx, double my) {
        return searchField != null && (!narrow() || searchPopup) && mx >= searchBoxX() && mx < searchBoxX() + searchBoxW()
                && my >= searchBoxY() && my < searchBoxY() + SEARCH_H;
    }

    /** 有字时框右端的 ×:清空。 */
    private boolean overSearchClear(double mx, double my) {
        int cx = searchBoxX() + searchBoxW() - 11;
        return !railQuery.isEmpty() && overSearch(mx, my) && mx >= cx;
    }

    /** 搜索框交出焦点,输入框接回来(Telegram 点别处回到写消息);窄栏上没字的浮框跟着收起。 */
    private void blurSearch() {
        searchActive = false;
        searchUi.requestFocus(null);
        if (narrow() && railQuery.isEmpty()) searchPopup = false;
        if (inputBar != null) inputBar.setFocused(true);
    }

    private void clearSearch() {
        railQuery = "";
        if (searchField != null) searchField.setValue("");
        railScroll = 0;
        blurSearch();
    }

    private void renderSearch(GuiGraphics g, int mouseX, int mouseY) {
        if (searchField == null || narrow() && !searchPopup) return;
        int bx = searchBoxX(), by = searchBoxY(), bw = searchBoxW();
        boolean focused = searchField.isFocused();
        // 平时只是一块底色,接字时描一圈强调色——和别处输入框聚焦同一个说法
        com.dwinovo.numen.client.ui.NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                bx, by, bw, SEARCH_H, FIELD, focused ? CTA : FIELD);
        com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.SEARCH,
                bx + 3, by + 1, ICON_N, focused ? CTA : TXT_FAINT);
        searchUi.render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                com.dwinovo.numen.client.screen.settings.HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());
        if (!railQuery.isEmpty()) {
            boolean hot = overSearchClear(mouseX, mouseY);
            txt(g, Component.literal("×"), bx + bw - 9, by + (SEARCH_H - font.lineHeight) / 2 + 1, hot ? TXT : TXT_FAINT);
        }
    }

    @Override
    protected void init() {
        // 输入框的真控件挂到本屏上:只注册事件、不进 renderables,画面归 NumenUI。
        // 屏幕作用域——关屏时在 removed() 卸掉。
        com.dwinovo.numen.client.ui.mc.McTextInput.mountVia(this::addWidget, this::setFocused);
        // 窗口留 12px 边距后能给多大给多大,夹在上下限之间;窗口比下限还小时
        // railX/top 至少钳到 0,保证头部(标题/tab/关闭途径)永远可见可点。
        int avail = this.width - 24;
        // 完整的左栏 + 最窄的正文都装不下,左栏就收成只有脸的窄栏;正文再按窗口在上下限之间伸缩
        railW = avail >= RAIL_FULL_W + PANEL_MIN_W ? RAIL_FULL_W : RAIL_NARROW_W;
        panelW = Math.clamp(avail - railW, PANEL_MIN_W, PANEL_MAX_W);
        panelH = Math.clamp(this.height - 24, PANEL_MIN_H, PANEL_MAX_H);
        int composite = railW + panelW;        // rail flush against the panel — one merged sprite
        this.railX = Math.max(0, (this.width - composite) / 2);
        this.left = railX + railW;
        this.top = Math.max(0, (this.height - panelH) / 2);
        overlayT = tab == baseTab ? 0f : panelW;   // 开屏就在哪页就摆在哪,不从外面滑
        rebuild();
    }

    /** Rebuild the widgets for the active tab. */
    private void rebuild() {
        if (inputBar != null) {
            savedInput = inputBar.text();
            savedQuote = inputBar.quoteState();
        }
        clearWidgets();
        overlay.clear();
        inputBar = null;
        settings.clearWidgets();
        buildSearch();
        buildFind();
        if (modalCard != null) {   // 淡出中的卡不重建:它只剩画面,控件已交还
            if (cardBox.shown()) buildCard();
            return;
        }
        switch (tab) {
            case CHAT -> { if (conv != null) buildChatWidgets(); }
            case SETTINGS -> settings.buildWidgets();
            case ITEMS -> { /* no widgets */ }
        }
        if ((searchActive || findActive) && inputBar != null) inputBar.setFocused(false);   // 搜索框在接字,输入框别抢
    }

    private SummonPanel summonPanel() {
        if (summonPanel == null) {
            summonPanel = new SummonPanel(new SummonHost());
        }
        return summonPanel;
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
            // 查皮肤那一两秒里卡可能已被收掉、换成了别的卡:只收召唤卡自己。
            // 新同伴经 CompanionListPayload 到达——点它的头像即可开工
            if (cardLive() && modalCard == summonPanel) closeCard();
        }

        @Override public void onCancel() {
            closeCard();
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

    private ConversationEditPanel convEditPanel() {
        if (convEditPanel == null) {
            convEditPanel = new ConversationEditPanel(new ConvEditHost());
        }
        return convEditPanel;
    }

    private InvitePanel invitePanel() {
        if (invitePanel == null) {
            invitePanel = new InvitePanel(new InviteHost());
        }
        return invitePanel;
    }

    /** 铅笔:开卡。作用在左栏选中的那一格——就他俩时改她,落过盘的会话改名。 */
    private void openEditCard() {
        if (solo() != null) {
            editCompanion(solo());
        } else {
            openCard(convEditPanel());
        }
    }

    /** 编辑这一只(抬头菜单、资料页的编辑块都走这里)。 */
    private void editCompanion(UUID who) {
        editTarget = who;
        openCard(editPanel());
    }

    /** 「＋」:邀请卡。勾完就进去——请进来的那个会话成为面板对着的。 */
    private void openInvite() {
        openCard(invitePanel());
    }

    /** 模态卡只有一个槽:开哪张都是同一套暗幕、居中、出没、Esc 收卡。 */
    private void openCard(ModalCard which) {
        modalCard = which;
        modalCard.reset();   // 开卡:草稿从当下真相取基线
        cardBox.show();
        rebuild();
    }

    /** 收卡:卡转进淡出,控件当场交还(重建一次,淡出中的卡不再建控件);淡没了 render 里再拆。 */
    private void closeCard() {
        cardBox.hide();
        rebuild();
    }

    /** 开着、接事件的那张卡;淡出中的不算。 */
    private boolean cardLive() {
        return modalCard != null && cardBox.shown();
    }

    /** 邀请卡的宿主面:请进来的人拉进会话,然后面板对着它。 */
    private final class InviteHost implements InvitePanel.Host {
        @Override public Conversation conversation() { return conv; }

        @Override public void onInvite(List<UUID> picked) {
            Conversation c = conv;
            for (UUID u : picked) c = Conversations.instance().pullIn(c, u);
            closeCard();
            switchTo(c);
        }

        @Override public void onClose() {
            closeCard();
        }
    }

    private void buildCard() {
        modalCard.build(modalCardX(), modalCardY(), modalCardW(), modalCardBottom() - modalCardY(),
                top + panelH - 2);
    }

    /** 会话编辑卡的宿主面:改名落库、关卡。 */
    private final class ConvEditHost implements ConversationEditPanel.Host {
        @Override public Conversation conversation() { return conv; }

        @Override public void onSave(String name) {
            conv = Conversations.instance().rename(conv, name);
        }

        @Override public void onClose() {
            closeCard();
        }
    }

    /** 编辑卡的宿主面:身份、网络动作(模式/皮肤发包)与关卡留在屏幕这边。 */
    /** 编辑卡对着哪只:抬头菜单开的是就他俩那只,资料页开的是页上那只(群成员也一样)。 */
    private UUID editTarget;

    private final class EditHost implements CompanionEditPanel.Host {
        @Override public UUID uuid() { return editTarget; }

        @Override public String name() {
            return editTarget == null ? "?" : nameFor(editTarget);
        }

        @Override public void onClose() {
            closeCard();
        }

        @Override public boolean canChooseMode() {
            // 与服务端 applyGameMode 的门同一判据:有 gamemode 权限,或主人本人在创造。
            return minecraft != null && minecraft.player != null
                    && (minecraft.player.hasPermissions(2) || minecraft.player.isCreative());
        }

        @Override public boolean currentCreative() {
            for (NumenRoster.Entry e : NumenRoster.instance().entries()) {
                if (e.uuid().equals(editTarget)) return e.creative();
            }
            return false;
        }

        @Override public void setCreative(boolean creative) {
            Services.NETWORK.sendToServer(
                    new com.dwinovo.numen.network.payload.SetGameModePayload(editTarget, creative));
        }

        @Override public void applySkin(String skinId) {
            UUID target = editTarget;   // 异步查询窗口内可能切换同伴:皮肤落到点选择时的那只
            var entry = com.dwinovo.numen.client.skin.SkinLibrary.instance().get(skinId);
            if (entry != null && entry.signed()) {
                sendSkin(target, entry.value(), entry.signature());
                return;
            }
            // 按名字:本机查同名正版(与召唤同一条路);查不到发空值 = 回原版默认皮肤。
            // 保存即关卡,查询过程不占 UI;失败的原因进聊天框留痕。
            String n = nameFor(target);
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

    // ---- modal cards(召唤/编辑/改名/邀请): 居中卡 + 暗幕,当前 tab 内容照常渲染作背景 ----
    private int modalCardH() { return modalCard.height(); }
    private int modalCardW() { return Math.min(modalCard.width(), panelW - 24); }
    private int modalCardX() { return left + (panelW - modalCardW()) / 2; }
    private int modalCardY() { return top + Math.max(10, (panelH - modalCardH()) / 2); }
    private int modalCardBottom() { return modalCardY() + Math.min(modalCardH(), panelH - 20); }

    /** 遣散确认:危险操作的最后一道闸——卡外点击吞掉、Esc 取消、删除钮红色。 */
    private void openDismissConfirm(UUID target) {
        dismissDialog.open(overlayUi, railX, top, railW + panelW, panelH,
                I18n.get("numen.dismiss.title", nameFor(target)),
                I18n.get("numen.dismiss.warning"),
                I18n.get("numen.gui.settings.cancel"), I18n.get("numen.dismiss.delete"),
                () -> {
                    Services.NETWORK.sendToServer(
                            new com.dwinovo.numen.network.payload.DismissRequestPayload(target));
                    if (target.equals(solo())) {   // 走的是当前这只:跳到另一个会话/回空屏
                        Conversation next = firstOther(conv);
                        if (next != null) {
                            switchTo(next);
                            return;
                        }
                        conv = null;
                    }
                    rebuild();
                });
        rebuild();
    }

    /** 解散确认:记录留在成员各自的日志里,所以副文本说的是"不会丢",不是"无法撤销"。 */
    private void openDissolveConfirm(Conversation target) {
        dismissDialog.open(overlayUi, railX, top, railW + panelW, panelH,
                I18n.get(ModLanguageData.Keys.CONVO_DISSOLVE_TITLE,
                        target.displayName(NumenRoster.instance()::name)),
                I18n.get(ModLanguageData.Keys.CONVO_DISSOLVE_WARNING),
                I18n.get("numen.gui.settings.cancel"), I18n.get(ModLanguageData.Keys.CONVO_DISSOLVE),
                () -> {
                    Conversations.instance().dissolve(target);
                    if (sameAs(target, conv)) {   // 解散的是当前这个:跳到另一个会话/回空屏
                        Conversation next = firstOther(target);
                        conv = null;
                        if (next != null) {
                            switchTo(next);
                            return;
                        }
                    }
                    rebuild();
                });
        rebuild();
    }

    /**
     * 抬头下面的正文:对话永远在底下;资料页从右、设置页从左滑进来盖住它(Telegram 在窄窗口下就是
     * 这样整页滑),滑的过程裁在正文区里。滑到位之前不接鼠标——mouseClickedInner 按 tab 路由,
     * 半路的点击落在还没到位的页上也只是这一帧的事。
     */
    private void renderOverlayPage(GuiGraphics g, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float dt = lastOverlayFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastOverlayFrameMs) / 1000f);
        lastOverlayFrameMs = now;
        overlayT = com.dwinovo.numen.client.ui.Anim.approach(overlayT, tab == baseTab ? 0f : panelW, 16f, dt);
        if (tab == baseTab || overlayT < panelW - 0.5f) {
            if (baseTab == Tab.MEMBERS) renderMembers(g, mouseX, mouseY);
            else if (conv != null) renderChat(g, mouseX, mouseY); else emptyHint(g, mouseX, mouseY);
        }
        if (overlayT <= 0.5f) return;
        // 设置从左边滑进来(☰ 在左),资料页和群资料从右边(点的名字、脸在正文里)
        int dx = overlayKind == Tab.SETTINGS ? -Math.round(panelW - overlayT) : Math.round(panelW - overlayT);
        int bodyTop = top + HEADER_H, bodyBottom = top + panelH - 3;
        g.enableScissor(left + 3, bodyTop, left + panelW - 3, bodyBottom);
        g.pose().pushPose();
        g.pose().translate(dx, 0, 0);
        UiTheme t = UiTheme.current();
        g.fill(left + 3, bodyTop, left + panelW - 3, bodyBottom, t.band());   // 盖着的页是窗口底色(Telegram 资料页、设置页),盖住下面的对话
        if (overlayKind == Tab.SETTINGS) {
            settings.render(g, mouseX - dx, mouseY);   // global — works with no companion
        } else if (overlayKind == Tab.MEMBERS) {
            renderMembers(g, mouseX - dx, mouseY);
        } else if (profileOf != null) {
            renderProfile(g, mouseX - dx, mouseY);
        }
        g.pose().popPose();
        g.disableScissor();
        overlayDx = dx;
    }

    /** 盖着的那页这一帧偏了多少(滑动中);设置页的真控件跟着它画。 */
    private int overlayDx;

    /** 屏幕级浮层(确认卡)在场——屏幕据此屏蔽背景交互。 */
    private boolean overlayOpen() {
        return overlayUi.hasOverlay();
    }

    /** 模态卡在场(含淡出中)——背景(页签/聊天/设置)交互一律屏蔽,侧栏留作逃生口。 */
    private boolean modalOpen() {
        return modalCard != null;
    }

    /** First rail conversation that isn't {@code exclude}, or null if none. */
    private Conversation firstOther(Conversation exclude) {
        for (Conversation c : Conversations.instance().all()) {   // 不管搜索框筛成什么样
            if (!sameAs(c, exclude)) return c;
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
        inputBar.build(left + PAD, inputY, panelW - PAD * 2, INPUT_H, 0);
        if (!savedInput.isEmpty()) {
            inputBar.setText(savedInput);
            savedInput = "";
        }
        inputBar.restoreQuote(savedQuote);
        savedQuote = null;
    }

    /** 输入行的宿主回调面:发言闸门与可按性判据都在屏幕这边。 */
    private final class ChatBarHost implements com.dwinovo.numen.client.screen.chat.ChatInputBar.Host {
        @Override public void onSend(String text) { submitChat(text); }

        @Override public String lastSent() { return chatView.lastOwnText(); }

        @Override public void onMicToggle() { NumenScreen.this.onMicToggle(); }

        @Override public void onAbort() {
            if (conv != null) Conversations.instance().abort(conv);   // 停止停全体
        }

        @Override public boolean canAbort() {
            return conv != null && Conversations.instance().canAbort(conv);
        }

        @Override public String hint() {
            if (micNotice != null && micNoticeUntil > System.currentTimeMillis()) return micNotice;
            String n = NumenScreen.this.name();
            return I18n.get("numen.chat.hint", n == null ? "" : n);
        }

        @Override public EntityAgentLoop loop() {
            return NumenScreen.this.loop();
        }

        @Override public Conversation conversation() {
            return conv;
        }

        /** 只注册事件,不进 renderables——画面归 NumenUI。见 {@code McTextInput}。 */


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
        baseTab = Tab.CHAT;   // 直接开关的页都垫在对话上;一层层推进去的见 pushProfile
        if (t != Tab.CHAT) overlayKind = t;   // 收回去时 tab 已是 CHAT,靠它记住往哪边收
        if (t == Tab.SETTINGS) settings.showList();   // 设置页每次从首页开始
        goalOpen = false;
        goalShownH = 0f;
        chatView.reset();
        if (t == Tab.ITEMS) requestInventory();
        rebuild();
    }

    /** 资料页对着哪只:点抬头名字是就他俩那只,点对话里的脸是那张脸的主人(群里点谁看谁)。 */
    private UUID profileOf;

    /** 资料页开/收:同一只再点是收,换一只是直接换页里的人;在群资料页里点是推进去一层。 */
    private void toggleInfo(UUID who) {
        if (who == null) return;
        if (tab == Tab.MEMBERS) {
            pushProfile(who);
            return;
        }
        if (tab == Tab.ITEMS && who.equals(profileOf)) {
            selectTab(Tab.CHAT);
            return;
        }
        profileOf = who;
        if (profilePage != null) profilePage.reset();
        if (tab == Tab.ITEMS) {
            requestInventory();
        } else {
            selectTab(Tab.ITEMS);
        }
    }

    /** 群资料页里点了一个人:她的资料页从右边推进来,盖在群资料页上;← 退回群资料页。 */
    private void pushProfile(UUID who) {
        profileOf = who;
        if (profilePage != null) profilePage.reset();
        baseTab = Tab.MEMBERS;
        overlayKind = Tab.ITEMS;
        tab = Tab.ITEMS;
        overlayT = 0f;
        requestInventory();
    }

    /** ← 与 Esc:退一层。设置的分区退回设置首页;资料页底下垫着群资料页就退回群资料页;否则回对话。 */
    private void back() {
        if (tab == Tab.SETTINGS && settings.inSection()) {
            settings.leaveSection();
            return;
        }
        if (tab == Tab.ITEMS && baseTab == Tab.MEMBERS) {
            tab = Tab.MEMBERS;   // 资料页滑出去,露出底下的群资料页
            return;
        }
        if (tab == Tab.MEMBERS && baseTab == Tab.MEMBERS) {
            // 群资料页这时垫在底下:换成滑着的那页,再往外收
            overlayKind = Tab.MEMBERS;
            baseTab = Tab.CHAT;
            overlayT = panelW;
        }
        selectTab(Tab.CHAT);
    }

    /**
     * 抬头 ⋮ 的菜单,作用在左栏选中的那一格:就他俩时是查看她的资料、编辑她、邀请、遣散;
     * 群是查看群资料、改名、邀请、解散。邀请有人可请才有;遣散/解散在最下面标红,点了还要过确认卡。
     */
    private void openHeaderMenu() {
        if (headerMenu == null) headerMenu = new PopupMenu(font);
        UUID her = solo();
        java.util.List<PopupMenu.Item> items = new java.util.ArrayList<>();
        items.add(new PopupMenu.Item(her != null ? com.dwinovo.numen.client.ui.mc.Sprites.USER
                : com.dwinovo.numen.client.ui.mc.Sprites.USERS,
                I18n.get(her != null ? ModLanguageData.Keys.MENU_PROFILE : ModLanguageData.Keys.MENU_GROUP_INFO),
                false, this::openInfo));
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.EDIT,
                I18n.get(her != null ? ModLanguageData.Keys.EDIT_TITLE : ModLanguageData.Keys.CONVO_RENAME),
                false, this::openEditCard));
        if (!Conversations.instance().pullable(conv).isEmpty()) {
            items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.USER_PLUS,
                    I18n.get(ModLanguageData.Keys.CONVO_INVITE), false, this::openInvite));
        }
        items.add(PopupMenu.SEPARATOR);
        Conversation c = conv;
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.DELETE,
                I18n.get(her != null ? ModLanguageData.Keys.EDIT_DISMISS : ModLanguageData.Keys.CONVO_DISSOLVE),
                true, () -> { if (her != null) openDismissConfirm(her); else openDissolveConfirm(c); }));
        headerMenu.open(overlayUi, items, moreX + ICON_N + 2, top + HEADER_H - 4, false);
    }

    /** 抬头名字:就他俩开她的资料页,群开群资料页。 */
    private void openInfo() {
        if (solo() != null) toggleInfo(solo()); else selectTab(Tab.MEMBERS);
    }

    /** 资料页(Telegram 资料页那样一整页可滚)。 */
    private ProfilePage profilePage;

    private void renderProfile(GuiGraphics g, int mouseX, int mouseY) {
        if (profilePage == null) profilePage = new ProfilePage(font);
        boolean live = tab == Tab.ITEMS && !modalOpen() && !overlayOpen() && Math.abs(overlayT - panelW) < 1f;
        long now = System.currentTimeMillis();
        profilePage.render(g, profileOf, left + 3, top + HEADER_H, panelW - 6, panelH - HEADER_H - 3,
                mouseX, mouseY, live, m -> {
                    HeaderStatus s = headerStatus(m, now);
                    return s == null ? "" : s.text();
                });
    }

    /** 资料页上的点击:发消息回到和她的对话,编辑开她的编辑卡,点群进那个群,遣散先过确认卡;背包开合页内自己管。 */
    private boolean profileClicked(double mx, double my) {
        if (tab != Tab.ITEMS || profilePage == null || profileOf == null) return false;
        boolean eaten = profilePage.consumes(mx, my);
        ProfilePage.Hit hit = profilePage.click(mx, my);
        if (hit == null) return eaten;
        UUID who = profileOf;
        switch (hit) {
            case ProfilePage.Hit.Message ignored -> openConversation(Conversations.instance().of(who));
            case ProfilePage.Hit.Edit ignored -> editCompanion(who);
            case ProfilePage.Hit.Dismiss ignored -> openDismissConfirm(who);
            case ProfilePage.Hit.Open open -> openConversation(open.conversation());
        }
        return true;
    }

    /** 从资料页进一个会话:切过去并收起资料页(本来就在那个会话里时 switchTo 什么都不做,收页得自己来)。 */
    private void openConversation(Conversation c) {
        switchTo(c);
        selectTab(Tab.CHAT);
    }

    /** 群资料页:成员一行一个。垫在底下时也画,只是不亮悬停、不接点击。 */
    private void renderMembers(GuiGraphics g, int mouseX, int mouseY) {
        if (conv == null) return;
        if (membersPage == null) membersPage = new MembersPage(font);
        int bodyTop = top + HEADER_H;
        g.fill(left + 3, bodyTop, left + panelW - 3, top + panelH - 3, UiTheme.current().band());
        boolean live = tab == Tab.MEMBERS && !modalOpen() && !overlayOpen()
                && Math.abs(overlayT - (tab == baseTab ? 0f : panelW)) < 1f;
        long now = System.currentTimeMillis();
        membersPage.render(g, conv, left + PAD, bodyTop + 4, panelW - PAD * 2, mouseX, mouseY, live, m -> {
            HeaderStatus s = headerStatus(m, now);
            return s == null ? "" : s.text();
        });
        String t = live ? membersPage.tipAt(mouseX, mouseY) : null;
        if (t != null) tip(java.util.List.of(Component.literal(t)), mouseX, mouseY);
    }

    /** 群资料页上的点击:点人推她的资料页,＋ 邀请。 */
    private boolean membersClicked(double mx, double my) {
        if (tab != Tab.MEMBERS || conv == null || membersPage == null) return false;
        switch (membersPage.click(mx, my)) {
            case MembersPage.Hit.Open o -> pushProfile(o.who());
            case MembersPage.Hit.Invite ignored -> openInvite();
            case null -> { return false; }
        }
        return true;
    }

    /** 左栏 ☰ 的菜单(Telegram 的主菜单):召唤同伴、设置,最下面一项夜间模式。 */
    private PopupMenu mainMenu;

    private void openMainMenu() {
        if (mainMenu == null) mainMenu = new PopupMenu(font);
        // 夜间模式:亮的主题一键切到暗的,暗的切回亮的(Telegram 主菜单最下面那个开关)
        boolean night = UiTheme.current().isDark();
        java.util.List<PopupMenu.Item> items = java.util.List.of(
                new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.USER_PLUS,
                        I18n.get("numen.summon.title"), false, this::openSummon),
                new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.SETTINGS,
                        I18n.get("numen.tab.settings"), false, () -> selectTab(Tab.SETTINGS)),
                PopupMenu.SEPARATOR,
                new PopupMenu.Item(night ? com.dwinovo.numen.client.ui.mc.Sprites.SUN : com.dwinovo.numen.client.ui.mc.Sprites.MOON,
                        I18n.get(night ? "numen.menu.day" : "numen.menu.night"), false, () -> {
                            UiTheme.select(night ? UiTheme.LIGHT.id() : UiTheme.DARK.id());
                            repaint();   // 屏幕的调色板常量重读新主题
                        }));
        mainMenu.open(overlayUi, items, railX + 3 + PAD - 3, top + 3 + RAIL_BAR_H - 2, true);
        rebuild();
    }

    /** 右键菜单(消息、左栏的行、群成员共用一个):挂在指针处,右边放不下就往左长。 */
    private PopupMenu contextMenu;

    private void openContextMenu(java.util.List<PopupMenu.Item> items, double mx, double my) {
        if (contextMenu == null) contextMenu = new PopupMenu(font);
        contextMenu.open(overlayUi, items, (int) mx, (int) my, mx + 120 < this.width);
    }

    private void openMemberMenu(UUID who, double mx, double my) {
        java.util.List<PopupMenu.Item> items = new java.util.ArrayList<>();
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.USER,
                I18n.get(ModLanguageData.Keys.MENU_PROFILE), false, () -> pushProfile(who)));
        if (membersPage.droppable()) {
            Conversation c = conv;
            items.add(PopupMenu.SEPARATOR);
            items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.USER_X,
                    I18n.get(ModLanguageData.Keys.CONVO_DROP, nameFor(who)), true,
                    () -> conv = Conversations.instance().drop(c, who)));
        }
        openContextMenu(items, mx, my);
    }

    private void openRailMenu(Conversation c, double mx, double my) {
        Conversations convos = Conversations.instance();
        UUID her = convos.soloOf(c);
        java.util.List<PopupMenu.Item> items = new java.util.ArrayList<>();
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.PIN,
                I18n.get(convos.pinned(c) ? "numen.menu.unpin" : "numen.menu.pin"), false, () -> convos.togglePin(c)));
        var last = com.dwinovo.numen.client.screen.chat.ConversationPreview.last(c);
        if (last != null && com.dwinovo.numen.client.screen.chat.ConversationPreview.unread(c, convos.lastSeen(c)) > 0) {
            items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.READ, I18n.get("numen.menu.read"),
                    false, () -> convos.markSeen(c, last.ts())));
        }
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.FOLDER,
                I18n.get(ModLanguageData.Keys.FOLDER_ADD_TO), false, () -> openAddToFolderMenu(c, mx, my)));
        items.add(PopupMenu.SEPARATOR);
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.DELETE,
                I18n.get(her != null ? ModLanguageData.Keys.EDIT_DISMISS : ModLanguageData.Keys.CONVO_DISSOLVE),
                true, () -> { if (her != null) openDismissConfirm(her); else openDissolveConfirm(c); }));
        openContextMenu(items, mx, my);
    }

    private void openMessageMenu(com.dwinovo.numen.client.screen.chat.ChatView.Picked p, double mx, double my) {
        String who = p.who() == null ? Minecraft.getInstance().getUser().getName() : nameFor(p.who());
        java.util.List<PopupMenu.Item> items = new java.util.ArrayList<>();
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.COPY, I18n.get("numen.menu.copy"), false,
                () -> Minecraft.getInstance().keyboardHandler.setClipboard(p.text())));
        if (inputBar != null) {
            items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.REPLY, I18n.get("numen.menu.reply"),
                    false, () -> replyTo(p, who)));
        }
        openContextMenu(items, mx, my);
    }

    /**
     * 引用回复:输入行上面出引用栏。群里回的是她的话,就在开头替你写上 @ 她——看得见、删得掉,
     * 谁回照旧只看 @,引用本身不叫醒谁。
     */
    private void replyTo(com.dwinovo.numen.client.screen.chat.ChatView.Picked p, String who) {
        if (inputBar == null) return;
        if (p.who() != null && solo() == null) {
            String at = "@" + who;
            if (!inputBar.text().contains(at)) inputBar.setText(at + " " + inputBar.text());
        }
        inputBar.quote(who, p.text());
    }

    /** 按左栏的顺序切到上一个或下一个会话,到头绕回去。 */
    private void stepConversation(int dir) {
        List<Conversation> items = rail();
        if (items.isEmpty()) return;
        int at = -1;
        for (int i = 0; i < items.size(); i++) if (sameAs(items.get(i), conv)) { at = i; break; }
        switchTo(items.get(Math.floorMod(at + dir, items.size())));
    }

    /** 召唤卡:每次开都是新的一张(默认/无/生存)。 */
    private void openSummon() {
        openCard(summonPanel());
    }

    /** ☰ 在左栏顶上那一条的左端。 */
    private boolean railMenuAt(double mx, double my) {
        int x = railX + 3 + PAD, y = top + 3 + (RAIL_BAR_H - ICON_N) / 2;
        return mx >= x - 3 && mx < x + ICON_N + 3 && my >= y - 3 && my < y + ICON_N + 3;
    }

    /** 设置页抬头的 ←。 */
    private boolean backAt(double mx, double my) {
        return tab != Tab.CHAT && mx >= left + PAD - 3 && mx < left + PAD + ICON_N + 3
                && my >= top + 3 && my < top + HEADER_H;
    }

    /** 抬头上名字与状态那一块(点它开资料页);图标那一截不算。 */
    private boolean overName(double mx, double my) {
        return tab == Tab.CHAT && conv != null && mx >= left + PAD && mx < nameRight
                && my >= top + 3 && my < top + HEADER_H - 2;
    }
    private int nameRight;

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
        int x0 = railX, y0 = top, x1 = railX + railW + panelW, y1 = top + panelH;
        g.fill(x0, y0, x1, y1, t.border());                          // frame + rail divider base
        g.fill(x0 + 3, y0 + 3, x0 + railW, y1 - 3, t.band());       // 左栏列:窗口底色,聊天区才是聊天背景
        g.fill(x0 + railW, y0 + 3, x0 + railW + 1, y1 - 3, t.border());   // 列表与正文之间一道竖线
        g.fill(left + 3, y0 + 3, x1 - 3, y0 + HEADER_H - 2, t.band());   // header band (underline = border gap)
        g.fill(left + 3, y0 + HEADER_H, x1 - 3, y1 - 3, t.ground()); // panel ground
        for (int dy = y0 + HEADER_H + 7; dy < y1 - 5; dy += 16) {    // dot grid (translucent theme dot)
            for (int dx = left + 10; dx < x1 - 5; dx += 16) {
                g.fill(dx, dy, dx + 2, dy + 2, t.dot());
            }
        }
    }

    /** 显示过滤统一走 {@link com.dwinovo.numen.client.chat.ChatDisplayMode}(可整体切换)。 */
    /** The active companion's current persona name (green marker in the list), or null. */
    private String activePersonaName() {
        UUID her = solo();
        if (her == null) return null;
        return AgentLoopRegistry.get(her).map(EntityAgentLoop::personaName).orElse(null);
    }

    @Override
    public void tick() {
        if (tab == Tab.ITEMS && ++tickCounter % INV_REFRESH_TICKS == 0) {
            requestInventory();
        }
        if (inputBar != null) {
            inputBar.tick();
        }
    }

    private void requestInventory() {
        // No companion selected (empty roster / hotkey-opened blank panel) → nothing to fetch.
        // The payload's UUID stream-codec can't encode null, so this guard also prevents a crash.
        UUID her = tab == Tab.ITEMS ? profileOf : solo();
        if (her == null) return;
        if (Minecraft.getInstance().getConnection() != null) {
            Services.NETWORK.sendToServer(new RequestStatePayload(her));
        }
    }

    /**
     * 说出去——和快捷对话、桥接同一个入口({@link com.dwinovo.numen.api.NumenGateway#emit})。
     * 这里不查端点:话一律进收件箱,内核要开 run 时发现没绑模型就停在 BLOCKED,原因画在输入行上面,
     * 绑好了排着的话自己接着走。外脑驱动时话由外脑经 get_events 取走。斜杠命令到不了这儿——输入行在本地跑完了。
     */
    private void submitChat(String text) {
        if (text == null || text.isBlank()) return;
        conv = Conversations.instance().say(conv, text).conversation();
        if (inputBar != null) inputBar.setText("");
        chatView.pinToBottom();
    }

    // ---- input ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int k = keyCode;
        if (overlayOpen()) {   // 确认卡在场:Esc = 取消(UiRoot 浮层通道保证),其余键不下传
            if (overlayUi.keyPressed(keyCode, modifiers)) { rebuild(); return true; }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (findActive && findField != null && !modalOpen()) {
            if (k == 256) {   // Esc:收起搜索栏
                closeFind();
                return true;
            }
            if (k == com.dwinovo.numen.client.ui.KeyCodes.ENTER || k == com.dwinovo.numen.client.ui.KeyCodes.UP) {
                // 回车、↑ 往旧的找;Shift+回车往新的
                chatView.jumpMatch(k == com.dwinovo.numen.client.ui.KeyCodes.ENTER
                        && com.dwinovo.numen.client.ui.KeyCodes.shift(modifiers) ? -1 : 1);
                return true;
            }
            if (k == com.dwinovo.numen.client.ui.KeyCodes.DOWN) {
                chatView.jumpMatch(-1);
                return true;
            }
            return findUi.keyPressed(keyCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (k == com.dwinovo.numen.client.ui.KeyCodes.KEY_F && com.dwinovo.numen.client.ui.KeyCodes.ctrl(modifiers)
                && tab == Tab.CHAT && conv != null && !modalOpen()) {
            if (findOpen) {   // 开着再按:光标回到搜索栏
                findActive = true;
                findUi.requestFocus(findField);
                if (inputBar != null) inputBar.setFocused(false);
            } else {
                openFind();
            }
            return true;
        }
        if (searchActive && searchField != null && !modalOpen()) {
            if (k == 256) {   // Esc:清空、交回输入框
                clearSearch();
                return true;
            }
            if (k == com.dwinovo.numen.client.ui.KeyCodes.ENTER) {   // 回车:开第一个对得上的
                List<Conversation> hits = rail();
                if (!hits.isEmpty()) switchTo(hits.get(0));
                clearSearch();
                return true;
            }
            // 编辑键:这里不接的,落到屏幕上的真输入框
            return searchUi.keyPressed(keyCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
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
        if (modalOpen()) {
            if (!cardLive()) return true;   // 淡出那几帧:键一概不接,免得 Esc 落到面板上把它整个关了
            if (k == 256) { closeCard(); return true; } // Esc 收卡,不关面板
            if (modalCard.keyPressed(keyCode, modifiers)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // Alt+↑/↓、Ctrl+Tab / Ctrl+Shift+Tab:切到上一个、下一个会话(Telegram 桌面版的键)
        boolean altArrow = com.dwinovo.numen.client.ui.KeyCodes.alt(modifiers)
                && (k == com.dwinovo.numen.client.ui.KeyCodes.UP || k == com.dwinovo.numen.client.ui.KeyCodes.DOWN);
        boolean ctrlTab = com.dwinovo.numen.client.ui.KeyCodes.ctrl(modifiers) && k == com.dwinovo.numen.client.ui.KeyCodes.TAB;
        if ((altArrow || ctrlTab) && conv != null) {
            boolean back = k == com.dwinovo.numen.client.ui.KeyCodes.UP
                    || (ctrlTab && com.dwinovo.numen.client.ui.KeyCodes.shift(modifiers));
            stepConversation(back ? -1 : 1);
            return true;
        }
        if (tab == Tab.CHAT && inputBar != null && inputBar.keyPressed(keyCode, modifiers)) {
            return true;
        }
        if (k == 256 && findOpen) {   // 搜索栏开着(光标不在它上面也算):Esc 先收它
            closeFind();
            return true;
        }
        if (k == 256 && tab != Tab.CHAT) {   // Esc 先退盖着的那页(资料/群资料/设置),退到对话再一次才关面板
            back();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (findActive && findField != null && !modalOpen()) {
            return findUi.charTyped(ch) || super.charTyped(ch, modifiers);
        }
        if (searchActive && searchField != null && !modalOpen()) {
            return searchUi.charTyped(ch) || super.charTyped(ch, modifiers);
        }
        if (tab == Tab.SETTINGS && !modalOpen() && settings.charTyped(ch)) {
            return true;
        }
        if (tab == Tab.CHAT && !modalOpen() && inputBar != null && inputBar.charTyped(ch)) {
            return true;
        }
        if (cardLive() && modalCard.charTyped(ch)) {
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
        if (overlayOpen()) {   // 确认卡:卡上按钮生效,卡外点击一律吞掉(危险操作不给误触留门)
            boolean handled = overlayUi.mouseClicked(mouseX, mouseY, button);
            if (!overlayOpen()) rebuild();   // 卡关了(取消/确认):背景 widget 复位
            return handled;
        }
        if (!modalOpen() && tab == Tab.SETTINGS && settings.formActive()) {
            // 设置页的表单模态:先给表单自己的下拉路由,其余只放行 widget 通道
            // (卡上字段/按钮),侧栏/页签/背景列表全部屏蔽。
            if (button == 0 && settings.mouseClicked(mouseX, mouseY, button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 1 && !modalOpen() && tab == Tab.MEMBERS && membersPage != null && conv != null) {
            UUID who = membersPage.rowAt(mouseX, mouseY);   // 右键群成员:查看资料、移出
            if (who != null) {
                openMemberMenu(who, mouseX, mouseY);
                return true;
            }
        }
        if (button == 1 && !modalOpen()) {   // 右键左栏一行:置顶、标为已读、加入分组、遣散/解散
            int row = railIndexAt((int) mouseX, (int) mouseY);
            if (row >= 0 && row < rail().size()) {
                openRailMenu(rail().get(row), mouseX, mouseY);
                return true;
            }
            int folderTab = folderTabAt(mouseX, mouseY);   // 右键分组标签:编辑、新建、删除
            if (folderTab >= 0) {
                openFolderMenu(folderTabs().get(folderTab).id(), mouseX, mouseY);
                return true;
            }
        }
        if (button == 1 && !modalOpen() && tab == Tab.SETTINGS && settings.mouseClicked(mouseX, mouseY, button)) {
            return true;   // 右键设置里条目库的一行:编辑、克隆、删除
        }
        if (button == 1 && !modalOpen() && tab == Tab.CHAT && conv != null) {   // 右键一条话:复制、引用回复
            var picked = chatView.bubbleAt(mouseX, mouseY);
            if (picked != null) {
                openMessageMenu(picked, mouseX, mouseY);
                return true;
            }
        }
        if (button == 0) {
            // Summon dropdowns get first pick (their open lists overlay the panel).
            // 遮挡关系:先路由"正展开"的那一个——下排下拉向上翻时,展开列表盖住
            // 上排的折叠框,固定顺序会让上排先吞掉点击。
            if (cardLive() && modalCard.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (railMenuAt(mouseX, mouseY)) {   // ☰ → 菜单(模态开着时也当逃生口:先收模态)
                if (cardLive()) closeCard();
                openMainMenu();
                return true;
            }
            if (!modalOpen() && overSearchIcon(mouseX, mouseY)) {   // 窄栏的放大镜:浮出搜索框
                searchPopup = true;
                searchActive = true;
                rebuild();
                return true;
            }
            if (!modalOpen() && overSearchClear(mouseX, mouseY)) {
                clearSearch();
                return true;
            }
            if (!modalOpen() && overSearch(mouseX, mouseY)) {   // 搜索框:接字,输入框交出焦点
                searchActive = true;
                searchUi.mouseClicked(mouseX, mouseY, button);
                if (inputBar != null) inputBar.setFocused(false);
                return true;
            }
            if (searchActive) blurSearch();   // 点在别处:搜索框交回焦点,这一下照常往下走
            if (!modalOpen() && tab == Tab.CHAT && overFindBar(mouseX, mouseY)) {
                switch (findButtonAt(mouseX, mouseY)) {
                    case 0 -> chatView.jumpMatch(1);
                    case 1 -> chatView.jumpMatch(-1);
                    case 2 -> closeFind();
                    default -> {   // 点在输入框上:接字
                        findActive = true;
                        findUi.mouseClicked(mouseX, mouseY, button);
                        if (inputBar != null) inputBar.setFocused(false);
                    }
                }
                return true;
            }
            if (findActive) blurFind();
            if (!modalOpen() && overEmptyButton(mouseX, mouseY)) {   // 空面板的召唤钮
                openSummon();
                return true;
            }
            int folderTab = modalOpen() ? -1 : folderTabAt(mouseX, mouseY);
            if (folderTab >= 0) {   // 分组标签:切过去
                selectFolder(folderTabs().get(folderTab).id());
                return true;
            }
            int rail = railIndexAt((int) mouseX, (int) mouseY);
            if (rail >= 0) {
                // 按下只记一笔:是点还是拖,松手时才知道(见 mouseReleased)
                if (rail < rail().size()) {
                    railPressed = rail;
                    railPressX = mouseX;
                    railPressY = mouseY;
                    railDragging = false;
                }
                return true;
            }
            if (modalOpen()) {
                // 召唤模态:页签/聊天/设置全在暗幕之下,只放行 widget 通道(卡上控件);
                // 侧栏的 +/头像在上面已处理(保留为模态的逃生口)。
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (tab == Tab.SETTINGS && settings.mouseClicked(mouseX, mouseY, button)) return true;
            if (conv != null && !overlayOpen() && overMore(mouseX, mouseY)) {
                openHeaderMenu();
                return true;
            }
            if (backAt(mouseX, mouseY)) {   // 盖着的页的 ← 退一层
                back();
                return true;
            }
            if (!overlayOpen() && overName(mouseX, mouseY)) {   // 名字 → 资料页 / 群资料页
                openInfo();
                return true;
            }
            if (!overlayOpen() && membersClicked(mouseX, mouseY)) return true;
            if (!overlayOpen() && profileClicked(mouseX, mouseY)) return true;
            if (tab == Tab.CHAT && pinShown > PIN_H - 1 && mouseX >= left + 3 && mouseX < left + panelW - 3
                    && mouseY >= pinY && mouseY < pinY + PIN_H) {
                if (mouseX >= pinCloseX()) {
                    // ×:这个目标本局不再置顶,条滑走;换了新目标再出现
                    com.dwinovo.numen.client.screen.chat.PinnedGoal.dismiss(pinGoal);
                    goalOpen = false;
                } else {
                    goalOpen = !goalOpen;   // 点条本身:展开/收起目标详情
                }
                return true;
            }
            if (tab == Tab.CHAT && goalOpen) {
                goalOpen = false;   // 展开的详情盖在对话流上:点别处只负责收起
                return true;
            }
            if (tab == Tab.CHAT && inputBar != null
                    && inputBar.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            if (tab == Tab.CHAT) {
                UUID who = chatView.faceAt(mouseX, mouseY);   // 消息旁的脸 → 她的资料页
                if (who != null) {
                    toggleInfo(who);
                    return true;
                }
            }
            if (tab == Tab.CHAT && chatView.mouseClicked(mouseX, mouseY)) return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && railPressed >= 0) {
            // 模态/浮层在场时侧栏只是逃生口,不拖
            if (!railDragging && !modalOpen() && !overlayOpen()
                    && Math.abs(mx - railPressX) + Math.abs(my - railPressY) >= DRAG_THRESHOLD) {
                railDragging = true;
            }
            dragX = mx;
            dragY = my;
            return true;
        }
        // 声线表单的音量滑条拖动(NumenUI 面板)。
        if (tab == Tab.CHAT && !modalOpen() && !overlayOpen() && chatView.mouseDragged(mx, my)) {
            return true;   // 拖对话流的滑块
        }
        if (tab == Tab.SETTINGS && !modalOpen() && settings.mouseDragged(mx, my, dx, dy)) {
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0 && railPressed >= 0) {
            int pressed = railPressed;
            railPressed = -1;
            List<Conversation> items = rail();
            if (pressed >= items.size()) return true;
            if (!railDragging) {
                railClicked(items.get(pressed));
                return true;
            }
            railDragging = false;
            int over = railIndexAt((int) mx, (int) my);
            if (over >= 0 && over != pressed && over < items.size()) {
                mergeInto(items.get(pressed), items.get(over), pressed);
            } else {
                flyBack(items.get(pressed), pressed);
            }
            return true;
        }
        if (tab == Tab.CHAT && chatView.mouseReleased()) {
            return true;
        }
        if (tab == Tab.SETTINGS && !modalOpen() && settings.mouseReleased(mx, my, button)) {
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    /** 侧栏一格被点了(按下后没拖):切过去。侧栏是纯切换器(Discord 语法)。 */
    private void railClicked(Conversation c) {
        // 点当前那格不再有动作,编辑入口在头部名字旁的铅笔;模态开着时当逃生口收卡。
        if (cardLive()) closeCard();
        if (!sameAs(c, conv)) switchTo(c);
    }

    /**
     * 拖着的那格落在另一格上:把前者的人拉进后者那个会话。落在"就他俩"上就是另起一个会话,
     * 落在落过盘的会话上就是扩它——和「＋ 拉人」同一条路({@link Conversations#pullIn})。
     * 残影从指针处缩进合并后那格,眼睛跟着到新会话。
     */
    private void mergeInto(Conversation dragged, Conversation target, int fromIndex) {
        Conversations convos = Conversations.instance();
        Conversation result = target;
        for (UUID m : convos.membersAlive(dragged)) {
            result = convos.pullIn(result, m);
        }
        switchTo(result);
        int to = -1;
        List<Conversation> items = rail();
        for (int i = 0; i < items.size(); i++) {
            if (sameAs(items.get(i), result)) { to = i; break; }
        }
        int[] face = railFaceAt(to);
        if (face == null) face = railFaceAt(fromIndex);   // 合并后那行滚出了视野:缩回原地
        if (face == null) return;
        // 残影落进叠脸格的右下那一张——和悬停预览里它长出来的位置是同一个,松手不跳
        ghost = new Ghost(dragged, (int) dragX - RAIL_AV / 2, (int) dragY - RAIL_AV / 2, RAIL_AV,
                face[0] + RAIL_STEP, face[1] + RAIL_STEP, RAIL_SMALL, System.currentTimeMillis(), 220);
        shrinkIndex = -1;
        shrinkPx = 0f;
    }

    /** 拖到半路松手(空处或自己那格):飞回原位。 */
    private void flyBack(Conversation dragged, int fromIndex) {
        int[] face = railFaceAt(fromIndex);
        if (face == null) return;
        ghost = new Ghost(dragged, (int) dragX - RAIL_AV / 2, (int) dragY - RAIL_AV / 2, RAIL_AV,
                face[0], face[1], RAIL_AV, System.currentTimeMillis(), 150);
    }

    /** 每帧推进悬停预览:指针在哪格上就往那格缩;移开或没在拖就退回来。换了格从头缩。 */
    private void updateShrink(int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        float dt = lastRailFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastRailFrameMs) / 1000f);
        lastRailFrameMs = now;
        int over = railDragging ? railIndexAt(mouseX, mouseY) : -1;
        if (over == railPressed || over >= rail().size()) over = -1;
        if (over >= 0 && over != shrinkIndex) {
            shrinkIndex = over;
            shrinkPx = 0f;
        }
        shrinkPx = com.dwinovo.numen.client.ui.Anim.approach(shrinkPx, over == shrinkIndex && over >= 0 ? RAIL_STEP : 0f, 18f, dt);
        if (shrinkPx <= 0f && over < 0) shrinkIndex = -1;
    }

    /** 第 i 行的顶边;没画出来(滚出视野)是 -1。 */
    private int railTileY(int i) {
        if (i < 0) return -1;
        int y = railStartY() + (i - railScroll) * RAIL_SLOT;
        return i >= railScroll && y + RAIL_SLOT <= railBottomEdge() ? y : -1;
    }

    /** 第 i 行里脸的左上角;行没画出来是 null。 */
    private int[] railFaceAt(int i) {
        int y = railTileY(i);
        return y < 0 ? null : new int[]{railX + 3 + RAIL_FACE_X, y + (RAIL_SLOT - RAIL_AV) / 2};
    }

    /** 拖着的那张脸跟着指针;残影按 easeOut 飞向落点,动完清掉。 */
    private void renderRailDrag(GuiGraphics g) {
        if (railDragging && railPressed >= 0 && railPressed < rail().size()) {
            com.dwinovo.numen.client.skin.ConversationFaces.draw(g, rail().get(railPressed),
                    (int) dragX - RAIL_AV / 2, (int) dragY - RAIL_AV / 2, RAIL_AV);
        }
        if (ghost == null) return;
        float t = (System.currentTimeMillis() - ghost.startMs()) / (float) ghost.durationMs();
        if (t >= 1f) {
            ghost = null;
            return;
        }
        float e = com.dwinovo.numen.client.ui.Anim.easeOutCubic(t);
        int x = Math.round(ghost.fromX() + (ghost.toX() - ghost.fromX()) * e);
        int y = Math.round(ghost.fromY() + (ghost.toY() - ghost.fromY()) * e);
        int size = Math.round(ghost.fromSize() + (ghost.toSize() - ghost.fromSize()) * e);
        com.dwinovo.numen.client.skin.ConversationFaces.draw(g, ghost.faces(), x, y, size);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        // 浮层在场:滚轮归它(多选卡翻行),背景(侧栏名册/设置列表)不响应。
        if (overlayOpen()) {
            return overlayUi.mouseScrolled(mx, my, sy);
        }
        // 打开着的下拉列表优先吃滚轮(列表被面板截断时滚动余下的行)。
        if (sy != 0) {
            if (cardLive() && modalCard.mouseScrolled(mx, my, sy)) return true;
        }
        if (modalOpen()) return false;   // 召唤/编辑模态:背景(侧栏/聊天/设置)不响应滚轮
        if (tab == Tab.SETTINGS && settings.formActive()) {
            // 表单模态:只放行表单自己的滚动(下拉列表 + 声线表单视口),背景列表/侧栏屏蔽。
            return sy != 0 && settings.mouseScrolledEarly(mx, my, sy);
        }
        // 设置页第一段:表单下拉 + 声线表单整体滚动(顺位与拆分前一致)。
        if (sy != 0 && tab == Tab.SETTINGS && settings.mouseScrolledEarly(mx, my, sy)) return true;
        // 分组标签条上的滚轮横着滚标签(Telegram 标签条吃掉滚轮,不往下面的列表传)
        if (sy != 0 && overFolderStrip(mx, my)) {
            folderScrollTo = Math.clamp(folderScrollTo - (float) sy * 20f, 0f, maxFolderScroll(folderTabs()));
            return true;
        }
        // Wheel over the left rail column scrolls the roster (works on any tab).
        if (sy != 0 && mx >= railX && mx < railX + railW && maxRailScroll() > 0) {
            railScroll = Math.clamp((long) (railScroll - sy), 0, maxRailScroll());
            return true;
        }
        if (tab == Tab.CHAT && sy != 0) {
            return chatView.mouseScrolled(sy);
        }
        if (tab == Tab.ITEMS && sy != 0 && profilePage != null) {
            return profilePage.scroll(sy);
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
        renderRailDrag(g);               // 拖着的脸与合并/弹回的残影,压在侧栏之上

        // 头部一行四个成员从右往左让位:tab(定宽) ← 用量 ← 人设名(可整个消失) ← 名字(最后裁)。
        // 用量、图标、复活倒计时、人设名都是一只同伴的:会话没有单一的主时抬头只有名字
        UUID her = solo();
        int headerLimit = left + panelW - PAD;
        if (tab != Tab.CHAT) {
            // 盖着的页的抬头(Telegram 设置页/资料页那一条):← 回到对话,后面是这页的标题——
            // 设置页是"设置",群资料页是群名 + 成员数,资料页是"资料"(她的名字和状态在页顶上)
            boolean hotBack = backAt(mouseX, mouseY) && !modalOpen() && !overlayOpen();
            com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.BACK,
                    left + PAD, top + (HEADER_H - ICON_N) / 2, ICON_N, hotBack ? CTA : ON_BAND);
            int tx = left + PAD + ICON_N + 6;
            if (tab == Tab.SETTINGS) {
                txt(g, Component.literal(settings.title()), tx,
                        top + (HEADER_H - font.lineHeight) / 2 + 1, ON_BAND);
            } else if (tab == Tab.MEMBERS) {
                String title = name();
                txt(g, Component.literal(Nb.clip(font, title == null ? "?" : title, headerLimit - tx)), tx, top + NAME_Y, ON_BAND);
                renderStatusText(g, null, tx, headerLimit);
            } else {
                // 资料页的名字和状态在页顶上(Telegram 资料页),抬头只说这是资料
                txt(g, Component.translatable(ModLanguageData.Keys.HEADER_PROFILE), tx,
                        top + (HEADER_H - font.lineHeight) / 2 + 1, ON_BAND);
            }
            moreX = -1;
            nameRight = left + PAD;
        } else {
            // 抬头右端只有一枚 ⋮(Telegram):改、邀请、遣散/解散这些不常用的都收进它的菜单,
            // 危险的那项在菜单最下面标红。名字占剩下的。
            moreX = conv != null && !modalOpen() ? headerLimit - ICON_N : -1;
            int iconsLeft = moreX >= 0 ? moreX - 8 : headerLimit;
            int nameRoom = iconsLeft - (left + PAD);
            String title = name();
            String nm = Nb.clip(font, title == null ? "Numen" : title, Math.max(24, nameRoom));
            nameRight = left + PAD + font.width(nm);
            boolean hotName = !modalOpen() && !overlayOpen() && overName(mouseX, mouseY);
            // 名字可点:就他俩开她的资料页,群开群资料页;悬停亮一档,像个能点的东西
            txt(g, Component.literal(nm), left + PAD, top + NAME_Y, hotName ? CTA : ON_BAND);
            if (hotName) {
                tip(java.util.List.of(Component.translatable(ModLanguageData.Keys.HEADER_PROFILE)), mouseX, mouseY);
            }
            int afterName = left + PAD + font.width(nm) + 6;
            if (moreX >= 0) {
                // 菜单开着时 ⋮ 保持亮着:它是菜单挂着的那个点
                boolean menuOpen = headerMenu != null && headerMenu.isOpen();
                boolean hotMore = !overlayOpen() && overMore(mouseX, mouseY);
                com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.MORE,
                        moreX, iconTop(), ICON_N, hotMore || menuOpen ? CTA : ON_BAND);
                if (hotMore) tip(java.util.List.of(Component.translatable(ModLanguageData.Keys.HEADER_MORE)), mouseX, mouseY);
            }
            String pn = activePersonaName();                   // current persona, faint, right after the name
            if (pn != null && afterName + font.width("…") <= iconsLeft) {
                txt(g, Component.literal(Nb.clip(font, pn, iconsLeft - afterName)), afterName, top + NAME_Y, ON_BAND_FAINT);
            }
            // 第二行:在线 / 正在输入… / 复活倒计时 / N 位成员
            renderStatusText(g, her, left + PAD, headerLimit);
        }
        // 抬头以下所有页共用一段:页面本身,再往上是模态卡、浮层、提示——盖着的页上开的卡也得画得出来
        renderOverlayPage(g, mouseX, mouseY);
        if (narrow()) renderSearch(g, mouseX, mouseY);   // 窄栏的搜索框浮在抬头左边,压在页面上
        // 对话里悬停的那张脸:提示能点开资料
        if (tab == Tab.CHAT && !modalOpen() && !overlayOpen() && chatView.faceAt(mouseX, mouseY) != null) {
            tip(java.util.List.of(Component.translatable(ModLanguageData.Keys.HEADER_PROFILE)), mouseX, mouseY);
        }
        if (modalCard != null) {
            // 模态卡:暗幕 + 居中卡(与确认卡同一个 DialogBox),卡里的东西(含脸)由卡自己画,
            // 跟着卡的不透明度一起淡。
            long now = net.minecraft.Util.getMillis();
            if (!cardBox.advance(now)) {   // 淡没了:这才拆,背景的控件接着建回来
                modalCard = null;
                rebuild();
            } else {
                var s = new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font);
                var c = com.dwinovo.numen.client.screen.settings.HostThemeColors.current();
                cardBox.paint(s, c, railX, top, railW + panelW, panelH,
                        modalCardX(), modalCardY(), modalCardW(), modalCardBottom() - modalCardY());
                boolean live = cardBox.shown();
                int mx = live ? mouseX : Integer.MIN_VALUE, my = live ? mouseY : Integer.MIN_VALUE;
                modalCard.render(s, c, mx, my, now, cardBox.card());
                String modeTip = live ? modalCard.tooltipAt(mouseX, mouseY) : null;
                if (modeTip != null) {
                    pendingTip = java.util.List.of(Component.literal(modeTip));
                    pendingTipX = mouseX;
                    pendingTipY = mouseY;
                }
            }
        }


        // Widgets render LAST, on top of the panel background (fixes the "dim fields" — the panel fill
        // used to paint over the auto-rendered widgets). Text fields are borderless EditBoxes, so draw
        // the shared field box behind each before it renders its text.
        for (AbstractWidget w : overlay) {
            // visible 检查:声线表单滚出视口的 EditBox 隐藏了自己,框也必须跟着消失
            // (否则空框越过面板边缘悬在世界上)。
            if (w instanceof EditBox eb && eb.visible) {
                // 所有文本字段与气泡同款的框;聚焦的字段边框亮 CTA。
                com.dwinovo.numen.client.ui.NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                        eb.getX() - FIELD_INSET_X, eb.getY() - FIELD_INSET_Y,
                        eb.getWidth() + FIELD_INSET_X * 2, eb.getHeight() + FIELD_INSET_Y * 2,
                        UiTheme.current().aiFill(),
                        eb.isFocused() ? UiTheme.current().cta() : UiTheme.current().aiBorder());
            }
        }
        boolean sliding = tab == Tab.SETTINGS && overlayDx != 0;
        if (sliding) {
            g.enableScissor(left + 3, top + HEADER_H, left + panelW - 3, top + panelH - 3);
            g.pose().pushPose();
            g.pose().translate(overlayDx, 0, 0);
        }
        for (AbstractWidget w : overlay) {
            w.render(g, mouseX, mouseY, partial);
        }
        if (sliding) {
            g.pose().popPose();
            g.disableScissor();
        }
        // Settings-tab overlay pass: field placeholders, voice-form row labels, and the form
        // dropdowns' open lists (drawn last so they sit above the fields) — see SettingsView.
        // 同伴删除模态在场时跳过——占位符/行标题不能画到暗幕上面。
        if (tab == Tab.SETTINGS && !overlayOpen() && !modalOpen()) {
            settings.renderOverlays(g, mouseX, mouseY);
        }
        // Summon warn — shown only when 创建 was clicked and something is missing
        // (error at the action, never ambient text). Takes the hint line's spot.

        // 屏幕级浮层(菜单、确认卡,连同刚收起还在淡出的):压在一切之上,tooltip 之前。
        overlayUi.render(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                com.dwinovo.numen.client.screen.settings.HostThemeColors.current(),
                mouseX, mouseY, net.minecraft.Util.getMillis());

        // Hovered tooltip — drawn last so nothing paints over it; only after the pointer has rested a while.
        if (pendingTip != null && !overlayOpen()) {
            String key = pendingTip.toString();
            long now = System.currentTimeMillis();
            if (!key.equals(tipKey)) {
                tipKey = key;
                // 上一条提示刚消失不久 = 指针是从它挪过来的:不再等
                tipSince = now - tipLastShownMs <= TIP_SKIP_MS ? now - TIP_DELAY_MS : now;
            }
            if (now - tipSince >= TIP_DELAY_MS) {
                g.renderComponentTooltip(font, pendingTip, pendingTipX, pendingTipY);
                tipLastShownMs = now;
            }
        } else {
            tipKey = null;
        }
    }

    // ---- left companion rail ----

    /** The folded-in roster (on the merged sprite's rail column): one tile per conversation below the
     *  green header — a companion's face, or stacked faces for a multi-member one — active one framed
     *  gold, a status dot on each companion, + tile at the bottom. */
    /** 选中底的纵坐标:切换时从上一行滑到这一行(Telegram),不是瞬移。 */
    private float selY = Float.NaN;

    private void renderRail(GuiGraphics g, int mouseX, int mouseY) {
        List<Conversation> items = rail();
        UiTheme t = UiTheme.current();
        int rowX = railX + 3, rowW = railW - 3;
        railScroll = Math.clamp(railScroll, 0, maxRailScroll());     // keep valid as the roster grows/shrinks
        long now = System.currentTimeMillis();
        float dt = lastRailFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastRailFrameMs) / 1000f);
        updateShrink(mouseX, mouseY);   // 它会把 lastRailFrameMs 推到现在,所以 dt 先算
        // 分组标签条:宽栏露出,窄栏收起(Telegram 会话列窄到只剩头像时标签条随宽度收掉,选中的分组照样筛)
        folderShown = Float.isNaN(folderShown) ? folderTarget()
                : com.dwinovo.numen.client.ui.Anim.approach(folderShown, folderTarget(), 18f, dt);
        // 顶上一条:☰(Telegram 会话列表顶上那一条的左端),点开是召唤同伴、设置;菜单开着时亮着
        {
            int mx0 = rowX + PAD, my0 = top + 3 + (RAIL_BAR_H - ICON_N) / 2;
            boolean hotMenu = railMenuAt(mouseX, mouseY) && !overlayOpen();
            boolean menuOpen = mainMenu != null && mainMenu.isOpen();
            com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.MENU, mx0, my0, ICON_N,
                    menuOpen || hotMenu ? CTA : TXT_MUTED);
            if (narrow()) {
                boolean hotSearch = overSearchIcon(mouseX, mouseY) && !overlayOpen();
                com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.SEARCH,
                        searchIconX(), my0, ICON_N, searchPopup || hotSearch ? CTA : TXT_MUTED);
            } else {
                renderSearch(g, mouseX, mouseY);
            }
        }
        renderFolderStrip(g, mouseX, mouseY, dt);
        // 标题区与列表之间一道线,跟着标签条上下
        g.fill(rowX, railStartY() - 1, rowX + rowW, railStartY(), t.border());
        // 切分组:旧列表整宽滑出、先淡掉,新列表从另一侧滑进、后淡入(Telegram 的 SlideAnimation,200ms)
        g.enableScissor(rowX, railStartY(), rowX + rowW, railBottomEdge());
        float p = slide == null ? 1f : Math.min(1f, (now - slide.startMs()) / (float) FOLDER_SLIDE_MS);
        if (p >= 1f) slide = null;
        if (slide != null) {
            renderRows(g, slide.items(), slide.folder(), slide.scroll(),
                    rowX - slide.dir() * Math.round(rowW * easeInCirc(p)), 1f - easeOutCirc(p),
                    false, mouseX, mouseY, dt, now);
            renderRows(g, items, Conversations.instance().folder(), railScroll,
                    rowX + slide.dir() * Math.round(rowW * (1f - easeOutCirc(p))), easeInCirc(p),
                    true, mouseX, mouseY, dt, now);
        } else {
            renderRows(g, items, Conversations.instance().folder(), railScroll, rowX, 1f, true, mouseX, mouseY, dt, now);
        }
        g.disableScissor();
        // scroll cues — chevrons when the list overflows in either direction
        int cx = railX + railW / 2;
        if (railScroll > 0) chevron(g, cx, top + 1, true);
        if (railScroll < maxRailScroll()) chevron(g, cx, railBottomEdge() + 2, false);
    }

    /**
     * 左栏的行,左缘在 {@code x0}、整体透明度 {@code a}。平时只画一份;切分组时新旧两份各画一遍。
     * {@code live} = 这是此刻的列表:悬停、拖拽、选中底的滑动、"在干什么"的进度都只跟着它走;
     * 滑出去的那份只照原样画,不接这些。
     */
    private void renderRows(GuiGraphics g, List<Conversation> items, String folder, int first, int x0, float a,
                            boolean live, int mouseX, int mouseY, float dt, long now) {
        UiTheme t = UiTheme.current();
        int rowW = railW - 3;
        int startY = railStartY();
        Conversation dragged = live && railDragging && railPressed < items.size() ? items.get(railPressed) : null;
        boolean railQuiet = live && !overlayOpen() && !modalOpen() && !railDragging;
        if (items.isEmpty() && railW >= RAIL_FULL_W) {
            // 在搜:没有对得上的;没在搜、分组里本来就空:这个分组里还没有会话
            if (!railQuery.isBlank()) {
                txt(g, Component.translatable(ModLanguageData.Keys.RAIL_NO_MATCH), x0 + PAD, startY + 8, fade(TXT_FAINT, a));
            } else if (!folder.equals(ChatFolders.ALL)) {
                txt(g, Component.translatable(ModLanguageData.Keys.FOLDER_EMPTY), x0 + PAD, startY + 8, fade(TXT_FAINT, a));
            }
        }
        // 选中底先画(滑动的),行的内容压在它上面
        int activeIdx = -1;
        for (int i = 0; i < items.size(); i++) if (sameAs(items.get(i), conv)) { activeIdx = i; break; }
        int activeY = startY + (activeIdx - first) * RAIL_SLOT;
        boolean activeShown = activeIdx >= first && activeY + RAIL_SLOT <= railBottomEdge();
        if (activeShown) {
            float y = activeY;
            if (live) {
                selY = Float.isNaN(selY) ? activeY : com.dwinovo.numen.client.ui.Anim.approach(selY, activeY, 18f, dt);
                y = selY;
            }
            // 选中那一行整行填色(Telegram 的 dialogsBgActive)
            g.fill(x0, Math.round(y), x0 + rowW, Math.round(y) + RAIL_SLOT, fade(t.active(), a));
        } else if (live) {
            selY = Float.NaN;
        }
        int textRight = x0 + rowW - 5;
        for (int i = first; i < items.size(); i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_SLOT > railBottomEdge()) break;
            Conversation c = items.get(i);
            UUID her = Conversations.instance().soloOf(c);
            boolean active = i == activeIdx;
            boolean hovered = live && mouseX >= x0 && mouseX < x0 + rowW && mouseY >= ay && mouseY < ay + RAIL_SLOT;
            // 拖拽中:指针下的另一行是落点,整行亮一道左缘条;原行压暗
            boolean dropTarget = railDragging && hovered && i != railPressed;
            if (!active && hovered && railQuiet) {
                g.fill(x0, ay, x0 + rowW, ay + RAIL_SLOT, t.over());
            }
            if (dropTarget) {
                g.fill(x0, ay, x0 + 2, ay + RAIL_SLOT, CTA);
            }
            int fx = x0 + RAIL_FACE_X, fy = ay + (RAIL_SLOT - RAIL_AV) / 2;
            com.dwinovo.numen.client.ui.NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font),
                    fx - 1, fy - 1, RAIL_AV + 2, RAIL_AV + 2, fade(FIELD, a), fade(active ? t.active() : BORDER, a));
            // 脸是贴图,透明度只能走着色(淡入淡出的那一份)
            if (a < 1f) {
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                g.setColor(1f, 1f, 1f, a);
            }
            if (live && i == shrinkIndex && shrinkPx > 0f && dragged != null) {
                // 合并预览:原来的脸缩向左上角,拖着的那张从右下角长出来,长满就是叠脸格的样子
                float sp = shrinkPx / RAIL_STEP;
                com.dwinovo.numen.client.skin.ConversationFaces.draw(g, c, fx, fy, Math.round(RAIL_AV - shrinkPx));
                int grow = Math.round(sp * RAIL_SMALL);
                if (grow > 2) {
                    com.dwinovo.numen.client.skin.ConversationFaces.draw(g, dragged,
                            fx + RAIL_AV - grow, fy + RAIL_AV - grow, grow);
                }
            } else {
                com.dwinovo.numen.client.skin.ConversationFaces.draw(g, c, fx, fy, RAIL_AV);
            }
            if (a < 1f) g.setColor(1f, 1f, 1f, 1f);
            if (live && railDragging && i == railPressed) {
                g.fill(x0, ay, x0 + rowW, ay + RAIL_SLOT, 0x90101010);
            }
            if (railW < RAIL_FULL_W) {
                // 窄栏:只有脸;名字靠悬停
                if (hovered && !active && railQuiet) {
                    tip(java.util.List.of(Component.literal(c.displayName(NumenRoster.instance()::name))), mouseX, mouseY);
                }
            } else {
            // 名字一行、最后一句一行;时间在名字那行的右端
            int tx = fx + RAIL_AV + 6;
            int nameColor = active ? t.onActive() : TXT;
            int dimColor = active ? UiTheme.mix(t.onActive(), t.active(), 0.25f) : TXT_MUTED;
            var last = com.dwinovo.numen.client.screen.chat.ConversationPreview.last(c);
            String when = last == null ? "" : whenLabel(last.ts(), now);
            int whenW = when.isEmpty() ? 0 : font.width(when) + 4;
            txt(g, Component.literal(Nb.clip(font, c.displayName(NumenRoster.instance()::name), textRight - tx - whenW)),
                    tx, ay + 6, fade(nameColor, a));
            if (!when.isEmpty()) {
                txt(g, Component.literal(when), textRight - font.width(when), ay + 6, fade(active ? dimColor : TXT_FAINT, a));
            }
            // 未读角标(Telegram):她在别的会话里说了话,这一行右边一枚强调色计数;当前这行没有
            int previewRight = textRight;
            int unread = railUnread(c);
            if (unread > 0) {
                String n = com.dwinovo.numen.client.screen.chat.UnreadBadge.label(unread);
                int bx = textRight - com.dwinovo.numen.client.screen.chat.UnreadBadge.width(font, n);
                com.dwinovo.numen.client.screen.chat.UnreadBadge.draw(g, font, n, bx, ay + 16, fade(CTA, a), fade(ON_CTA, a));
                previewRight = bx - 4;
            } else if (Conversations.instance().pinned(c)) {
                // 置顶的行没有未读时右端挂一枚置顶图标(Telegram 同一个位置)
                int px = textRight - ICON_N;
                com.dwinovo.numen.client.ui.mc.Sprites.draw(g, com.dwinovo.numen.client.ui.mc.Sprites.PIN,
                        px, ay + 16, ICON_N, fade(dimColor, a));
                previewRight = px - 4;
            }
            // 第二行:她此刻在干什么 > 没发出去的草稿 > 最后一句。换的时候和抬头第二行一样上下滑,只露一行
            float at = railActT.getOrDefault(c.id(), 0f);
            if (live) {
                String act = railActivity(c, now);
                if (act != null) railActText.put(c.id(), act);
                at = Math.clamp(at + (act != null ? dt : -dt) * 7f, 0f, 1f);
                railActT.put(c.id(), at);
            }
            float e = com.dwinovo.numen.client.ui.Anim.easeOutCubic(at);
            int py = ay + 18, pw = previewRight - tx, lh = font.lineHeight;
            g.enableScissor(tx, py - 1, previewRight, py + lh + 1);
            if (e < 0.97f) {
                int y0 = py - Math.round(lh * e);
                float k = (1f - e) * a;
                String draft = active ? "" : Conversations.instance().draft(c);
                if (!draft.isEmpty()) {
                    String pre = I18n.get(ModLanguageData.Keys.RAIL_DRAFT) + ": ";
                    txt(g, Component.literal(pre), tx, y0, fade(FAIL, k));
                    txt(g, Component.literal(Nb.clip(font, draft, pw - font.width(pre))), tx + font.width(pre), y0, fade(dimColor, k));
                } else {
                    String preview = last == null ? I18n.get(ModLanguageData.Keys.RAIL_EMPTY) : last.text();
                    txt(g, Component.literal(Nb.clip(font, preview, pw)), tx, y0, fade(last == null ? TXT_FAINT : dimColor, k));
                }
            }
            if (e > 0.03f && railActText.containsKey(c.id())) {
                txt(g, Component.literal(Nb.clip(font, railActText.get(c.id()), pw)), tx, py + Math.round(lh * (1f - e)),
                        fade(active ? t.onActive() : t.accent(), e * a));
            }
            g.disableScissor();
            }
            // 状态点、复活倒计时、等点头的"!"都是一只同伴的事;会话行上只有脸
            if (her == null) continue;
            if (NumenRoster.instance().isDead(her)) {                 // dead — dim veil + respawn countdown
                g.fill(fx, fy, fx + RAIL_AV, fy + RAIL_AV, fade(0xB0101010, a));
                long rem = NumenRoster.instance().remainingMs(her);
                // 头像太小写不下字:归零改画一个"等"字记号,细节在抬头第二行
                String cd = rem <= 0 ? "…" : String.valueOf((int) Math.ceil(rem / 1000.0));
                txt(g, Component.literal(cd), fx + (RAIL_AV - font.width(cd)) / 2, fy + (RAIL_AV - 8) / 2, fade(CTA, a));
            } else {
                int d = fx + RAIL_AV - 6, e2 = fy + RAIL_AV - 6;     // status LED, bottom-right
                g.fill(d, e2, d + 5, e2 + 5, fade(statusColor(her), a));
                Nb.border(g, d, e2, 5, 5, 1, fade(BORDER, a));
            }
            if (com.dwinovo.numen.client.consent.ConsentCards.pending(her) != null) {
                // 她在等主人点头:脸的右上角一枚"!",没选中她的时候也看得见
                int bx = fx + RAIL_AV - 7, by = fy - 1;
                g.fill(bx, by, bx + 8, by + 10, fade(CTA, a));
                txt(g, Component.literal("!"), bx + (8 - font.width("!")) / 2 + 1, by + 1, fade(ON_CTA, a));
            }
        }
    }

    /** 左栏这一行的未读数:当前对着的那行没有(正看着)。行尾的角标和分组标签上的数都数它。 */
    private int railUnread(Conversation c) {
        return sameAs(c, conv) ? 0 : com.dwinovo.numen.client.screen.chat.ConversationPreview.unread(
                c, Conversations.instance().lastSeen(c));
    }

    // ---- 分组标签条(Telegram 的 Chat Folders):☰ 与搜索框那一条下面一排标签 ----

    private static final int FOLDER_H = 16;
    /** 标签里字两侧的留白;标签条两端的留白。 */
    private static final int FOLDER_TAB_PAD = 5;
    private static final int FOLDER_EDGE = 3;
    /** 自建分组的名字在标签上最多这么宽,再长裁掉。 */
    private static final int FOLDER_NAME_MAX = 60;
    /** 切分组时列表滑动的时长(Telegram dialogsFilterSlideDuration)。 */
    private static final int FOLDER_SLIDE_MS = 200;

    /** 标签条露出多高(像素,按趋近走);NaN = 还没画过,第一帧直接摆到位,开面板不滑。 */
    private float folderShown = Float.NaN;
    /** 标签比栏宽时横着滚了多少,按趋近走向 {@link #folderScrollTo}。 */
    private float folderScroll, folderScrollTo;
    /**
     * 强调色下划线的左缘(不含横滚)与宽,按趋近走:切分组时滑到新标签下面、宽度变成它的字宽
     * (Telegram 的 barSnapToLabel)。NaN = 还没画过。
     */
    private float folderBarX = Float.NaN, folderBarW;
    /** 切分组那一下滑出去的旧列表(Telegram 切分组时先截一张旧列表的图);滑完清掉。 */
    private RailSlide slide;

    /** @param dir 1 = 往右边的标签切,列表往左走;-1 反过来 */
    private record RailSlide(List<Conversation> items, String folder, int scroll, int dir, long startMs) {}

    /** 标签条上的一格:{@code x} 已经减去横滚。 */
    private record FolderTab(String id, String label, int unread, int x, int w, int labelW) {}

    private float folderTarget() {
        return narrow() ? 0f : FOLDER_H;
    }

    /** 标签条此刻占多高。 */
    private int folderStripH() {
        return Math.round(Float.isNaN(folderShown) ? folderTarget() : folderShown);
    }

    /** 标签条的顶边:压在 ☰ 那一条的底线上,那道线跟着标签条挪到下面去。 */
    private int folderStripY() {
        return top + RAIL_TOP - 1;
    }

    /** 标签条整条露着才接鼠标;收起的途中、窄栏上都不接。 */
    private boolean overFolderStrip(double mx, double my) {
        return folderStripH() >= FOLDER_H && mx >= railX + 3 && mx < railX + railW
                && my >= folderStripY() && my < folderStripY() + FOLDER_H;
    }

    private String folderLabel(String id) {
        return switch (id) {
            case ChatFolders.ALL -> I18n.get(ModLanguageData.Keys.FOLDER_ALL);
            case ChatFolders.SOLO -> I18n.get(ModLanguageData.Keys.FOLDER_SOLO);
            case ChatFolders.GROUP -> I18n.get(ModLanguageData.Keys.FOLDER_GROUP);
            default -> Nb.clip(font, Conversations.instance().customFolder(id).name(), FOLDER_NAME_MAX);
        };
    }

    /** 这个分组里有几个会话有未读——Telegram 标签上的数是"几个会话",不是几条。 */
    private int folderUnread(String id) {
        Conversations convos = Conversations.instance();
        int n = 0;
        for (Conversation c : convos.all()) {
            if (convos.inFolder(id, c) && railUnread(c) > 0) n++;
        }
        return n;
    }

    private List<FolderTab> folderTabs() {
        List<FolderTab> out = new ArrayList<>();
        int x = railX + 3 + FOLDER_EDGE - Math.round(folderScroll);
        for (String id : Conversations.instance().folderIds()) {
            String label = folderLabel(id);
            int unread = folderUnread(id);
            int lw = font.width(label);
            int w = FOLDER_TAB_PAD * 2 + lw + (unread > 0
                    ? 3 + com.dwinovo.numen.client.screen.chat.UnreadBadge.width(font,
                            com.dwinovo.numen.client.screen.chat.UnreadBadge.label(unread))
                    : 0);
            out.add(new FolderTab(id, label, unread, x, w, lw));
            x += w;
        }
        return out;
    }

    private float maxFolderScroll(List<FolderTab> tabs) {
        int total = FOLDER_EDGE * 2;
        for (FolderTab tab : tabs) total += tab.w();
        return Math.max(0, total - (railW - 3));
    }

    /** 指针下的标签(folderTabs 的下标),不在标签上是 -1。 */
    private int folderTabAt(double mx, double my) {
        if (!overFolderStrip(mx, my)) return -1;
        List<FolderTab> tabs = folderTabs();
        for (int i = 0; i < tabs.size(); i++) {
            if (mx >= tabs.get(i).x() && mx < tabs.get(i).x() + tabs.get(i).w()) return i;
        }
        return -1;
    }

    /**
     * 切到这个分组:旧列表记下来滑出去,新列表从另一侧滑进来;切到的标签滚到标签条正中。
     * 再点当前分组是回到列表顶上(Telegram)。
     */
    private void selectFolder(String id) {
        Conversations convos = Conversations.instance();
        String was = convos.folder();
        if (id.equals(was)) {
            railScroll = 0;
            return;
        }
        List<String> ids = convos.folderIds();
        slide = new RailSlide(rail(), was, railScroll, ids.indexOf(id) > ids.indexOf(was) ? 1 : -1,
                System.currentTimeMillis());
        convos.selectFolder(id);
        railScroll = 0;
        selY = Float.NaN;   // 新列表里选中底直接落在它那一行,不从旧列表的位置滑过来
        List<FolderTab> tabs = folderTabs();
        int i = ids.indexOf(id);
        FolderTab tab = tabs.get(i);
        // 滚到正中;第一个贴左(Telegram scrollToIndex)
        float center = tab.x() + Math.round(folderScroll) + tab.w() / 2f - (railX + 3);
        folderScrollTo = i == 0 ? 0f : Math.clamp(center - (railW - 3) / 2f, 0f, maxFolderScroll(tabs));
    }

    /**
     * 右键分组标签的菜单(Telegram 同一个位置):自建的能编辑、删除;哪个标签上都能新建。
     * 删除在最下面标红,点了还要过确认卡。
     */
    private void openFolderMenu(String id, double mx, double my) {
        java.util.List<PopupMenu.Item> items = new java.util.ArrayList<>();
        ChatFolders.Folder folder = Conversations.instance().customFolder(id);
        if (folder != null) {
            items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.EDIT,
                    I18n.get(ModLanguageData.Keys.FOLDER_EDIT), false, () -> openFolderCard(folder)));
        }
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.FOLDER_PLUS,
                I18n.get(ModLanguageData.Keys.FOLDER_NEW), false,
                () -> openFolderCard(new ChatFolders.Folder(null, "", List.of()))));
        if (folder != null) {
            items.add(PopupMenu.SEPARATOR);
            items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.DELETE,
                    I18n.get(ModLanguageData.Keys.FOLDER_DELETE), true, () -> openDeleteFolderConfirm(folder)));
        }
        openContextMenu(items, mx, my);
    }

    /**
     * 左栏一行菜单里的"加入分组…":换成一张列着自建分组的菜单,已经收着它的那几个前面打勾,点一下是放进去或
     * 拿出来(Telegram 的 Add to folder);最下面新建一个分组,这一行预先勾好。
     */
    private void openAddToFolderMenu(Conversation c, double mx, double my) {
        Conversations convos = Conversations.instance();
        java.util.List<PopupMenu.Item> items = new java.util.ArrayList<>();
        for (String id : convos.folderIds()) {
            ChatFolders.Folder folder = convos.customFolder(id);
            if (folder == null) continue;   // 全部、私聊、群聊按会话本身归,放不进也拿不出
            boolean in = convos.inFolder(id, c);
            items.add(new PopupMenu.Item(in ? com.dwinovo.numen.client.ui.mc.Sprites.CHECK
                    : com.dwinovo.numen.client.ui.mc.Sprites.FOLDER,
                    folder.name(), false, () -> convos.toggleInFolder(id, c)));
        }
        if (!items.isEmpty()) items.add(PopupMenu.SEPARATOR);
        items.add(new PopupMenu.Item(com.dwinovo.numen.client.ui.mc.Sprites.FOLDER_PLUS,
                I18n.get(ModLanguageData.Keys.FOLDER_NEW), false,
                () -> openFolderCard(new ChatFolders.Folder(null, "", List.of(c.id())))));
        openContextMenu(items, mx, my);
    }

    /** 分组卡开着时改的是哪个分组;新建时 id 为 null。 */
    private ChatFolders.Folder folderDraft;
    private FolderEditPanel folderEditPanel;

    private void openFolderCard(ChatFolders.Folder draft) {
        folderDraft = draft;
        if (folderEditPanel == null) folderEditPanel = new FolderEditPanel(new FolderHost());
        openCard(folderEditPanel);
    }

    /** 分组卡的宿主面:新建或改好的分组落库,关卡。 */
    private final class FolderHost implements FolderEditPanel.Host {
        @Override public ChatFolders.Folder draft() { return folderDraft; }

        @Override public void onSave(String name, List<String> conversationIds) {
            if (folderDraft.id() == null) {
                Conversations.instance().createFolder(name, conversationIds);
            } else {
                Conversations.instance().editFolder(folderDraft.id(), name, conversationIds);
            }
        }

        @Override public void onClose() {
            cardOpen = false;
            rebuild();
        }
    }

    /** 删分组的确认卡:会话本身不受影响,所以副文本说的是"不会被删除"。删的是选中的那个就先滑回全部。 */
    private void openDeleteFolderConfirm(ChatFolders.Folder folder) {
        dismissDialog.open(overlayUi, railX, top, railW + panelW, panelH,
                I18n.get(ModLanguageData.Keys.FOLDER_DELETE_TITLE, folder.name()),
                I18n.get(ModLanguageData.Keys.FOLDER_DELETE_WARNING),
                I18n.get("numen.gui.settings.cancel"), I18n.get(ModLanguageData.Keys.FOLDER_DELETE),
                () -> {
                    if (folder.id().equals(Conversations.instance().folder())) selectFolder(ChatFolders.ALL);
                    Conversations.instance().deleteFolder(folder.id());
                    rebuild();
                });
        rebuild();
    }

    private void renderFolderStrip(GuiGraphics g, int mouseX, int mouseY, float dt) {
        int shown = folderStripH();
        if (shown <= 0) return;
        UiTheme t = UiTheme.current();
        int x0 = railX + 3, x1 = railX + railW, y0 = folderStripY();
        folderScrollTo = Math.clamp(folderScrollTo, 0f, maxFolderScroll(folderTabs()));
        folderScroll = com.dwinovo.numen.client.ui.Anim.approach(folderScroll, folderScrollTo, 18f, dt);
        List<FolderTab> tabs = folderTabs();
        FolderTab on = tabs.get(Conversations.instance().folderIds().indexOf(Conversations.instance().folder()));
        // 下划线在不含横滚的坐标里走,画的时候再减去横滚:滚标签条时它跟着标签走,不自己滑
        float barX = on.x() + Math.round(folderScroll) + FOLDER_TAB_PAD;
        if (Float.isNaN(folderBarX)) {
            folderBarX = barX;
            folderBarW = on.labelW();
        }
        folderBarX = com.dwinovo.numen.client.ui.Anim.approach(folderBarX, barX, 18f, dt);
        folderBarW = com.dwinovo.numen.client.ui.Anim.approach(folderBarW, on.labelW(), 18f, dt);
        int barL = Math.round(folderBarX - folderScroll), barW = Math.round(folderBarW);
        g.enableScissor(x0, y0, x1, y0 + shown);
        int ty = y0 + shown - FOLDER_H;   // 收起的途中整条往上缩进顶上那一条
        boolean quiet = !overlayOpen() && !modalOpen() && !railDragging;
        for (FolderTab tab : tabs) {
            boolean hot = quiet && overFolderStrip(mouseX, mouseY) && mouseX >= tab.x() && mouseX < tab.x() + tab.w();
            if (hot) g.fill(tab.x(), ty, tab.x() + tab.w(), ty + FOLDER_H, t.over());
            int lx = tab.x() + FOLDER_TAB_PAD;
            // 字色跟着下划线走:下划线压在谁的字下面谁就是强调色,滑过去的途中两边各插一半(Telegram 的 SettingsSlider)
            int overlap = Math.min(barL + barW, lx + tab.labelW()) - Math.max(barL, lx);
            float k = Math.max(0f, overlap) / (float) Math.max(1, Math.max(barW, tab.labelW()));
            txt(g, Component.literal(tab.label()), lx, ty + 4, UiTheme.mix(TXT_MUTED, t.accent(), k));
            if (tab.unread() > 0) {
                com.dwinovo.numen.client.screen.chat.UnreadBadge.draw(g, font,
                        com.dwinovo.numen.client.screen.chat.UnreadBadge.label(tab.unread()),
                        lx + tab.labelW() + 3, ty + 2, CTA, ON_CTA);
            }
        }
        g.fill(barL, ty + FOLDER_H - 2, barL + barW, ty + FOLDER_H, CTA);
        g.disableScissor();
    }

    /**
     * Telegram SlideAnimation 的两条曲线:滑进来的那份位移走 easeOut、透明度走 easeIn(先到位后显形);
     * 滑出去的那份位移走 easeIn、透明度按 1 - easeOut 掉(先淡掉再走远)。
     */
    private static float easeOutCirc(float p) {
        return (float) Math.sqrt(1 - (p - 1) * (p - 1));
    }

    private static float easeInCirc(float p) {
        return 1f - (float) Math.sqrt(1 - p * p);
    }

    /** 左栏每行第二行"在干什么"的滑入进度,与最后一句(滑出去的时候还要画它)。 */
    private final java.util.Map<String, Float> railActT = new java.util.HashMap<>();
    private final java.util.Map<String, String> railActText = new java.util.HashMap<>();

    /**
     * 左栏这一行此刻该说她在干什么(Telegram 列表里的"正在输入…"):会话里有人在想、在干活、在整理记忆;
     * 都没有是 null。只算她此刻所在的会话——在群里忙,私聊那行不跟着亮。群里带名字。
     */
    private String railActivity(Conversation c, long now) {
        String tag = Conversations.instance().tagOf(c);
        boolean group = Conversations.instance().soloOf(c) == null;
        for (UUID m : Conversations.instance().membersAlive(c)) {
            EntityAgentLoop lp = AgentLoopRegistry.get(m).orElse(null);
            if (lp == null || !java.util.Objects.equals(lp.conversation(), tag)) continue;
            HeaderStatus s = headerStatus(m, now);
            if (s == null || !(s.key().equals("typing") || s.key().equals("busy") || s.key().equals("compacting"))) {
                continue;
            }
            return group ? NumenRoster.instance().name(m) + " " + s.text() : s.text();
        }
        return null;
    }

    /** 列表里那句话的时间:今天给时分,再往前给日期(Telegram)。 */
    private static String whenLabel(long ts, long now) {
        if (ts <= 0) return "";
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (day.equals(today)) {
            java.time.LocalTime t = java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalTime();
            return String.format("%02d:%02d", t.getHour(), t.getMinute());
        }
        return I18n.get(ModLanguageData.Keys.CHAT_DATE_MD, day.getMonthValue(), day.getDayOfMonth());
    }

    /** Scroll-affordance chevron sprite (amber pixel-art triangle, up = more above / down = more below).
     *  Blitted at its native 11×6 so the pixels stay crisp (no scaling, no AA). */
    private void chevron(GuiGraphics g, int cx, int y, boolean up) {
        g.blitSprite(
                up ? CHEVRON_UP : CHEVRON_DOWN, cx - 5, y, 11, 6);
    }

    /** 最后一行的底边最多到哪(下面留一道缝给箭头)。 */
    private int railBottomEdge() {
        return top + panelH - 3 - RAIL_BOT_GAP;
    }

    /** 左栏装得下几行。 */
    private int railVisibleSlots() {
        int slots = 0;
        while (railStartY() + (slots + 1) * RAIL_SLOT <= railBottomEdge()) slots++;
        return Math.max(1, slots);
    }

    private int maxRailScroll() {
        return Math.max(0, rail().size() - railVisibleSlots());
    }

    /** 第一行(可见的)的顶边:☰ 那一条、分组标签条下面,列表从上往下排(Telegram),不居中。 */
    private int railStartY() {
        return top + RAIL_TOP + folderStripH();
    }

    /** idle = green, working/compacting = amber, queued = gold; faint if no loop yet. */
    private int statusColor(UUID u) {
        return AgentLoopRegistry.get(u).map(loop -> {
            var status = loop.status();
            if (status.busy()) return RUN;   // 等模型、跑工具、整理记忆、身体有后台任务
            if (!status.queuedPreview().isEmpty()) return CTA;
            return OK;
        }).orElse(TXT_FAINT);
    }

    private static PlayerSkin skinFor(UUID u) {
        return com.dwinovo.numen.client.agent.KnownSkins.of(u);
    }

    /** 指针下那一行的下标(整行都算),不在行上则 -1。 */
    private int railIndexAt(int mx, int my) {
        if (mx < railX + 3 || mx >= railX + railW) return -1;
        int n = rail().size();
        int first = Math.clamp(railScroll, 0, maxRailScroll());
        int startY = railStartY();
        for (int i = first; i < n; i++) {
            int ay = startY + (i - first) * RAIL_SLOT;
            if (ay + RAIL_SLOT > railBottomEdge()) break;
            if (my >= ay && my < ay + RAIL_SLOT) return i;
        }
        return -1;
    }

    /**
     * 空面板(一只同伴都没有):正中一句话,下面一颗强调色的召唤钮——Telegram 空列表那颗"新消息"。
     * 有了同伴以后召唤只留在 ☰ 菜单里,不常驻占地方。
     */
    private void emptyHint(GuiGraphics g, int mouseX, int mouseY) {
        Component msg = Component.translatable("numen.empty.no_companions");
        int cx = left + panelW / 2;
        txt(g, msg, cx - font.width(msg) / 2, emptyButtonY() - 16, TXT_MUTED);
        boolean hot = !modalOpen() && !overlayOpen() && overEmptyButton(mouseX, mouseY);
        int bx = emptyButtonX(), by = emptyButtonY(), bw = emptyButtonW();
        g.fill(bx, by, bx + bw, by + EMPTY_BTN_H, hot ? UiTheme.mix(CTA, 0xFFFFFFFF, 0.15f) : CTA);
        String label = I18n.get("numen.summon.title");
        txt(g, Component.literal(label), bx + (bw - font.width(label)) / 2, by + (EMPTY_BTN_H - 8) / 2, ON_CTA);
    }

    private static final int EMPTY_BTN_H = 18;

    private int emptyButtonW() {
        return font.width(I18n.get("numen.summon.title")) + 24;
    }

    private int emptyButtonX() {
        return left + (panelW - emptyButtonW()) / 2;
    }

    private int emptyButtonY() {
        return top + HEADER_H + (panelH - HEADER_H) / 2;
    }

    private boolean overEmptyButton(double mx, double my) {
        return conv == null && tab == Tab.CHAT && mx >= emptyButtonX() && mx < emptyButtonX() + emptyButtonW()
                && my >= emptyButtonY() && my < emptyButtonY() + EMPTY_BTN_H;
    }

    // ---- chat transcript + plan ----

    /**
     * 置顶条(Telegram 的置顶消息那一条):左一道强调色竖线;第一行强调色写"目标",第二行是目标本身;
     * 右端一个 ×,点了本局收起。点条本身往下展开目标详情。
     * 目标不把评估器那句"还差什么"摆出来——没达成就静默接着干,不该每轮在主人眼前刷判词。
     */
    private void renderPin(GuiGraphics g, com.dwinovo.numen.agent.goal.GoalState goal, int y, int mouseX, int mouseY) {
        UiTheme t = UiTheme.current();
        int x = left + 3, w = panelW - 6;
        int shown = Math.round(pinShown);
        g.enableScissor(x, y, x + w, y + shown);
        int py = y + shown - PIN_H;   // 从抬头下面滑下来
        boolean hot = !modalOpen() && !overlayOpen() && mouseX >= x && mouseX < x + w
                && mouseY >= y && mouseY < y + shown;
        boolean closeHot = hot && mouseX >= pinCloseX();
        g.fill(x, py, x + w, py + PIN_H, hot ? t.over() : t.band());
        g.fill(x, py + PIN_H - 1, x + w, py + PIN_H, t.surfaceBorder());
        g.fill(left + PAD, py + 4, left + PAD + 2, py + PIN_H - 4, CTA);
        int tx = left + PAD + 8;
        txt(g, Component.literal(I18n.get("numen.pin.goal")), tx, py + 4, t.accent());
        txt(g, Component.literal(Nb.clip(font, goal.objective(), pinCloseX() - 4 - tx)), tx, py + 14, TXT);
        int cx = pinCloseX() + (left + panelW - 3 - pinCloseX() - font.width("×")) / 2;
        txt(g, Component.literal("×"), cx, py + (PIN_H - 8) / 2, closeHot ? TXT : TXT_MUTED);
        g.disableScissor();
    }

    /** 置顶条右端 × 那一格的左缘:从这儿到条的右缘点下去是收起,不是展开。 */
    private int pinCloseX() {
        return left + panelW - PAD - 12;
    }

    private void tip(List<Component> lines, int x, int y) {
        pendingTip = lines;
        pendingTipX = x;
        pendingTipY = y;
    }


    /**
     * 输入行此刻占多高:平时一行;她在等主人点头时是答复框的高度——答复框和输入框同级,正文往上让,不压在对话流上。
     */
    private int inputH() {
        return inputBar == null ? INPUT_H : inputBar.height();
    }

    private void renderChat(GuiGraphics g, int mouseX, int mouseY) {
        renderFind(g, mouseX, mouseY);
        EntityAgentLoop lp = loop();
        long now = System.currentTimeMillis();
        float dt = lastPinFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastPinFrameMs) / 1000f);
        lastPinFrameMs = now;
        // 置顶条(Telegram 的置顶消息):她有目标、主人没点 × 时从抬头下面滑出来;对话流往下让
        int pinTop = top + HEADER_H + Math.round(findShown);
        var goal = lp == null ? null : lp.goal();
        boolean pinned = goal != null && !com.dwinovo.numen.client.screen.chat.PinnedGoal.dismissed(goal);
        if (pinned) pinGoal = goal;
        else goalOpen = false;
        pinShown = com.dwinovo.numen.client.ui.Anim.approach(pinShown, pinned ? PIN_H : 0f, 18f, dt);
        pinY = pinTop;
        if (pinShown > 0.5f && pinGoal != null) renderPin(g, pinGoal, pinTop, mouseX, mouseY);
        int bodyY = pinTop + 4 + Math.round(pinShown);
        int transX = left + PAD;
        int transW = panelW - PAD * 2;   // 对话流永远占满整行
        // 输入框上方那一行只给一时的提示:整理记忆的进度、没绑模型的原因、命令的回话、麦克风提示。
        // 平时不占地方,有提示才滑出来(Telegram 的输入区上面没有常驻的一行)。
        int inputTop = top + panelH - inputH() - PAD;
        int dockY = inputTop - STATUS_H;
        boolean compacting = lp != null && lp.status().phase() == com.dwinovo.numen.agent.loop.Phase.COMPACT;
        boolean blocked = lp != null && lp.status().hold() == com.dwinovo.numen.agent.loop.Hold.BLOCKED
                && lp.status().holdReason() != null;
        boolean cmdLive = cmdReplyUntil > System.currentTimeMillis() && !cmdReply.isEmpty();
        boolean noticeLive = micNotice != null && micNoticeUntil > System.currentTimeMillis();
        if (!noticeLive && micNoticeUntil != 0) {   // 过期一次性复位(占位文案由输入行现取)
            micNoticeUntil = 0;
            micNotice = null;
        }
        boolean noticeLine = noticeLive && inputBar != null && !inputBar.text().isEmpty();
        boolean dock = compacting || blocked || cmdLive || noticeLine;
        dockShown = com.dwinovo.numen.client.ui.Anim.approach(dockShown, dock ? STATUS_H : 0f, 18f, dt);
        int bodyBottom = inputTop - 4 - Math.round(dockShown);

        // 外脑驱动中:对话流换成现场——同一套气泡语法,画的是现场缓冲(主人的话、
        // 外脑的 say 与动作行),顶上一条"谁接进来了"的知情行。
        if (lp != null && com.dwinovo.numen.mcp.server.McpMode.instance().driving()) {
            chatView.renderExternal(g, transX, bodyY, transW, bodyBottom - bodyY);
        } else {
            chatView.scrollbarRight(left + panelW - 4);   // 滑块贴正文区右缘,不是气泡区的
            chatView.render(g, transX, bodyY, transW, bodyBottom - bodyY, mouseX, mouseY);
        }

        // 置顶条点开:目标详情从它下面往下长,盖在对话流上;展开/收起都有过渡,收完才不画
        if (pinGoal != null && (goalOpen || goalShownH > 0f)) {
            int fullH = com.dwinovo.numen.client.screen.chat.PinnedGoal.renderOpen(g, font, pinGoal,
                    transX, transW, pinTop + PIN_H, bodyBottom, Math.round(goalShownH), now);
            goalShownH = com.dwinovo.numen.client.ui.Anim.approach(goalShownH, goalOpen ? fullH : 0f, 16f, dt);
        }
        // 框里已有文字时占位不显示,这条兜底行接管(用醒目的 FAIL 色)
        if (noticeLine) {
            txt(g, Component.literal(micNotice), left + PAD, dockY, FAIL);
        }
        // 没绑模型/没填 key:她停在 BLOCKED,原因一直挂在输入行上面,绑好了自己消失
        if (blocked && !compacting) {
            txt(g, Component.literal(lp.status().holdReason()), left + PAD, dockY, FAIL);
        }
        // 整理记忆:一条随摘要流回来的字数逼近满格的进度条。摘要多长事先不知道,所以它
        // 报的是"还在动",不是"完成了百分之几"——永远差一点,收尾时整条消失。
        if (compacting) {
            double p = lp.status().compactProgress();
            int bw = panelW - PAD * 2;
            int by = top + panelH - inputH() - PAD - 8;
            txt(g, Component.literal("整理记忆… " + Math.round(p * 100) + "%"),
                    left + PAD, by - 11, TXT_MUTED);
            g.fill(left + PAD, by, left + PAD + bw, by + 3, FIELD);
            g.fill(left + PAD, by, left + PAD + (int) Math.round(bw * p), by + 3, ACCENT);
        } else if (cmdLive) {
            int ly = dockY;
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


    @Override
    public void removed() {
        if (conv != null && inputBar != null) Conversations.instance().setDraft(conv, inputBar.text());
        com.dwinovo.numen.client.ui.mc.McTextInput.mountVia(null, null);
        super.removed();
    }
}
