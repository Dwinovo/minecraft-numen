package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.dwinovo.numen.client.ui.mc.Sprites;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 她的计划(最近一次 {@code todowrite})。收起时不写字:状态行里一枚清单图标 + 一排小格,一格一条待办,
 * 做完的实色、正在做的呼吸、没做的淡——几步、走到哪,看一眼就知道;悬停才出那一步的字,点开才从状态行
 * 往上长、盖在对话流上展开成清单。没计划时一个像素不占。
 *
 * <p>读的是物理记录,所以压缩过上下文计划也还在。
 */
public final class PlanStrip {

    private static final int LINE_H = 10;
    private static final int PAD = 7;
    /** 一格一条待办:宽 5 高 4、隔 1。超过 {@link #MAX_SEGS} 条就改画一条按比例填的横条,不然摆不下。 */
    private static final int SEG_W = 5;
    private static final int SEG_H = 4;
    private static final int SEG_GAP = 1;
    private static final int MAX_SEGS = 12;
    private static final int BAR_W = 48;

    private PlanStrip() {}

    /**
     * 收起态:在 {@code (x, y)} 画图标 + 小格,最多占 {@code maxW} 宽。返回实际占的宽——没计划是 0。
     *
     * @param open 展开着(图标亮强调色),清单由 {@link #renderOpen} 另画
     */
    public static int render(GuiGraphics g, EntityAgentLoop loop, int x, int y, int maxW, boolean open, long nowMs) {
        JsonArray todos = latestPlan(loop);
        if (todos == null || todos.isEmpty() || maxW < Sprites.SIZE) {
            return 0;
        }
        UiTheme th = UiTheme.current();
        Sprites.draw(g, Sprites.PLAN, x, y, Sprites.SIZE, open ? th.cta() : th.textDim());
        int bx = x + Sprites.SIZE + 4;
        int by = y + (Sprites.SIZE - SEG_H) / 2;
        int n = todos.size();
        int room = maxW - (Sprites.SIZE + 4);
        if (n <= MAX_SEGS && n * (SEG_W + SEG_GAP) - SEG_GAP <= room) {
            for (int i = 0; i < n; i++) {
                String status = todos.get(i).isJsonObject() ? str(todos.get(i).getAsJsonObject(), "status") : "";
                int color = switch (status) {
                    case "completed" -> th.ok();
                    case "in_progress" -> pulse(th.run(), nowMs);
                    default -> th.faint();
                };
                int sx = bx + i * (SEG_W + SEG_GAP);
                g.fill(sx, by, sx + SEG_W, by + SEG_H, color);
            }
            return Sprites.SIZE + 4 + n * (SEG_W + SEG_GAP) - SEG_GAP;
        }
        if (room < BAR_W) {
            return Sprites.SIZE;   // 只放得下图标
        }
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (todos.get(i).isJsonObject() && "completed".equals(str(todos.get(i).getAsJsonObject(), "status"))) done++;
        }
        g.fill(bx, by, bx + BAR_W, by + SEG_H, th.faint());
        g.fill(bx, by, bx + BAR_W * done / n, by + SEG_H, th.ok());
        return Sprites.SIZE + 4 + BAR_W;
    }

    /** 悬停时那一句:{@code 2/5 · 正在做的那步};没计划是 null。 */
    public static String currentStep(EntityAgentLoop loop) {
        JsonArray todos = latestPlan(loop);
        if (todos == null || todos.isEmpty()) {
            return null;
        }
        int done = 0;
        String current = null;
        for (int i = 0; i < todos.size(); i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            if ("completed".equals(status)) done++;
            else if (current == null && ("in_progress".equals(status) || "pending".equals(status))) {
                current = str(it, "content");
            }
        }
        return done + "/" + todos.size() + (current == null || current.isBlank() ? "" : " · " + current);
    }

    /** 呼吸:透明度在 0x90–0xFF 之间来回,"正在做"是活的。 */
    private static int pulse(int argb, long nowMs) {
        int a = 0x90 + (int) (0x6F * (0.5 + 0.5 * Math.sin(nowMs / 250.0)));
        return (argb & 0xFFFFFF) | (a << 24);
    }

    /**
     * 展开态:清单从 {@code bottom} 往上长、盖在对话流上;高度贴内容,顶不过 {@code top}。
     * 每条前面是和状态行同一套的色块(做完实色、正在做呼吸、没做淡),不用字符当图标。
     *
     * @param shownH 这一帧露出多高(展开/收起的过渡由宿主按帧推进);超出内容高按内容高算
     * @return 内容的完整高度——宿主拿它当过渡的目标
     */
    public static int renderOpen(GuiGraphics g, Font font, EntityAgentLoop loop, int x, int w, int bottom, int top,
                                 int shownH, long nowMs) {
        JsonArray todos = latestPlan(loop);
        if (todos == null || todos.isEmpty()) {
            return 0;
        }
        UiTheme th = UiTheme.current();
        int TXT = th.text(), MUTED = th.textDim(), FAINT = th.faint();
        int ix = x + PAD;
        int iw = w - PAD * 2 - 12;
        // 先量后画:框贴内容,顶不过 top
        int contentH = PAD;
        for (int i = 0; i < todos.size(); i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            int n = font.split(Nb.colored(str(it, "content"), TXT), iw).size();
            contentH += Math.max(1, Math.min(2, n)) * LINE_H;
            if (bottom - (contentH + PAD) <= top) break;
        }
        int fullH = Math.min(bottom - top, contentH + PAD - 2);
        int h = Math.min(fullH, shownH);
        if (h <= 0) {
            return fullH;
        }
        // 过渡时只露下面这一截:框从状态行往上长,里面的行跟着框的顶边一起露出来
        int y = bottom - h;
        g.enableScissor(x, y, x + w, bottom);
        int boxY = bottom - fullH;
        NumenStyle.box(new McDrawSurface(g, font), x, boxY, w, fullH, th.aiFill(), th.aiBorder());
        int ly = boxY + PAD;
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            int mark = switch (status) {
                case "completed" -> th.ok();
                case "in_progress" -> pulse(th.run(), nowMs);
                default -> th.faint();
            };
            g.fill(ix, ly + 2, ix + 6, ly + 8, mark);
            // 层次:正在做的最亮,做完的退后,还没做的最淡
            int textColor = switch (status) {
                case "in_progress" -> TXT;
                case "completed" -> MUTED;
                default -> FAINT;
            };
            List<FormattedCharSequence> lines = font.split(Nb.colored(content, textColor), iw);
            int sub = 0;
            for (FormattedCharSequence seq : lines) {
                if (ly + LINE_H >= bottom) break;
                Nb.text(g, font, seq, ix + 12, ly);
                ly += LINE_H;
                if (++sub >= 2) break;   // 每条最多两行
            }
            if (lines.isEmpty()) ly += LINE_H;
        }
        g.disableScissor();
        return fullH;
    }

    /** 最近一次 todowrite 的 todos;没有则 null。 */
    private static JsonArray latestPlan(EntityAgentLoop loop) {
        JsonArray latest = null;
        for (com.dwinovo.numen.agent.llm.ConvoLog.Line line : loop.display()) {
            if (line.msg() instanceof ConvoState.Msg.Assistant a) {
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
}
