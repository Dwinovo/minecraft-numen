package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.llm.ConvoLog;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.ClientNumenLookup;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.chat.ChatDisplayModes;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.Anim;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.RoundRect;
import com.dwinovo.numen.mcp.server.McpMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The chat transcript as a conversation: the owner's messages are right-aligned
 * bubbles, the companion's replies left-aligned bubbles — both with their real
 * skin avatar — and a run of consecutive tool calls folds into one rounded chip
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
    private static final int RADIUS = 4;
    private static final int ICON_W = 11;       // chip status-icon column
    private static final int TOOL_ARG_CHARS = 44;
    private static final float SCROLL_RATE = 14f;
    /** Typewriter reveal speed (chars/s) and the max lag before it jumps to catch up. */
    private static final float REVEAL_CPS = 80f;
    private static final int REVEAL_MAX_LAG = 120;
    private static final String[] SPIN = {"|", "/", "-", "\\"};

    // ---- palette: re-read from the CURRENT theme each frame (loadPalette), so the
    // Settings picker recolours the transcript live. Field names keep the constant
    // convention — they behave as constants within a frame. ----
    private int TOOL, MUTED, FAINT, OK, RUN, FAIL, TXT;
    private int AI_FILL, AI_BORDER, OWN_FILL, OWN_BORDER, QUEUED_FILL, QUEUED_BORDER, CHIP_FILL;
    /** 机器行的左缘竖线色(工具/思考过程共用)。 */
    private int TRACE_BAR;

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
    }

    private static ResourceLocation spr(String n) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, n);
    }
    private static final ResourceLocation SCROLL_TRACK = spr("scroll_track");
    private static final ResourceLocation SCROLL_THUMB = spr("scroll_thumb");

    private final Font font;
    private final Supplier<EntityAgentLoop> loop;
    private final Supplier<String> name;
    private final Supplier<UUID> uuid;

    // ---- scroll + fold state ----
    private float scrollPos;
    private int scrollTarget;
    private boolean pinBottom = true;
    private int lastMaxScroll;
    private long lastFrameMs;
    /** Typewriter state: how much of the live partial is revealed, and the string
     *  (text + blinking caret) the current frame shows — build() reads the cache so
     *  click-time rebuilds see the same geometry. */
    private float revealed;
    private String liveShown = "";
    /** Completed tool-call groups the user clicked open (keyed by the group's first call id). */
    private final Set<String> expandedGroups = new HashSet<>();
    // geometry of the last render, for click / wheel hit-testing
    private int gx, gy, gw, gh;

    public ChatView(Font font, Supplier<EntityAgentLoop> loop, Supplier<String> name, Supplier<UUID> uuid) {
        this.font = font;
        this.loop = loop;
        this.name = name;
        this.uuid = uuid;
    }

    /** Forget scroll + fold state (companion or tab switch). */
    public void reset() {
        scrollPos = 0;
        scrollTarget = 0;
        pinBottom = true;
        lastFrameMs = 0;
        revealed = 0;
        liveShown = "";
        expandedGroups.clear();
    }

    /** Re-pin to the bottom (a message was just sent). */
    public void pinToBottom() {
        pinBottom = true;
    }

    // ---- render ----

    public void render(GuiGraphics g, int x, int y, int w, int h) {
        gx = x; gy = y; gw = w; gh = h;
        loadPalette();
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;
        updateLive(dt, now);
        List<Block> blocks = build(bubbleMaxW(w));
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
            g.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, SCROLL_TRACK, x + w - SB_W, y, SB_W, h);
            g.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, SCROLL_THUMB, x + w - SB_W, thumbY, SB_W, thumbH);
        }
    }

    // ---- 外接大脑控制台:模式开启时顶掉整条对话流 ----

    /**
     * 「外接大脑」模式下的聊天区形态:这不是对话,是一台控制台——顶部写清谁接进来了,
     * 下面滚外部 AI 的工具调用。故意长得和聊天完全不一样(无气泡、无头像、等宽两行一条),
     * 让主人一眼知道"这屏不是我在跟同伴说话,是别的 AI 在开她"。
     *
     * <p>数据来自 {@link McpMode.ActivityFeed} 的只读快照(HTTP 线程写、这里读),
     * 纯内存不持久化;还没人接入时这里改显示接入向导。
     */
    public void renderConsole(GuiGraphics g, int x, int y, int w, int h) {
        loadPalette();
        McpMode mcp = McpMode.instance();
        int cx = x + EDGE;
        int cw = w - EDGE - SB_W - 3;

        g.enableScissor(x, y, x + w, y + h);
        line(g, I18n.get("numen.brain.console_title"), cx, y + 2, TXT);
        String who = mcp.clientName();
        line(g, who == null
                        ? I18n.get("numen.brain.status_waiting")
                        : I18n.get("numen.brain.status_connected", who, sinceLabel(mcp.lastActivityMs())),
                cx, y + 14, who == null ? FAINT : OK);
        g.fill(cx, y + 26, cx + cw, y + 27, CHIP_FILL);

        List<McpMode.Activity> feed = mcp.feed().snapshot();
        int listTop = y + 32;
        if (feed.isEmpty()) {
            renderConsoleGuide(g, mcp, cx, listTop, cw, who != null);
            g.disableScissor();
            return;
        }
        // 自底向上画:最新一条钉在底部,超出上沿的自然被裁掉(不做滚动——这是活动流,
        // 要看历史去日志;面板只答"现在在干嘛")。
        int rowH = LINE_H * 2 + 2;
        int cy = y + h - BOT_PAD - rowH;
        for (int i = feed.size() - 1; i >= 0 && cy >= listTop; i--, cy -= rowH) {
            McpMode.Activity a = feed.get(i);
            String head = (a.error() ? "✗ " : "✔ ") + a.tool()
                    + (a.args().isBlank() ? "" : "  " + a.args());
            line(g, fitOneLine(head, cw), cx, cy, a.error() ? FAIL : TXT);
            line(g, fitOneLine("→ " + a.summary(), cw - 6), cx + 6, cy + LINE_H, FAINT);
        }
        g.disableScissor();
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

    private sealed interface Block permits Bubble, Chip, Notice {}

    /** One spoken message. {@code label} non-null = companion side (name above the bubble);
     *  {@code showAvatar} false = a consecutive message from the same side (head hidden). */
    private record Bubble(boolean own, String label, List<FormattedCharSequence> lines,
                          int maxLineW, int textColor, int fill, int border,
                          boolean showAvatar) implements Block {}

    /** A run of tool calls. {@code foldKey} non-null = finished group, clickable to expand/fold. */
    private record Chip(List<ChipRow> rows, String foldKey) implements Block {}

    private record ChipRow(String icon, int iconColor, FormattedCharSequence text) {}

    /** A centred, faint system note. */
    private record Notice(FormattedCharSequence text) implements Block {}

    private int bubbleMaxW(int w) {
        return w - EDGE - AV - AV_GAP - OPP_MARGIN - SB_W - 3;
    }

    private int heightOf(Block b) {
        return switch (b) {
            case Bubble bb -> (bb.label() != null ? LABEL_H : 0) + bb.lines().size() * LINE_H + PAD_V * 2;
            case Chip c -> c.rows().size() * LINE_H + PAD_V * 2;
            case Notice ignored -> LINE_H;
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
    private List<Block> build(int bubbleMaxW) {
        List<Block> out = new ArrayList<>();
        EntityAgentLoop lp = loop.get();
        List<ConvoState.Msg> source = lp.display();
        Set<String> done = new HashSet<>();
        Set<String> failed = new HashSet<>();
        for (ConvoState.Msg m : source) {
            if (m instanceof ConvoState.Msg.Tool t) {
                done.add(t.toolCallId());
                // 成败判据的单一真源(展示层不猜字符串)。
                if (com.dwinovo.numen.agent.llm.ToolOutcome.failed(t.content())) {
                    failed.add(t.toolCallId());
                }
            }
        }
        int innerW = bubbleMaxW - PAD_H * 2;
        List<LlmToolCall> group = new ArrayList<>();
        // Consecutive same-side messages group like a chat app: avatar + name only on
        // the first of a run. Chips don't break a run; notices do. null = run broken.
        Boolean lastSide = null;
        int msgIndex = -1;
        for (ConvoState.Msg msg : source) {
            msgIndex++;
            switch (msg) {
                case ConvoState.Msg.User u -> {
                    flushTools(out, group, done, failed, bubbleMaxW);
                    if (ConvoLog.PERSONA_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.persona_changed"));
                        lastSide = null;
                        continue;
                    }
                    if (ConvoLog.COMPACT_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.compacted"));
                        lastSide = null;
                        continue;
                    }
                    if (ConvoLog.CLEAR_DIVIDER.equals(u.content())) {
                        notice(out, I18n.get("numen.chat.cleared"));
                        lastSide = null;
                        continue;
                    }
                    String shown = ownerText(u.content());   // owner's words only, never injected content
                    if (shown.isEmpty()) continue;
                    boolean first = lastSide == null || !lastSide;
                    out.add(bubble(true, null, shown, TXT, OWN_FILL, OWN_BORDER, innerW, first));
                    lastSide = true;
                }
                case ConvoState.Msg.Assistant a -> {
                    AssistantTurn turn = a.turn();
                    // 思考在说话之前:落库的思考默认折叠(它是过程不是结论,想看再展开)。
                    String reasoned = turn.reasoning();
                    if (reasoned != null && !reasoned.isBlank()) {
                        flushTools(out, group, done, failed, bubbleMaxW);
                        out.add(reasoningChip(reasoned, "reason#" + msgIndex, innerW, false));
                    }
                    String spoken = ChatDisplayModes.current().assistantText(turn.content());
                    if (!spoken.isBlank()) {
                        flushTools(out, group, done, failed, bubbleMaxW);   // spoken reply breaks the fold
                        boolean first = lastSide == null || lastSide;
                        out.add(bubble(false, first ? name.get() : null, spoken,
                                TXT, AI_FILL, AI_BORDER, innerW, first));
                        lastSide = false;
                    }
                    group.addAll(turn.toolCalls());
                }
                case ConvoState.Msg.Tool ignored -> { /* result drives done/fail, not a block */ }
            }
        }
        flushTools(out, group, done, failed, bubbleMaxW);
        // 在飞的思考流:展开着实时长(它正在发生,折起来就看不见了);回合落库后
        // 由上面那条 committed 的思考块接管,永不双份。
        String liveReasoning = lp.liveReasoning();
        if (!liveReasoning.isBlank()) {
            out.add(reasoningChip(liveReasoning, null, innerW, true));
        }
        // The in-flight reply, typed out live (chunk stream → EntityAgentLoop.livePartial).
        if (!liveShown.isEmpty()) {
            boolean first = lastSide == null || lastSide;
            out.add(bubble(false, first ? name.get() : null, liveShown,
                    TXT, AI_FILL, AI_BORDER, innerW, first));
            lastSide = false;
        }
        // Prompts still waiting for a protocol-valid splice point — visible immediately
        // so a queued message never feels swallowed.
        for (String queued : lp.queuedPrompts()) {
            String shown = ownerText(queued);
            if (shown.isEmpty()) continue;
            boolean first = lastSide == null || !lastSide;
            out.add(bubble(true, null, "⌛ " + shown, FAINT, QUEUED_FILL, QUEUED_BORDER, innerW, first));
            lastSide = true;
        }
        if (lp.isCompacting()) notice(out, I18n.get("numen.chat.compacting"));
        if (out.isEmpty()) notice(out, I18n.get("numen.chat.empty", name.get()));
        return out;
    }

    private Bubble bubble(boolean own, String label, String text, int color, int fill, int border,
                          int innerW, boolean showAvatar) {
        List<FormattedCharSequence> lines = font.split(Nb.colored(text, color), innerW);
        int maxW = 0;
        for (FormattedCharSequence l : lines) maxW = Math.max(maxW, font.width(l));
        return new Bubble(own, label, lines, maxW, color, fill, border, showAvatar);
    }

    /** Advance the typewriter: filter the live partial, ease the reveal toward the
     *  full length, and cache "revealed text + blinking caret". */
    private void updateLive(float dt, long now) {
        String full = ChatDisplayModes.current().assistantText(loop.get().livePartial());
        if (full.isEmpty()) {
            revealed = 0;
            liveShown = "";
            return;
        }
        if (revealed > full.length()) revealed = full.length();
        if (full.length() - revealed > REVEAL_MAX_LAG) revealed = full.length() - REVEAL_MAX_LAG;
        revealed = Math.min(full.length(), revealed + dt * REVEAL_CPS);
        liveShown = cut(full, (int) revealed) + (((now / 500) & 1) == 0 ? "_" : "");
    }

    /** Cut at {@code n} chars without splitting a surrogate pair. */
    private static String cut(String s, int n) {
        if (n >= s.length()) return s;
        if (n > 0 && Character.isHighSurrogate(s.charAt(n - 1))) n--;
        return s.substring(0, n);
    }

    private void notice(List<Block> out, String text) {
        out.add(new Notice(Nb.colored(text, FAINT).getVisualOrderText()));
    }

    /** Emit the chip for a run of consecutive tool calls. A single call is one unfoldable chip;
     *  a run stays EXPANDED while any call still runs (live spinners) and auto-folds to a
     *  "N steps · names" summary once done — unless clicked open ({@link #expandedGroups}). */
    private void flushTools(List<Block> out, List<LlmToolCall> group,
                            Set<String> done, Set<String> failed, int chipMaxW) {
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
        out.add(new Chip(List.copyOf(rows), foldKey));
        group.clear();
    }

    /**
     * 思考块:与工具 chip 同一形制(同样的行、同样的折叠交互),只是内容是
     * 推理文本。{@code live}=在飞,展开着实时长且不可折(正在发生的事折起来
     * 就看不见);已落库的默认折叠成一行摘要,点开看全文——它是过程不是结论。
     */
    private Chip reasoningChip(String text, String foldKey, int innerW, boolean live) {
        String flat = text.replaceAll("\\s+", " ").trim();
        List<ChipRow> rows = new ArrayList<>();
        boolean expanded = live || (foldKey != null && expandedGroups.contains(foldKey));
        if (!expanded) {
            // 不报字数:中英混排的 length() 一半是字一半是字符,数出来没有意义。
            rows.add(new ChipRow("▸", MUTED,
                    Nb.colored(I18n.get("numen.chat.reasoning") + " ▸", MUTED).getVisualOrderText()));
            return new Chip(List.copyOf(rows), foldKey);
        }
        rows.add(new ChipRow(live ? SPIN[(int) ((System.currentTimeMillis() / 120) % 4)] : "▾", MUTED,
                Nb.colored(I18n.get("numen.chat.reasoning"), MUTED).getVisualOrderText()));
        for (FormattedCharSequence line : font.split(Nb.colored(flat, FAINT), innerW - ICON_W)) {
            rows.add(new ChipRow(" ", MUTED, line));
        }
        return new Chip(List.copyOf(rows), foldKey);
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
        switch (b) {
            case Notice n -> {
                int tw = font.width(n.text());
                draw(g, n.text(), x + (w - SB_W - tw) / 2, y);
            }
            case Bubble bb -> drawBubble(g, bb, x, y, w);
            case Chip c -> drawChip(g, c, x, y);
        }
    }

    private void drawBubble(GuiGraphics g, Bubble b, int x, int y, int w) {
        int bw = b.maxLineW() + PAD_H * 2;
        int bh = b.lines().size() * LINE_H + PAD_V * 2;
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
            RoundRect.card(g, avX - 2, bubTop - 2, avX + AV + 2, bubTop + AV + 2, 3,
                    b.fill(), b.border());
            PlayerFaceRenderer.draw(g, skin(b.own()), avX, bubTop, AV);
        }
        RoundRect.card(g, bx, bubTop, bx + bw, bubTop + bh, RADIUS, b.fill(), b.border());
        int ty = bubTop + PAD_V + 1;
        for (FormattedCharSequence l : b.lines()) {
            draw(g, l, bx + PAD_H, ty);
            ty += LINE_H;
        }
    }

    /**
     * 机器行(工具调用 / 思考过程)——刻意长得<b>不像对话</b>:左缘一条竖线 +
     * 极淡底,没有气泡的实底、圆角与头像。过程与话分得开,读者才不会把旁白
     * 当成模型的输出。几何取自 {@link NumenStyle} 的 TRACE_* 令牌。
     */
    private void drawChip(GuiGraphics g, Chip c, int x, int y) {
        int maxW = 0;
        for (ChipRow r : c.rows()) maxW = Math.max(maxW, font.width(r.text()));
        int cx = x + EDGE + AV + AV_GAP;
        int cw = NumenStyle.TRACE_INDENT + ICON_W + maxW + PAD_H;
        int ch = c.rows().size() * LINE_H + PAD_V * 2;
        // 极淡底衬出块的范围(半透明再减半),左缘竖线是"这是过程"的记号
        int faintFill = (CHIP_FILL & 0xFFFFFF) | (((CHIP_FILL >>> 24) / 2) << 24);
        RoundRect.fill(g, cx, y, cx + cw, y + ch, NumenStyle.TRACE_RADIUS, faintFill);
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

    private PlayerSkin skin(boolean own) {
        if (own) {
            AbstractClientPlayer p = Minecraft.getInstance().player;
            if (p != null) return p.getSkin();
            return DefaultPlayerSkin.get(uuid.get());
        }
        return com.dwinovo.numen.client.agent.KnownSkins.of(uuid.get());
    }

    // ---- text helpers ----

    private static String ownerText(String s) {
        return ChatDisplayModes.current().userText(s);
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
