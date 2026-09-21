package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.mc.McDrawSurface;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 她的计划(最近一次 {@code todowrite}):收起时是状态行里的一小段——"▸ 计划 2/5 · 正在做的那步",
 * 点开才从状态行往上长、盖在对话流上展开成清单(和补全弹层同一个方向)。正文那一列永远最宽,
 * 附属信息在底部一行、按需展开——pi 的做法(pi 连专门的计划面板都没有,待办就是记录里的一条)。
 * 没计划时一个像素不占。
 *
 * <p>读的是物理记录,所以压缩过上下文计划也还在。
 */
public final class PlanStrip {

    private static final int LINE_H = 10;
    private static final int PAD = 7;

    private PlanStrip() {}

    /**
     * 收起态:在 {@code (x, y)} 画一段,最多占 {@code maxW} 宽。返回实际占的宽——没计划是 0。
     *
     * @param open 展开着(箭头朝下),清单由 {@link #renderOpen} 另画
     */
    public static int render(GuiGraphics g, Font font, EntityAgentLoop loop, int x, int y, int maxW, boolean open) {
        JsonArray todos = latestPlan(loop);
        if (todos == null || todos.isEmpty()) {
            return 0;
        }
        UiTheme th = UiTheme.current();
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
        String head = (open ? "▾ " : "▸ ") + I18n.get("numen.chat.plan") + " " + done + "/" + todos.size();
        int headW = font.width(head);
        if (headW > maxW) {
            return 0;   // 连"计划 2/5"都放不下:这一帧不画,别画半截
        }
        Nb.text(g, font, head, x, y, th.run());
        int used = headW;
        if (current != null && !current.isBlank()) {
            String tail = " · " + current;
            int room = maxW - headW;
            if (font.width(tail) > room) {
                while (tail.length() > 4 && font.width(tail + "…") > room) {
                    tail = tail.substring(0, tail.length() - 1);
                }
                tail = tail + "…";
            }
            if (tail.length() > 4 && font.width(tail) <= room) {
                Nb.text(g, font, tail, x + headW, y, th.textDim());
                used += font.width(tail);
            }
        }
        return used;
    }

    /** 展开态:清单从 {@code bottom} 往上长、盖在对话流上;高度贴内容,顶不过 {@code top}。 */
    public static void renderOpen(GuiGraphics g, Font font, EntityAgentLoop loop, int x, int w, int bottom, int top) {
        JsonArray todos = latestPlan(loop);
        if (todos == null || todos.isEmpty()) {
            return;
        }
        UiTheme th = UiTheme.current();
        int TXT = th.text(), MUTED = th.textDim(), FAINT = th.faint(), OK = th.ok(), RUN = th.run();
        int ix = x + PAD;
        int iw = w - PAD * 2;
        // 先量后画:框贴内容,顶不过 top
        int contentH = PAD;
        for (int i = 0; i < todos.size(); i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            int n = font.split(Nb.colored(str(it, "content"), TXT), iw - 10).size();
            contentH += Math.max(1, Math.min(2, n)) * LINE_H;
            if (bottom - (contentH + PAD) <= top) break;
        }
        int y = Math.max(top, bottom - (contentH + PAD - 2));
        NumenStyle.box(new McDrawSurface(g, font), x, y, w, bottom - y, th.aiFill(), th.aiBorder());
        int ly = y + PAD;
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            String glyph = switch (status) { case "completed" -> "✔"; case "in_progress" -> "▸"; default -> "○"; };
            int glyphColor = switch (status) { case "completed" -> OK; case "in_progress" -> RUN; default -> FAINT; };
            Nb.text(g, font, glyph, ix, ly, glyphColor);
            // 层次:正在做的最亮,做完的退后,还没做的最淡
            int textColor = switch (status) {
                case "in_progress" -> TXT;
                case "completed" -> MUTED;
                default -> FAINT;
            };
            List<FormattedCharSequence> lines = font.split(Nb.colored(content, textColor), iw - 10);
            int sub = 0;
            for (FormattedCharSequence seq : lines) {
                if (ly + LINE_H >= bottom) break;
                Nb.text(g, font, seq, ix + 10, ly);
                ly += LINE_H;
                if (++sub >= 2) break;   // 每条最多两行
            }
            if (lines.isEmpty()) ly += LINE_H;
        }
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
