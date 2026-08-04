package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.agent.goal.GoalState;
import com.dwinovo.numen.client.agent.goal.GoalStatus;
import com.dwinovo.numen.data.ModLanguageData.Keys;
import com.dwinovo.numen.client.screen.Nb;
import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.RoundRect;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Right-side goal card. It is intentionally separate from the plan card and
 * only appears once the owner has actually used goal state; ordinary chat and
 * never-used companions keep the old sidebar layout.
 */
public final class GoalCard {

    private static final int LINE_H = 10;
    private static final int PAD = 7;
    private static final int RADIUS = 6;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private GoalCard() {}

    /** Renders the goal card if present and returns its consumed height, else 0. */
    public static int render(GuiGraphics g, Font font, EntityAgentLoop loop,
                             int x, int y, int w, int bottom) {
        GoalState goal = loop == null ? null : loop.goalState();
        if (goal == null || !goal.hasGoal()) return 0;

        UiTheme th = UiTheme.current();
        int ix = x + PAD;
        int iw = w - PAD * 2;

        int rows = 4;
        if (!goal.currentTask().isBlank()) rows++;
        if (!goal.lastError().isBlank()) rows++;
        int contentH = PAD + 13 + rows * LINE_H;
        int cardBottom = Math.min(bottom, y + contentH + PAD - 2);
        if (cardBottom <= y) return 0;

        RoundRect.fill(g, x, y, x + w, cardBottom, RADIUS, th.cardFill());
        Nb.text(g, font, clip(font, goal.title(), iw), ix, y + PAD, th.text());

        int ly = y + PAD + 13;
        String status = statusText(goal.status());
        String elapsed = I18n.get(Keys.GOAL_ELAPSED,
                formatDuration(goal.effectiveElapsedMs(System.currentTimeMillis())));
        Nb.text(g, font, status + " · " + elapsed, ix, ly, statusColor(th, goal.status()));
        ly += LINE_H;

        String times = I18n.get(Keys.GOAL_CREATED, time(goal.createdAtMs()));
        if (goal.startedAtMs() > 0) {
            times += I18n.get(Keys.GOAL_STARTED, time(goal.startedAtMs()));
        }
        Nb.text(g, font, clip(font, times, iw), ix, ly, th.textDim());
        ly += LINE_H;

        if (goal.completedAtMs() > 0) {
            Nb.text(g, font, I18n.get(Keys.GOAL_COMPLETED_AT, time(goal.completedAtMs())),
                    ix, ly, th.ok());
        } else {
            String progress = I18n.get(Keys.GOAL_PROGRESS,
                    goal.completedTodoCount(), goal.totalTodoCount());
            Nb.text(g, font, progress, ix, ly, th.textDim());
        }
        ly += LINE_H;

        if (!goal.currentTask().isBlank()) {
            Nb.text(g, font, I18n.get(Keys.GOAL_CURRENT,
                    clip(font, goal.currentTask(), iw)), ix, ly, th.run());
            ly += LINE_H;
        }
        if (!goal.lastError().isBlank()) {
            Nb.text(g, font, I18n.get(Keys.GOAL_ERROR,
                    clip(font, goal.lastError(), iw)), ix, ly, th.fail());
        }

        return cardBottom - y;
    }

    private static String statusText(GoalStatus status) {
        return switch (status) {
            case ACTIVE -> I18n.get(Keys.GOAL_STATUS_ACTIVE);
            case PAUSED -> I18n.get(Keys.GOAL_STATUS_PAUSED);
            case COMPLETED -> I18n.get(Keys.GOAL_STATUS_COMPLETED);
            case CANCELLED -> I18n.get(Keys.GOAL_STATUS_CANCELLED);
            case FAILED -> I18n.get(Keys.GOAL_STATUS_FAILED);
            case BLOCKED -> I18n.get(Keys.GOAL_STATUS_BLOCKED);
            case NONE -> I18n.get(Keys.GOAL_STATUS_NONE);
        };
    }

    private static int statusColor(UiTheme th, GoalStatus status) {
        return switch (status) {
            case ACTIVE -> th.run();
            case PAUSED -> th.textDim();
            case COMPLETED -> th.ok();
            case CANCELLED, FAILED, BLOCKED -> th.fail();
            case NONE -> th.faint();
        };
    }

    private static String time(long ms) {
        return ms <= 0 ? "-" : Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME);
    }

    private static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        if (min < 60) return min + "m " + (sec % 60) + "s";
        long hour = min / 60;
        if (hour < 24) return hour + "h " + (min % 60) + "m";
        long day = hour / 24;
        return day + "d " + (hour % 24) + "h";
    }

    private static String clip(Font font, String text, int maxW) {
        if (text == null || text.isBlank()) return "";
        if (font.width(text) <= maxW) return text;
        String s = text;
        while (s.length() > 1 && font.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
