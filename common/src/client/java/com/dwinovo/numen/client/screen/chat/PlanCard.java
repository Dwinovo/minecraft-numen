package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * The right-side PLAN card: the companion's latest {@code todowrite}, drawn on a
 * translucent rounded wash so it reads as a sidebar, not more chat. Reads the
 * physical transcript so the plan survives a context compaction.
 */
public final class PlanCard {

    private static final int LINE_H = 10;
    private static final int PAD = 7;
    private static final int RADIUS = 6;

    private PlanCard() {}

    public static void render(GuiGraphics g, Font font, EntityAgentLoop loop,
                              int x, int y, int w, int bottom) {
        // 每帧从当前主题取色——设置页切主题即时生效。
        UiTheme th = UiTheme.current();
        int CARD_FILL = th.cardFill(), TXT = th.text(), MUTED = th.textDim(),
                FAINT = th.faint(), OK = th.ok(), RUN = th.run();
        int ix = x + PAD;
        int iw = w - PAD * 2;
        JsonArray todos = latestPlan(loop);
        // 先量后画:卡片高度贴内容(空计划只有标题+一行),不再拖满整条侧栏。
        int contentH = PAD + 13;
        if (todos == null || todos.isEmpty()) {
            contentH += LINE_H;
        } else {
            for (int i = 0; i < todos.size(); i++) {
                if (!todos.get(i).isJsonObject()) continue;
                JsonObject it = todos.get(i).getAsJsonObject();
                int n = font.split(Nb.colored(str(it, "content"), TXT), iw - 10).size();
                contentH += Math.max(1, Math.min(2, n)) * LINE_H;
                if (y + contentH + PAD >= bottom) break;
            }
        }
        int cardBottom = Math.min(bottom, y + contentH + PAD - 2);
        RoundRect.fill(g, x, y, x + w, cardBottom, RADIUS, CARD_FILL);
        Nb.text(g, font, I18n.get("numen.chat.plan"), ix, y + PAD, MUTED);
        int ly = y + PAD + 13;
        if (todos == null || todos.isEmpty()) {
            Nb.text(g, font, I18n.get("numen.chat.no_plan"), ix, ly, FAINT);
            return;
        }
        for (int i = 0; i < todos.size() && ly + LINE_H < bottom; i++) {
            if (!todos.get(i).isJsonObject()) continue;
            JsonObject it = todos.get(i).getAsJsonObject();
            String status = str(it, "status");
            String content = str(it, "content");
            String glyph = switch (status) { case "completed" -> "✔"; case "in_progress" -> "▸"; default -> "○"; };
            int glyphColor = switch (status) { case "completed" -> OK; case "in_progress" -> RUN; default -> FAINT; };
            Nb.text(g, font, glyph, ix, ly, glyphColor);
            // text hierarchy: in-progress = strong (current focus), completed = recede, pending = faint
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
                if (++sub >= 2) break;   // cap each item at 2 lines
            }
            if (lines.isEmpty()) ly += LINE_H;
        }
    }

    /** Parse the most recent todowrite call's todos array, or null. */
    private static JsonArray latestPlan(EntityAgentLoop loop) {
        JsonArray latest = null;
        for (ConvoState.Msg m : loop.display()) {
            if (m instanceof ConvoState.Msg.Assistant a) {
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
