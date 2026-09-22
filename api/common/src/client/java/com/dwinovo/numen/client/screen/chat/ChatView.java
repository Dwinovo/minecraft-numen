package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.conversation.Conversation;
import com.dwinovo.numen.agent.conversation.Mentions;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.dwinovo.numen.client.skin.CompanionFace;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
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
    private static final int BLOCK_GAP = 6;
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
    private int AI_FILL, AI_BORDER, OWN_FILL, OWN_BORDER, QUEUED_FILL, QUEUED_BORDER, CHIP_FILL;
    /** 机器行的左缘竖线色(工具/思考过程共用)。 */
    private int TRACE_BAR;
    /** 主人话里 @ 到的名字。 */
    private int MENTION;

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
        OWN_BORDER = t.ownBorder();
        QUEUED_FILL = t.queuedFill();
        QUEUED_BORDER = t.queuedBorder();
        CHIP_FILL = t.chipFill();
        TRACE_BAR = t.surfaceBorder();
        MENTION = t.cta();
    }

    private static ResourceLocation spr(String n) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, n);
    }
    private static final ResourceLocation SCROLL_TRACK = spr("scroll_track");
    private static final ResourceLocation SCROLL_THUMB = spr("scroll_thumb");

    private final Font font;
    private final Supplier<Conversation> conv;

    // ---- scroll + fold state ----
    private float scrollPos;
    private int scrollTarget;
    private boolean pinBottom = true;
    private int lastMaxScroll;
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
        scrollPos = 0;
        scrollTarget = 0;
        pinBottom = true;
        lastFrameMs = 0;
        live.clear();
        expandedGroups.clear();
        splits.clear();
        flattened.clear();
        born.clear();
    }

    /** Re-pin to the bottom (a message was just sent). */
    public void pinToBottom() {
        pinBottom = true;
    }

    // ---- render ----

    public void render(GuiGraphics g, int x, int y, int w, int h) {
        loadPalette();
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        frameNow = now;
        updateLive(dt, now);
        renderBlocks(g, x, y, w, h, build(bubbleMaxW(w)), dt);
    }

    /** 滚动 + 裁剪 + 逐块绘制——对话视图与外脑现场视图共用的那台机器。 */
    private void renderBlocks(GuiGraphics g, int x, int y, int w, int h, List<Block> blocks, float dt) {
        gx = x; gy = y; gw = w; gh = h;
        int content = totalHeight(blocks);
        lastMaxScroll = Math.max(0, content - h);
        if (pinBottom) scrollTarget = lastMaxScroll;
        scrollTarget = Math.clamp(scrollTarget, 0, lastMaxScroll);
        scrollPos = Anim.approach(scrollPos, scrollTarget, SCROLL_RATE, dt);

        g.enableScissor(x, y, x + w, y + h);
        int cy = y + TOP_PAD - Math.round(scrollPos);
        for (Block b : blocks) {
            int bh = heightOf(b);
            if (cy + bh > y && cy < y + h) drawBlock(g, b, x, cy, w);
            cy += bh + BLOCK_GAP;
        }
        g.disableScissor();

        if (lastMaxScroll > 0) {
            int thumbH = Math.max(12, h * h / (h + lastMaxScroll));
            int thumbY = y + Math.round((h - thumbH) * (scrollPos / lastMaxScroll));
            g.blitSprite(SCROLL_TRACK, x + w - SB_W, y, SB_W, h);
            g.blitSprite(SCROLL_THUMB, x + w - SB_W, thumbY, SB_W, thumbH);
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
        long now = System.currentTimeMillis();
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
                    out.add(bubble(true, null, Nb.colored(ln.text(), TXT), OWN_FILL, OWN_BORDER,
                            innerW, first, null, null, -1));
                    last = OWNER;
                }
                case SAY -> {
                    boolean first = !id.equals(last);
                    out.add(bubble(false, first ? speaker(id) : null, Nb.colored(ln.text(), TXT),
                            AI_FILL, AI_BORDER, innerW, first, id, null, -1));
                    last = id;
                }
                case TOOL -> {
                    boolean first = !id.equals(last);
                    out.add(new Chip(List.of(new ChipRow(
                            ln.error() ? "✗" : "✔", ln.error() ? FAIL : OK,
                            Nb.colored(fitOneLine(ln.text(), chipTextW), ln.error() ? FAIL : TOOL)
                                    .getVisualOrderText())), null, first ? speaker(id) : null, id, -1));
                    last = id;
                }
            }
        }
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
        scrollTarget = Math.clamp((long) (scrollTarget - sy * LINE_H * 3), 0, lastMaxScroll);
        pinBottom = scrollTarget >= lastMaxScroll;
        return true;
    }

    /** Toggle the fold of a completed tool chip under the mouse. */
    public boolean mouseClicked(double mx, double my) {
        if (McpMode.instance().driving()) return false;   // 现场视图没有可折叠的块
        if (gw == 0 || mx < gx || mx >= gx + gw || my < gy || my >= gy + gh) return false;
        loadPalette();
        int cy = gy + TOP_PAD - Math.round(scrollPos);
        for (Block b : build(bubbleMaxW(gw))) {
            int bh = heightOf(b);
            if (b instanceof Chip c && c.foldKey() != null && my >= cy && my < cy + bh) {
                if (!expandedGroups.add(c.foldKey())) expandedGroups.remove(c.foldKey());
                return true;
            }
            cy += bh + BLOCK_GAP;
        }
        return false;
    }

    // ---- blocks ----

    private sealed interface Block permits Bubble, Chip, Notice, Divider {}

    /** One spoken message. {@code label} non-null = companion side (name above the bubble);
     *  {@code showAvatar} false = a consecutive message from the same side (head hidden);
     *  {@code who} = the companion whose face goes on it (null on the owner's side);
     *  {@code time} = 时间戳贴在气泡右下角(Telegram),放得进最后一行右侧就 {@code timeInline},
     *  放不进单独占一小行;{@code entry} = 归并后的记录序号,新来的按它飞入(-1 = 不飞)。 */
    private record Bubble(boolean own, String label, List<FormattedCharSequence> lines,
                          int maxLineW, int fill, int border,
                          boolean showAvatar, UUID who, String time, boolean timeInline, int entry) implements Block {}

    /** A run of tool calls (or a reasoning block). {@code foldKey} non-null = finished group,
     *  clickable to expand/fold. {@code who} = 干这些活的那只;{@code label} non-null = 她这一轮连发
     *  的第一块,脸和名字画在它上面——多人会话里工具行也得认得出是谁的。 */
    private record Chip(List<ChipRow> rows, String foldKey, String label, UUID who, int entry) implements Block {}

    /** 日期分隔:一天的第一条上面一枚居中的日期小牌(今天 / 昨天 / 几月几日)。 */
    private record Divider(String text) implements Block {}

    private record ChipRow(String icon, int iconColor, FormattedCharSequence text) {}

    /** A centred, faint system note. */
    /** 居中的一行提示(分隔、中断、整理中);画的时候按这一刻的宽度收口,长的切断原因不会画穿面板。 */
    private record Notice(String text) implements Block {}

    private int bubbleMaxW(int w) {
        return w - EDGE - AV - AV_GAP - OPP_MARGIN - SB_W - 3;
    }

    private int heightOf(Block b) {
        return switch (b) {
            case Bubble bb -> (bb.label() != null ? LABEL_H : 0) + bb.lines().size() * LINE_H + PAD_V * 2
                    + (bb.time() != null && !bb.timeInline() ? TIME_H : 0);
            case Chip c -> (c.label() != null ? LABEL_H : 0) + c.rows().size() * LINE_H + PAD_V * 2;
            case Notice ignored -> LINE_H;
            case Divider ignored -> LINE_H + 4;
        };
    }

    private int totalHeight(List<Block> blocks) {
        int sum = TOP_PAD + BOT_PAD;
        for (int i = 0; i < blocks.size(); i++) {
            sum += heightOf(blocks.get(i)) + (i > 0 ? BLOCK_GAP : 0);
        }
        return sum;
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
    /** 一次 build 的手头:输出、攒着的工具调用与它们的主人、连发的上一位。 */
    private static final class Feed {
        final List<Block> out = new ArrayList<>();
        final List<LlmToolCall> group = new ArrayList<>();
        UUID groupWho;
        int groupEntry = -1;
        UUID last;
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
                    flushTools(f, done, failed, bubbleMaxW);
                    out.add(new Divider(dayLabel(day)));
                    f.last = null;
                    lastDay = day;
                }
            }
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    flushTools(f, done, failed, bubbleMaxW);
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
                    out.add(bubble(true, null, mentionsLit(shown), OWN_FILL, OWN_BORDER, innerW, first, null,
                            clock(entry.ts()), msgIndex));
                    f.last = OWNER;
                }
                case ConvoState.Msg.Assistant a -> {
                    UUID who = entry.companion();
                    // 换了一只:前一只攒着的工具行先收口,两只的活不折进同一块
                    if (!who.equals(f.groupWho)) flushTools(f, done, failed, bubbleMaxW);
                    AssistantTurn turn = a.turn();
                    // 思考在说话之前:落库的思考默认折叠(它是过程不是结论,想看再展开)。
                    String reasoned = turn.reasoning();
                    if (reasoned != null && !reasoned.isBlank()) {
                        flushTools(f, done, failed, bubbleMaxW);
                        boolean first = !who.equals(f.last);
                        out.add(reasoningChip(reasoned, "reason#" + msgIndex, innerW, false,
                                first ? speaker(who) : null, who, msgIndex));
                        f.last = who;
                    }
                    String spoken = ChatDisplayModes.current().assistantText(turn.content());
                    if (!spoken.isBlank()) {
                        flushTools(f, done, failed, bubbleMaxW);   // spoken reply breaks the fold
                        boolean first = !who.equals(f.last);
                        out.add(bubble(false, first ? speaker(who) : null,
                                Nb.colored(spoken, TXT), AI_FILL, AI_BORDER, innerW, first, who,
                                clock(entry.ts()), msgIndex));
                        f.last = who;
                    }
                    f.group.addAll(turn.toolCalls());
                    f.groupWho = who;
                    f.groupEntry = msgIndex;
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a block */ }
                case ConvoState.Msg.Halt h -> {
                    flushTools(f, done, failed, bubbleMaxW);
                    notice(out, I18n.get("numen.chat.halted", h.reason()));
                    f.last = null;
                }
            }
        }
        flushTools(f, done, failed, bubbleMaxW);
        // 在飞的状态按成员各自的循环取:单成员就是她一个,多人各画各的。
        // 只画她此刻所在的会话里的:她在群里想着,私聊页不该也看见——和落库的行同一条印的规矩。
        String tag = Conversations.instance().tagOf(conv.get());
        java.util.Set<String> queued = new java.util.LinkedHashSet<>();
        boolean compacting = false;
        for (UUID her : Conversations.instance().membersAlive(conv.get())) {
            EntityAgentLoop lp = AgentLoopRegistry.get(her).orElse(null);
            if (lp == null || !java.util.Objects.equals(lp.conversation(), tag)) continue;
            // 在飞的思考流:展开着实时长(它正在发生,折起来就看不见了);回合落库后
            // 由上面那条 committed 的思考块接管,永不双份。
            String liveReasoning = lp.liveReasoning();
            if (!liveReasoning.isBlank()) {
                boolean first = !her.equals(f.last);
                out.add(reasoningChip(liveReasoning, null, innerW, true, first ? speaker(her) : null, her, -1));
                f.last = her;
            }
            // The in-flight reply, typed out live (chunk stream → EntityAgentLoop.livePartial).
            Live l = live.get(her);
            if (l != null && !l.shown.isEmpty()) {
                boolean first = !her.equals(f.last);
                out.add(bubble(false, first ? speaker(her) : null, Nb.colored(l.shown, TXT),
                        AI_FILL, AI_BORDER, innerW, first, her, null, -1));
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
        // Prompts still waiting for a protocol-valid splice point — visible immediately
        // so a queued message never feels swallowed.
        for (String shown : queued) {
            boolean first = !OWNER.equals(f.last);
            out.add(bubble(true, null, Nb.colored("⌛ " + shown, FAINT), QUEUED_FILL, QUEUED_BORDER,
                    innerW, first, null, null, -1));
            f.last = OWNER;
        }
        if (compacting) notice(out, I18n.get("numen.chat.compacting"));
        if (out.isEmpty()) {
            notice(out, I18n.get("numen.chat.empty", conv.get().displayName(NumenRoster.instance()::name)));
        }
        return out;
    }

    private Bubble bubble(boolean own, String label, Component body, int fill, int border,
                          int innerW, boolean showAvatar, UUID who, String time, int entry) {
        List<FormattedCharSequence> lines = split(body, innerW);
        int maxW = 0;
        for (FormattedCharSequence l : lines) maxW = Math.max(maxW, font.width(l));
        boolean inline = false;
        if (time != null) {
            // 时间戳挤在最后一行右侧(Telegram):放得下就同一行,放不下自己占一小行
            int tw = font.width(time) + 6;
            int last = lines.isEmpty() ? 0 : font.width(lines.get(lines.size() - 1));
            inline = last + tw <= innerW;
            maxW = Math.max(maxW, inline ? last + tw : tw);
        }
        return new Bubble(own, label, lines, maxW, fill, border, showAvatar, who, time, inline, entry);
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

    /** Emit the chip for a run of consecutive tool calls. A single call is one unfoldable chip;
     *  a run stays EXPANDED while any call still runs (live spinners) and auto-folds to a
     *  "N steps · names" summary once done — unless clicked open ({@link #expandedGroups}). */
    private void flushTools(Feed f, Set<String> done, Set<String> failed, int chipMaxW) {
        List<LlmToolCall> group = f.group;
        if (group.isEmpty()) return;
        int textW = chipMaxW - PAD_H * 2 - ICON_W;
        long t = System.currentTimeMillis();
        List<ChipRow> rows = new ArrayList<>();
        String foldKey = null;
        if (group.size() == 1) {
            rows.add(toolRow(group.get(0), done, failed, t, textW));
        } else {
            String key = group.get(0).id();
            boolean running = group.stream().anyMatch(tc -> !done.contains(tc.id()));
            if (!running) foldKey = key;
            if (running || expandedGroups.contains(key)) {
                if (!running) {
                    rows.add(new ChipRow("▾", MUTED,
                            Nb.colored(I18n.get("numen.chat.steps", group.size()), MUTED).getVisualOrderText()));
                }
                for (LlmToolCall tc : group) rows.add(toolRow(tc, done, failed, t, textW));
            } else {
                List<String> names = new ArrayList<>();
                for (LlmToolCall tc : group) {
                    String label = toolLabel(tc.name());
                    if (!names.contains(label)) names.add(label);
                }
                boolean anyFail = group.stream().anyMatch(tc -> failed.contains(tc.id()));
                String summary = I18n.get("numen.chat.steps", group.size())
                        + " · " + String.join(" · ", names) + " ▸";
                rows.add(new ChipRow(anyFail ? "✗" : "✔", anyFail ? FAIL : OK,
                        Nb.colored(fitOneLine(summary, textW), anyFail ? FAIL : TOOL).getVisualOrderText()));
            }
        }
        boolean first = !f.groupWho.equals(f.last);
        f.out.add(new Chip(List.copyOf(rows), foldKey, first ? speaker(f.groupWho) : null, f.groupWho, f.groupEntry));
        f.last = f.groupWho;
        group.clear();
        f.groupWho = null;
        f.groupEntry = -1;
    }

    /**
     * 思考块:与工具 chip 同一形制(同样的行、同样的折叠交互),只是内容是
     * 推理文本。{@code live}=在飞,展开着实时长且不可折(正在发生的事折起来
     * 就看不见);已落库的默认折叠成一行摘要,点开看全文——它是过程不是结论。
     */
    private Chip reasoningChip(String text, String foldKey, int innerW, boolean live, String label, UUID who, int entry) {
        List<ChipRow> rows = new ArrayList<>();
        boolean expanded = live || (foldKey != null && expandedGroups.contains(foldKey));
        if (!expanded) {
            // 不报字数:中英混排的 length() 一半是字一半是字符,数出来没有意义。
            rows.add(new ChipRow("▸", MUTED,
                    Nb.colored(I18n.get("numen.chat.reasoning") + " ▸", MUTED).getVisualOrderText()));
            return new Chip(List.copyOf(rows), foldKey, label, who, entry);
        }
        rows.add(new ChipRow(live ? SPIN[(int) ((System.currentTimeMillis() / 120) % 4)] : "▾", MUTED,
                Nb.colored(I18n.get("numen.chat.reasoning"), MUTED).getVisualOrderText()));
        String flat = flattened.computeIfAbsent(text, t -> t.replaceAll("\\s+", " ").trim());
        for (FormattedCharSequence line : split(Nb.colored(flat, FAINT), innerW - ICON_W)) {
            rows.add(new ChipRow(" ", MUTED, line));
        }
        return new Chip(List.copyOf(rows), foldKey, label, who, entry);
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
            case Divider d -> {
                // 居中的日期小牌:一圈描边、极淡底,和工具行同一层的"这不是话"
                int tw = font.width(d.text());
                int bx = x + (w - SB_W - tw) / 2 - 5;
                NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), bx, y, tw + 10, LINE_H + 4,
                        (CHIP_FILL & 0xFFFFFF) | (((CHIP_FILL >>> 24) / 2) << 24), TRACE_BAR);
                draw(g, Nb.colored(d.text(), MUTED).getVisualOrderText(), bx + 5, y + 3);
            }
            case Notice n -> {
                FormattedCharSequence line = Nb.colored(fitOneLine(n.text(), w - SB_W), FAINT).getVisualOrderText();
                int tw = font.width(line);
                draw(g, line, x + (w - SB_W - tw) / 2, y);
            }
            case Bubble bb -> drawBubble(g, bb, x, y, w);
            case Chip c -> drawChip(g, c, x, y);
        }
    }

    private void drawBubble(GuiGraphics g, Bubble b, int x, int y, int w) {
        int bw = b.maxLineW() + PAD_H * 2;
        boolean timeLine = b.time() != null && !b.timeInline();
        int bh = b.lines().size() * LINE_H + PAD_V * 2 + (timeLine ? TIME_H : 0);
        int bubTop = y + (b.label() != null ? LABEL_H : 0);
        int avX, bx;
        if (b.own()) {
            avX = x + w - SB_W - 3 - AV;
            bx = avX - AV_GAP - bw;
        } else {
            avX = x + EDGE;
            bx = x + EDGE + AV + AV_GAP;
        }
        if (b.label() != null) {
            draw(g, Nb.colored(b.label(), MUTED).getVisualOrderText(), bx + 2, y);
        }
        if (b.showAvatar()) {
            // 头像框纯代码绘制,继承所在气泡的配色——AI/主人两侧色调天然分明,且跟主题走。
            NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), avX - 2, bubTop - 2,
                    AV + 4, AV + 4, b.fill(), b.border());
            // 主人自己那侧画的是玩家本人,不是同伴——改外观的插件不该接管它
            if (b.own()) {
                PlayerFaceRenderer.draw(g, ownerSkin(), avX, bubTop, AV);
            } else {
                CompanionFace.draw(g, b.who(), KnownSkins.of(b.who()), avX, bubTop, AV);
            }
        }
        NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), bx, bubTop, bw, bh,
                b.fill(), b.border());
        int ty = bubTop + PAD_V + 1;
        for (FormattedCharSequence l : b.lines()) {
            draw(g, l, bx + PAD_H, ty);
            ty += LINE_H;
        }
        if (b.time() != null) {
            // 时间戳贴右下角:同一行就压在最后一行的右侧,否则在下面自己一小行
            int tx = bx + bw - PAD_H - font.width(b.time());
            int tyy = b.timeInline() ? ty - LINE_H + 1 : ty - 1;
            draw(g, Nb.colored(b.time(), FAINT).getVisualOrderText(), tx, tyy);
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
        int cx = x + EDGE + AV + AV_GAP;
        if (c.label() != null) {
            // 她这一轮的第一块:名字在上、脸在左——和气泡同一套,所以谁在干活一眼认得出
            draw(g, Nb.colored(c.label(), MUTED).getVisualOrderText(), cx + 2, y);
            y += LABEL_H;
            int avX = x + EDGE;
            NumenStyle.box(new com.dwinovo.numen.client.ui.mc.McDrawSurface(g, font), avX - 2, y - 2,
                    AV + 4, AV + 4, AI_FILL, AI_BORDER);
            CompanionFace.draw(g, c.who(), KnownSkins.of(c.who()), avX, y, AV);
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

    /** Shadowless draw — the colour is baked into the sequence's Style (see {@link Nb}). */
    private void draw(GuiGraphics g, FormattedCharSequence seq, int x, int y) {
        Nb.text(g, font, seq, x, y);
    }

    private static PlayerSkin ownerSkin() {
        AbstractClientPlayer p = Minecraft.getInstance().player;
        return p != null ? p.getSkin() : DefaultPlayerSkin.get(OWNER);
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
