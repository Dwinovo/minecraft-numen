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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 她的目标与计划(最近一次 {@code todowrite}),画在对话流顶上那条置顶条里(Telegram 的置顶消息):
 * 收起时一排小格,一格一条待办,做完的实色、正在做的呼吸、没做的淡;点开从置顶条往下长,
 * 先是目标,再是整份清单,盖在对话流上。
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

    /** 计划走到哪了:做完几条、一共几条、正在做(或下一条要做)的那条;没有计划时 {@link #progress} 给 null。 */
    public record Progress(int done, int total, String current) {}

    public static Progress progress(EntityAgentLoop loop) {
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
        return new Progress(done, todos.size(), current == null || current.isBlank() ? null : current);
    }

    /** 一排小格(太多就是一条按比例填的横条),从 {@code (x, y)} 起、最多 {@code maxW} 宽;返回实际占的宽。 */
    public static int segments(GuiGraphics g, EntityAgentLoop loop, int x, int y, int maxW, long nowMs) {
        JsonArray todos = latestPlan(loop);
        if (todos == null || todos.isEmpty()) {
            return 0;
        }
        UiTheme th = UiTheme.current();
        int n = todos.size();
        if (n <= MAX_SEGS && n * (SEG_W + SEG_GAP) - SEG_GAP <= maxW) {
            for (int i = 0; i < n; i++) {
                String status = todos.get(i).isJsonObject() ? str(todos.get(i).getAsJsonObject(), "status") : "";
                int sx = x + i * (SEG_W + SEG_GAP);
                g.fill(sx, y, sx + SEG_W, y + SEG_H, mark(th, status, nowMs));
            }
            return n * (SEG_W + SEG_GAP) - SEG_GAP;
        }
        if (maxW < BAR_W) {
            return 0;
        }
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (todos.get(i).isJsonObject() && "completed".equals(str(todos.get(i).getAsJsonObject(), "status"))) done++;
        }
        g.fill(x, y, x + BAR_W, y + SEG_H, th.faint());
        g.fill(x, y, x + BAR_W * done / n, y + SEG_H, th.ok());
        return BAR_W;
    }

    /** 做完实色、正在做呼吸、没做淡。 */
    private static int mark(UiTheme th, String status, long nowMs) {
        return switch (status) {
            case "completed" -> th.ok();
            case "in_progress" -> pulse(th.run(), nowMs);
            default -> th.faint();
        };
    }

    /** 呼吸:透明度在 0x90–0xFF 之间来回,"正在做"是活的。 */
    private static int pulse(int argb, long nowMs) {
        int a = 0x90 + (int) (0x6F * (0.5 + 0.5 * Math.sin(nowMs / 250.0)));
        return (argb & 0xFFFFFF) | (a << 24);
    }

    /**
     * 展开态:从 {@code top} 往下长、盖在对话流上;高度贴内容,底不过 {@code bottom}。先是目标(旗子、目标本身、
     * 第几轮与用时),再是清单,每条前面是和收起时同一套的色块。
     *
     * @param goalLine 目标那一句;没有目标是 null
     * @param goalMeta 目标下面那行淡字(第几轮 · 用时);没有目标是 null
     * @param shownH   这一帧露出多高(展开/收起的过渡由宿主按帧推进);超出内容高按内容高算
     * @return 内容的完整高度——宿主拿它当过渡的目标
     */
    public static int renderOpen(GuiGraphics g, Font font, EntityAgentLoop loop, String goalLine, String goalMeta,
                                 int x, int w, int top, int bottom, int shownH, long nowMs) {
        JsonArray todos = latestPlan(loop);
        boolean hasPlan = todos != null && !todos.isEmpty();
        if (!hasPlan && goalLine == null) {
            return 0;
        }
        UiTheme th = UiTheme.current();
        int TXT = th.text(), MUTED = th.textDim(), FAINT = th.faint();
        int ix = x + PAD;
        int iw = w - PAD * 2 - 12;
        // 先量后画:框贴内容,底不过 bottom
        int contentH = PAD;
        if (goalLine != null) {
            contentH += Math.min(2, font.split(Nb.colored(goalLine, TXT), iw).size()) * LINE_H + LINE_H + 4;
        }
        if (hasPlan) {
            for (int i = 0; i < todos.size(); i++) {
                if (!todos.get(i).isJsonObject()) continue;
                int n = font.split(Nb.colored(str(todos.get(i).getAsJsonObject(), "content"), TXT), iw).size();
                contentH += Math.max(1, Math.min(2, n)) * LINE_H;
                if (top + contentH + PAD >= bottom) break;
            }
        }
        int fullH = Math.min(bottom - top, contentH + PAD - 2);
        int h = Math.min(fullH, shownH);
        if (h <= 0) {
            return fullH;
        }
        // 过渡时只露上面这一截:框从置顶条往下长,里面的行跟着框的底边一起露出来
        g.enableScissor(x, top, x + w, top + h);
        NumenStyle.box(new McDrawSurface(g, font), x, top, w, fullH, th.band(), th.aiBorder());
        int ly = top + PAD;
        int end = top + fullH;
        if (goalLine != null) {
            Sprites.draw(g, Sprites.FLAG, ix - 3, ly - 1, Sprites.SIZE, th.cta());
            int sub = 0;
            for (FormattedCharSequence seq : font.split(Nb.colored(goalLine, TXT), iw)) {
                Nb.text(g, font, seq, ix + 12, ly);
                ly += LINE_H;
                if (++sub >= 2) break;
            }
            if (goalMeta != null) Nb.text(g, font, goalMeta, ix + 12, ly, FAINT);
            ly += LINE_H + 4;
        }
        if (hasPlan) {
            for (int i = 0; i < todos.size() && ly + LINE_H < end; i++) {
                if (!todos.get(i).isJsonObject()) continue;
                JsonObject it = todos.get(i).getAsJsonObject();
                String status = str(it, "status");
                g.fill(ix, ly + 2, ix + 6, ly + 8, mark(th, status, nowMs));
                // 层次:正在做的最亮,做完的退后,还没做的最淡
                int textColor = switch (status) {
                    case "in_progress" -> TXT;
                    case "completed" -> MUTED;
                    default -> FAINT;
                };
                List<FormattedCharSequence> lines = font.split(Nb.colored(str(it, "content"), textColor), iw);
                int sub = 0;
                for (FormattedCharSequence seq : lines) {
                    if (ly + LINE_H >= end) break;
                    Nb.text(g, font, seq, ix + 12, ly);
                    ly += LINE_H;
                    if (++sub >= 2) break;   // 每条最多两行
                }
                if (lines.isEmpty()) ly += LINE_H;
            }
        }
        g.disableScissor();
        return fullH;
    }

    /** 置顶条第一行的标签:有计划是"计划 2/5",只有目标是"目标"。 */
    public static String label(Progress p) {
        return p != null ? I18n.get("numen.pin.plan", p.done(), p.total()) : I18n.get("numen.pin.goal");
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
