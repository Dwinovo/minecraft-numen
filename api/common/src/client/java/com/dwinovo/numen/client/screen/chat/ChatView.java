package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.conversation.Mentions;
import com.dwinovo.numen.agent.conversation.Quote;
import com.dwinovo.numen.agent.conversation.Transcript;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.Conversations;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.KnownSkins;
import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.mcp.server.McpMode;
import com.dwinovo.numen.mcp.server.McpTranscript;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.dwinovo.numen.client.skin.CompanionFace;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The chat transcript as a conversation: the owner's messages are right-aligned
 * bubbles, the companion's replies left-aligned bubbles — both with their real
 * skin avatar — and a run of consecutive tool calls folds into one chip
 * (click to expand once done). System notes (persona change / compaction / empty
 * hint) sit centred and faint. Scrolling is eased ({@link Anim#approach}) and
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
    /** 时间戳放不进最后一行右侧时,单独占的一行(小字)。 */
    private static final int TIME_H = 8;
    /** 新消息飞入:从下面 8px 淡入,220ms easeOut——Telegram 那种"升上来"。 */
    private static final int ENTER_MS = 220;
    private static final int ENTER_DY = 8;
    private static final int AV = 18;           // avatar face size
    private static final int AV_GAP = 5;        // avatar ↔ bubble
    private static final int PAD_H = 5;         // bubble text inset
    private static final int PAD_V = 4;         // 1 line → 18px bubble = exactly the avatar height
    /** 块与块之间:换了人、日期牌、提示行前后拉开 {@code BLOCK_GAP};同一个人接连的块只隔 {@code RUN_GAP}。 */
    private static final int BLOCK_GAP = 6;
    private static final int RUN_GAP = 2;
    private static final int TOP_PAD = 2;
    private static final int BOT_PAD = 2;
    private static final int SB_W = 4;          // scrollbar width
    private static final int EDGE = 2;          // left inset so the avatar FRAME (-2px) clears the scissor
    private static final int OPP_MARGIN = 24;   // kept clear on the far side of a bubble
    private static final int ICON_W = 11;       // chip status-icon column
    private static final int TOOL_ARG_CHARS = 44;
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
    private int TOOL, MUTED, FAINT, OK, RUN, FAIL, TXT;
    private int AI_FILL, AI_BORDER, OWN_FILL, QUEUED_FILL, CHIP_FILL;
    /** 机器行的左缘竖线色(工具/思考过程共用)。 */
    private int TRACE_BAR;
    /** 主人话里 @ 到的名字。 */
    private int MENTION;
    /** 未读角标上的数字色。 */
    private int ON_CTA;

    private void loadPalette() {
        UiTheme t = UiTheme.current();
        TOOL = t.textDim();
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
        CHIP_FILL = t.chipFill();
        TRACE_BAR = t.surfaceBorder();
        MENTION = t.cta();
        ON_CTA = t.onCta();
    }

    private static ResourceLocation spr(String n) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, n);
    }
    private static final ResourceLocation SCROLL_THUMB = spr("scroll_thumb");
    private static final ResourceLocation CHEVRON_DOWN = spr("chevron_down");
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
        String shown = "";
    }
    /** Completed tool-call groups the user clicked open (keyed by the group's first call id). */
    private final Set<String> expandedGroups = new HashSet<>();
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
    /** 思考正文压成一行的缓存(正则 + 拷贝,每帧对几 KB 的思考跑一遍也不便宜)。同样 LRU。 */
    private final java.util.Map<String, String> flattened =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) {
                    return size() > 256;
                }
            };

    private record SplitKey(Component text, int width) {}

    private List<FormattedCharSequence> split(Component text, int width) {
        return splits.computeIfAbsent(new SplitKey(text, width), k -> font.split(k.text(), k.width()));
    }
    // geometry of the last render, for click / wheel hit-testing
    private int gx, gy, gw, gh;
    /** 这一帧画出来的脸(气泡旁、工具行旁):点哪张开哪只的资料页(Telegram 点消息头像看资料)。 */
    private record Face(UUID who, int x, int y) {}
    private final List<Face> faces = new ArrayList<>();
    /** 这一帧画的是不是群:只有群里画别人的脸和名字(Telegram 私聊两边都不画)。 */
    private boolean group;
    private int hoverX = -1, hoverY = -1;
    /** 每条记录第一次被看见的时刻(与归并后的记录同序);0 = 打开时就有的历史,不飞入。 */
    private final List<Long> born = new ArrayList<>();
    private long frameNow;

    public ChatView(Font font, Supplier<Conversation> conv) {
        this.font = font;
        this.conv = conv;
    }

    /** 就他俩时是她;否则 null。 */
    private UUID solo() {
        return Conversations.instance().soloOf(conv.get());
    }

    /** Forget scroll + fold state (companion or tab switch). */
    public void reset() {
        unreadSince = -1;
        scrollPos = 0;
        scrollTarget = 0;
        pinBottom = true;
        snapNext = true;
        lastFrameMs = 0;
        live.clear();
        expandedGroups.clear();
        splits.clear();
        flattened.clear();
        born.clear();
    }

    /**
     * 打开这个会话那一刻主人看到哪了:之后她说的第一句前面横一条"未读消息",打开时停在那儿,不停在最底。
     * 这次看的时候那条一直在(Telegram 也是离开才消)。-1 = 下一帧现取;{@link Long#MAX_VALUE} = 打开时没有未读。
     */
    private long unreadSince = -1;
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
        group = solo() == null;
        hoverX = mouseX;
        hoverY = mouseY;
        faces.clear();
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        frameNow = now;
        updateLive(dt, now);
        if (unreadSince < 0) {
            Conversation opened = conv.get();
            long seen = Conversations.instance().lastSeen(opened);
            unreadSince = ConversationPreview.unread(opened, seen) > 0 ? seen : Long.MAX_VALUE;
        }
        renderBlocks(g, x, y, w, h, build(bubbleMaxW(w)), dt);
        // 看过 = 视图停在底部时的最后一条;翻上去后来的话算未读,挂在"回到最新"钮上,左栏角标也据此消
        Conversation c = conv.get();
        var latest = ConversationPreview.last(c);
        if (pinBottom && latest != null) Conversations.instance().markSeen(c, latest.ts());
        renderJump(g, x, y, h, dt, ConversationPreview.unread(c, Conversations.instance().lastSeen(c)));
    }

    /** "回到最新"钮:翻上去超过半屏、或底下有没看过的话就浮出;从底边滑上来,顶上压一枚未读数。 */
    private void renderJump(GuiGraphics g, int x, int y, int h, float dt, int unread) {
        boolean want = lastMaxScroll - scrollTarget > h / 2 || unread > 0;
        jumpShown = Anim.approach(jumpShown, want ? 1f : 0f, 16f, dt);
        if (jumpShown <= 0.02f) return;
        int right = barX() + SB_W;
        jumpX = right - JUMP_INSET - JUMP;
        jumpY = y + h - Math.round(jumpShown * (JUMP + JUMP_INSET));
        g.enableScissor(x, y, right, y + h);
        NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), jumpX, jumpY, JUMP, JUMP,
                AI_FILL, AI_BORDER);
        g.blitSprite(CHEVRON_DOWN, jumpX + (JUMP - 11) / 2, jumpY + (JUMP - 6) / 2, 11, 6);
        if (unread > 0) {
            String n = UnreadBadge.label(unread);
            int bw = UnreadBadge.width(font, n);
            UnreadBadge.draw(g, font, n, jumpX + (JUMP - bw) / 2, jumpY - UnreadBadge.H / 2, MENTION, ON_CTA);
        }
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
            // 有未读:停在"未读消息"那条上(上面留一点),不停在最底
            int bar = unreadBarOffset(blocks);
            if (bar >= 0) {
                scrollTarget = Math.clamp(bar - 6, 0, lastMaxScroll);
                pinBottom = scrollTarget >= lastMaxScroll;
            }
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
                drawBlock(g, b, x, cy, w);
                if (i == current && hits.size() > drawn) {
                    // 停在的那个命中:气泡外描一圈强调色
                    Hit hit = hits.get(hits.size() - 1);
                    Nb.border(g, hit.x() - 1, hit.y() - 1, hit.w() + 2, hit.h() + 2, 1, MENTION);
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
            g.setColor(1f, 1f, 1f, barShown / 8f);
            g.blitSprite(SCROLL_THUMB, barX(), thumbY(), SB_W, thumbH());   // 只有滑块,没有槽,贴正文区右缘(Telegram)
            g.setColor(1f, 1f, 1f, 1f);
        }
    }

    // ---- 外脑驱动中:聊天区画现场缓冲 ----

    /** 外脑现场的头部知情区高度(标题行 + 状态行 + 分隔线)。 */
    private static final int EXT_HEADER_H = 30;

    /**
     * 外脑驱动时的聊天区:顶上一条"谁接进来了"的知情行(主人得一眼知道这屏对面
     * 是外接大脑),下面用<b>同一套气泡语法</b>画 {@link McpTranscript} 的现场——
     * 主人的话右侧气泡、外脑的 say 左侧气泡、动作淡色一行。还没动静时显示接入向导。
     */
    public void renderExternal(GuiGraphics g, int x, int y, int w, int h) {
        loadPalette();
        group = false;   // 外脑现场只有她一个
        long now = System.currentTimeMillis();
        frameNow = now;
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        McpMode mcp = McpMode.instance();
        int cx = x + EDGE;
        int cw = w - EDGE - SB_W - 3;

        line(g, I18n.get("numen.brain.console_title"), cx, y + 2, TXT);
        String who = mcp.clientName();
        line(g, who == null
                        ? I18n.get("numen.brain.status_waiting")
                        : I18n.get("numen.brain.status_connected", who, sinceLabel(mcp.lastActivityMs())),
                cx, y + 14, who == null ? FAINT : OK);
        g.fill(cx, y + 26, cx + cw, y + 27, CHIP_FILL);

        UUID id = solo();
        if (McpTranscript.isEmpty(id)) {
            renderConsoleGuide(g, mcp, cx, y + EXT_HEADER_H + 2, cw, who != null);
            return;
        }
        renderBlocks(g, x, y + EXT_HEADER_H, w, h - EXT_HEADER_H, buildExternal(id, bubbleMaxW(w)), dt);
        renderJump(g, x, y + EXT_HEADER_H, h - EXT_HEADER_H, dt, 0);   // 现场缓冲没有未读一说
    }

    /** 现场缓冲 → 可画的块。与 {@link #build} 同一套 Block 词汇,只是来源不同。 */
    private List<Block> buildExternal(UUID id, int bubbleMaxW) {
        List<Block> out = new ArrayList<>();
        int innerW = bubbleMaxW - PAD_H * 2;
        int chipTextW = bubbleMaxW - PAD_H * 2 - ICON_W;
        UUID last = null;
        for (McpTranscript.Line ln : McpTranscript.view(id)) {
            switch (ln.kind()) {
                case OWNER -> {
                    boolean first = !OWNER.equals(last);
                    out.add(bubble(true, null, Nb.colored(ln.text(), TXT), OWN_FILL,
                            innerW, first, null, null, -1, ln.text(), null));
                    last = OWNER;
                }
                case SAY -> {
                    boolean first = !id.equals(last);
                    out.add(bubble(false, first ? label(id) : null, Nb.colored(ln.text(), TXT),
                            AI_FILL, innerW, first, id, null, -1, ln.text(), null));
                    last = id;
                }
                case TOOL -> {
                    boolean first = !id.equals(last);
                    out.add(new Chip(List.of(new ChipRow(
                            ln.error() ? "✗" : "✔", ln.error() ? FAIL : OK,
                            Nb.colored(fitOneLine(ln.text(), chipTextW), ln.error() ? FAIL : TOOL)
                                    .getVisualOrderText())), null, first ? label(id) : null, id, -1, false));
                    last = id;
                }
            }
        }
        settleAvatars(out);
        return out;
    }

    /** 还没有调用记录时的引导:连上了就等它动手,没连过就讲怎么接。 */
    private void renderConsoleGuide(GuiGraphics g, McpMode mcp, int cx, int cy, int cw, boolean connected) {
        if (connected) {
            line(g, I18n.get("numen.brain.console_empty"), cx, cy, FAINT);
            return;
        }
        line(g, I18n.get("numen.brain.guide_title"), cx, cy, TXT);
        line(g, I18n.get("numen.brain.endpoint") + ": " + mcp.endpoint(), cx, cy + 14, MUTED);
        line(g, I18n.get("numen.brain.token") + ": "
                        + (mcp.token().isBlank() ? I18n.get("numen.brain.token_none") : mcp.maskedToken()),
                cx, cy + 26, MUTED);
        int ty = cy + 44;
        for (FormattedCharSequence l : font.split(Nb.colored(I18n.get("numen.brain.guide_step"), FAINT), cw)) {
            draw(g, l, cx, ty);
            ty += LINE_H + 2;
        }
    }

    private void line(GuiGraphics g, String text, int x, int y, int color) {
        draw(g, Nb.colored(text, color).getVisualOrderText(), x, y);
    }

    /** "12 秒前" / "3 分钟前"。 */
    private static String sinceLabel(long stampMs) {
        long sec = Math.max(0, (System.currentTimeMillis() - stampMs) / 1000);
        return sec < 60 ? I18n.get("numen.brain.since_sec", sec) : I18n.get("numen.brain.since_min", sec / 60);
    }

    /** Wheel anywhere on the chat tab scrolls the transcript (parity with the old list). */
    public boolean mouseScrolled(double sy) {
        lastScrollMs = System.currentTimeMillis();
        scrollTarget = Math.clamp((long) (scrollTarget - sy * LINE_H * 3), 0, lastMaxScroll);
        pinBottom = scrollTarget >= lastMaxScroll;
        return true;
    }

    /** Toggle the fold of a completed tool chip under the mouse. */
    public boolean mouseClicked(double mx, double my) {
        // 钮连同顶上的角标一起算点中
        if (jumpShown > 0.5f && mx >= jumpX && mx < jumpX + JUMP
                && my >= jumpY - UnreadBadge.H / 2 && my < jumpY + JUMP && my < gy + gh) {
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
        if (McpMode.instance().driving()) return false;   // 现场视图没有可折叠的块
        if (gw == 0 || mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return false;
        loadPalette();
        int cy = gy + TOP_PAD - Math.round(scrollPos);
        List<Block> blocks = build(bubbleMaxW(gw));
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            int bh = heightOf(b);
            if (b instanceof Chip c && c.foldKey() != null && my >= cy && my < cy + bh) {
                if (!expandedGroups.add(c.foldKey())) expandedGroups.remove(c.foldKey());
                return true;
            }
            cy += bh + gapAfter(blocks, i);
        }
        return false;
    }

    // ---- blocks ----

    private sealed interface Block permits Bubble, Chip, Notice, Divider, UnreadBar {}

    /** One spoken message. {@code label} non-null = companion side (name above the bubble);
     *  {@code showAvatar} false = a consecutive message from the same side (head hidden);
     *  {@code who} = the companion whose face goes on it (null on the owner's side);
     *  {@code time} = 时间戳贴在气泡右下角(Telegram),放得进最后一行右侧就 {@code timeInline},
     *  放不进单独占一小行;{@code entry} = 归并后的记录序号,新来的按它飞入(-1 = 不飞)。 */
    private record Bubble(boolean own, String label, List<FormattedCharSequence> lines,
                          int maxLineW, int fill,
                          boolean showAvatar, UUID who, String time, boolean timeInline, int entry,
                          String raw, Quote quote) implements Block {
        Bubble withAvatar(boolean on) {
            return new Bubble(own, label, lines, maxLineW, fill, on, who, time, timeInline, entry, raw, quote);
        }
    }

    /** 气泡顶上的引用条:两行(谁、那句),左缘一道竖线;{@code QUOTE_IN} 是字离竖线多远。 */
    private static final int QUOTE_H = 20;
    private static final int QUOTE_IN = 6;

    /** 这一帧画出来的气泡在哪、是哪条:右键按它认点中的是哪句。 */
    private record Hit(int x, int y, int w, int h, Bubble b) {}
    private final List<Hit> hits = new ArrayList<>();

    /** 右键点中的那句:谁说的(主人自己是 null)、原文(引用条不算在内)。 */
    public record Picked(UUID who, String text) {}

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

    /** 指针下那个气泡;不在气泡上是 null。只认对话流可见区里的。 */
    public Picked bubbleAt(double mx, double my) {
        if (mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return null;
        for (Hit h : hits) {
            if (h.b().raw() != null && mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
                return new Picked(h.b().own() ? null : h.b().who(), h.b().raw());
            }
        }
        return null;
    }

    /** A run of tool calls (or a reasoning block). {@code foldKey} non-null = finished group,
     *  clickable to expand/fold. {@code who} = 干这些活的那只;{@code label} non-null = 她这一轮连发
     *  的第一块,脸和名字画在它上面——多人会话里工具行也得认得出是谁的。 */
    private record Chip(List<ChipRow> rows, String foldKey, String label, UUID who, int entry,
                        boolean showAvatar) implements Block {
        Chip withAvatar(boolean on) { return new Chip(rows, foldKey, label, who, entry, on); }
    }

    /** 日期分隔:一天的第一条上面一枚居中的日期小牌(今天 / 昨天 / 几月几日)。 */
    private record Divider(String text) implements Block {}

    /** "未读消息"那一条:横贯整行的淡色带,居中一行字。 */
    private record UnreadBar() implements Block {}

    private record ChipRow(String icon, int iconColor, FormattedCharSequence text) {}

    /** A centred, faint system note. */
    /** 居中的一行提示(分隔、中断、整理中);画的时候按这一刻的宽度收口,长的切断原因不会画穿面板。 */
    private record Notice(String text) implements Block {}

    private int bubbleMaxW(int w) {
        return w - EDGE - faceCol() - OPP_MARGIN - SB_W - 3;
    }

    private int heightOf(Block b) {
        return switch (b) {
            case Bubble bb -> (bb.label() != null ? LABEL_H : 0) + (bb.quote() != null ? QUOTE_H : 0)
                    + bb.lines().size() * LINE_H + PAD_V * 2
                    + (bb.time() != null && !bb.timeInline() ? TIME_H : 0);
            case Chip c -> (c.label() != null ? LABEL_H : 0) + c.rows().size() * LINE_H + PAD_V * 2;
            case Notice ignored -> LINE_H;
            case Divider ignored -> LINE_H + 4;
            case UnreadBar ignored -> LINE_H + 6;
        };
    }

    private int totalHeight(List<Block> blocks) {
        int sum = TOP_PAD + BOT_PAD;
        for (int i = 0; i < blocks.size(); i++) {
            sum += heightOf(blocks.get(i)) + (i > 0 ? gapAfter(blocks, i - 1) : 0);
        }
        return sum;
    }

    /** "未读消息"那条的顶边离内容顶多远;没有那条是 -1。 */
    private int unreadBarOffset(List<Block> blocks) {
        int y = TOP_PAD;
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i) instanceof UnreadBar) return y;
            y += heightOf(blocks.get(i)) + gapAfter(blocks, i);
        }
        return -1;
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
     * <p>模式只改<b>每条怎么渲染</b>(见 {@link com.dwinovo.numen.client.chat.ChatDisplayMode}):
     * 常态只取 {@code <query>} 里主人的话,debug 连 {@code <query>} 外的一起画。不换来源
     * ——请求里临时挂载、从未入过记录的东西(如 {@code <current_task>})混进来的话,
     * 画出来的会是一条<b>从未存在过</b>的消息。
     */
    /** 过程里的一段:一次思考({@code live} = 还在往外流),或一次工具调用。 */
    private record Piece(String thought, boolean live, LlmToolCall call) {}

    /** 一次 build 的手头:输出、她攒着的这一段过程与它的主人、连发的上一位。 */
    private static final class Feed {
        final List<Block> out = new ArrayList<>();
        /** 她连着的思考和工具调用,中间没开口说话:收口时合成一行。 */
        final List<Piece> process = new ArrayList<>();
        UUID processWho;
        int processEntry = -1;
        UUID last;
        boolean unreadPlaced;
    }

    private static void addPiece(Feed f, UUID who, int entry, Piece p) {
        if (f.process.isEmpty()) {
            f.processWho = who;
            f.processEntry = entry;
        }
        f.process.add(p);
    }

    private List<Block> build(int bubbleMaxW) {
        Feed f = new Feed();
        List<Block> out = f.out;
        List<Transcript.Entry> source = transcript();
        Set<String> done = new HashSet<>();
        Set<String> failed = new HashSet<>();
        for (Transcript.Entry e : source) {
            if (e.msg() instanceof ConvoState.Msg.Tool t) {
                done.add(t.toolCallId());
                // 成败判据的单一真源(展示层不猜字符串)。
                if (com.dwinovo.numen.agent.llm.ToolOutcome.failed(t.content())) {
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
        // 新来的记录记下第一次被看见的时刻,画的时候按它飞入;打开面板时就在的历史不飞
        if (born.size() > source.size()) born.clear();   // 记录被清了(/clear):从头记
        boolean opening = born.isEmpty();
        while (born.size() < source.size()) born.add(opening ? 0L : frameNow);
        // 连发合并(聊天软件的惯例):同一个人接连的话和活只在第一块画头像和名字。多人会话里
        // "同一个人"按那只算,不按左右哪一侧——换了一只就得重新亮名字。工具行是她的活,
        // 算在她的连发里;提示行打断。f.last == null = 连发已断。
        int msgIndex = -1;
        LocalDate lastDay = null;
        for (Transcript.Entry entry : source) {
            msgIndex++;
            ConvoState.Msg msg = entry.msg();
            // 日期分隔:换了一天,先收口、插一枚日期小牌、连发断开
            if (entry.ts() > 0) {
                LocalDate day = Instant.ofEpochMilli(entry.ts()).atZone(ZoneId.systemDefault()).toLocalDate();
                if (!day.equals(lastDay)) {
                    flushProcess(f, done, failed, bubbleMaxW);
                    out.add(new Divider(dayLabel(day)));
                    f.last = null;
                    lastDay = day;
                }
            }
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    flushProcess(f, done, failed, bubbleMaxW);
                    if (ConvoLog.PERSONA_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.persona_changed"));
                        f.last = null;
                        continue;
                    }
                    if (ConvoLog.COMPACT_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.compacted"));
                        f.last = null;
                        continue;
                    }
                    if (ConvoLog.CLEAR_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.cleared"));
                        f.last = null;
                        continue;
                    }
                    String shown = ownerText(u.content());   // owner's words only, never injected content
                    if (shown.isEmpty()) continue;
                    boolean first = !OWNER.equals(f.last);
                    Quote q = Quote.parse(shown);   // 引用回复:第一行画成气泡顶上的引用条
                    out.add(bubble(true, null, mentionsLit(q.body()), OWN_FILL, innerW, first, null,
                            clock(entry.ts()), msgIndex, q.body(), q.quoted() ? q : null));
                    f.last = OWNER;
                }
                case ConvoState.Msg.Assistant a -> {
                    UUID who = entry.companion();
                    // 换了一只:前一只攒着的过程先收口,两只的活不折进同一行
                    if (!who.equals(f.processWho)) flushProcess(f, done, failed, bubbleMaxW);
                    AssistantTurn turn = a.turn();
                    // 思考在说话之前;它和前后的工具调用同属"她在干活",并进同一段过程
                    String reasoned = turn.reasoning();
                    if (reasoned != null && !reasoned.isBlank()) {
                        addPiece(f, who, msgIndex, new Piece(reasoned, false, null));
                    }
                    String spoken = ChatDisplayModes.current().assistantText(turn.content());
                    if (!spoken.isBlank()) {
                        flushProcess(f, done, failed, bubbleMaxW);   // 开口说话把过程收口
                        if (!f.unreadPlaced && entry.ts() > unreadSince) {
                            out.add(new UnreadBar());   // 打开时还没看过的第一句
                            f.unreadPlaced = true;
                            f.last = null;
                        }
                        boolean first = !who.equals(f.last);
                        out.add(bubble(false, first ? label(who) : null,
                                Nb.colored(spoken, TXT), AI_FILL, innerW, first, who,
                                clock(entry.ts()), msgIndex, spoken, null));
                        f.last = who;
                    }
                    for (LlmToolCall tc : turn.toolCalls()) addPiece(f, who, msgIndex, new Piece(null, false, tc));
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a block */ }
                case ConvoState.Msg.Halt h -> {
                    flushProcess(f, done, failed, bubbleMaxW);
                    notice(out, I18n.get("numen.chat.halted", h.reason()));
                    f.last = null;
                }
            }
        }
        // 最后一段过程先不收口:她正在想的那段接在它后面,并成同一行
        // 在飞的状态按成员各自的循环取:单成员就是她一个,多人各画各的。
        // 只画她此刻所在的会话里的:她在群里想着,私聊页不该也看见——和落库的行同一条印的规矩。
        String tag = Conversations.instance().tagOf(conv.get());
        java.util.Set<String> queued = new java.util.LinkedHashSet<>();
        boolean compacting = false;
        for (UUID her : Conversations.instance().membersAlive(conv.get())) {
            EntityAgentLoop lp = AgentLoopRegistry.get(her).orElse(null);
            if (lp == null || !java.util.Objects.equals(lp.conversation(), tag)) continue;
            if (!her.equals(f.processWho)) flushProcess(f, done, failed, bubbleMaxW);
            // 在飞的思考流接在她这段过程末尾;回合落库后由落库的那段接管,永不双份。
            String liveReasoning = lp.liveReasoning();
            if (!liveReasoning.isBlank()) addPiece(f, her, -1, new Piece(liveReasoning, true, null));
            // The in-flight reply, typed out live (chunk stream → EntityAgentLoop.livePartial).
            Live l = live.get(her);
            if (l != null && !l.shown.isEmpty()) {
                flushProcess(f, done, failed, bubbleMaxW);
                boolean first = !her.equals(f.last);
                out.add(bubble(false, first ? label(her) : null, Nb.colored(l.shown, TXT),
                        AI_FILL, innerW, first, her, null, -1, l.shown, null));
                f.last = her;
            }
            // 排着的话:主人一句话复制进每个醒着的成员的队列,按原文去重,画一次
            var status = lp.status();
            for (String q : status.queuedPreview()) {
                String shown = ownerText(q);
                if (!shown.isEmpty()) queued.add(shown);
            }
            compacting |= status.phase() == com.dwinovo.numen.agent.loop.Phase.COMPACT;
        }
        flushProcess(f, done, failed, bubbleMaxW);
        // Prompts still waiting for a protocol-valid splice point — visible immediately
        // so a queued message never feels swallowed.
        for (String shown : queued) {
            boolean first = !OWNER.equals(f.last);
            String body = Quote.parse(shown).body();
            out.add(bubble(true, null, Nb.colored("⌛ " + body, FAINT), QUEUED_FILL,
                    innerW, first, null, null, -1, body, null));
            f.last = OWNER;
        }
        if (compacting) notice(out, I18n.get("numen.chat.compacting"));
        if (out.isEmpty()) {
            notice(out, I18n.get("numen.chat.empty", conv.get().displayName(NumenRoster.instance()::name)));
        }
        settleAvatars(out);
        return out;
    }

    /**
     * Telegram 的排法:群里别人的名字在这一组的第一块上面,脸贴在这一组的<b>最后一块</b>旁边(底部对齐)。
     * 建块时只知道"是不是第一块",所以脸在这儿补:同一个人连着的块算一组,提示行、日期牌把组断开。
     */
    private void settleAvatars(List<Block> out) {
        for (int i = 0; i < out.size(); i++) {
            UUID who = speakerOf(out.get(i));
            if (who == null) continue;
            // 主人自己的话永远不带脸;私聊里谁的都不带
            boolean last = group && !OWNER.equals(who)
                    && (i + 1 >= out.size() || !who.equals(speakerOf(out.get(i + 1))));
            Block b = out.get(i);
            if (b instanceof Bubble bb && bb.showAvatar() != last) out.set(i, bb.withAvatar(last));
            else if (b instanceof Chip c && c.showAvatar() != last) out.set(i, c.withAvatar(last));
        }
    }

    /** 这一块是谁的:主人用 {@link #OWNER} 代表;提示行、日期牌不是谁的。 */
    private static UUID speakerOf(Block b) {
        return switch (b) {
            case Bubble bb -> bb.own() ? OWNER : bb.who();
            case Chip c -> c.who();
            default -> null;
        };
    }

    private Bubble bubble(boolean own, String label, Component body, int fill,
                          int innerW, boolean showAvatar, UUID who, String time, int entry,
                          String raw, Quote quote) {
        List<FormattedCharSequence> lines = split(body, innerW);
        int maxW = 0;
        for (FormattedCharSequence l : lines) maxW = Math.max(maxW, font.width(l));
        if (quote != null) {
            maxW = Math.max(maxW, Math.min(innerW,
                    QUOTE_IN + Math.max(font.width(quote.who()), font.width(quote.snippet()))));
        }
        boolean inline = false;
        if (time != null) {
            // 时间戳挤在最后一行右侧(Telegram):放得下就同一行,放不下自己占一小行
            int tw = font.width(time) + 6;
            int last = lines.isEmpty() ? 0 : font.width(lines.get(lines.size() - 1));
            inline = last + tw <= innerW;
            maxW = Math.max(maxW, inline ? last + tw : tw);
        }
        return new Bubble(own, label, lines, maxW, fill, showAvatar, who, time, inline, entry, raw, quote);
    }

    /** {@code HH:mm},本机时区;没有时间戳的旧记录不标。 */
    private static String clock(long ts) {
        if (ts <= 0) return null;
        LocalTime t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalTime();
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /** 日期小牌上的字:今天、昨天,再往前是几月几日,跨年带年。 */
    private static String dayLabel(LocalDate day) {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        if (day.equals(today)) return I18n.get(com.dwinovo.numen.data.ModLanguageData.Keys.CHAT_TODAY);
        if (day.equals(today.minusDays(1))) return I18n.get(com.dwinovo.numen.data.ModLanguageData.Keys.CHAT_YESTERDAY);
        if (day.getYear() == today.getYear()) {
            return I18n.get(com.dwinovo.numen.data.ModLanguageData.Keys.CHAT_DATE_MD, day.getMonthValue(), day.getDayOfMonth());
        }
        return I18n.get(com.dwinovo.numen.data.ModLanguageData.Keys.CHAT_DATE_YMD, day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }

    /**
     * 主人的话,{@code @} 到的名字画亮。用的是路由那一份匹配({@link Mentions#spans}),所以亮的
     * 正好是会醒的,不是"长得像名字"。名字按此刻名册,改过名之后旧记录里的不亮。
     */
    private Component mentionsLit(String text) {
        List<Mentions.Span> spans = Mentions.spans(text, Conversations.instance().named(conv.get()));
        if (spans.isEmpty()) return Nb.colored(text, TXT);
        MutableComponent out = Component.empty();
        int at = 0;
        for (Mentions.Span s : spans) {
            if (s.start() > at) out.append(Nb.colored(text.substring(at, s.start()), TXT));
            out.append(Nb.colored(text.substring(s.start(), s.end()), MENTION));
            at = s.end();
        }
        if (at < text.length()) out.append(Nb.colored(text.substring(at), TXT));
        return out;
    }

    /** Advance the typewriter: filter the live partial, ease the reveal toward the
     *  full length, and cache "revealed text + blinking caret". */
    private void updateLive(float dt, long now) {
        List<UUID> members = Conversations.instance().membersAlive(conv.get());
        live.keySet().retainAll(members);
        for (UUID her : members) {
            EntityAgentLoop lp = AgentLoopRegistry.get(her).orElse(null);
            String full = lp == null ? "" : ChatDisplayModes.current().assistantText(lp.livePartial());
            if (full.isEmpty()) {
                live.remove(her);
                continue;
            }
            Live l = live.computeIfAbsent(her, k -> new Live());
            if (l.revealed > full.length()) l.revealed = full.length();
            if (full.length() - l.revealed > REVEAL_MAX_LAG) l.revealed = full.length() - REVEAL_MAX_LAG;
            l.revealed = Math.min(full.length(), l.revealed + dt * REVEAL_CPS);
            l.shown = cut(full, (int) l.revealed) + (((now / 500) & 1) == 0 ? "_" : "");
        }
    }

    /** Cut at {@code n} chars without splitting a surrogate pair. */
    private static String cut(String s, int n) {
        if (n >= s.length()) return s;
        if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }

    private void notice(List<Block> out, String text) {
        out.add(new Notice(text));
    }

    /**
     * 收口她这一段过程(连着的思考和工具调用,中间没开口):合成<b>一行</b>,点开才看每一步。
     * 过程是旁白不是话,一轮干活只占一行,正文留给她说的话。在跑时这一行是转圈 + 眼下在干什么;
     * 干完是"思考过程 · N 步 · 用了哪些",有一步失败整行标失败色。只有一次调用、没有思考时
     * 那一行就是调用本身,没什么可展开的。
     */
    private void flushProcess(Feed f, Set<String> done, Set<String> failed, int chipMaxW) {
        List<Piece> ps = f.process;
        if (ps.isEmpty()) return;
        int textW = chipMaxW - PAD_H * 2 - ICON_W;
        long t = System.currentTimeMillis();
        List<LlmToolCall> calls = new ArrayList<>();
        boolean thought = false;
        for (Piece pc : ps) {
            if (pc.call() != null) calls.add(pc.call());
            else thought = true;
        }
        boolean liveThought = ps.get(ps.size() - 1).live();
        LlmToolCall runningCall = null;
        for (LlmToolCall tc : calls) {
            if (!done.contains(tc.id())) { runningCall = tc; break; }
        }
        List<ChipRow> rows = new ArrayList<>();
        String foldKey = null;
        if (!thought && calls.size() == 1) {
            rows.add(toolRow(calls.get(0), done, failed, t, textW));
        } else {
            foldKey = "proc#" + f.processWho + "#" + f.processEntry;
            boolean open = expandedGroups.contains(foldKey);
            boolean running = liveThought || runningCall != null;
            boolean anyFail = calls.stream().anyMatch(tc -> failed.contains(tc.id()));
            String head = running
                    ? (liveThought ? I18n.get("numen.chat.reasoning_now") : toolLine(runningCall))
                    : processSummary(thought, calls);
            rows.add(new ChipRow(running ? SPIN[(int) ((t / 120) % 4)] : (open ? "▾" : "▸"), running ? RUN : MUTED,
                    Nb.colored(fitOneLine(head, textW), anyFail && !running ? FAIL : TOOL).getVisualOrderText()));
            if (open) {
                for (Piece pc : ps) {
                    if (pc.call() != null) {
                        rows.add(toolRow(pc.call(), done, failed, t, textW));
                        continue;
                    }
                    String flat = flattened.computeIfAbsent(pc.thought(), s -> s.replaceAll("\\s+", " ").trim());
                    for (FormattedCharSequence line : split(Nb.colored(flat, FAINT), textW)) {
                        rows.add(new ChipRow(" ", MUTED, line));
                    }
                }
            }
        }
        boolean first = !f.processWho.equals(f.last);
        f.out.add(new Chip(List.copyOf(rows), foldKey, first ? label(f.processWho) : null,
                f.processWho, f.processEntry, false));
        f.last = f.processWho;
        ps.clear();
        f.processWho = null;
        f.processEntry = -1;
    }

    /** 干完的一段过程的摘要:"思考过程 · N 步 · 用了哪些",没有的那半不写。 */
    private String processSummary(boolean thought, List<LlmToolCall> calls) {
        List<String> parts = new ArrayList<>();
        if (thought) parts.add(I18n.get("numen.chat.reasoning"));
        if (!calls.isEmpty()) {
            parts.add(I18n.get("numen.chat.steps", calls.size()));
            for (LlmToolCall tc : calls) {
                String name = toolLabel(tc.name());
                if (!parts.contains(name)) parts.add(name);
            }
        }
        return String.join(" · ", parts);
    }

    private ChipRow toolRow(LlmToolCall tc, Set<String> done, Set<String> failed, long t, int textW) {
        boolean running = !done.contains(tc.id());
        boolean fail = failed.contains(tc.id());
        String icon = running ? SPIN[(int) ((t / 120) % 4)] : (fail ? "✗" : "✔");
        int ic = running ? RUN : (fail ? FAIL : OK);
        return new ChipRow(icon, ic,
                Nb.colored(fitOneLine(toolLine(tc), textW), fail ? FAIL : TOOL).getVisualOrderText());
    }

    // ---- drawing ----

    private void drawBlock(GuiGraphics g, Block b, int x, int y, int w) {
        // 新来的块飞入:从下面 8px 升上来、同时淡入。整块一起动——框、脸、字用同一个透明度
        int entry = switch (b) {
            case Bubble bb -> bb.entry();
            case Chip c -> c.entry();
            default -> -1;
        };
        float e = 1f;
        if (entry >= 0 && entry < born.size() && born.get(entry) > 0) {
            long age = frameNow - born.get(entry);
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
            case Notice n -> {
                FormattedCharSequence line = Nb.colored(fitOneLine(n.text(), w - SB_W), FAINT).getVisualOrderText();
                int tw = font.width(line);
                draw(g, line, x + (w - SB_W - tw) / 2, y);
            }
            case UnreadBar ignored -> {
                String t = I18n.get("numen.chat.unread_bar");
                g.fill(x, y, x + w - SB_W, y + LINE_H + 6, (CHIP_FILL & 0xFFFFFF) | (((CHIP_FILL >>> 24) / 2) << 24));
                draw(g, Nb.colored(t, MUTED).getVisualOrderText(), x + (w - SB_W - font.width(t)) / 2, y + 4);
            }
            case Bubble bb -> drawBubble(g, bb, x, y, w);
            case Chip c -> drawChip(g, c, x, y);
        }
    }

    /** 居中的日期小牌:一圈描边、极淡底,和工具行同一层的"这不是话"。对话里的和翻页时浮在顶上的是同一枚。 */
    private void drawDayChip(GuiGraphics g, String text, int x, int y, int w) {
        int tw = font.width(text);
        int bx = x + (w - SB_W - tw) / 2 - 5;
        NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), bx, y, tw + 10, LINE_H + 4,
                (CHIP_FILL & 0xFFFFFF) | (((CHIP_FILL >>> 24) / 2) << 24), TRACE_BAR);
        draw(g, Nb.colored(text, MUTED).getVisualOrderText(), bx + 5, y + 3);
    }

    private void drawBubble(GuiGraphics g, Bubble b, int x, int y, int w) {
        int bw = b.maxLineW() + PAD_H * 2;
        boolean timeLine = b.time() != null && !b.timeInline();
        int qh = b.quote() != null ? QUOTE_H : 0;
        int bh = qh + b.lines().size() * LINE_H + PAD_V * 2 + (timeLine ? TIME_H : 0);
        int bubTop = y + (b.label() != null ? LABEL_H : 0);
        // 自己的贴右缘;别人的在脸那一列右边(私聊没有那一列)
        int bx = b.own() ? x + w - EDGE - bw : x + EDGE + faceCol();
        if (b.label() != null) {
            draw(g, Nb.colored(b.label(), nameColor(b.who())).getVisualOrderText(), bx + 2, y);
        }
        if (b.showAvatar()) {
            // 脸贴在气泡底部(Telegram):这一块是这一组的最后一块,脸和最后一句齐底
            int avX = x + EDGE, avY = bubTop + bh - AV;
            CompanionFace.draw(g, b.who(), KnownSkins.of(b.who()), avX, avY, AV);
            face(g, b.who(), avX, avY);
        }
        // 气泡只有底色、没有描边(Telegram):和地面分开靠色块,不靠框线
        g.fill(bx, bubTop, bx + bw, bubTop + bh, b.fill());
        hits.add(new Hit(bx, bubTop, bw, bh, b));
        if (b.quote() != null) {
            // 引用条(Telegram 回复的样子):一道竖线、谁、那句。自己的气泡是强调色底,线和字往白里提
            int qx = bx + PAD_H, qy = bubTop + PAD_V;
            int ink = b.own() ? UiTheme.mix(b.fill(), 0xFFFFFFFF, 0.85f) : MENTION;
            int dim = b.own() ? UiTheme.mix(b.fill(), 0xFFFFFFFF, 0.55f) : FAINT;
            g.fill(qx, qy, qx + 2, qy + QUOTE_H - 3, ink);
            int room = bw - PAD_H * 2 - QUOTE_IN;
            draw(g, Nb.colored(fitOneLine(b.quote().who(), room), ink).getVisualOrderText(), qx + QUOTE_IN, qy);
            draw(g, Nb.colored(fitOneLine(b.quote().snippet(), room), dim).getVisualOrderText(), qx + QUOTE_IN, qy + 9);
        }
        int ty = bubTop + PAD_V + 1 + qh;
        for (FormattedCharSequence l : b.lines()) {
            draw(g, l, bx + PAD_H, ty);
            ty += LINE_H;
        }
        if (b.time() != null) {
            // 时间戳贴右下角:同一行就压在最后一行的右侧,否则在下面自己一小行
            int tx = bx + bw - PAD_H - font.width(b.time());
            int tyy = b.timeInline() ? ty - LINE_H + 1 : ty - 1;
            // 出向气泡是强调色底,时间戳用半透明白(Telegram);别人的用淡字
            draw(g, Nb.colored(b.time(), b.own() ? 0xB0FFFFFF : FAINT).getVisualOrderText(), tx, tyy);
        }
    }

    /**
     * 机器行(工具调用 / 思考过程)——刻意长得<b>不像对话</b>:左缘一条竖线 +
     * 极淡底,没有气泡的实底、描边与头像。过程与话分得开,读者才不会把旁白
     * 当成模型的输出。几何取自 {@link NumenStyle} 的 TRACE_* 令牌。
     */
    private void drawChip(GuiGraphics g, Chip c, int x, int y) {
        int maxW = 0;
        for (ChipRow r : c.rows()) maxW = Math.max(maxW, font.width(r.text()));
        int cx = x + EDGE + faceCol();
        if (c.label() != null) {
            // 她这一组的第一块:名字在上——和气泡同一套,谁在干活一眼认得出
            draw(g, Nb.colored(c.label(), nameColor(c.who())).getVisualOrderText(), cx + 2, y);
            y += LABEL_H;
        }
        if (c.showAvatar()) {
            // 这一组的最后一块:脸贴在它的底部
            int ch0 = c.rows().size() * LINE_H + PAD_V * 2;
            int avX = x + EDGE, avY = y + ch0 - AV;
            CompanionFace.draw(g, c.who(), KnownSkins.of(c.who()), avX, avY, AV);
            face(g, c.who(), avX, avY);
        }
        int cw = NumenStyle.TRACE_INDENT + ICON_W + maxW + PAD_H;
        int ch = c.rows().size() * LINE_H + PAD_V * 2;
        // 极淡底衬出块的范围(半透明再减半),左缘竖线是"这是过程"的记号
        int faintFill = (CHIP_FILL & 0xFFFFFF) | (((CHIP_FILL >>> 24) / 2) << 24);
        g.fill(cx, y, cx + cw, y + ch, faintFill);
        g.fill(cx, y, cx + NumenStyle.TRACE_BAR_W, y + ch, TRACE_BAR);
        int ty = y + PAD_V + 1;
        for (ChipRow r : c.rows()) {
            draw(g, Nb.colored(r.icon(), r.iconColor()).getVisualOrderText(),
                    cx + NumenStyle.TRACE_INDENT, ty);
            draw(g, r.text(), cx + NumenStyle.TRACE_INDENT + ICON_W, ty);
            ty += LINE_H;
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

    /**
     * 这个面板画的会话:成员各自的日志按会话印归并成一条时间线。单成员时就是她一本日志——
     * 同一条路,没有"单聊另一条路"。循环还没起来的成员这一刻没有记录可读,跳过。
     */
    private List<Transcript.Entry> transcript() {
        Conversations convos = Conversations.instance();
        Conversation c = conv.get();
        java.util.Map<UUID, List<ConvoLog.Line>> logs = new java.util.LinkedHashMap<>();
        for (UUID member : convos.membersAlive(c)) {
            AgentLoopRegistry.get(member).ifPresent(l -> logs.put(member, l.display()));
        }
        return Transcript.merge(convos.tagOf(c), logs);
    }

    /** 这次调用还在她那条循环的派发器手里没有——"还在跑"只问派发器,不从历史长什么样去猜。 */
    private static boolean outstanding(UUID companion, String callId) {
        return AgentLoopRegistry.get(companion).map(l -> l.isToolCallOutstanding(callId)).orElse(false);
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

    /** 气泡左边留给脸的那一列:群里要认人才有,私聊和外脑现场没有。 */
    private int faceCol() {
        return group ? AV + AV_GAP : 0;
    }

    private String fitOneLine(String s, int pxWidth) {
        if (font.width(s) <= pxWidth) return s;
        while (s.length() > 1 && font.width(s + "…") > pxWidth) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String toolLine(LlmToolCall tc) {
        String args = humanArgs(tc.arguments());
        return args.isEmpty() ? toolLabel(tc.name()) : toolLabel(tc.name()) + "  " + args;
    }

    /** 工具名 → 人话:约定键 {@code numen.tool.<name>};没有译文的(MCP 外部工具)原样显示。 */
    private static String toolLabel(String name) {
        String key = "numen.tool." + name;
        return I18n.exists(key) ? I18n.get(key) : name;
    }

    /** 参数 JSON → 人读摘要:抓最能说明这一步的名词(物品/方块/目标)、坐标、数量,
     *  最多三段;拿不出名词或解析不了就回落到压平截断的原文。 */
    private static String humanArgs(String json) {
        if (json == null || json.isBlank() || json.replaceAll("\\s+", "").equals("{}")) return "";
        com.google.gson.JsonObject o;
        try {
            o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return compactRaw(json);
        }
        List<String> parts = new ArrayList<>();
        try {
            for (String k : new String[]{"item", "item_id", "block", "block_id", "entity", "target",
                    "name", "skill", "structure", "biome", "recipe", "query", "text", "button", "slot"}) {
                if (parts.size() >= 2) break;
                var v = o.get(k);
                if (v != null && v.isJsonPrimitive()) parts.add(stripNs(v.getAsString()));
            }
            for (String k : new String[]{"block_ids", "items"}) {
                if (!parts.isEmpty()) break;
                var v = o.get(k);
                if (v != null && v.isJsonArray() && !v.getAsJsonArray().isEmpty()) {
                    var a = v.getAsJsonArray();
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < a.size() && i < 2; i++) {
                        if (i > 0) b.append('、');
                        b.append(stripNs(a.get(i).getAsString()));
                    }
                    if (a.size() > 2) b.append('…');
                    parts.add(b.toString());
                }
            }
            var count = o.get("count");
            if (count != null && count.isJsonPrimitive()) parts.add("×" + count.getAsString());
            if (o.has("x") && o.has("y") && o.has("z")) {
                parts.add("(" + o.get("x").getAsInt() + ", " + o.get("y").getAsInt()
                        + ", " + o.get("z").getAsInt() + ")");
            }
        } catch (RuntimeException ignored) { /* 结构不合预期:能抓多少是多少 */ }
        if (parts.isEmpty()) return compactRaw(json);
        return String.join(" · ", parts.subList(0, Math.min(3, parts.size())));
    }

    private static String stripNs(String s) {
        return s != null && s.startsWith("minecraft:") ? s.substring(10) : s;
    }

    private static String compactRaw(String json) {
        String args = json.replaceAll("\\s+", " ").trim();
        if (args.length() > TOOL_ARG_CHARS) args = args.substring(0, TOOL_ARG_CHARS) + "…";
        return args;
    }

}
