package com.dwinovo.numen.api.client.screen.chat;

import com.dwinovo.numen.api.agent.llm.ConvoLog;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Mentions;
import com.dwinovo.numen.api.agent.conversation.Quote;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.client.agent.ClientCompanions;
import com.dwinovo.numen.api.client.agent.CauseWords;
import com.dwinovo.numen.api.client.agent.ToolWords;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolLine;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.client.agent.Conversations;
import com.dwinovo.numen.api.client.agent.CompanionView;
import com.dwinovo.numen.api.client.agent.KnownSkins;
import com.dwinovo.numen.api.client.agent.NumenRoster;
import com.dwinovo.numen.api.client.chat.ChatDisplayModes;
import com.dwinovo.numen.api.client.consent.ConsentCards;
import com.dwinovo.numen.api.client.consent.ConsentMessage;
import com.dwinovo.numen.api.client.screen.Nb;
import com.dwinovo.numen.api.client.screen.UiTheme;
import com.dwinovo.numen.api.client.ui.Anim;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.markdown.Markdown;
import com.dwinovo.numen.api.data.ModLanguageData;
import com.dwinovo.numen.api.client.ui.richtext.Doc;
import com.dwinovo.numen.api.client.ui.richtext.RichLayout;
import com.dwinovo.numen.api.entity.Keeper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import com.dwinovo.numen.api.client.skin.CompanionFace;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.dwinovo.numen.api.client.ui.mc.Sprites;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The chat transcript as a conversation: the owner's messages are right-aligned
 * bubbles, the companion's replies left-aligned bubbles — both with their real
 * skin avatar — and her run of thoughts and tool calls is one quiet process line
 * (click to open the step rail). Date chips and system notes (persona change /
 * compaction) are Telegram service messages: a centred translucent box, white text. Scrolling is eased ({@link Anim#approach}) and
 * pins to the bottom while the owner hasn't scrolled away.
 *
 * <p>Blocks are rebuilt every frame from the loop's PHYSICAL transcript (exactly
 * what the old flat row list read), so live tool spinners and mid-run arrivals
 * need no extra invalidation. The view owns scroll + fold state; {@code reset()}
 * on companion/tab switch.
 */
public final class ChatView {

    // ---- metrics ----
    private static final int LINE_H = 10;
    private static final int LABEL_H = 9;       // companion name line above its bubble
    /** 征询挂着时的倒计时放不进正文最后一行右侧,单独占的一行(小字)。 */
    private static final int COUNTDOWN_H = 8;
    /** 新消息飞入:从下面 8px 淡入,220ms easeOut——Telegram 那种"升上来"。 */
    private static final int ENTER_MS = 220;
    private static final int ENTER_DY = 8;
    private static final int AV = 18;           // avatar face size
    private static final int AV_GAP = 5;        // avatar ↔ bubble
    /** 气泡尾巴自底向上每一行伸出多长;最长那行就是尾巴的宽 {@code TAIL},比脸和气泡之间的缝窄。 */
    private static final int[] TAIL_STEPS = {4, 2, 1};
    private static final int TAIL = 4;
    private static final int PAD_H = 5;         // bubble text inset
    private static final int PAD_V = 4;         // 1 line → 18px bubble = exactly the avatar height
    /** 她的话是不带底色的正文:左边与名字对齐的缩进、上下各留的空,代替气泡的 {@code PAD_H}、{@code PAD_V}。 */
    private static final int LABEL_IN = 2;
    private static final int PLAIN_PAD_V = 2;
    /** 块与块之间:换了人、日期牌、提示行前后拉开 {@code BLOCK_GAP};同一个人接连的块只隔 {@code RUN_GAP}。 */
    private static final int BLOCK_GAP = 6;
    private static final int RUN_GAP = 2;
    private static final int TOP_PAD = 2;
    private static final int BOT_PAD = 2;
    private static final int SB_W = 4;          // scrollbar width
    private static final int EDGE = 2;          // left inset so the avatar FRAME (-2px) clears the scissor
    private static final int OPP_MARGIN = 24;   // kept clear on the far side of a bubble
    private static final int ICON_W = 11;       // 过程里一步前面的状态图标列
    /** 过程块:收起那一行与调用那一行的高、步骤线离块左缘与步骤正文的距离、步骤之间的缝、点开的代码与回执的内缩。 */
    private static final int HEAD_H = LINE_H + 4;
    private static final int STEP_GAP = 4;
    private static final int RAIL_X = 5;
    private static final int RAIL_W = 2;
    private static final int STEP_X = RAIL_X + RAIL_W + 7;
    private static final int BODY_PAD = 2;
    private static final int DETAIL_IN = ICON_W;
    /** 代码与回执的淡底里字离底边的距离。 */
    private static final int BOX_IN = 3;
    /** 展开、收起的趋近:量程 {@link #FOLD_FULL}(趋近到半个单位内就落定,所以量程取大),每秒的速率。 */
    private static final float FOLD_FULL = 10f;
    private static final float FOLD_RATE = 16f;
    /** "思考中"的高光扫过一遍用多久。 */
    private static final int SHIMMER_MS = 1400;
    private static final float SCROLL_RATE = 14f;
    /** Typewriter reveal speed (chars/s) and the max lag before it jumps to catch up. */
    private static final float REVEAL_CPS = 80f;
    private static final int REVEAL_MAX_LAG = 120;
    private static final String[] SPIN = {"|", "/", "-", "\\"};
    /** 连发合并里代表主人的那一格——主人不是同伴,没有 UUID。 */
    private static final UUID OWNER = net.minecraft.Util.NIL_UUID;

    // ---- palette: re-read from the CURRENT theme each frame (loadPalette), so the
    // Settings picker recolours the transcript live. Field names keep the constant
    // convention — they behave as constants within a frame. ----
    private int MUTED, FAINT, OK, RUN, FAIL, TXT;
    private int AI_FILL, AI_BORDER, OWN_FILL, QUEUED_FILL;
    /** 过程步骤线的竖线色。 */
    private int TRACE_BAR;
    /** 主人话里 @ 到的名字。 */
    private int MENTION;
    /** 引用条里那句和划掉的清单项用的淡字:她的、主人的(Telegram 的 msgInDateFg / msgOutDateFg)。 */
    private int IN_META, OUT_META;
    /** 未读角标上的数字色。 */
    private int ON_CTA;
    /** 服务消息(日期牌、未读消息、提示行)的半透明底与字(Telegram 的 msgServiceBg / msgServiceFg)。 */
    private int SERVICE_BG, SERVICE_FG;

    private void loadPalette() {
        UiTheme t = UiTheme.current();
        MUTED = t.textDim();
        FAINT = t.faint();
        OK = t.ok();
        RUN = t.run();
        FAIL = t.fail();
        TXT = t.text();
        AI_FILL = t.aiFill();
        AI_BORDER = t.aiBorder();
        OWN_FILL = t.ownFill();
        QUEUED_FILL = t.queuedFill();
        TRACE_BAR = t.surfaceBorder();
        MENTION = t.accent();
        IN_META = t.inMeta();
        OUT_META = t.outMeta();
        ON_CTA = t.onCta();
        SERVICE_BG = t.serviceBg();
        SERVICE_FG = t.serviceFg();
    }

    /**
     * 回到最新的浮钮(Telegram 翻上去时右下角那枚):边长、离右缘和底边的距离,
     * 以及露出的进度 0..1(趋近)——它从底边下面滑上来,被对话流裁掉,不是原地淡入。
     */
    private static final int JUMP = 18, JUMP_INSET = 6;
    private float jumpShown;
    private int jumpX, jumpY;
    /** 滚动条只在滚动时和指针在对话流上时出现(Telegram),淡入淡出按趋近走。 */
    private float barShown;
    private long lastScrollMs;
    /** 滑块贴哪条右缘(宿主给正文区的右缘,不是气泡区的);-1 = 气泡区自己的右缘。 */
    private int barRight = -1;
    /** 正拖着滑块;{@code barGrab} 是按下时指针离滑块顶边多远,拖的时候保持这个差。 */
    private boolean barDragging;
    private int barGrab;

    public void scrollbarRight(int x) {
        barRight = x;
    }

    private int barX() {
        return (barRight > 0 ? barRight : gx + gw) - SB_W;
    }

    private int thumbH() {
        return Math.max(12, gh * gh / (gh + lastMaxScroll));
    }

    private int thumbY() {
        return gy + Math.round((gh - thumbH()) * (scrollPos / Math.max(1, lastMaxScroll)));
    }

    /** 直接跳到某个滚动位置(拖滑块、点槽):不走趋近,手在哪滑块就在哪。 */
    private void scrollTo(float target) {
        scrollTarget = Math.clamp(Math.round(target), 0, lastMaxScroll);
        scrollPos = scrollTarget;
        pinBottom = scrollTarget >= lastMaxScroll;
        lastScrollMs = System.currentTimeMillis();
    }

    /** 拖滑块:按下时按住的那一点相对滑块的位置不变。 */
    public boolean mouseDragged(double mx, double my) {
        if (!barDragging) return false;
        int th = thumbH();
        float span = Math.max(1, gh - th);
        scrollTo((float) (my - barGrab - gy) / span * lastMaxScroll);
        return true;
    }

    public boolean mouseReleased() {
        if (!barDragging) return false;
        barDragging = false;
        return true;
    }

    private final Font font;
    private final Supplier<Conversation> conv;

    // ---- scroll + fold state ----
    private float scrollPos;
    private int scrollTarget;
    private boolean pinBottom = true;
    private int lastMaxScroll;
    /** 刚切进来的第一帧直接落到最底(Telegram 打开会话就停在最新一条),不从顶上滚下来;之后再平滑。 */
    private boolean snapNext;
    private long lastFrameMs;
    /** 打字机:每个成员在飞的回复各自露出多少、这一帧显示成什么(正文 + 闪烁光标)。
     *  build() 读的是缓存,点击时的重建和渲染看到同一份几何。 */
    private final java.util.Map<UUID, Live> live = new java.util.LinkedHashMap<>();

    private static final class Live {
        float revealed;
        /** 已经露出来的正文;光标不在里面(光标是排在正文后面的一小段字,不参与 Markdown 解析)。 */
        String shown = "";
        boolean caret;
    }
    /**
     * 主人点开的:一段过程({@code proc#她#第一步的记录序号})和一次调用的代码与回执({@code call#调用号})。
     * 开合只随点击变,过程在跑、收了都不自己动。
     */
    private final Set<String> expandedGroups = new HashSet<>();
    /** 每个开合此刻展开了几成(0..{@link #FOLD_FULL}),按 {@link #expandedGroups} 趋近;收完的从表里去掉。 */
    private final Map<String, Float> folds = new HashMap<>();
    /**
     * 排版缓存:同一段字、同一宽度只 split 一次。块每帧重建是为了活着的东西(转圈、在飞的字)不用另设
     * 失效,但 font.split 是这一路里最贵的一步——历史消息每帧重排一遍,思考一长整个界面就卡。
     * 键是带样式的文本 + 宽度,主人的话里 @ 亮不亮、主题换没换色都在键里。访问序 LRU,上限一千段。
     */
    private final java.util.Map<SplitKey, List<FormattedCharSequence>> splits =
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<SplitKey, List<FormattedCharSequence>> e) {
                    return size() > 1024;
                }
            };
    private record SplitKey(Component text, int width) {}

    private List<FormattedCharSequence> split(Component text, int width) {
        return splits.computeIfAbsent(new SplitKey(text, width), k -> font.split(k.text(), k.width()));
    }

    /** 气泡正文的 Markdown 解析缓存(同一段字只解析一次)与排版缓存(同一份富文本、同一宽度只排一次),同样 LRU。 */
    private final java.util.Map<String, Doc> parsed = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, Doc> e) {
            return size() > 512;
        }
    };
    private final java.util.Map<LayKey, RichLayout.Laid> laid = new java.util.LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<LayKey, RichLayout.Laid> e) {
            return size() > 1024;
        }
    };

    private record LayKey(Doc doc, int width) {}

    private RichLayout.Laid lay(Doc doc, int width) {
        return laid.computeIfAbsent(new LayKey(doc, width), k -> RichLayout.layout(k.doc(), k.width(), this::richWidth));
    }
    // geometry of the last render, for click / wheel hit-testing
    private int gx, gy, gw, gh;
    /** 这一帧画出来的脸(气泡旁、工具行旁):点哪张开哪只的资料页(Telegram 点消息头像看资料)。 */
    private record Face(UUID who, int x, int y) {}
    private final List<Face> faces = new ArrayList<>();
    /**
     * 这一帧画的是不是群:只有群里画别人的脸和名字(Telegram 私聊两边都不画)。就他俩的会话里她不止一位主人也算——
     * 说话的不止本机这一位,每句得认得出是谁的。
     */
    private boolean group;
    private int hoverX = -1, hoverY = -1;
    /** 每条记录第一次被看见的时刻(与归并后的记录同序);0 = 打开时就有的历史,不飞入。 */
    private final List<Long> born = new ArrayList<>();
    private long frameNow;

    /** 招呼页上的建议:点一条 = 主人把这句话发出去,和在输入行里打字发送走同一个入口。 */
    private final java.util.function.Consumer<String> say;

    public ChatView(Font font, Supplier<Conversation> conv, java.util.function.Consumer<String> say) {
        this.font = font;
        this.conv = conv;
        this.say = say;
    }

    // ---- 招呼页:和她还没说过话的单人会话,聊天区正中是她的大头像、名字、一句招呼和几条建议 ----
    private static final int WELCOME_FACE = 78;     // 左栏头像(26)的三倍
    private static final int WELCOME_GAP = 8, WELCOME_LINE_GAP = 4, WELCOME_CHIPS_GAP = 10;
    private static final int CHIP_H = 18, CHIP_PAD_H = 12, CHIP_GAP = 6, CHIP_ROW_GAP = 6, CHIP_SHADOW = 2;
    private static final int WELCOME_MAX_W = 300;
    /** 出现、消失与悬停的趋近量程与速率(趋近到半个单位内就落定,所以量程取大)。 */
    private static final float WELCOME_FULL = 10f, WELCOME_RATE = 12f, CHIP_RATE = 18f;
    private static final String[] SUGGESTIONS = {
            "numen.chat.welcome.follow", "numen.chat.welcome.nearby",
            "numen.chat.welcome.wood", "numen.chat.welcome.inventory"};
    /** 招呼页此刻露出几成(0..{@link #WELCOME_FULL}),随"是不是一条消息也没有"趋近,第一句话发出去时淡出、不硬切。 */
    private float welcomeShown;
    /** 这一帧招呼页是不是该在场(单人会话、没有可见消息);点击只认在场的。 */
    private boolean welcomeOn;
    private final float[] chipHover = new float[SUGGESTIONS.length];
    private final int[][] chipRect = new int[SUGGESTIONS.length][];

    /** 就他俩时是她;否则 null。 */
    private UUID solo() {
        return Conversations.instance().soloOf(conv.get());
    }

    /** Forget scroll + fold state (companion or tab switch). */
    /**
     * 换了会话(或面板不再对着任何会话):上一个会话的一切连同它这一帧画出来的样子都忘掉——点击、滚轮、拖滑块按的是画出来的
     * 几何,下一帧画了新会话才有新的;空面板不画对话,这里就什么也点不中。
     */
    public void reset() {
        gx = gy = gw = gh = 0;
        hits.clear();
        faces.clear();
        lastMaxScroll = 0;
        jumpShown = 0f;
        barDragging = false;
        scrollPos = 0;
        scrollTarget = 0;
        pinBottom = true;
        snapNext = true;
        topKey = null;
        lastFrameMs = 0;
        live.clear();
        expandedGroups.clear();
        folds.clear();
        ticked.clear();
        splits.clear();
        laid.clear();
        born.clear();
        welcomeShown = 0f;
        java.util.Arrays.fill(chipHover, 0f);
    }

    /** 对话里搜(Ctrl+F):小写的查询词,null = 没在搜。 */
    private String query;
    /** 这一帧命中的块:下标和离内容顶多远,从上到下。 */
    private final List<Integer> matchBlocks = new ArrayList<>();
    private final List<Integer> matchOffsets = new ArrayList<>();
    /** 停在第几个命中上,从最新那个数起(0 = 最新);-1 = 还没跳。 */
    private int matchAt = -1;
    /** 刚改了查询词:下一帧算出命中就跳到最新那个(Telegram 边打边跳)。 */
    private boolean jumpPending;

    /** 换查询词;空 = 不搜了。 */
    public void search(String q) {
        query = q == null || q.isBlank() ? null : q.strip().toLowerCase(java.util.Locale.ROOT);
        matchAt = -1;
        jumpPending = query != null;
    }

    public int matchCount() {
        return matchOffsets.size();
    }

    /** 停在第几个命中上(从最新数起,0 起);-1 = 还没跳。 */
    public int matchAt() {
        return matchAt;
    }

    /** 跳到下一个命中:{@code +1} 往旧的,{@code -1} 往新的;命中的那条停在对话流上三分之一处。 */
    public void jumpMatch(int dir) {
        int n = matchOffsets.size();
        if (n == 0) return;
        matchAt = Math.clamp(matchAt + dir, 0, n - 1);
        int off = matchOffsets.get(n - 1 - matchAt);
        scrollTarget = Math.clamp(off - gh / 3, 0, lastMaxScroll);
        pinBottom = scrollTarget >= lastMaxScroll;
        lastScrollMs = System.currentTimeMillis();
    }

    private boolean matches(Block b) {
        return query != null && b instanceof Bubble bb && bb.raw() != null
                && bb.raw().toLowerCase(java.util.Locale.ROOT).contains(query);
    }

    /** 顶上浮着的日期牌:露出多少(0..1)、写的哪天(淡出的时候还要画它)。 */
    private float dayShown;
    private String floatingDay;

    /** Re-pin to the bottom (a message was just sent). */
    public void pinToBottom() {
        pinBottom = true;
    }

    // ---- render ----

    public void render(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        loadPalette();
        group = solo() == null || NumenRoster.instance().severalKeepers(solo());
        hoverX = mouseX;
        hoverY = mouseY;
        faces.clear();
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        frameNow = now;
        updateLive(dt, now);
        advanceFolds(dt);
        List<Block> blocks = build(w);
        keepViewOnPrepend(blocks);
        renderBlocks(g, x, y, w, h, blocks, dt);
        welcomeOn = !group && blocks.isEmpty();
        welcomeShown = Anim.approach(welcomeShown, welcomeOn ? WELCOME_FULL : 0f, WELCOME_RATE, dt);
        if (welcomeShown > 0f) drawWelcome(g, x, y, w, h, dt);
        // 看过 = 视图停在底部时的最后一条;翻上去后来的话还没看过,"回到最新"钮据此浮出,左栏状态点也据此消
        Conversation c = conv.get();
        var latest = ConversationPreview.last(c);
        if (pinBottom && latest != null) Conversations.instance().markSeen(c, latest.ts());
        renderJump(g, x, y, h, dt, ConversationPreview.unseen(c, Conversations.instance().lastSeen(c)));
    }

    /** 上一帧记录最老那条的编号和内容总高:往上翻出更早的一页接在最前面时,据此把视图挪开新长出来的高度。 */
    private String topKey;
    private int topContent;

    /**
     * 更早的一页接到了记录最前面:内容从上面长高了,视图按长出的高度往下挪,主人正读的那条留在原处。
     * 停在最底的(新话从下面来)不挪,那边本来就跟着底走。
     */
    private void keepViewOnPrepend(List<Block> blocks) {
        List<Transcript.Entry> source = transcript();
        String key = source.isEmpty() ? null : String.valueOf(source.get(0).ids());
        int content = totalHeight(blocks);
        if (!pinBottom && topKey != null && key != null && !key.equals(topKey) && content > topContent) {
            int grew = content - topContent;
            scrollTarget += grew;
            scrollPos += grew;
        }
        topKey = key;
        topContent = content;
    }

    /** "回到最新"钮:翻上去超过半屏、或底下有没看过的话就浮出;从底边滑上来。 */
    private void renderJump(GuiGraphics g, int x, int y, int h, float dt, boolean unseen) {
        boolean want = lastMaxScroll - scrollTarget > h / 2 || unseen;
        jumpShown = Anim.approach(jumpShown, want ? 1f : 0f, 16f, dt);
        if (jumpShown <= 0.02f) return;
        int right = barX() + SB_W;
        jumpX = right - JUMP_INSET - JUMP;
        jumpY = y + h - Math.round(jumpShown * (JUMP + JUMP_INSET));
        g.enableScissor(x, y, right, y + h);
        NumenStyle.box(new com.dwinovo.numen.api.client.ui.mc.McDrawSurface(g, font), jumpX, jumpY, JUMP, JUMP,
                AI_FILL, AI_BORDER);
        Sprites.draw(g, Sprites.CHEVRON_DOWN, jumpX + (JUMP - Sprites.CHEVRON_W) / 2, jumpY + (JUMP - Sprites.CHEVRON_H) / 2,
                Sprites.CHEVRON_W, Sprites.CHEVRON_H, MUTED);
        g.disableScissor();
    }

    /** 滚动 + 裁剪 + 逐块绘制——对话视图与外脑现场视图共用的那台机器。 */
    private void renderBlocks(GuiGraphics g, int x, int y, int w, int h, List<Block> blocks, float dt) {
        gx = x; gy = y; gw = w; gh = h;
        hits.clear();
        int content = totalHeight(blocks);
        lastMaxScroll = Math.max(0, content - h);
        if (pinBottom) scrollTarget = lastMaxScroll;
        scrollTarget = Math.clamp(scrollTarget, 0, lastMaxScroll);
        if (snapNext) {
            scrollPos = scrollTarget;
            snapNext = false;
        }
        scrollPos = Anim.approach(scrollPos, scrollTarget, SCROLL_RATE, dt);

        // 搜索命中:先过一遍记下是哪几块、在哪;刚换了词就跳到最新那个
        matchBlocks.clear();
        matchOffsets.clear();
        if (query != null) {
            int off = TOP_PAD;
            for (int i = 0; i < blocks.size(); i++) {
                if (matches(blocks.get(i))) {
                    matchBlocks.add(i);
                    matchOffsets.add(off);
                }
                off += heightOf(blocks.get(i)) + gapAfter(blocks, i);
            }
            if (matchAt >= matchBlocks.size()) matchAt = matchBlocks.size() - 1;
            if (jumpPending && !matchBlocks.isEmpty()) {
                jumpPending = false;
                jumpMatch(1);
            }
        }
        int current = matchAt >= 0 ? matchBlocks.get(matchBlocks.size() - 1 - matchAt) : -1;
        g.enableScissor(x, y, x + w, y + h);
        int cy = y + TOP_PAD - Math.round(scrollPos);
        String floatDay = null;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            int bh = heightOf(b);
            if (b instanceof Divider d && cy < y) floatDay = d.text();   // 已经翻过顶的最近那枚日期牌
            if (cy + bh > y && cy < y + h) {
                int drawn = hits.size();
                drawingIndex = i;
                drawBlock(g, b, x, cy, w);
                if (i == current && hits.size() > drawn) {
                    // 停在的那个命中:气泡外描一圈强调色
                    Hit hit = hits.get(hits.size() - 1);
                    Nb.border(g, hit.x() - 1, hit.y() - 1, hit.w() + 2, hit.h() + 2, 1, MENTION);
                }
                long since = frameNow - flashAt;
                if (i == flashIndex && since < FLASH_MS && hits.size() > drawn) {
                    // 刚跳到的那句:盖一层强调色,先亮后退
                    Hit hit = hits.get(hits.size() - 1);
                    int a = Math.round(0x60 * (1f - since / (float) FLASH_MS));
                    g.fill(hit.x(), hit.y(), hit.x() + hit.w(), hit.y() + hit.h(), (MENTION & 0xFFFFFF) | (a << 24));
                }
            }
            cy += bh + gapAfter(blocks, i);
        }
        // 翻的时候顶上浮一枚眼前这一屏是哪天(Telegram),停下来一会儿淡出
        boolean scrolling = frameNow - lastScrollMs < 900 || barDragging || Math.abs(scrollPos - scrollTarget) > 0.5f;
        dayShown = Math.clamp(dayShown + (scrolling && floatDay != null ? dt : -dt) * 6f, 0f, 1f);
        if (floatDay != null) floatingDay = floatDay;
        if (dayShown > 0.02f && floatingDay != null) {
            g.setColor(1f, 1f, 1f, dayShown);
            drawDayChip(g, floatingDay, x, y + 3, w);
            g.setColor(1f, 1f, 1f, 1f);
        }
        g.disableScissor();

        boolean overBody = hoverX >= x && hoverX < barX() + SB_W && hoverY >= y && hoverY < y + h;
        boolean wantBar = lastMaxScroll > 0
                && (overBody || barDragging || frameNow - lastScrollMs < 800 || Math.abs(scrollPos - scrollTarget) > 0.5f);
        barShown = Anim.approach(barShown, wantBar ? 8f : 0f, 14f, dt);
        if (barShown > 0.5f && lastMaxScroll > 0) {
            // 只有滑块,没有槽,贴正文区右缘;半透明的灰(Telegram 的滚动条),随出没淡入淡出
            int a = Math.round(0x80 * Math.min(1f, barShown / 8f));
            g.fill(barX(), thumbY(), barX() + SB_W, thumbY() + thumbH(), (a << 24) | (MUTED & 0xFFFFFF));
        }
    }

    /** Wheel anywhere on the chat tab scrolls the transcript (parity with the old list). */
    public boolean mouseScrolled(double sy) {
        lastScrollMs = System.currentTimeMillis();
        scrollTarget = Math.clamp((long) (scrollTarget - sy * LINE_H * 3), 0, lastMaxScroll);
        pinBottom = scrollTarget >= lastMaxScroll;
        if (scrollTarget == 0) {
            ClientCompanions.loadOlder(conv.get().id());   // 翻到头了:要更早的一页
        }
        return true;
    }

    /** Toggle the fold of a completed tool chip under the mouse. */
    public boolean mouseClicked(double mx, double my) {
        if (jumpShown > 0.5f && mx >= jumpX && mx < jumpX + JUMP
                && my >= jumpY && my < jumpY + JUMP && my < gy + gh) {
            pinToBottom();
            return true;
        }
        // 滚动条那一列:按在滑块上开始拖;按在空处滑块先跳到指针下再开始拖(Telegram)
        if (lastMaxScroll > 0 && mx >= barX() - 2 && mx < barX() + SB_W + 2 && my >= gy && my < gy + gh) {
            int th = thumbH();
            int ty = thumbY();
            if (my < ty || my >= ty + th) {
                scrollTo((float) (my - gy - th / 2.0) / Math.max(1, gh - th) * lastMaxScroll);
                ty = thumbY();
            }
            barGrab = (int) my - ty;
            barDragging = true;
            lastScrollMs = System.currentTimeMillis();
            return true;
        }
        String link = linkAt(mx, my);
        if (link != null) {
            // 点链接走原版聊天那一套:看"聊天链接"选项、按需先弹确认框,确认完回到这个面板
            net.minecraft.client.Minecraft.getInstance().screen.handleComponentClicked(net.minecraft.network.chat.Style.EMPTY
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, link)));
            return true;
        }
        if (gw == 0 || mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return false;
        for (Hit h : hits) {
            // 点在气泡顶上的引用条上:跳回被引的那句
            Quote q = h.b().quote();
            if (q != null && mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + padV(h.b()) + QUOTE_H) {
                jumpToQuoted(h.index(), q);
                return true;
            }
        }
        if (welcomeOn) {
            for (int i = 0; i < SUGGESTIONS.length; i++) {
                int[] r = chipRect[i];
                if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    say.accept(I18n.get(SUGGESTIONS[i]));
                    return true;
                }
            }
        }
        loadPalette();
        int cy = gy + TOP_PAD - Math.round(scrollPos);
        List<Block> blocks = build(gw);
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            int bh = heightOf(b);
            if (b instanceof Consent c && my >= cy && my < cy + bh) {
                return ConsentMessage.click(font, c.card(), gx + EDGE + faceCol(), consentKeysTop(c, cy), c.w(), mx, my);
            }
            if (b instanceof Process p && my >= cy && my < cy + bh) {
                String key = processClickKey(p, cy, my);
                if (key != null) {
                    if (!expandedGroups.add(key)) expandedGroups.remove(key);
                    return true;
                }
            }
            cy += bh + gapAfter(blocks, i);
        }
        return false;
    }

    // ---- blocks ----

    private sealed interface Block permits Bubble, Checklist, Process, Notice, Divider, Consent {}

    /** One spoken message. {@code label} non-null = companion side (name above the bubble);
     *  {@code runEnd} = 这一块是这个人连发的最后一块(群里脸贴它旁边,气泡带尾巴);
     *  {@code who} = the companion whose face goes on it (null on the owner's side);
     *  {@code fill} 只有主人的气泡用,她与别的说话人的话是不带底色的正文;
     *  {@code by} 非空 = 这句是她的另一位主人说的,是他的名字——这时 {@code who} 是那位玩家,气泡和她的话一样画在左边、
     *  按人标名字和脸(脸是玩家的皮肤,不是同伴,点不开资料页);
     *  {@code entry} = 归并后的记录序号,新来的按它飞入(-1 = 不飞)。 */
    private record Bubble(boolean own, String label, RichLayout.Laid text, int ink,
                          int maxLineW, int fill,
                          boolean runEnd, UUID who, int entry,
                          String raw, Quote quote, String by) implements Block {
        Bubble withRunEnd(boolean on) {
            return new Bubble(own, label, text, ink, maxLineW, fill, on, who, entry, raw, quote, by);
        }

        int rows() {
            return text.rows().size();
        }
    }

    /**
     * 她的一份计划,画成她说的一条清单消息(Telegram 的清单消息):她的气泡底色,抬头一行强调色"计划 2/5",
     * 下面一项一行(长的折行),前面一个方格。{@code items} 留着比"是不是同一份";{@code rows} 是按这一刻的宽度
     * 折好的行;{@code entry} 是第一次出现时的——同一份计划原地更新,不改它是哪一条。
     */
    private record Checklist(UUID who, String label, List<com.dwinovo.numen.api.agent.tool.TodoTool.Item> items, String header,
                             List<CheckRow> rows, int maxLineW, int entry,
                             boolean runEnd) implements Block {
        Checklist withRunEnd(boolean on) {
            return new Checklist(who, label, items, header, rows, maxLineW, entry, on);
        }
    }

    /** 清单里的一项:状态(画方格用)和折好的行。 */
    private record CheckRow(com.dwinovo.numen.api.agent.tool.TodoTool.Status state, List<FormattedCharSequence> lines) {}

    /** 打勾的过渡:方格从中间往外填满,满了再出勾。 */
    private static final int TICK_MS = 220;
    /**
     * 清单里每项第一次被看见打上勾的时刻,键见 {@link #tickKey};0 = 打开面板时就已经勾上,不播过渡。
     * 勾又被取消就删掉,再勾上重新播。只按归并完的最终那份记({@link #settleTicks}),同一份计划前后几版不来回翻。
     */
    private final java.util.Map<String, Long> ticked = new java.util.HashMap<>();

    /** 清单的方格边长,和方格那一列的宽(方格 + 离字的缝)。 */
    private static final int BOX = 7;
    private static final int BOX_COL = BOX + 4;

    /** 气泡顶上的引用条:两行(谁、那句),左缘一道竖线;{@code QUOTE_IN} 是字离竖线多远。 */
    private static final int QUOTE_H = 20;
    /** 服务消息里字离底边的距离:左右、上下(Telegram 的 msgServicePadding 按这里的字号缩)。 */
    private static final int SERVICE_PAD_H = 5;
    private static final int SERVICE_PAD_V = 2;
    private static final int QUOTE_IN = 6;

    /** 这一帧画出来的气泡在哪、是哪条:右键按它认点中的是哪句。 */
    private record Hit(int x, int y, int w, int h, Bubble b, int index) {}

    /** 正在画第几块:记进 {@link Hit},点引用条时知道从哪一块往回找。 */
    private int drawingIndex;
    /** 刚跳到的那一块闪一下(Telegram 点回复条跳过去,那条亮一下再退):哪一块、什么时候开始闪。 */
    private int flashIndex = -1;
    private long flashAt;
    private static final int FLASH_MS = 1200;
    private final List<Hit> hits = new ArrayList<>();

    /** 右键点中的那句:哪只同伴说的(人说的——本机的玩家、她的另一位主人——是 null)、说的人的名字、原文(引用条不算在内)。 */
    public record Picked(UUID who, String name, String text) {}

    /** 这个气泡是谁说的,给人读的名字:本机的玩家、她的另一位主人、或那只同伴。 */
    private static String saidBy(Bubble b) {
        return b.own() ? net.minecraft.client.Minecraft.getInstance().getUser().getName()
                : b.by() != null ? b.by() : speaker(b.who());
    }

    /** 主人在这个会话里上一句说的话(引的那句不算);没说过是 null。 */
    public String lastOwnText() {
        List<Transcript.Entry> source = transcript();
        for (int i = source.size() - 1; i >= 0; i--) {
            if (source.get(i).msg() instanceof ConvoState.Msg.User u) {
                String c = u.content();
                if (ConvoLog.PERSONA_DIVIDER.equals(c) || ConvoLog.COMPACT_DIVIDER.equals(c)
                        || ConvoLog.CLEAR_DIVIDER.equals(c)) {
                    continue;
                }
                String shown = Quote.parse(ownerText(c)).body();
                if (!shown.isBlank()) return shown;
            }
        }
        return null;
    }

    /** 指针下那个链接的网址;不在链接上是 null。只认对话流可见区里的气泡正文。 */
    private String linkAt(double mx, double my) {
        if (mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return null;
        for (Hit h : hits) {
            Bubble b = h.b();
            int textTop = h.y() + padV(b) + 1 + (b.quote() != null ? QUOTE_H : 0);
            if (mx < h.x() + padH(b) || mx >= h.x() + h.w() || my < textTop) continue;
            String url = b.text().linkAt((int) ((my - textTop) / LINE_H), (int) (mx - h.x() - padH(b)));
            if (url != null) return url;
        }
        return null;
    }

    /** 指针下那个气泡;不在气泡上是 null。只认对话流可见区里的。 */
    public Picked bubbleAt(double mx, double my) {
        if (mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return null;
        for (Hit h : hits) {
            if (h.b().raw() != null && mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                return new Picked(h.b().own() || h.b().by() != null ? null : h.b().who(), saidBy(h.b()), h.b().raw());
            }
        }
        return null;
    }

    /**
     * 她连着的思考与工具调用:收起是一行安静的字({@code head}),点开是一根线串起的步骤({@code rows})。
     * 只有一次调用、没有思考({@code single})时没有那一行,画出来就是那一步。{@code key} 是这段过程的开合键
     * (single 时没有,开合在那一步自己的键上);{@code open} 是此刻展开了几成(0..1);{@code bodyH} 是此刻露出的步骤区高度。
     * {@code who} = 干这些活的那只;{@code label} non-null = 她这一轮连发的第一块,名字画在它上面——多人会话里
     * 也得认得出是谁的;{@code runEnd} = 连发的最后一块,群里脸贴它旁边;{@code width} = 步骤线与每一步占的宽。
     */
    private record Process(Head head, String key, boolean single, float open, List<StepRow> rows, int bodyH,
                           int width, String label, UUID who, int entry, boolean runEnd) implements Block {
        Process withRunEnd(boolean on) {
            return new Process(head, key, single, open, rows, bodyH, width, label, who, entry, on);
        }

        int height() {
            return single ? bodyH : HEAD_H + bodyH;
        }
    }

    /**
     * 收起那一行:{@code lab} 是左边的字(思考中、这次调用读作什么、已处理 N 秒),{@code secs} 是它后面走动的秒数
     * (定格后已并进 {@code lab},为空),{@code sum} 是在想时这段思考的第一句。{@code shimmer} = 在想,字上有高光扫过;
     * {@code fail} = 这一行是失败色。
     */
    private record Head(String lab, boolean shimmer, String secs, String sum, boolean fail) {}

    /**
     * 步骤线上的一步,{@code y} 离步骤区顶多远。思考:{@code lines} 是保留换行折好的淡字。调用:{@code lines} 是那一句
     * (一行),{@code callKey} 是它的开合键,点开是折好的代码与回执。{@code secs} 在这一步右边。{@code h} 含展开的那一截
     * 此刻露出的高度。
     */
    private record StepRow(ProcessLine.Step step, int y, int h, List<FormattedCharSequence> lines, String secs,
                           String callKey, List<FormattedCharSequence> code, List<FormattedCharSequence> receipt) {}

    /** 日期分隔:一天的第一条上面一枚居中的服务消息(今天 / 昨天 / 几月几日)。 */
    private record Divider(String text) implements Block {}

    /** "未读消息"那一条服务消息:打开时还没看过的第一句上面。 */

    /**
     * 她的征询(Telegram 带内联按钮的消息):她那一侧的气泡里是清单,下面挂按钮,答完再挂一条结果。它不是对话记录
     * 里的一条,按到的时刻排进时间线,算在她的连发里;清单、按钮与结果条的画法和点法归 {@link ConsentMessage}。
     * {@code w} 是气泡与键盘共用的宽(Telegram 的键盘与气泡同宽,气泡被键盘撑宽);{@code label}、{@code runEnd}
     * 与话的气泡同义;{@code timeInline} = 挂着时右下角的倒计时放得进正文最后一行右侧,放不进单独占一小行。
     */
    private record Consent(ConsentCards.Card card, String label, int w, boolean runEnd, boolean timeInline)
            implements Block {
        Consent withRunEnd(boolean on) { return new Consent(card, label, w, on, timeInline); }
    }

    /** 居中的提示(整理过记忆、换了人设、清空、中断之类)画成服务消息;长的按气泡宽折行,{@code lines} 是折好的。 */
    private record Notice(List<FormattedCharSequence> lines) implements Block {}

    private int bubbleMaxW(int w) {
        return w - EDGE - faceCol() - OPP_MARGIN - SB_W - 3;
    }

    /** 她的正文占的宽:从脸那一列右边铺到滚动条前,不留 {@code OPP_MARGIN}。 */
    private int plainWidth(int w) {
        return w - EDGE - faceCol() - SB_W - 3;
    }

    /** 这一块话离字的内缩:主人的气泡是 {@code PAD_H}/{@code PAD_V},她的正文只留对齐名字的缩进与一点上下空。 */
    private static int padH(Bubble b) {
        return b.own() ? PAD_H : LABEL_IN;
    }

    private static int padV(Bubble b) {
        return b.own() ? PAD_V : PLAIN_PAD_V;
    }

    private int heightOf(Block b) {
        return switch (b) {
            case Bubble bb -> (bb.label() != null ? LABEL_H : 0) + (bb.quote() != null ? QUOTE_H : 0)
                    + bb.rows() * LINE_H + padV(bb) * 2;
            case Checklist c -> (c.label() != null ? LABEL_H : 0) + checklistH(c);
            case Process p -> (p.label() != null ? LABEL_H : 0) + p.height();
            case Notice n -> n.lines().size() * LINE_H + SERVICE_PAD_V * 2;
            case Divider ignored -> LINE_H + SERVICE_PAD_V * 2;
            case Consent c -> (c.label() != null ? LABEL_H : 0) + consentBubbleH(c) + ConsentMessage.GAP
                    + ConsentMessage.keyboardHeight(font, c.card(), c.w()) + consentResultH(c.card());
        };
    }

    /** 清单气泡本身多高(不含上面的名字):抬头一行、每项折出的行。 */
    private static int checklistH(Checklist c) {
        int lines = 1;
        for (CheckRow r : c.rows()) lines += r.lines().size();
        return lines * LINE_H + PAD_V * 2;
    }

    /** 征询那条的气泡本身多高(不含上面的名字):清单;挂着时放不进最后一行右侧的倒计时另占一小行。 */
    private static int consentBubbleH(Consent c) {
        return PAD_V * 2 + ConsentMessage.listHeight(c.card())
                + (c.card().waiting() && !c.timeInline() ? COUNTDOWN_H : 0);
    }

    /** 征询收起后键盘下面那条服务消息(连同上面的缝)此刻多高:收起的那一刻从零长出来。 */
    private static int consentResultH(ConsentCards.Card card) {
        return Math.round((ConsentMessage.GAP + LINE_H + SERVICE_PAD_V * 2) * ConsentMessage.reveal(card));
    }


    /** 她的一条征询接进她的连发:连发的第一块才带名字。 */
    private void consent(Feed f, ConsentCards.Card card, int bubbleMaxW) {
        UUID who = card.companion();
        f.out.add(consent(card, who.equals(f.last) ? null : label(who), bubbleMaxW));
        f.last = who;
    }

    private Consent consent(ConsentCards.Card card, String label, int bubbleMaxW) {
        // 挂着时右下角的倒计时挤在正文最后一行右侧(Telegram):放得下就同一行,放不下自己占一小行;收起后没有它
        int tw = card.waiting() ? font.width(ConsentMessage.countdown(card)) + 6 : 0;
        int last = ConsentMessage.lastRowWidth(font, card);
        int text = Math.max(ConsentMessage.listWidth(font, card), last + tw);
        int w = Math.min(bubbleMaxW, Math.max(text + PAD_H * 2, ConsentMessage.keyboardWidth(font, card)));
        return new Consent(card, label, w, false, last + tw <= w - PAD_H * 2);
    }

    private int totalHeight(List<Block> blocks) {
        int sum = TOP_PAD + BOT_PAD;
        for (int i = 0; i < blocks.size(); i++) {
            sum += heightOf(blocks.get(i)) + (i > 0 ? gapAfter(blocks, i - 1) : 0);
        }
        return sum;
    }

    /**
     * 从第 {@code from} 块往回找被引的那句(说话的人对得上、原文以引的那截开头),找到就把它滚到对话流上三分之一处、
     * 闪一下。引的那截是压成一行、截短过的,所以比的是开头。找不到(那句已经在视图之外)就不动。
     */
    private void jumpToQuoted(int from, Quote q) {
        List<Block> blocks = build(gw);
        String head = q.snippet().endsWith("…") ? q.snippet().substring(0, q.snippet().length() - 1) : q.snippet();
        for (int j = Math.min(from, blocks.size()) - 1; j >= 0; j--) {
            if (!(blocks.get(j) instanceof Bubble bb) || bb.raw() == null) continue;
            String flat = bb.raw().replace('\r', ' ').replace('\n', ' ').strip();
            if (!q.who().equals(saidBy(bb)) || !flat.startsWith(head)) continue;
            int y = TOP_PAD;
            for (int k = 0; k < j; k++) y += heightOf(blocks.get(k)) + gapAfter(blocks, k);
            scrollTarget = Math.clamp(y - gh / 3, 0, lastMaxScroll);
            pinBottom = scrollTarget >= lastMaxScroll;
            lastScrollMs = System.currentTimeMillis();
            flashIndex = j;
            flashAt = System.currentTimeMillis();
            return;
        }
    }

    /** 第 {@code i} 块下面留多宽:同一个人接着说(Telegram 连发几乎贴着)是窄缝,否则拉开。 */
    private static int gapAfter(List<Block> blocks, int i) {
        if (i + 1 >= blocks.size()) return BLOCK_GAP;
        UUID a = speakerOf(blocks.get(i));
        return a != null && a.equals(speakerOf(blocks.get(i + 1))) ? RUN_GAP : BLOCK_GAP;
    }

    /**
     * 摊平成可画的块。<b>来源恒定:物理对话史</b>({@code display()})——面板是消息记录的
     * 视图,画的必须是真的发生过的那些消息。压缩重写的是模型上下文,不该连主人看得见的
     * 历史一起吃掉。
     *
     * <p>模式只改<b>每条怎么渲染</b>(见 {@link com.dwinovo.numen.api.client.chat.ChatDisplayMode}):
     * 常态只取 {@code <query>} 里主人的话,debug 连 {@code <query>} 外的一起画。不换来源
     * ——请求里临时挂载、从未入过记录的东西(如 {@code <current_task>})混进来的话,
     * 画出来的会是一条<b>从未存在过</b>的消息。
     */
    /** 一次 build 的手头:输出、她攒着的这一段过程与它的主人、连发的上一位。 */
    private static final class Feed {
        final List<Block> out = new ArrayList<>();
        /** 她连着的思考和工具调用,中间没开口说话:收口时合成一块过程。 */
        final List<ProcessLine.Step> process = new ArrayList<>();
        UUID processWho;
        int processEntry = -1;
        UUID last;
        /** 每只她最近那条清单消息在 {@code out} 里的位置:同一份计划改了状态就换掉那一块。 */
        final java.util.Map<UUID, Integer> plans = new java.util.HashMap<>();
    }

    private static void addStep(Feed f, UUID who, int entry, ProcessLine.Step p) {
        if (f.process.isEmpty()) {
            f.processWho = who;
            f.processEntry = entry;
        }
        f.process.add(p);
    }

    private List<Block> build(int w) {
        int bubbleMaxW = bubbleMaxW(w);
        Feed f = new Feed();
        List<Block> out = f.out;
        List<Transcript.Entry> source = transcript();
        Set<String> done = new HashSet<>();
        Set<String> failed = new HashSet<>();
        Map<String, String> results = new HashMap<>();
        Map<String, Long> resultAt = new HashMap<>();
        for (Transcript.Entry e : source) {
            if (e.msg() instanceof ConvoState.Msg.Tool t) {
                done.add(t.toolCallId());
                results.put(t.toolCallId(), t.content());
                resultAt.put(t.toolCallId(), e.ts());
                // 成败判据的单一真源(展示层不猜字符串)。
                if (com.dwinovo.numen.api.agent.llm.ToolOutcome.failed(t.content())) {
                    failed.add(t.toolCallId());
                }
            }
        }
        // 没有结果、派发器也不再攥着的调用永远等不到结果了(被打断、死了、游戏关掉时还在跑):
        // 按失败画,不能一直转圈。"还在跑"只问派发器,不从历史长什么样去猜。
        for (Transcript.Entry e : source) {
            if (e.msg() instanceof ConvoState.Msg.Assistant a) {
                for (LlmToolCall tc : a.turn().toolCalls()) {
                    if (!done.contains(tc.id()) && !outstanding(e.companion(), tc.id())) {
                        done.add(tc.id());
                        failed.add(tc.id());
                    }
                }
            }
        }
        int innerW = bubbleMaxW - PAD_H * 2;
        int plainW = plainWidth(w) - LABEL_IN * 2;
        // 新来的记录记下第一次被看见的时刻,画的时候按它飞入;打开面板时就在的历史不飞
        if (born.size() > source.size()) born.clear();   // 记录被清了(/clear):从头记
        boolean opening = born.isEmpty();
        while (born.size() < source.size()) born.add(opening ? 0L : frameNow);
        // 连发合并(聊天软件的惯例):同一个人接连的话和活只在第一块画头像和名字。多人会话里
        // "同一个人"按那只算,不按左右哪一侧——换了一只就得重新亮名字。工具行是她的活,
        // 算在她的连发里;提示行打断。f.last == null = 连发已断。
        int msgIndex = -1;
        LocalDate lastDay = null;
        // 前一条记录的时刻:一步思考从它算起,到她这一轮的时刻为止
        long lastTs = 0;
        // 她的征询按到的时刻排进时间线。只在和她的私聊里:征询问的是她的身体
        List<ConsentCards.Card> asks = ConsentCards.history(solo());
        int asked = 0;
        for (Transcript.Entry entry : source) {
            msgIndex++;
            ConvoState.Msg msg = entry.msg();
            long prevTs = lastTs;
            if (entry.ts() > 0) lastTs = entry.ts();
            while (asked < asks.size() && entry.ts() > 0 && asks.get(asked).arrivedAt() <= entry.ts()) {
                flushProcess(f, bubbleMaxW);
                consent(f, asks.get(asked++), bubbleMaxW);
            }
            // 日期分隔:换了一天,先收口、插一枚日期小牌、连发断开
            if (entry.ts() > 0) {
                LocalDate day = Instant.ofEpochMilli(entry.ts()).atZone(ZoneId.systemDefault()).toLocalDate();
                if (!day.equals(lastDay)) {
                    flushProcess(f, bubbleMaxW);
                    out.add(new Divider(dayLabel(day)));
                    f.last = null;
                    lastDay = day;
                }
            }
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    flushProcess(f, bubbleMaxW);
                    if (ConvoLog.PERSONA_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.persona_changed"), bubbleMaxW);
                        f.last = null;
                        continue;
                    }
                    if (ConvoLog.COMPACT_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.compacted"), bubbleMaxW);
                        f.last = null;
                        continue;
                    }
                    if (ConvoLog.CLEAR_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.cleared"), bubbleMaxW);
                        f.last = null;
                        f.plans.clear();   // 清空之后她不记得之前那份计划,再写就是新的一条
                        continue;
                    }
                    String shown = ownerText(u.content());   // owner's words only, never injected content
                    if (shown.isEmpty()) continue;
                    if (ConvoLog.scheduled(u.content())) {
                        notice(out, I18n.get(com.dwinovo.numen.api.data.ModLanguageData.Keys.CHAT_SCHEDULED), bubbleMaxW);   // 主人定的定时到点说的,不是他此刻开的口
                        f.last = null;
                    }
                    Quote q = Quote.parse(shown);   // 引用回复:第一行画成气泡顶上的引用条
                    Keeper other = otherOwner(entry.companion(), u.content());
                    if (other == null) {
                        out.add(bubble(true, null, rich(q.body(), true), TXT,
                                OWN_FILL, innerW, null,
                                msgIndex, q.body(), q.quoted() ? q : null, null));
                        f.last = OWNER;
                    } else {
                        // 她的另一位主人说的:和群里别人的话一样在左边,连发的第一块上标他的名字
                        out.add(bubble(false, other.uuid().equals(f.last) ? null : other.shown(), rich(q.body(), true), TXT,
                                0, plainW, other.uuid(),
                                msgIndex, q.body(), q.quoted() ? q : null, other.shown()));
                        f.last = other.uuid();
                    }
                }
                case ConvoState.Msg.Assistant a -> {
                    UUID who = entry.companion();
                    // 换了一只:前一只攒着的过程先收口,两只的活不折进同一行
                    if (!who.equals(f.processWho)) flushProcess(f, bubbleMaxW);
                    AssistantTurn turn = a.turn();
                    // 思考在说话之前;它和前后的工具调用同属"她在干活",并进同一段过程
                    String reasoned = turn.reasoning();
                    if (reasoned != null && !reasoned.isBlank()) {
                        addStep(f, who, msgIndex, ProcessLine.Step.thought(reasoned, prevTs, entry.ts()));
                    }
                    String spoken = ChatDisplayModes.current().assistantText(turn.content());
                    if (!spoken.isBlank()) {
                        flushProcess(f, bubbleMaxW);   // 开口说话把过程收口
                        boolean first = !who.equals(f.last);
                        out.add(bubble(false, first ? label(who) : null,
                                rich(spoken, false), TXT, 0, plainW, who,
                                msgIndex, spoken, null, null));
                        f.last = who;
                    }
                    for (LlmToolCall tc : turn.toolCalls()) {
                        addStep(f, who, msgIndex, ProcessLine.Step.call(tc, entry.ts(), resultAt.getOrDefault(tc.id(), 0L),
                                !done.contains(tc.id()), failed.contains(tc.id()), results.get(tc.id())));
                        // 这次调用写下的计划是她的一条清单消息,画在这次调用之后
                        List<com.dwinovo.numen.api.agent.tool.TodoTool.Item> plan = PlanChecklist.of(tc, results.get(tc.id()));
                        if (plan != null) {
                            checklist(f, who, msgIndex, plan, innerW, bubbleMaxW);
                        }
                        // 主人答了她的提问:对话流里留一行服务消息,说他答了什么
                        List<com.dwinovo.numen.api.agent.tool.Questions.Answer> answered = com.dwinovo.numen.api.agent.tool.Questions.answersIn(results.get(tc.id()));
                        if (!answered.isEmpty()) {
                            flushProcess(f, bubbleMaxW);
                            String said = answered.stream().map(x -> "「" + x.answer() + "」").collect(java.util.stream.Collectors.joining());
                            notice(out, I18n.get(com.dwinovo.numen.api.data.ModLanguageData.Keys.QUESTION_ANSWERED, said), bubbleMaxW);
                            f.last = null;
                        }
                    }
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a block */ }
                case ConvoState.Msg.Halt h -> {
                    flushProcess(f, bubbleMaxW);
                    // 有结构化原因就按当前语言说;老日志只有当时存下的那句
                    String why = h.code() == null ? h.reason()
                            : CauseWords.text(new com.dwinovo.numen.api.agent.loop.Cause(h.code(), h.args()));
                    notice(out, I18n.get("numen.chat.halted", why), bubbleMaxW);
                    f.last = null;
                }
            }
        }
        // 最后一段过程先不收口:她正在想的那段接在它后面,并成同一行
        // 在飞的状态按成员各自的循环取:单成员就是她一个,多人各画各的。
        // 只画她此刻所在的会话里的:她在群里想着,私聊页不该也看见——和落库的行同一条印的规矩。
        String tag = Conversations.instance().tagOf(conv.get());
        // 排着的话,连同是不是她的另一位主人说的(本机的玩家说的、整理与清空是 null)
        Map<String, Keeper> queued = new java.util.LinkedHashMap<>();
        boolean compacting = false;
        for (UUID her : Conversations.instance().membersAlive(conv.get())) {
            CompanionView lp = ClientCompanions.get(her).orElse(null);
            if (lp == null || !java.util.Objects.equals(lp.conversation(), tag)) continue;
            if (!her.equals(f.processWho)) flushProcess(f, bubbleMaxW);
            // 在飞的思考流接在她这段过程末尾;回合落库后由落库的那段接管,永不双份。
            String liveReasoning = lp.liveReasoning();
            if (!liveReasoning.isBlank()) addStep(f, her, -1, ProcessLine.Step.liveThought(liveReasoning, lastTs));
            // The in-flight reply, typed out live (chunk stream → CompanionView.livePartial).
            Live l = live.get(her);
            if (l != null && (!l.shown.isEmpty() || l.caret)) {
                flushProcess(f, bubbleMaxW);
                boolean first = !her.equals(f.last);
                // 光标是接在解析完的正文后面的一小段字:没写完的 Markdown 记号照字面显示,写完才变样式
                Doc typing = l.caret ? rich(l.shown, false).withTail("_") : rich(l.shown, false);
                out.add(bubble(false, first ? label(her) : null, typing, TXT,
                        0, plainW, her, -1, l.shown, null, null));
                f.last = her;
            }
            // 排着的话:主人一句话复制进每个醒着的成员的队列,按原文去重,画一次
            var status = lp.status();
            for (var preview : status.queuedPreview()) {
                // 整理、清空排着时,条目的字是语言键(整理后面可跟主人附的关注点):键按主人的语言翻;主人的话取 <query> 里的原话
                boolean control = com.dwinovo.numen.api.agent.inbox.EventTypes.COMPACT.equals(preview.type())
                        || com.dwinovo.numen.api.agent.inbox.EventTypes.CLEAR.equals(preview.type());
                String shown = control ? controlShown(preview.text()) : ownerText(preview.text());
                if (!shown.isEmpty()) queued.putIfAbsent(shown, control ? null : otherOwner(her, preview.text()));
            }
            compacting |= status.phase() == com.dwinovo.numen.api.agent.loop.Phase.COMPACT;
        }
        flushProcess(f, bubbleMaxW);
        // 挂着的那条(和记录里最后一条之后才到的)排在她这段过程后面
        while (asked < asks.size()) {
            consent(f, asks.get(asked++), bubbleMaxW);
        }
        // Prompts still waiting for a protocol-valid splice point — visible immediately
        // so a queued message never feels swallowed.
        for (Map.Entry<String, Keeper> waiting : queued.entrySet()) {
            String body = Quote.parse(waiting.getKey()).body();
            Keeper other = waiting.getValue();
            if (other == null) {
                out.add(bubble(true, null, Doc.plain("⌛ " + body), FAINT, QUEUED_FILL,
                        innerW, null, -1, body, null, null));
                f.last = OWNER;
            } else {
                out.add(bubble(false, other.uuid().equals(f.last) ? null : other.shown(), Doc.plain("⌛ " + body), FAINT,
                        0, plainW, other.uuid(), -1, body, null, other.shown()));
                f.last = other.uuid();
            }
        }
        if (compacting) notice(out, I18n.get("numen.chat.compacting"), bubbleMaxW);
        settleRuns(out);
        settleTicks(out, opening);
        return out;
    }

    /** 清单里这一项的勾记在哪个键下:她、哪一条清单(第一次出现的记录序号)、第几项、内容。 */
    private static String tickKey(Checklist c, int i) {
        return c.who() + "#" + c.entry() + "#" + i + "#" + c.items().get(i).content();
    }

    /** 按这一遍的最终清单记下每项什么时候打上的勾;{@code opening} 时已经勾上的算历史,不播。 */
    private void settleTicks(List<Block> out, boolean opening) {
        for (Block b : out) {
            if (!(b instanceof Checklist c)) continue;
            for (int i = 0; i < c.items().size(); i++) {
                String key = tickKey(c, i);
                if (c.items().get(i).status() == com.dwinovo.numen.api.agent.tool.TodoTool.Status.COMPLETED) {
                    ticked.computeIfAbsent(key, k -> opening ? 0L : frameNow);
                } else {
                    ticked.remove(key);
                }
            }
        }
    }

    /**
     * Telegram 的排法:群里别人的名字在这一组的第一块上面,脸贴在这一组的<b>最后一块</b>旁边(底部对齐),
     * 最后一块气泡还带一条小尾巴。建块时只知道"是不是第一块",所以"最后一块"在这儿补:同一个人连着的块算一组,
     * 提示行、日期牌把组断开。
     */
    private void settleRuns(List<Block> out) {
        for (int i = 0; i < out.size(); i++) {
            UUID who = speakerOf(out.get(i));
            if (who == null) continue;
            boolean end = i + 1 >= out.size() || !who.equals(speakerOf(out.get(i + 1)));
            Block b = out.get(i);
            if (b instanceof Bubble bb && bb.runEnd() != end) out.set(i, bb.withRunEnd(end));
            else if (b instanceof Checklist c && c.runEnd() != end) out.set(i, c.withRunEnd(end));
            else if (b instanceof Process p && p.runEnd() != end) out.set(i, p.withRunEnd(end));
            else if (b instanceof Consent c && c.runEnd() != end) out.set(i, c.withRunEnd(end));
        }
    }

    /** 这一块是谁的:主人用 {@link #OWNER} 代表;提示行、日期牌不是谁的。 */
    private static UUID speakerOf(Block b) {
        return switch (b) {
            case Bubble bb -> bb.own() ? OWNER : bb.who();
            case Checklist c -> c.who();
            case Process p -> p.who();
            case Consent c -> c.card().companion();
            default -> null;
        };
    }

    /**
     * 这句主人的话若是她的另一位主人说的,是那一位;本机的玩家说的是 {@code null}(见 {@link NumenRoster#otherSpeaker})。
     *
     * @param companion 这句话出自哪只的日志:说话人在她的主人表里认
     */
    private static Keeper otherOwner(UUID companion, String content) {
        return NumenRoster.instance().otherSpeaker(companion, content, ClientCompanions.viewer());
    }

    private Bubble bubble(boolean own, String label, Doc body, int ink, int fill,
                          int innerW, UUID who, int entry,
                          String raw, Quote quote, String by) {
        RichLayout.Laid text = lay(body, innerW);
        int maxW = text.width();
        if (quote != null) {
            maxW = Math.max(maxW, Math.min(innerW,
                    QUOTE_IN + Math.max(font.width(quote.who()), font.width(quote.snippet()))));
        }
        return new Bubble(own, label, text, ink, maxW, fill, false, who, entry, raw, quote, by);
    }

    /**
     * 她写下一份计划:和她最近那条清单是同一份(内容一样、只是状态变了)就在那一条上原地更新,
     * 不往下挪;内容变了才是她新说的一条——先把攒着的过程收口,再接在后面。
     */
    private void checklist(Feed f, UUID who, int entry, List<com.dwinovo.numen.api.agent.tool.TodoTool.Item> items, int innerW,
                           int bubbleMaxW) {
        Integer at = f.plans.get(who);
        if (at != null) {
            Checklist prev = (Checklist) f.out.get(at);
            if (PlanChecklist.sameItems(prev.items(), items)) {
                f.out.set(at, checklist(who, prev.label(), items, innerW, prev.entry()));
                return;
            }
        }
        flushProcess(f, bubbleMaxW);
        boolean first = !who.equals(f.last);
        f.out.add(checklist(who, first ? label(who) : null, items, innerW, entry));
        f.plans.put(who, f.out.size() - 1);
        f.last = who;
    }

    /** 折好一份清单:做完的和划掉的字退成气泡里的淡字、加删除线,还要做的照常。 */
    private Checklist checklist(UUID who, String label, List<com.dwinovo.numen.api.agent.tool.TodoTool.Item> items, int innerW,
                                int entry) {
        String header = I18n.get("numen.chat.plan", PlanChecklist.done(items), items.size());
        int maxW = font.width(header);
        List<CheckRow> rows = new ArrayList<>(items.size());
        int textW = innerW - BOX_COL;
        for (com.dwinovo.numen.api.agent.tool.TodoTool.Item it : items) {
            boolean off = it.status() == com.dwinovo.numen.api.agent.tool.TodoTool.Status.COMPLETED || it.status() == com.dwinovo.numen.api.agent.tool.TodoTool.Status.CANCELLED;
            Component text = off
                    ? Nb.colored(it.content(), IN_META).copy().withStyle(net.minecraft.ChatFormatting.STRIKETHROUGH)
                    : Nb.colored(it.content(), TXT);
            List<FormattedCharSequence> lines = split(text, textW);
            for (FormattedCharSequence l : lines) maxW = Math.max(maxW, BOX_COL + font.width(l));
            rows.add(new CheckRow(it.status(), lines));
        }
        return new Checklist(who, label, items, header, List.copyOf(rows), maxW, entry, false);
    }

    /** 日期小牌上的字:今天、昨天,再往前是几月几日,跨年带年。 */
    private static String dayLabel(LocalDate day) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (day.equals(today)) return I18n.get(com.dwinovo.numen.api.data.ModLanguageData.Keys.CHAT_TODAY);
        if (day.equals(today.minusDays(1))) return I18n.get(com.dwinovo.numen.api.data.ModLanguageData.Keys.CHAT_YESTERDAY);
        if (day.getYear() == today.getYear()) {
            return I18n.get(com.dwinovo.numen.api.data.ModLanguageData.Keys.CHAT_DATE_MD, day.getMonthValue(), day.getDayOfMonth());
        }
        return I18n.get(com.dwinovo.numen.api.data.ModLanguageData.Keys.CHAT_DATE_YMD, day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }

    /**
     * 气泡正文:Markdown({@link Markdown},粗体、斜体、删除线、代码、链接、列表……)解析成富文本,
     * 链接画成强调色加下划线,点了由 {@link #mouseClicked} 交给原版确认打开。{@code mentions} 为真时(主人的话)
     * {@code @} 到的名字画亮——用的是路由那一份匹配({@link Mentions#spans}),量的是画出来的那份正文,
     * 所以亮的正好是会醒的,不是"长得像名字";名字按此刻名册,改过名之后旧记录里的不亮。
     */
    private Doc rich(String text, boolean mentions) {
        Doc doc = parsed.computeIfAbsent(text, Markdown::parse);
        if (!mentions) return doc;
        return doc.accented(Mentions.spans(doc.plainText(), Conversations.instance().named(conv.get())).stream()
                .map(m -> new Doc.Range(m.start(), m.end())).toList());
    }

    /** 一段字在某种样式下多宽(排版用):粗体更宽,斜体、删除线不变。 */
    private int richWidth(String text, int style) {
        return font.width(richStyled(text, style, TXT).getVisualOrderText());
    }

    private static MutableComponent richStyled(String text, int style, int color) {
        return Component.literal(text).withStyle(st -> st.withColor(color & 0xFFFFFF)
                .withBold((style & Doc.BOLD) != 0).withItalic((style & Doc.ITALIC) != 0)
                .withStrikethrough((style & Doc.STRIKE) != 0));
    }

    /** 把排好的正文画在 {@code (x, y)}:链接与 @ 的名字用强调色(链接再加下划线),代码铺一层字色的淡底。 */
    private void drawRich(GuiGraphics g, RichLayout.Laid text, int x, int y, int ink) {
        text.paint(new RichLayout.Canvas() {
            @Override
            public void backdrop(int bx, int by, int w, int h) {
                g.fill(bx, by, bx + w, by + h, (ink & 0xFFFFFF) | 0x26000000);
            }

            @Override
            public void text(RichLayout.Frag f, int fx, int fy) {
                boolean accent = f.link() != null || (f.style() & Doc.ACCENT) != 0;
                MutableComponent c = richStyled(f.text(), f.style(), accent ? MENTION : ink);
                if (f.link() != null) c = c.withStyle(st -> st.withUnderlined(true));
                draw(g, c.getVisualOrderText(), fx, fy);
            }
        }, x, y, LINE_H);
    }

    /** Advance the typewriter: filter the live partial, ease the reveal toward the
     *  full length, and cache "revealed text + blinking caret". */
    private void updateLive(float dt, long now) {
        List<UUID> members = Conversations.instance().membersAlive(conv.get());
        live.keySet().retainAll(members);
        for (UUID her : members) {
            CompanionView lp = ClientCompanions.get(her).orElse(null);
            String full = lp == null ? "" : ChatDisplayModes.current().assistantText(lp.livePartial());
            if (full.isEmpty()) {
                live.remove(her);
                continue;
            }
            Live l = live.computeIfAbsent(her, k -> new Live());
            if (l.revealed > full.length()) l.revealed = full.length();
            if (full.length() - l.revealed > REVEAL_MAX_LAG) l.revealed = full.length() - REVEAL_MAX_LAG;
            l.revealed = Math.min(full.length(), l.revealed + dt * REVEAL_CPS);
            l.shown = cut(full, (int) l.revealed);
            l.caret = ((now / 500) & 1) == 0;
        }
    }

    /** Cut at {@code n} chars without splitting a surrogate pair. */
    private static String cut(String s, int n) {
        if (n >= s.length()) return s;
        if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }

    private void notice(List<Block> out, String text, int maxW) {
        out.add(new Notice(split(Nb.colored(text, SERVICE_FG), maxW - SERVICE_PAD_H * 2)));
    }

    /**
     * 收口她这一段过程(连着的思考和工具调用,中间没开口):合成一块。过程是旁白不是话,一轮干活在对话里只占安静的一行,
     * 点开才是每一步;只有一次调用、没有思考时不包这一层,那一块就是调用本身。
     */
    private void flushProcess(Feed f, int maxW) {
        if (f.process.isEmpty()) return;
        boolean first = !f.processWho.equals(f.last);
        f.out.add(process(List.copyOf(f.process), f.processWho, f.processEntry, first ? label(f.processWho) : null, maxW));
        f.last = f.processWho;
        f.process.clear();
        f.processWho = null;
        f.processEntry = -1;
    }

    /** 一次调用点开的开合键。 */
    private static String callKey(LlmToolCall call) {
        return "call#" + call.id();
    }

    /** 这个开合此刻展开了几成(0..1)。 */
    private float fold(String key) {
        return folds.getOrDefault(key, 0f) / FOLD_FULL;
    }

    /** 开合各按主人点开的状态朝展开或收起趋近一步;收完的从表里去掉。 */
    private void advanceFolds(float dt) {
        for (String key : expandedGroups) folds.putIfAbsent(key, 0f);
        folds.replaceAll((key, v) -> Anim.approach(v, expandedGroups.contains(key) ? FOLD_FULL : 0f, FOLD_RATE, dt));
        folds.entrySet().removeIf(e -> e.getValue() <= 0f && !expandedGroups.contains(e.getKey()));
    }

    private Process process(List<ProcessLine.Step> steps, UUID who, int entry, String label, int maxW) {
        long now = System.currentTimeMillis();
        boolean single = ProcessLine.single(steps);
        String key = single ? callKey(steps.get(0).call()) : "proc#" + who + "#" + entry;
        float open = single ? 1f : fold(key);
        List<StepRow> rows = open > 0f ? stepRows(steps, single, maxW, now) : List.of();
        int rowsH = 0;
        for (StepRow r : rows) rowsH += r.h() + (r == rows.get(rows.size() - 1) ? 0 : STEP_GAP);
        int bodyH = single ? rowsH : Math.round((rowsH + BODY_PAD * 2) * open);
        return new Process(single ? null : head(steps, now), key, single, open, rows, bodyH, maxW, label, who, entry, false);
    }

    /**
     * 收起那一行写什么。在想:"思考中"、已用的秒数、这段思考的第一句;在跑一次调用:这次调用读作的那一句(工具名加它自己说的
     * 那句话)、已用的秒数;收了:有调用是"已处理 N 秒",只有思考是"已思考 N 秒",最后一步失败是失败色。秒数是整段从第一步
     * 开始算起的。
     */
    private Head head(List<ProcessLine.Step> steps, long now) {
        ProcessLine.Step current = ProcessLine.current(steps);
        long total = ProcessLine.seconds(ProcessLine.totalMillis(steps, now));
        if (current == null) {
            String key = ProcessLine.hasCalls(steps) ? ModLanguageData.Keys.CHAT_PROCESS_WORKED
                    : ModLanguageData.Keys.CHAT_PROCESS_THOUGHT;
            return new Head(I18n.get(key, total), false, "", "", ProcessLine.lastFailed(steps));
        }
        String secs = steps.get(0).start() > 0 ? I18n.get(ModLanguageData.Keys.CHAT_PROCESS_SECS, total) : "";
        return current.isCall()
                ? new Head(toolLine(current.call()), true, secs, "", false)
                : new Head(I18n.get("numen.chat.reasoning_now"), true, secs, ProcessLine.firstLine(current.thought()), false);
    }

    /** 一步右边写的秒数;记录里没有时刻的不写。 */
    private static String secsOf(ProcessLine.Step s, long now) {
        return s.start() > 0 ? I18n.get(ModLanguageData.Keys.CHAT_PROCESS_SECS, ProcessLine.seconds(s.millis(now))) : "";
    }

    /** 一步的字离块左缘多远:步骤线旁的步骤让出线的位置,单独一步贴着左缘。 */
    private static int stepX(boolean single) {
        return single ? 4 : STEP_X;
    }

    /** 一步占的宽:块宽去掉左边让出的和右边留的边。 */
    private static int stepW(int width, boolean single) {
        return width - stepX(single) - 4;
    }

    /**
     * 排出步骤线上的每一步:思考是保留换行折好的淡字,调用是状态加那一句;点开的调用再带上折好的代码与回执,
     * 高度按它展开了几成算。{@code y} 是从步骤区顶往下数的。
     */
    private List<StepRow> stepRows(List<ProcessLine.Step> steps, boolean single, int width, long now) {
        int w = stepW(width, single);
        int secsW = 0;
        for (ProcessLine.Step s : steps) secsW = Math.max(secsW, font.width(secsOf(s, now)));
        secsW += secsW > 0 ? 6 : 0;
        List<StepRow> rows = new ArrayList<>(steps.size());
        int y = 0;
        for (ProcessLine.Step s : steps) {
            String secs = secsOf(s, now);
            StepRow row;
            if (!s.isCall()) {
                List<FormattedCharSequence> lines = wrap(s.thought().stripTrailing(), MUTED, w - secsW);
                row = new StepRow(s, y, lines.size() * LINE_H, lines, secs, null, List.of(), List.of());
            } else {
                String key = callKey(s.call());
                float detail = fold(key);
                List<FormattedCharSequence> code = List.of();
                List<FormattedCharSequence> receipt = List.of();
                int h = HEAD_H;
                if (detail > 0f) {
                    int inner = w - DETAIL_IN - BOX_IN * 2;
                    code = wrap(ProcessLine.code(s.call()), TXT, inner);
                    receipt = s.receipt() == null ? List.of() : wrap(s.receipt(), s.failed() ? FAIL : TXT, inner);
                    h += Math.round(detailH(code, receipt) * detail);
                }
                List<FormattedCharSequence> line = List.of(Nb.colored(
                        Nb.clip(font, toolLine(s.call()), w - ICON_W - secsW), s.failed() ? FAIL : TXT).getVisualOrderText());
                row = new StepRow(s, y, h, line, secs, key, code, receipt);
            }
            rows.add(row);
            y += row.h() + STEP_GAP;
        }
        return rows;
    }

    /** 一段文字按宽折行,保留它自己的换行(空行也占一行);颜色烙在每一行上。 */
    private List<FormattedCharSequence> wrap(String text, int color, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            List<FormattedCharSequence> cut = split(Nb.colored(line, color), Math.max(1, width));
            if (cut.isEmpty()) out.add(FormattedCharSequence.EMPTY);
            else out.addAll(cut);
        }
        return out;
    }

    /** 点开的调用完全展开有多高:"代码"小标题与它的块,有回执再加"回执"小标题与它的块。 */
    private static int detailH(List<FormattedCharSequence> code, List<FormattedCharSequence> receipt) {
        int h = 2 + LINE_H + code.size() * LINE_H + 4;
        if (!receipt.isEmpty()) h += 2 + LINE_H + receipt.size() * LINE_H + 4;
        return h + 2;
    }

    /**
     * 点在这一块的 {@code my} 高度上,翻动哪个开合:收起那一行翻整段过程;步骤线上的一次调用翻它的代码与回执
     * (只在步骤区露出的时候);都不是返回 null。
     */
    private String processClickKey(Process p, int cy, double my) {
        int top = cy + (p.label() != null ? LABEL_H : 0);
        if (my < top) return null;
        if (!p.single()) {
            if (my < top + HEAD_H) return p.key();
            if (p.open() < 0.5f) return null;
            top += HEAD_H + BODY_PAD;
        }
        for (StepRow r : p.rows()) {
            if (r.callKey() != null && my >= top + r.y() && my < top + r.y() + HEAD_H) return r.callKey();
        }
        return null;
    }

    // ---- drawing ----

    private void drawBlock(GuiGraphics g, Block b, int x, int y, int w) {
        // 新来的块飞入:从下面 8px 升上来、同时淡入。整块一起动——框、脸、字用同一个透明度
        int entry = switch (b) {
            case Bubble bb -> bb.entry();
            case Checklist c -> c.entry();
            case Process p -> p.entry();
            default -> -1;
        };
        // 征询不是记录里的一条:按它到的时刻飞入
        long bornAt = b instanceof Consent c ? c.card().arrivedAt()
                : entry >= 0 && entry < born.size() ? born.get(entry) : 0L;
        float e = 1f;
        if (bornAt > 0) {
            long age = frameNow - bornAt;
            if (age < ENTER_MS) e = Anim.easeOutCubic(age / (float) ENTER_MS);
        }
        if (e < 1f) {
            g.pose().pushPose();
            g.pose().translate(0, Math.round(ENTER_DY * (1f - e)), 0);
            g.setColor(1f, 1f, 1f, Math.max(0.05f, e));
        }
        drawBlockBody(g, b, x, y, w);
        if (e < 1f) {
            g.setColor(1f, 1f, 1f, 1f);
            g.pose().popPose();
        }
    }

    private void drawBlockBody(GuiGraphics g, Block b, int x, int y, int w) {
        switch (b) {
            case Divider d -> drawDayChip(g, d.text(), x, y, w);
            case Notice n -> drawService(g, n.lines(), x, y, w);
            case Bubble bb -> drawBubble(g, bb, x, y, w);
            case Checklist c -> drawChecklist(g, c, x, y);
            case Process p -> drawProcess(g, p, x, y);
            case Consent c -> drawConsent(g, c, x, y, w);
        }
    }

    /**
     * 征询那条:和话、清单同一份气泡外形(名字、脸、底色、尾巴),里面是问话与右下角的秒数,挂着时底边一道缩短的线;
     * 气泡下面贴着内联按钮(Telegram 的键盘挂在气泡外面、与气泡同宽),收起后再下面一条服务消息写结果。
     */
    private void drawConsent(GuiGraphics g, Consent c, int x, int y, int w) {
        ConsentCards.Card card = c.card();
        int bx = x + EDGE + faceCol();
        int bh = consentBubbleH(c);
        int bubTop = y + (c.label() != null ? LABEL_H : 0);
        bubbleFrame(g, false, c.label(), card.companion(), false, c.runEnd(), x, y, bx, c.w(), bh, AI_FILL);
        ConsentMessage.drawList(g, font, card, bx + PAD_H, bubTop + PAD_V, c.w() - PAD_H * 2);
        if (card.waiting()) {
            String countdown = ConsentMessage.countdown(card);
            int countdownY = c.timeInline() ? ConsentMessage.lastRowTextY(font, card, bubTop + PAD_V)
                    : bubTop + PAD_V + ConsentMessage.listHeight(card);
            draw(g, Nb.colored(countdown, IN_META).getVisualOrderText(),
                    bx + c.w() - PAD_H - font.width(countdown), countdownY);
            g.fill(bx, bubTop + bh - 1, bx + Math.round(c.w() * ConsentMessage.timeLeft(card)), bubTop + bh,
                    ConsentMessage.tone());
        }
        int ky = consentKeysTop(c, y);
        ConsentMessage.drawKeyboard(g, font, card, bx, ky, c.w(), hoverX, hoverY);
        float shown = ConsentMessage.reveal(card);
        if (shown > 0f) {
            // 收起后键盘下面一条服务消息写结果,随收起淡入;透明度乘在飞入的透明度上
            float base = com.mojang.blaze3d.systems.RenderSystem.getShaderColor()[3];
            String text = Nb.clip(font, ConsentMessage.result(card), w - SB_W - SERVICE_PAD_H * 2);
            g.setColor(1f, 1f, 1f, base * shown);
            drawService(g, List.of(Nb.colored(text, SERVICE_FG).getVisualOrderText()), x,
                    ky + ConsentMessage.keyboardHeight(font, c.card(), c.w()) + ConsentMessage.GAP, w);
            g.setColor(1f, 1f, 1f, base);
        }
    }

    /** 征询那条的按钮从哪一行起:名字、气泡下面隔一道缝。画和点都照它。 */
    private static int consentKeysTop(Consent c, int y) {
        return y + (c.label() != null ? LABEL_H : 0) + consentBubbleH(c) + ConsentMessage.GAP;
    }

    /**
     * 招呼页:聊天区正中竖排她的大头像(和左栏同一张脸)、名字、一句招呼、一排折行的建议。方角、现在的主题色;
     * 整页随 {@link #welcomeShown} 淡入并从下面 {@link #ENTER_DY}px 升上来(消失时反过来),建议悬停时底与字向强调色走一程。
     */
    private void drawWelcome(GuiGraphics g, int x, int y, int w, int h, float dt) {
        float e = Anim.easeOutCubic(welcomeShown / WELCOME_FULL);
        String name = conv.get().displayName(NumenRoster.instance()::name);
        String greeting = I18n.get("numen.chat.welcome.greeting");
        int[] widths = new int[SUGGESTIONS.length];
        String[] labels = new String[SUGGESTIONS.length];
        for (int i = 0; i < SUGGESTIONS.length; i++) {
            labels[i] = I18n.get(SUGGESTIONS[i]);
            widths[i] = font.width(labels[i]) + CHIP_PAD_H * 2;
        }
        List<List<Integer>> rows = com.dwinovo.numen.api.client.ui.ChipFlow.rows(
                widths, CHIP_GAP, Math.min(WELCOME_MAX_W, w - SB_W - 16));
        int rowsH = rows.size() * CHIP_H + (rows.size() - 1) * CHIP_ROW_GAP;
        int total = WELCOME_FACE + WELCOME_GAP + LINE_H + WELCOME_LINE_GAP + LINE_H + WELCOME_CHIPS_GAP + rowsH;
        int mid = x + (w - SB_W) / 2;
        int top = y + Math.max(4, (h - total) / 2);
        UiTheme t = UiTheme.current();
        g.enableScissor(x, y, x + w, y + h);
        g.pose().pushPose();
        g.pose().translate(0, Math.round(ENTER_DY * (1f - e)), 0);
        g.setColor(1f, 1f, 1f, Math.max(0.02f, e));
        com.dwinovo.numen.api.client.skin.ConversationFaces.draw(g, conv.get(), mid - WELCOME_FACE / 2, top, WELCOME_FACE);
        int ty = top + WELCOME_FACE + WELCOME_GAP;
        Component bold = Component.literal(name).withStyle(net.minecraft.ChatFormatting.BOLD);
        Nb.text(g, font, bold, mid - font.width(bold) / 2, ty, TXT);
        ty += LINE_H + WELCOME_LINE_GAP;
        Nb.text(g, font, greeting, mid - font.width(greeting) / 2, ty, MUTED);
        int cy = ty + LINE_H + WELCOME_CHIPS_GAP;
        for (List<Integer> row : rows) {
            int rowW = (row.size() - 1) * CHIP_GAP;
            for (int i : row) rowW += widths[i];
            int cx = mid - rowW / 2;
            for (int i : row) {
                boolean hot = welcomeOn && hoverX >= cx && hoverX < cx + widths[i] && hoverY >= cy && hoverY < cy + CHIP_H;
                chipHover[i] = Anim.approach(chipHover[i], hot ? WELCOME_FULL : 0f, CHIP_RATE, dt);
                float k = chipHover[i] / WELCOME_FULL;
                g.fill(cx, cy + CHIP_SHADOW, cx + widths[i], cy + CHIP_H + CHIP_SHADOW, 0x14000000);
                g.fill(cx, cy, cx + widths[i], cy + CHIP_H, NumenStyle.mixColor(t.band(), t.accent(), 0.14f * k));
                Nb.text(g, font, labels[i], cx + CHIP_PAD_H, cy + (CHIP_H - 8) / 2,
                        NumenStyle.mixColor(t.onBand(), t.accent(), k));
                chipRect[i] = new int[]{cx, cy, widths[i], CHIP_H};
                cx += widths[i] + CHIP_GAP;
            }
            cy += CHIP_H + CHIP_ROW_GAP;
        }
        g.setColor(1f, 1f, 1f, 1f);
        g.pose().popPose();
        g.disableScissor();
    }

    /** 一行的服务消息(日期牌、未读消息)。对话里的日期牌和翻页时浮在顶上的是同一枚。 */
    private void drawDayChip(GuiGraphics g, String text, int x, int y, int w) {
        drawService(g, List.of(Nb.colored(text, SERVICE_FG).getVisualOrderText()), x, y, w);
    }

    /**
     * 服务消息(Telegram 的 service message):在对话流里居中的一块半透明底、白字,不是谁说的话。
     * 几行各自居中;底贴最宽那行,四周留 {@code SERVICE_PAD_*}。方角,不画圆角。
     */
    private void drawService(GuiGraphics g, List<FormattedCharSequence> lines, int x, int y, int w) {
        int maxW = 0;
        for (FormattedCharSequence l : lines) maxW = Math.max(maxW, font.width(l));
        int mid = x + (w - SB_W) / 2;
        int bx = mid - maxW / 2 - SERVICE_PAD_H;
        g.fill(bx, y, bx + maxW + SERVICE_PAD_H * 2, y + lines.size() * LINE_H + SERVICE_PAD_V * 2, SERVICE_BG);
        int ty = y + SERVICE_PAD_V + 1;
        for (FormattedCharSequence l : lines) {
            draw(g, l, mid - font.width(l) / 2, ty);
            ty += LINE_H;
        }
    }

    private void drawBubble(GuiGraphics g, Bubble b, int x, int y, int w) {
        int bw = b.maxLineW() + padH(b) * 2;
        int qh = b.quote() != null ? QUOTE_H : 0;
        int bh = qh + b.rows() * LINE_H + padV(b) * 2;
        int bubTop = y + (b.label() != null ? LABEL_H : 0);
        // 自己的贴右缘(留出尾巴的宽);别人的正文在脸那一列右边(私聊那一列只有尾巴宽)
        int bx = b.own() ? x + w - TAIL - bw : x + EDGE + faceCol();
        if (b.own()) {
            bubbleFrame(g, true, b.label(), b.who(), false, b.runEnd(), x, y, bx, bw, bh, b.fill());
        } else {
            speakerHead(g, b.label(), b.who(), b.by() != null, b.runEnd(), x, y, bx, bh);
        }
        hits.add(new Hit(bx, bubTop, bw, bh, b, drawingIndex));
        if (b.quote() != null) {
            // 引用条(Telegram 回复的样子):一道强调色竖线、谁(强调色)、那句(和时间同一档淡字)
            int qx = bx + padH(b), qy = bubTop + padV(b);
            int ink = MENTION;
            int dim = b.own() ? OUT_META : IN_META;
            g.fill(qx, qy, qx + 2, qy + QUOTE_H - 3, ink);
            int room = bw - padH(b) * 2 - QUOTE_IN;
            draw(g, Nb.colored(Nb.clip(font, b.quote().who(), room), ink).getVisualOrderText(), qx + QUOTE_IN, qy);
            draw(g, Nb.colored(Nb.clip(font, b.quote().snippet(), room), dim).getVisualOrderText(), qx + QUOTE_IN, qy + 9);
        }
        int ty = bubTop + padV(b) + 1 + qh;
        drawRich(g, b.text(), bx + padH(b), ty, b.ink());
    }

    /**
     * 一块气泡的外形,话和清单共用:群里的名字在上(从 {@code y} 起占一行)、群里别人连发最后一块旁边贴脸
     * (和气泡齐底)、底色、连发最后一块靠脸那侧的尾巴。气泡只有底色、没有描边(Telegram):和地面分开靠色块,
     * 不靠框线。{@code bh} 是气泡本身的高,不含名字那一行。{@code person} = {@code who} 是一位玩家(她的另一位主人):
     * 脸是他的皮肤,不是同伴的头像,也点不开资料页。
     */
    private void bubbleFrame(GuiGraphics g, boolean own, String label, UUID who, boolean person, boolean runEnd,
                             int x, int y, int bx, int bw, int bh, int fill) {
        int bubTop = y + (label != null ? LABEL_H : 0);
        if (!own) speakerHead(g, label, who, person, runEnd, x, y, bx, bh);
        g.fill(bx, bubTop, bx + bw, bubTop + bh, fill);
        if (runEnd) tail(g, own, own ? bx + bw : bx, bubTop + bh, fill);
    }

    /**
     * 她那一侧一块话的头:群里的名字在上(从 {@code y} 起占一行,与正文左缘对齐)、连发最后一块旁边贴脸(和这一块齐底)。
     * {@code bh} 是这一块本身的高,不含名字那一行;{@code person} 见 {@link #bubbleFrame}。
     */
    private void speakerHead(GuiGraphics g, String label, UUID who, boolean person, boolean runEnd,
                             int x, int y, int bx, int bh) {
        int bubTop = y + (label != null ? LABEL_H : 0);
        if (label != null) {
            draw(g, Nb.colored(label, nameColor(who)).getVisualOrderText(), bx + LABEL_IN, y);
        }
        if (group && runEnd) {
            int avX = x + EDGE, avY = bubTop + bh - AV;
            if (person) {
                PlayerFaceRenderer.draw(g, KnownSkins.of(who), avX, avY, AV);
            } else {
                CompanionFace.draw(g, who, KnownSkins.of(who), avX, avY, AV);
                face(g, who, avX, avY);
            }
        }
    }

    /** 清单消息:她的气泡,抬头强调色"计划 2/5",下面一项一行,方格在每项第一行前面。 */
    private void drawChecklist(GuiGraphics g, Checklist c, int x, int y) {
        int bw = c.maxLineW() + PAD_H * 2;
        int bh = checklistH(c);
        int bubTop = y + (c.label() != null ? LABEL_H : 0);
        int bx = x + EDGE + faceCol();
        bubbleFrame(g, false, c.label(), c.who(), false, c.runEnd(), x, y, bx, bw, bh, AI_FILL);
        int tx = bx + PAD_H;
        int ty = bubTop + PAD_V + 1;
        draw(g, Nb.colored(c.header(), MENTION).getVisualOrderText(), tx, ty);
        ty += LINE_H;
        for (int i = 0; i < c.rows().size(); i++) {
            CheckRow r = c.rows().get(i);
            checkBox(g, r.state(), ticked.getOrDefault(tickKey(c, i), 0L), tx, ty);
            for (FormattedCharSequence l : r.lines()) {
                draw(g, l, tx + BOX_COL, ty);
                ty += LINE_H;
            }
        }
    }

    /**
     * 一项前面的方格(贴着字的第一行,与字同高):没做空心,正在做的边框呼吸(透明度来回,"正在做"是活的),
     * 做完实心成功色、里面一枚白勾——刚勾上的({@code tickedAt} 非 0)先从中间往外填满,满了才出勾;
     * 划掉的空心,字已经划了线。
     */
    private void checkBox(GuiGraphics g, com.dwinovo.numen.api.agent.tool.TodoTool.Status state, long tickedAt, int x, int y) {
        switch (state) {
            case COMPLETED -> {
                float e = tickedAt == 0L ? 1f : Anim.easeOutCubic((frameNow - tickedAt) / (float) TICK_MS);
                if (e < 1f) {
                    int size = 1 + 2 * Math.round((BOX - 1) / 2f * e);   // 奇数边长,始终居中
                    int in = (BOX - size) / 2;
                    Nb.border(g, x, y, BOX, BOX, 1, OK);
                    g.fill(x + in, y + in, x + in + size, y + in + size, OK);
                    return;
                }
                g.fill(x, y, x + BOX, y + BOX, OK);
                // 像素勾:短边两格往右下,长边三格往右上
                int[][] tick = {{1, 3}, {2, 4}, {3, 3}, {4, 2}, {5, 1}};
                for (int[] p : tick) g.fill(x + p[0], y + p[1], x + p[0] + 1, y + p[1] + 1, ON_CTA);
            }
            case IN_PROGRESS -> {
                int a = 0x90 + (int) (0x6F * (0.5 + 0.5 * Math.sin(frameNow / 250.0)));
                Nb.border(g, x, y, BOX, BOX, 1, (RUN & 0xFFFFFF) | (a << 24));
            }
            case PENDING, CANCELLED -> Nb.border(g, x, y, BOX, BOX, 1, IN_META);
        }
    }

    /**
     * 她干活的过程——刻意长得<b>不像对话</b>:没有气泡底色与描边。收起是一行安静的字(在想、在跑哪一步、已处理多久),
     * 点开是一根左边的竖线串起每一步,每步右边是它用的秒数;点一次调用再展开它的代码与回执。
     * 开合与展开的那一截按 {@link #folds} 的几成裁出来,不是硬切。
     */
    private void drawProcess(GuiGraphics g, Process p, int x, int y) {
        int cx = x + EDGE + faceCol();
        if (p.label() != null) {
            // 她这一组的第一块:名字在上——和气泡同一套,谁在干活一眼认得出
            draw(g, Nb.colored(p.label(), nameColor(p.who())).getVisualOrderText(), cx + 2, y);
            y += LABEL_H;
        }
        if (group && p.runEnd()) {
            // 这一组的最后一块:脸贴在它的底部
            int avX = x + EDGE, avY = y + p.height() - AV;
            CompanionFace.draw(g, p.who(), KnownSkins.of(p.who()), avX, avY, AV);
            face(g, p.who(), avX, avY);
        }
        if (p.single()) {
            drawSteps(g, p, cx, y);
            return;
        }
        drawHead(g, p, cx, y);
        if (p.bodyH() > 0) {
            int top = y + HEAD_H;
            g.enableScissor(cx, top, cx + p.width(), top + p.bodyH());
            g.fill(cx + RAIL_X, top, cx + RAIL_X + RAIL_W, top + p.bodyH(), TRACE_BAR);
            drawSteps(g, p, cx, top + BODY_PAD);
            g.disableScissor();
        }
    }

    /** 收起的那一行:转动的小箭头、字(在跑的有高光扫过)、走动的秒数、在想时这段思考的第一句。 */
    private void drawHead(GuiGraphics g, Process p, int cx, int y) {
        Head h = p.head();
        if (hoverX >= cx && hoverX < cx + p.width() && hoverY >= y && hoverY < y + HEAD_H) {
            g.fill(cx, y, cx + p.width(), y + HEAD_H, (TXT & 0xFFFFFF) | 0x0A000000);
        }
        // 小箭头:收起朝右,点开朝下,中间转过去
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx + 8, y + HEAD_H / 2f, 0);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90f * (1f - p.open())));
        Sprites.draw(g, Sprites.CHEVRON_DOWN, -Sprites.CHEVRON_W / 2, -Sprites.CHEVRON_H / 2,
                Sprites.CHEVRON_W, Sprites.CHEVRON_H, MUTED);
        pose.popPose();
        int tx = cx + 16;
        int ty = y + 3;
        int room = cx + p.width() - 4 - tx;
        int secsW = h.secs().isEmpty() ? 0 : font.width(h.secs()) + 6;
        String lab = Nb.clip(font, h.lab(), room - secsW);
        int color = h.fail() ? FAIL : MUTED;
        if (h.shimmer()) shimmer(g, lab, tx, ty, MUTED, TXT);
        else Nb.text(g, font, lab, tx, ty, color);
        int sx = tx + font.width(lab) + 6;
        if (!h.secs().isEmpty()) {
            Nb.text(g, font, h.secs(), sx, ty, FAINT);
            sx += font.width(h.secs()) + 6;
        }
        if (!h.sum().isEmpty() && cx + p.width() - 4 - sx > 0) {
            Nb.text(g, font, Nb.clip(font, h.sum(), cx + p.width() - 4 - sx), sx, ty, FAINT);
        }
    }

    /** 一段字,一道高光从左扫到右:每个字在 {@code base} 与 {@code hi} 之间按离高光多远取色。 */
    private void shimmer(GuiGraphics g, String text, int x, int y, int base, int hi) {
        int n = text.codePointCount(0, text.length());
        float pos = (frameNow % SHIMMER_MS) / (float) SHIMMER_MS * (n + 6) - 3;
        int i = 0;
        for (int at = 0; at < text.length(); i++) {
            int cp = text.codePointAt(at);
            String ch = new String(Character.toChars(cp));
            float k = Math.max(0f, 1f - Math.abs(i - pos) / 3f);
            Nb.text(g, font, ch, x, y, lerpColor(base, hi, k));
            x += font.width(ch);
            at += Character.charCount(cp);
        }
    }

    private static int lerpColor(int a, int b, float k) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= Math.round(ca + (cb - ca) * k) << shift;
        }
        return out;
    }

    /** 步骤区里的各步,{@code top} 是第一步的顶边。 */
    private void drawSteps(GuiGraphics g, Process p, int cx, int top) {
        int sx = cx + stepX(p.single());
        int sw = stepW(p.width(), p.single());
        for (StepRow r : p.rows()) {
            drawStep(g, r, sx, top + r.y(), sw);
        }
    }

    private void drawStep(GuiGraphics g, StepRow r, int x, int y, int w) {
        if (!r.step().isCall()) {
            for (int i = 0; i < r.lines().size(); i++) draw(g, r.lines().get(i), x, y + 1 + i * LINE_H);
            drawSecs(g, r, x + w, y + 1);
            return;
        }
        ProcessLine.Step s = r.step();
        boolean fail = s.failed();
        String icon = s.running() ? SPIN[(int) ((frameNow / 120) % 4)] : (fail ? "✗" : "✔");
        Nb.text(g, font, icon, x, y + 3, s.running() ? RUN : (fail ? FAIL : OK));
        draw(g, r.lines().get(0), x + ICON_W, y + 3);
        drawSecs(g, r, x + w, y + 3);
        if (r.h() > HEAD_H) {
            g.enableScissor(x, y + HEAD_H, x + w, y + r.h());
            drawDetail(g, r, x + DETAIL_IN, y + HEAD_H, w - DETAIL_IN);
            g.disableScissor();
        }
    }

    private void drawSecs(GuiGraphics g, StepRow r, int right, int y) {
        if (!r.secs().isEmpty()) Nb.text(g, font, r.secs(), right - font.width(r.secs()), y, FAINT);
    }

    /** 一次调用点开的内容:"代码"、程序;"回执"、结果原文(还没回来就没有这一段)。每段淡底一块,长行已折。 */
    private void drawDetail(GuiGraphics g, StepRow r, int x, int y, int w) {
        int dy = y + 2;
        dy = drawBox(g, I18n.get(ModLanguageData.Keys.CHAT_PROCESS_CODE), r.code(), x, dy, w);
        if (!r.receipt().isEmpty()) {
            drawBox(g, I18n.get(ModLanguageData.Keys.CHAT_PROCESS_RECEIPT), r.receipt(), x, dy + 2, w);
        }
    }

    /** 一行小标题加一块淡底的字;返回这一段下面的纵坐标。 */
    private int drawBox(GuiGraphics g, String caption, List<FormattedCharSequence> lines, int x, int y, int w) {
        Nb.text(g, font, caption, x, y, FAINT);
        y += LINE_H;
        int h = lines.size() * LINE_H + 4;
        g.fill(x, y, x + w, y + h, (TXT & 0xFFFFFF) | 0x14000000);
        for (int i = 0; i < lines.size(); i++) draw(g, lines.get(i), x + BOX_IN, y + 2 + i * LINE_H);
        return y + h;
    }

    /**
     * 气泡的小尾巴(Telegram 连发只有最后一条有):贴在靠脸那一侧的底角往外伸,自底向上一级比一级短,
     * 方角像素台阶,不画弧。{@code right} = 主人的,伸向右下;{@code edgeX} 是气泡那一侧的边。
     */
    private static void tail(GuiGraphics g, boolean right, int edgeX, int bottom, int fill) {
        for (int k = 0; k < TAIL_STEPS.length; k++) {
            int y = bottom - 1 - k;
            int len = TAIL_STEPS[k];
            if (right) g.fill(edgeX, y, edgeX + len, y + 1, fill);
            else g.fill(edgeX - len, y, edgeX, y + 1, fill);
        }
    }

    /** 记下这张脸能点;指针在它上面时贴着脸描一圈强调色边,像个能点的东西。 */
    private void face(GuiGraphics g, UUID who, int x, int y) {
        faces.add(new Face(who, x, y));
        if (hoverX >= x - 2 && hoverX < x + AV + 2 && hoverY >= y - 2 && hoverY < y + AV + 2) {
            Nb.border(g, x - 1, y - 1, AV + 2, AV + 2, 1, MENTION);   // 只描边,脸照样看得见
        }
    }

    /** 指针下那张脸是谁的;不在脸上是 null。只认对话流可见区里的。 */
    public UUID faceAt(double mx, double my) {
        if (mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return null;
        for (Face f : faces) {
            if (mx >= f.x() - 2 && mx < f.x() + AV + 2 && my >= f.y() - 2 && my < f.y() + AV + 2) return f.who();
        }
        return null;
    }

    /** Shadowless draw — the colour is baked into the sequence's Style (see {@link Nb}). */
    private void draw(GuiGraphics g, FormattedCharSequence seq, int x, int y) {
        Nb.text(g, font, seq, x, y);
    }

    // ---- text helpers ----

    private static String ownerText(String s) {
        return ChatDisplayModes.current().userText(s);
    }

    /** 排着的整理或清空:条目的语言键翻成主人的语言,整理附的关注点接在后面。 */
    private static String controlShown(String text) {
        String label = net.minecraft.client.resources.language.I18n.get(
                com.dwinovo.numen.api.agent.inbox.EventTypes.controlLabel(text));
        String arg = com.dwinovo.numen.api.agent.inbox.EventTypes.controlArg(text);
        return arg.isEmpty() ? label : label + ": " + arg;
    }

    /**
     * 这个面板画的会话:成员各自的日志按会话印归并成一条时间线。单成员时就是她一本日志——
     * 同一条路,没有"单聊另一条路"。循环还没起来的成员这一刻没有记录可读,跳过。
     */
    private List<Transcript.Entry> transcript() {
        Conversations convos = Conversations.instance();
        Conversation c = conv.get();
        return ClientCompanions.transcript(c.id());
    }

    /** 这次调用还在她那条循环的派发器手里没有——"还在跑"只问派发器,不从历史长什么样去猜。 */
    private static boolean outstanding(UUID companion, String callId) {
        return ClientCompanions.get(companion).map(l -> l.isToolCallOutstanding(callId)).orElse(false);
    }

    /** 气泡上的名字:名册名。多人会话里每条回复各标各的说话人。 */
    private static String speaker(UUID companion) {
        return NumenRoster.instance().name(companion);
    }

    /** 连发第一块上面的名字:只有群里标——私聊里对面是谁抬头已经写了(Telegram 私聊不标名字)。 */
    private String label(UUID companion) {
        return group ? speaker(companion) : null;
    }

    /** 她在群里的名字色:按 UUID 取模挑七色之一,同一只永远同一色。 */
    private static int nameColor(UUID companion) {
        return UiTheme.current().peerName(companion.hashCode());
    }

    /** 气泡左边留的那一列:群里放脸(尾巴伸在脸和气泡之间的缝里);私聊和外脑现场不画脸,只留尾巴那几像素。 */
    private int faceCol() {
        return group ? AV + AV_GAP : TAIL;
    }

    /** 一次调用的一行:工具的名字,加上这个工具说这次调用读作什么(工具没登记,旧日志里的调用,只有名字)。 */
    private static String toolLine(LlmToolCall tc) {
        NumenTool tool = ToolRegistry.resolve(Minecraft.getInstance().getUser().getProfileId(), tc.name());
        String summary = NumenTool.summaryOf(tool, tc.arguments());
        return ToolLine.of(ToolWords.label(tc.name()), summary);
    }

}
