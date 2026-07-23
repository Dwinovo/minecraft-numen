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
    private static final UiTheme TH = UiTheme.WARM;
    private static final int CARD_FILL = 0x16352818;
    private static final int TXT = TH.text();
    private static final int MUTED = TH.textDim();
    private static final int FAINT = 0xFF8C7C62;
    private static final int OK = TH.ok();
    private static final int RUN = TH.run();

    private PlanCard() {}

    public static void render(GuiGraphics g, Font font, EntityAgentLoop loop,
                              int x, int y, int w, int bottom) {
        RoundRect.fill(g, x, y, x + w, bottom, RADIUS, CARD_FILL);
        int ix = x + PAD;
        int iw = w - PAD * 2;
        Nb.text(g, font, I18n.get("numen.chat.plan"), ix, y + PAD, MUTED);
        int ly = y + PAD + 13;
        JsonArray todos = latestPlan(loop);
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
                g.drawString(font, seq, ix + 10, ly, -1, false);
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
